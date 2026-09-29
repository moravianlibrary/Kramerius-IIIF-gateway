/*
 * The three properties that only failure ever exercises.
 *
 * Everything else about the store is covered offline, because it is arithmetic over a blob. These three
 * are not: each is a claim about what happens when a process DIES or when two of them race, and none of
 * them can be tested without a real Postgres, because the guarantees being claimed are Postgres'.
 *
 *   1. A prefetch interrupted mid-walk and restarted does no work for subtrees already complete.
 *      An iiif_document_sizes row IS the completion record, so no
 *      separate marker is needed. If that is wrong, a re-run after a failure restarts instead of
 *      resuming — and a walk with no depth limit becomes unsafe, because the whole argument for having
 *      no limit is that repeating it is cheap.
 *
 *   2. A sync killed mid-library resumes where it stopped. A backfill is ~22 days at MZK; a run that
 *      restarts from zero on day 12 is not a feature that works slowly, it is one that never finishes.
 *
 *   3. Two workers claiming concurrently never process the same job twice. FOR UPDATE SKIP LOCKED is
 *      what lets workers scale without coordinating, and its failure mode is silent duplicated upstream
 *      requests against somebody else's image server.
 *
 * Skipped, not failed, without IIIF_TEST_DB_URL: `make check` must run on a JDK alone, and a developer
 * who forgets `make db-up` should get a skip rather than a red build.
 */
package cz.mzk.iiif.gateway.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cz.mzk.iiif.document.ImageSize;
import cz.mzk.iiif.gateway.Config;

@EnabledIfEnvironmentVariable(
    named = "IIIF_TEST_DB_URL",
    matches = ".+",
    disabledReason = "needs a real Postgres: make db-up")
class PostgresSizeStoreIT {

  private static final String LIBRARY = "it";

  private static PostgresSizeStore store;

  @BeforeAll
  static void connect() {
    store =
        new PostgresSizeStore(
            new Config.Database(
                System.getenv("IIIF_TEST_DB_URL"),
                System.getenv().getOrDefault("IIIF_TEST_DB_USER", "iiif"),
                System.getenv().getOrDefault("IIIF_TEST_DB_PASSWORD", "iiif"),
                4));
  }

  @BeforeEach
  void clean() {
    // Every test owns the whole library namespace, so nothing carries over between them.
    //
    // DELETE, not drain-by-claiming. Claiming only reaches rows that are still `queued`, so a row
    // left in `failed` survived the reset and was then unclaimable for the rest of the run — and
    // `enqueue` cannot resurrect it, because ON CONFLICT DO NOTHING is the whole point of it. That
    // made concurrentClaimsAreDisjoint fail with 58 of 60 claimed, for a reason inside the fixture
    // rather than in the code under test. It happened for real: a worker pointed at this database
    // woke on the test's own NOTIFY, claimed two rows, and failed them.
    try (java.sql.Connection connection = testConnection();
        java.sql.Statement sql = connection.createStatement()) {
      sql.executeUpdate("DELETE FROM iiif_dimension_job WHERE library = '" + LIBRARY + "'");
      sql.executeUpdate("DELETE FROM iiif_document_sizes WHERE library = '" + LIBRARY + "'");
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("could not reset the test namespace", e);
    }
  }

  private static java.sql.Connection testConnection() throws java.sql.SQLException {
    return java.sql.DriverManager.getConnection(
        System.getenv("IIIF_TEST_DB_URL"),
        System.getenv().getOrDefault("IIIF_TEST_DB_USER", "iiif"),
        System.getenv().getOrDefault("IIIF_TEST_DB_PASSWORD", "iiif"));
  }

  // 1. A walk resumes rather than restarts.

  @Test
  @DisplayName("a walk killed mid-way redoes no document it had already finished")
  void interruptedWalkSkipsFinishedSubtrees() {
    List<String> tree = pids("doc", 6);

    // The first run dies after four of the six.
    List<String> firstRun = tree.subList(0, 4);
    firstRun.forEach(pid -> store.put(LIBRARY, pid, Map.of(page(pid), ImageSize.of(800, 1200, 3))));

    // The second run asks the same question the walker asks: is this one already done?
    List<String> wouldFetch =
        tree.stream().filter(pid -> store.find(LIBRARY, pid).isEmpty()).toList();

    assertThat(wouldFetch).containsExactlyElementsOf(tree.subList(4, 6));
    // The point of the property: the work skipped is real work, not a bookkeeping row.
    assertThat(store.find(LIBRARY, tree.get(0)).orElseThrow().sizes())
        .containsEntry(page(tree.get(0)), ImageSize.of(800, 1200, 3));
  }

