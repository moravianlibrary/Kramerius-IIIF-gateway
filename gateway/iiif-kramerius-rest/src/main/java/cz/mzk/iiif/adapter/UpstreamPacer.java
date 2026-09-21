/*
 * How fast this gateway is allowed to talk to somebody else's image server.
 *
 * This is not the inbound rate limiter, and neither substitutes for the other. RateLimiter protects the
 * gateway from its callers by REFUSING; this protects fifty third-party institutions from the gateway by
 * WAITING. Refusing would be wrong here — the request is ours, it is legitimate, and dropping it would
 * only turn a slow manifest into a broken one.
 *
 * It is also not the concurrency cap, which bounds how many requests are in flight but says nothing about
 * how many are made. Ten concurrent lookups against an image server answering in 20 ms is 500 requests a
 * second: within the cap and far outside what anyone would call polite. The worker makes that concrete —
 * a full MZK backfill is 75.7 million info.json requests, and at 500/s it would arrive as a sustained
 * denial of service on a library that never agreed to it.
 *
 * One bucket per library, shared by the web and worker roles in a process, because the thing being
 * protected is one institution's server and it does not care which of our roles the request came from.
 *
 * IMPLEMENTED AS A CURSOR, NOT A TOKEN COUNT. `nextFree` is the instant the next request may go out;
 * each caller claims it and pushes it forward by one interval, so callers queue in arrival order and
 * every wait is bounded by the length of the queue ahead of it. The first version kept a token count
 * AND advanced a refill clock, which recorded the same debt twice: each waiting caller made the clock
 * run backwards for the next one, its deficit grew faster than it drained, and a 202-page document
 * stopped answering at all. A single monotonic cursor cannot do that, because there is only one thing
 * to move.
 */
package cz.mzk.iiif.adapter;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class UpstreamPacer {

  /** No pacing at all, for tests and for an operator who sets IIIF_UPSTREAM_RPS=0. */
  public static final UpstreamPacer UNLIMITED = new UpstreamPacer(0);

  /** A server asking for an hour is a server we should stop asking, not one we should sleep on. */
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(2);

  private final boolean pacing;
  private final long intervalNanos;

  /** How far ahead of the cursor a caller may be, so an idle pacer does not throttle a burst. */
  private final long burstNanos;

  /** No request goes out before this. Only ever moves forward. */
  private long nextFree;

  public UpstreamPacer(double ratePerSecond) {
    this.pacing = ratePerSecond > 0;
    this.intervalNanos = pacing ? (long) (1_000_000_000L / ratePerSecond) : 0;
    // A second's worth: a manifest's fan-out starts immediately, and only a sustained walk is
    // paced.
    // Bursting is the normal request shape; sustaining is what a backfill does.
    this.burstNanos = pacing ? (long) (Math.max(1, ratePerSecond) * intervalNanos) : 0;
    // Behind by a full burst: a pacer that has never been used is as idle as one can be. The credit
    // rule in acquire() only ever grants time that has elapsed, so starting the cursor at `now`
    // would leave a fresh pacer no allowance at all and pace the very first document served.
    this.nextFree = System.nanoTime() - burstNanos;
  }

  /**
   * Blocks until one request may be made.
   *
   * <p>Restores the interrupt and returns rather than throwing: an interrupted pacer means the JVM
   * is stopping, and the caller's own shutdown path is what should notice that.
   */
  public void acquire() {
    if (!pacing) {
      return;
    }
    long waitNanos;
    synchronized (this) {
      long now = System.nanoTime();
      // Credit for idle time, capped at one burst. Without the cap an idle pacer would let an
      // unbounded burst through the moment traffic resumed.
      long earliest = now - burstNanos;
      if (nextFree < earliest) {
        nextFree = earliest;
      }
      waitNanos = nextFree - now;
      nextFree += intervalNanos;
    }
    if (waitNanos <= 0) {
      return;
    }
    try {
      TimeUnit.NANOSECONDS.sleep(waitNanos);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Stops issuing for a while, because the server asked us to.
   *
   * <p>A 429 or a 503 is the upstream saying our rate is too high, which is better information than
   * anything we configured — IIIF_UPSTREAM_RPS is our guess about what an institution can take, and
   * this is their answer. Moving the cursor holds every waiting caller, not only the one that got
   * the 429.
   */
  public synchronized void backOff(Duration retryAfter) {
    if (!pacing || retryAfter.isNegative() || retryAfter.isZero()) {
      return;
    }
    long resumeAt = System.nanoTime() + Math.min(retryAfter.toNanos(), MAX_BACKOFF.toNanos());
    if (resumeAt > nextFree) {
      nextFree = resumeAt;
    }
  }
}
