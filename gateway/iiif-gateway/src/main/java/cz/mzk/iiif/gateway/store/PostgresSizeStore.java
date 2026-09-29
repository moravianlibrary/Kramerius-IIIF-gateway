/*
 * The size store, the job queue and the sync state, on Postgres.
 *
 * The queue is Postgres rather than a broker for three reasons, and only the first is about
 * convenience. The enqueue commits ATOMICALLY with the state it describes, which no external broker can
 * offer against a Postgres write. NOTIFY is only a wakeup, so a worker that is down loses nothing — the
 * row is still queued when it returns. And FOR UPDATE SKIP LOCKED is the standard claim, so several
 * workers run with no coordination.
 *
 * Adding Redis or RabbitMQ would mean a second service to run, monitor and back up, and would split the
 * job state away from the data it is about — for one job type flowing one way between two roles that
 * already share a database.
 */
package cz.mzk.iiif.gateway.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.document.ImageSize;
import cz.mzk.iiif.gateway.Config;

public final class PostgresSizeStore implements SizeStore, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(PostgresSizeStore.class);
  private static final String CHANNEL = "iiif_job";

  private final HikariDataSource pool;

  /** A dedicated connection, because LISTEN is per-connection and must not be pooled away. */
  private volatile Connection listener;

  public PostgresSizeStore(Config.Database database) {
    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(database.url());
    hikari.setUsername(database.user());
    hikari.setPassword(database.password());
    hikari.setMaximumPoolSize(database.poolSize());
    hikari.setPoolName("iiif-sizes");
    this.pool = new HikariDataSource(hikari);
    migrate();
  }

  private void migrate() {
    try (InputStream in = PostgresSizeStore.class.getResourceAsStream("/schema.sql")) {
      if (in == null) {
        throw new IllegalStateException("schema.sql missing from the jar");
      }
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      try (Connection connection = pool.getConnection();
          Statement statement = connection.createStatement()) {
        // IF NOT EXISTS does not serialize concurrent CREATE TABLE calls: two freshly started web
        // and worker processes can both see no relation, then one loses while PostgreSQL creates
        // the table's implicit row type. Keep the schema operation single-file across processes.
        statement.execute("SELECT pg_advisory_lock(hashtext('iiif_size_store_schema'))");
        try {
          statement.execute(sql);
        } finally {
          statement.execute("SELECT pg_advisory_unlock(hashtext('iiif_size_store_schema'))");
        }
      }
      LOG.info("size store schema applied");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (SQLException e) {
      throw new IllegalStateException("could not apply the size store schema", e);
    }
  }

  // Sizes

  @Override
  public Optional<Document> find(String library, String pid) {
    return query(
        "SELECT pages, fetched_at FROM iiif_document_sizes WHERE library = ? AND pid = ?",
        statement -> {
          statement.setString(1, library);
          statement.setString(2, pid);
        },
        rows ->
            rows.next()
                ? Optional.of(
                    new Document(
                        PageBlob.decode(rows.getBytes(1)), rows.getTimestamp(2).toInstant()))
                : Optional.empty());
  }

  @Override
  public void put(String library, String pid, Map<String, ImageSize> sizes) {
    update(
        """
        INSERT INTO iiif_document_sizes (library, pid, pages, api_version, fetched_at)
        VALUES (?, ?, ?, ?, now())
        ON CONFLICT (library, pid)
          DO UPDATE SET pages = EXCLUDED.pages, fetched_at = now()
        """,
        statement -> {
          statement.setString(1, library);
          statement.setString(2, pid);
          statement.setBytes(3, PageBlob.encode(sizes));
          statement.setShort(4, PageBlob.VERSION);
        });
  }

  @Override
  public void evict(String library, String pid) {
    update(
        "DELETE FROM iiif_document_sizes WHERE library = ? AND pid = ?",
        statement -> {
          statement.setString(1, library);
          statement.setString(2, pid);
        });
  }

  // Jobs

  @Override
  public void enqueue(String library, String pid) {
    try (Connection connection = pool.getConnection()) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              """
              INSERT INTO iiif_dimension_job (library, pid, state, requested_at)
              VALUES (?, ?, 0, now())
              ON CONFLICT (library, pid) DO NOTHING
              """)) {
        statement.setString(1, library);
        statement.setString(2, pid);
        statement.executeUpdate();
      }
      // Only a wakeup. A worker that is down loses nothing: the row is still queued.
      try (Statement notify = connection.createStatement()) {
        notify.execute("NOTIFY " + CHANNEL);
      }
    } catch (SQLException e) {
      LOG.warn("could not enqueue {}/{}: {}", library, pid, e.toString());
    }
  }

  @Override
  public List<Job> claim(int limit) {
    List<Job> claimed = new ArrayList<>();
    try (Connection connection = pool.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement statement =
          connection.prepareStatement(
              """
              UPDATE iiif_dimension_job SET state = 1, started_at = now(), attempts = attempts + 1
              WHERE (library, pid) IN (
                SELECT library, pid FROM iiif_dimension_job
                WHERE state = 0 ORDER BY requested_at
                FOR UPDATE SKIP LOCKED LIMIT ?)
              RETURNING library, pid, attempts
              """)) {
        statement.setInt(1, limit);
        try (ResultSet rows = statement.executeQuery()) {
          while (rows.next()) {
            claimed.add(new Job(rows.getString(1), rows.getString(2), rows.getInt(3)));
          }
        }
      }
      connection.commit();
    } catch (SQLException e) {
      LOG.warn("could not claim jobs: {}", e.toString());
    }
    return List.copyOf(claimed);
  }

  @Override
  public void complete(String library, String pid) {
    update(
        "DELETE FROM iiif_dimension_job WHERE library = ? AND pid = ?",
        statement -> {
          statement.setString(1, library);
          statement.setString(2, pid);
        });
  }

  @Override
  public void fail(String library, String pid, String error) {
    update(
        "UPDATE iiif_dimension_job SET state = 2, last_error = ? WHERE library = ? AND pid = ?",
        statement -> {
          statement.setString(
              1, error == null ? null : error.substring(0, Math.min(500, error.length())));
          statement.setString(2, library);
          statement.setString(3, pid);
        });
  }

  @Override
  public boolean awaitJob(Duration timeout) {
    try {
      Connection connection = listenerConnection();
      try (Statement statement = connection.createStatement()) {
        statement.execute("LISTEN " + CHANNEL);
      }
      PGNotification[] notifications =
          connection.unwrap(PGConnection.class).getNotifications((int) timeout.toMillis());
      return notifications != null && notifications.length > 0;
    } catch (SQLException e) {
      LOG.debug("listen failed, falling back to polling: {}", e.toString());
      closeListener();
      return false;
    }
  }

  private synchronized Connection listenerConnection() throws SQLException {
    if (listener == null || listener.isClosed()) {
      listener = pool.getConnection();
    }
    return listener;
  }

  private synchronized void closeListener() {
    try {
      if (listener != null) {
        listener.close();
      }
    } catch (SQLException ignored) {
      // Being unable to close a broken connection changes nothing.
    }
    listener = null;
  }

  // Sync state

  @Override
  public Optional<Sync> sync(String library) {
    return query(
        "SELECT library, state, cursor, watermark, seen, last_run_at, last_error"
            + " FROM iiif_library_sync WHERE library = ?",
        statement -> statement.setString(1, library),
        rows -> rows.next() ? Optional.of(readSync(rows)) : Optional.empty());
  }

  @Override
  public List<Sync> allSyncs() {
    return query(
        "SELECT library, state, cursor, watermark, seen, last_run_at, last_error"
            + " FROM iiif_library_sync ORDER BY library",
        statement -> {},
        rows -> {
          List<Sync> all = new ArrayList<>();
          while (rows.next()) {
            all.add(readSync(rows));
          }
          return List.copyOf(all);
        });
  }

  @Override
  public void saveSync(Sync sync) {
    update(
        """
        INSERT INTO iiif_library_sync
          (library, state, cursor, watermark, seen, last_run_at, last_error)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (library) DO UPDATE SET
          state = EXCLUDED.state, cursor = EXCLUDED.cursor, watermark = EXCLUDED.watermark,
          seen = EXCLUDED.seen, last_run_at = EXCLUDED.last_run_at, last_error = EXCLUDED.last_error
        """,
        statement -> {
          statement.setString(1, sync.library());
          statement.setShort(2, (short) sync.state().ordinal());
          statement.setString(3, sync.cursor().orElse(null));
          statement.setTimestamp(4, Timestamp.from(sync.watermark()));
          statement.setLong(5, sync.seen());
          statement.setTimestamp(6, sync.lastRunAt().map(Timestamp::from).orElse(null));
          statement.setString(7, sync.lastError().orElse(null));
        });
  }

  private static Sync readSync(ResultSet rows) throws SQLException {
    Timestamp lastRun = rows.getTimestamp(6);
    return new Sync(
        rows.getString(1),
        Sync.State.values()[rows.getShort(2)],
        Optional.ofNullable(rows.getString(3)),
        rows.getTimestamp(4).toInstant(),
        rows.getLong(5),
        Optional.ofNullable(lastRun).map(Timestamp::toInstant),
        Optional.ofNullable(rows.getString(7)));
  }

  // Plumbing

  @FunctionalInterface
  private interface Binder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  @FunctionalInterface
  private interface Reader<T> {
    T read(ResultSet rows) throws SQLException;
  }

  private <T> T query(String sql, Binder binder, Reader<T> reader) {
    try (Connection connection = pool.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      binder.bind(statement);
      try (ResultSet rows = statement.executeQuery()) {
        return reader.read(rows);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("size store query failed", e);
    }
  }

  private void update(String sql, Binder binder) {
    try (Connection connection = pool.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      binder.bind(statement);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("size store update failed", e);
    }
  }

  /** The tables this store reads and writes. Anything less is not a store we can use. */
  private static final List<String> REQUIRED_TABLES =
      List.of("iiif_document_sizes", "iiif_dimension_job", "iiif_library_sync");

  @Override
  public Health health() {
    try (Connection connection = pool.getConnection()) {
      if (!connection.isValid(2)) {
        return Health.UNREACHABLE;
      }
      // One catalogue query, not one per table, and no caching of the answer: the whole point is
      // that a schema present a minute ago can be absent now, which is exactly what a cached
      // positive would hide.
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT count(*) FROM pg_tables WHERE schemaname = current_schema()"
                  + " AND tablename = ANY (?)")) {
        statement.setArray(1, connection.createArrayOf("text", REQUIRED_TABLES.toArray()));
        try (ResultSet rows = statement.executeQuery()) {
          boolean complete = rows.next() && rows.getInt(1) == REQUIRED_TABLES.size();
          if (!complete) {
            LOG.warn("size store is reachable but its schema is incomplete");
          }
          return complete ? Health.OK : Health.SCHEMA_MISSING;
        }
      }
    } catch (SQLException e) {
      return Health.UNREACHABLE;
    }
  }

  @Override
  public void close() {
    closeListener();
    pool.close();
  }
}