  @Test
  @DisplayName("a document is complete when its row covers every page, not when a flag says so")
  void completenessIsTheRowItself() {
    String pid = "uuid:it-partial";
    store.put(LIBRARY, pid, Map.of("uuid:p1", ImageSize.of(100, 200, 3)));

    Map<String, ImageSize> stored = store.find(LIBRARY, pid).orElseThrow().sizes();

    // Two of the three pages are missing, so the walker has something to do and knows what.
    assertThat(
            List.of("uuid:p1", "uuid:p2", "uuid:p3").stream().filter(stored::containsKey).toList())
        .containsExactly("uuid:p1");
  }

  // 2. A sync resumes at its watermark.

  @Test
  @DisplayName("a sync killed mid-library resumes at its stored watermark, not at the beginning")
  void syncResumesAtItsWatermark() {
    Instant watermark = Instant.now().minus(Duration.ofDays(9)).truncatedTo(ChronoUnit.MILLIS);
    store.saveSync(
        new SizeStore.Sync(
            LIBRARY,
            SizeStore.Sync.State.RUNNING,
            Optional.of("AoIIP4AAACpjb21wb3NpdGVJZA=="),
            watermark,
            412_000,
            Optional.of(Instant.now()),
            Optional.empty()));

    // A new process, which knows nothing but the library code.
    SizeStore.Sync resumed = store.sync(LIBRARY).orElseThrow();

    assertThat(resumed.watermark()).isEqualTo(watermark);
    assertThat(resumed.cursor()).contains("AoIIP4AAACpjb21wb3NpdGVJZA==");
    assertThat(resumed.seen()).isEqualTo(412_000);
  }

  @Test
  @DisplayName("the watermark alone is enough to resume, so a lost cursor is not a lost walk")
  void watermarkSurvivesALostCursor() {
    // The reason the walk is ordered by `modified`: a cursorMark expires, the query changes, the
    // index is rebuilt. The cursor is the fast path; the watermark is the one that always works.
    Instant watermark = Instant.now().minus(Duration.ofDays(3)).truncatedTo(ChronoUnit.MILLIS);
    store.saveSync(
        new SizeStore.Sync(
            LIBRARY,
            SizeStore.Sync.State.RUNNING,
            Optional.empty(),
            watermark,
            10,
            Optional.empty(),
            Optional.empty()));

    SizeStore.Sync resumed = store.sync(LIBRARY).orElseThrow();

    assertThat(resumed.cursor()).isEmpty();
    assertThat(resumed.watermark()).isEqualTo(watermark);
  }

  @Test
  @DisplayName("rewinding the watermark to epoch is what 'backfill this library' means")
  void rewindIsJustAWatermark() {
    store.saveSync(
        new SizeStore.Sync(
            LIBRARY,
            SizeStore.Sync.State.RUNNING,
            Optional.of("cursor"),
            Instant.EPOCH,
            0,
            Optional.empty(),
            Optional.empty()));

    assertThat(store.sync(LIBRARY).orElseThrow().watermark()).isEqualTo(Instant.EPOCH);
  }

  // 3. Concurrent workers never double-process.

