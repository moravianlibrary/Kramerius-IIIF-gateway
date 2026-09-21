/*
 * Decides whether to resolve a cold document's dimensions now or hand them to the worker.
 *
 * It PREDICTS rather than times out, and that distinction is the point. count_page arrives in the very
 * first Solr response, before any image server is touched, so the request knows the size of the job up
 * front:
 *
 *   estimate = ceil(uncached / concurrency) x observed_latency     (EWMA, per library)
 *
 * A pure time budget would make every request for a 3000-page cold document burn the full budget and
 * STILL return partial — slow and wrong at once. Predicting means nobody ever waits for an answer that
 * was never going to be complete.
 *
 * This matters because of the distribution: p50 is 12 canvases, p90 233, p95 332, max 3568. Half of all
 * documents resolve completely in well under a second, so the common case is always accurate.
 *
 * The EWMA starts pessimistic. A fresh process has measured nothing, and guessing fast would make the
 * first request for a large document commit to work it cannot finish.
 */
package cz.mzk.iiif.gateway;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import cz.mzk.iiif.spi.DimensionPolicy;

public final class PredictivePolicy implements DimensionPolicy {

  /** Until something is measured, assume an image server takes this long per info.json. */
  private static final long INITIAL_LATENCY_MILLIS = 250;

  /** Weight of the newest observation. Slow enough that one outlier does not move the estimate. */
  private static final double ALPHA = 0.2;

  private final Duration budget;
  private final int concurrency;
  private final AtomicLong latencyMillis = new AtomicLong(INITIAL_LATENCY_MILLIS);

  public PredictivePolicy(Duration budget, int concurrency) {
    this.budget = budget;
    this.concurrency = Math.max(1, concurrency);
  }

  @Override
  public boolean resolveInline(int uncachedPages) {
    if (uncachedPages <= 0) {
      return true;
    }
    long rounds = (long) Math.ceil((double) uncachedPages / concurrency);
    return rounds * latencyMillis.get() <= budget.toMillis();
  }

  /**
   * Feeds back what a batch actually cost, so the estimate tracks the upstream rather than a guess.
   */
  public void observed(int pages, Duration elapsed) {
    if (pages <= 0) {
      return;
    }
    long rounds = Math.max(1, (long) Math.ceil((double) pages / concurrency));
    long perRound = elapsed.toMillis() / rounds;
    latencyMillis.updateAndGet(
        current -> Math.max(1, Math.round(ALPHA * perRound + (1 - ALPHA) * current)));
  }

  /** For the ops view: what this library's image server currently looks like. */
  public long latencyMillis() {
    return latencyMillis.get();
  }
}
