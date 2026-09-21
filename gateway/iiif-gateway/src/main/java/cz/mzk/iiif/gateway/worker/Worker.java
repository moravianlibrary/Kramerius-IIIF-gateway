/*
 * The worker role: drain the job queue, and run one sync loop per library.
 *
 * Both halves do the same underlying thing — fetch info.json for pages that are missing or stale, and
 * write the result — but they are triggered differently. The queue holds documents a request judged too
 * large to resolve inline, plus explicit PUT .../dimensions warm-ups. The sync loop walks a whole
 * library.
 *
 * "Whole-library backfill" and "incremental sync" are the SAME walk. The only difference is where the
 * watermark starts: seeded at epoch it is a backfill, seeded at now it tracks only new material. One
 * loop, one state row, one code path — and "enable backfill for this library" is `watermark := epoch`
 * rather than a second mechanism.
 */
package cz.mzk.iiif.gateway.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.gateway.Config;
import cz.mzk.iiif.gateway.Libraries;
import cz.mzk.iiif.gateway.store.SizeStore;

public final class Worker implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Worker.class);

  private final Config config;
  private final Libraries libraries;
  private final SizeStore store;
  private final SyncLoop sync;

  private final AtomicBoolean running = new AtomicBoolean(true);
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  private Thread consumer;

  public Worker(Config config, Libraries libraries, SizeStore store) {
    this.config = config;
    this.libraries = libraries;
    this.store = store;
    this.sync = new SyncLoop(config, libraries, store);
  }

  public void start() {
    consumer = Thread.ofPlatform().name("job-consumer").start(this::consume);

    scheduler.scheduleWithFixedDelay(
        this::runSyncs, 0, config.syncInterval().toSeconds(), TimeUnit.SECONDS);

    LOG.info(
        "worker started: job queue plus a sync pass every {} minutes",
        config.syncInterval().toMinutes());
  }

  /**
   * Claims and processes jobs, sleeping on LISTEN between rounds.
   *
   * <p>The NOTIFY is only a wakeup. A worker that was down while jobs were enqueued finds them on
   * its next round regardless, because the rows are still there — which is why losing a
   * notification costs nothing and why no acknowledgement flows back to the web role.
   */
  private void consume() {
    while (running.get()) {
      try {
        List<SizeStore.Job> jobs = store.claim(config.workerBatch());
        if (jobs.isEmpty()) {
          store.awaitJob(Duration.ofSeconds(30));
          continue;
        }
        for (SizeStore.Job job : jobs) {
          if (!running.get()) {
            return;
          }
          process(job);
        }
      } catch (RuntimeException e) {
        LOG.warn("job round failed: {}", e.toString());
        sleep(Duration.ofSeconds(5));
      }
    }
  }

  private void process(SizeStore.Job job) {
    Instant started = Instant.now();
    try {
      if (!(libraries.resolve(job.library()) instanceof Libraries.Resolution.Ready ready)) {
        // Not retryable: the library is gone, unsupported or down. Failing it beats spinning.
        //
        // Logged, because this used to return in silence: the row went to `failed` and the document
        // simply never filled, with nothing anywhere saying why. `last_error` had it, but only
        // somebody who already suspected the queue would look there.
        LOG.warn("{}/{}: library not resolvable, failing the job", job.library(), job.pid());
        store.fail(job.library(), job.pid(), "library not resolvable");
        return;
      }
      int pages = new DimensionFiller(ready.library(), store).fill(job.pid());
      store.complete(job.library(), job.pid());
      LOG.info(
          "{}/{}: {} pages resolved in {}s",
          job.library(),
          job.pid(),
          pages,
          Duration.between(started, Instant.now()).toSeconds());
    } catch (RuntimeException e) {
      LOG.warn(
          "{}/{} failed on attempt {}: {}", job.library(), job.pid(), job.attempts(), e.toString());
      store.fail(job.library(), job.pid(), e.toString());
    }
  }

  private void runSyncs() {
    for (SizeStore.Sync state : store.allSyncs()) {
      if (!running.get()) {
        return;
      }
      if (state.state() != SizeStore.Sync.State.RUNNING) {
        continue;
      }
      try {
        sync.pass(state);
      } catch (RuntimeException e) {
        LOG.warn("sync pass for {} failed: {}", state.library(), e.toString());
        store.saveSync(
            new SizeStore.Sync(
                state.library(),
                state.state(),
                state.cursor(),
                state.watermark(),
                state.seen(),
                java.util.Optional.of(Instant.now()),
                java.util.Optional.of(e.toString())));
      }
    }
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    running.set(false);
    scheduler.shutdownNow();
    if (consumer != null) {
      consumer.interrupt();
    }
  }
}
