/*
 * Cached page dimensions, plus the job queue and sync state that live in the same database.
 *
 * One interface rather than three because they are one transactional unit: enqueueing a job commits
 * atomically with the state it describes, which is the whole reason Postgres carries the messaging
 * instead of a broker. See docs/DESIGN.md.
 */
package cz.mzk.iiif.gateway.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import cz.mzk.iiif.document.ImageSize;

public interface SizeStore {

  /** What a document was measured to be, and when. */
  record Document(Map<String, ImageSize> sizes, Instant fetchedAt) {
    public Document {
      sizes = Map.copyOf(sizes);
    }
  }

  /** One document's worth of work waiting for the worker. */
  record Job(String library, String pid, int attempts) {}

  /** Per-library sync progress, which is what GET /libraries reports. */
  record Sync(
      String library,
      State state,
      Optional<String> cursor,
      Instant watermark,
      long seen,
      Optional<Instant> lastRunAt,
      Optional<String> lastError) {

    public enum State {
      DISABLED,
      RUNNING,
      PAUSED
    }
  }

  Optional<Document> find(String library, String pid);

  /**
   * Records what a document's pages measure.
   *
   * <p>Written whole rather than merged, because the blob IS the record that a document was
   * measured: its entry count against count_page says whether it was complete, and fetched_at says
   * when.
   */
  void put(String library, String pid, Map<String, ImageSize> sizes);

  /** Drops a document's cached sizes, so the next request re-resolves them. */
  void evict(String library, String pid);

  /** Idempotent: a second enqueue for the same document does nothing. */
  void enqueue(String library, String pid);

  /** Claims up to `limit` jobs, skipping any another worker holds. */
  List<Job> claim(int limit);

  void complete(String library, String pid);

  void fail(String library, String pid, String error);

  Optional<Sync> sync(String library);

  List<Sync> allSyncs();

  void saveSync(Sync sync);

  /** Blocks until a job is enqueued or the timeout expires. Returns whether one arrived. */
  boolean awaitJob(java.time.Duration timeout);

  /** What /health can say about the store. Three states, because two of them are different jobs. */
  enum Health {
    OK,
    /** The store did not answer at all. */
    UNREACHABLE,
    /** It answered, but does not hold the tables this gateway reads and writes. */
    SCHEMA_MISSING
  }

  /**
   * Whether the store is usable, for /health.
   *
   * <p>On the interface rather than only on the Postgres one because /health used to cast to
   * PostgresSizeStore to ask — a ClassCastException for any other implementation, and the SPI
   * exists precisely so there can be others. A store with nothing to be unreachable is healthy.
   *
   * <p>SCHEMA_MISSING is its own answer because a reachable but empty database is the case a
   * connection check calls healthy and an operator does not. Seen for real: the Postgres container
   * was recreated under a running gateway, the pool reconnected, and /health stayed green with no
   * tables in the database at all — the schema is applied once, at startup. "Reachable" and
   * "usable" had been treated as the same question, and they answered differently for several
   * minutes.
   */
  default Health health() {
    return Health.OK;
  }
}