  @Test
  @DisplayName("two workers claiming at once never take the same job")
  void concurrentClaimsAreDisjoint() throws InterruptedException {
    int jobs = 60;
    List<String> queued = pids("job", jobs);
    queued.forEach(pid -> store.enqueue(LIBRARY, pid));

    int workers = 4;
    Set<String> claimed = ConcurrentHashMap.newKeySet();
    List<String> duplicates = java.util.Collections.synchronizedList(new ArrayList<>());
    CountDownLatch go = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(workers);

    for (int i = 0; i < workers; i++) {
      Thread worker =
          new Thread(
              () -> {
                try {
                  go.await();
                  // Loop, because SKIP LOCKED means a worker can legitimately come back empty
                  // while another holds the rows — that is the mechanism, not a failure.
                  for (int attempt = 0; attempt < 20; attempt++) {
                    List<SizeStore.Job> batch = store.claim(7);
                    for (SizeStore.Job job : batch) {
                      if (!claimed.add(job.pid())) {
                        duplicates.add(job.pid());
                      }
                      store.complete(job.library(), job.pid());
                    }
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
      worker.start();
    }

    go.countDown();
    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();

    // The property. A duplicate here is an image server being asked the same question twice by two
    // of our own processes, which is precisely what SKIP LOCKED is claimed to prevent.
    assertThat(duplicates).isEmpty();
    assertThat(claimed).hasSize(jobs);
  }

  @Test
  @DisplayName("a reachable database with no tables is not healthy")
  void healthSeesAMissingSchema() throws java.sql.SQLException {
    // The incident this exists for: the Postgres container was recreated under a running gateway,
    // the pool reconnected, and /health reported the store as ok with no tables in the database at
    // all — because the schema is applied once, at startup, and "reachable" was the only question
    // being asked.
    //
    // A scratch database, so dropping tables cannot affect the other tests in this class.
    String scratch = "iiif_health_scratch";
    try (java.sql.Connection admin = testConnection();
        java.sql.Statement sql = admin.createStatement()) {
      sql.executeUpdate("DROP DATABASE IF EXISTS " + scratch);
      sql.executeUpdate("CREATE DATABASE " + scratch);
    }

    String url = System.getenv("IIIF_TEST_DB_URL").replaceAll("/[^/]+$", "/" + scratch);
    PostgresSizeStore scratchStore =
        new PostgresSizeStore(
            new Config.Database(
                url,
                System.getenv().getOrDefault("IIIF_TEST_DB_USER", "iiif"),
                System.getenv().getOrDefault("IIIF_TEST_DB_PASSWORD", "iiif"),
                2));
    try {
      // Constructing it applied the schema, so it starts usable.
      assertThat(scratchStore.health()).isEqualTo(SizeStore.Health.OK);

      try (java.sql.Connection connection =
              java.sql.DriverManager.getConnection(
                  url,
                  System.getenv().getOrDefault("IIIF_TEST_DB_USER", "iiif"),
                  System.getenv().getOrDefault("IIIF_TEST_DB_PASSWORD", "iiif"));
          java.sql.Statement sql = connection.createStatement()) {
        sql.executeUpdate("DROP TABLE iiif_document_sizes, iiif_dimension_job, iiif_library_sync");
      }

      // Still connectable — which is all the old check asked — and now unusable.
      assertThat(scratchStore.health()).isEqualTo(SizeStore.Health.SCHEMA_MISSING);
    } finally {
      scratchStore.close();
      try (java.sql.Connection admin = testConnection();
          java.sql.Statement sql = admin.createStatement()) {
        sql.executeUpdate("DROP DATABASE IF EXISTS " + scratch);
      }
    }
  }

  @Test
  @DisplayName("enqueueing the same document twice leaves one job, across processes")
  void enqueueIsIdempotent() {
    String pid = "uuid:it-dedup";
    for (int i = 0; i < 5; i++) {
      store.enqueue(LIBRARY, pid);
    }

    List<SizeStore.Job> claimed = store.claim(10);

    assertThat(claimed.stream().filter(job -> job.pid().equals(pid)).toList()).hasSize(1);
    store.complete(LIBRARY, pid);
  }

  @Test
  @DisplayName("a completed job does not come back")
  void completedJobsStayCompleted() {
    String pid = "uuid:it-once";
    store.enqueue(LIBRARY, pid);
    store.claim(10).forEach(job -> store.complete(job.library(), job.pid()));

    assertThat(store.claim(10).stream().map(SizeStore.Job::pid)).doesNotContain(pid);
  }

  private static List<String> pids(String prefix, int count) {
    List<String> pids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      pids.add("uuid:it-" + prefix + "-" + i);
    }
    return pids;
  }

  private static String page(String documentPid) {
    return documentPid + "-p1";
  }
}
