/*
 * Per-IP token bucket on the public routes.
 *
 * This protects the gateway from its callers. It is NOT what protects the fifty institutions whose
 * Kramerius instances this gateway reads — that is the upstream concurrency cap, and it matters more,
 * because a single request for a cold periodical can become thousands of requests to somebody else's
 * image server. The two limits are deliberately separate and neither substitutes for the other.
 */
package cz.mzk.iiif.gateway.web;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RateLimiter {

  private final double ratePerSecond;
  private final double burst;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  private static final class Bucket {
    double tokens;
    long lastRefillNanos;

    Bucket(double tokens, long now) {
      this.tokens = tokens;
      this.lastRefillNanos = now;
    }
  }

  /**
   * @param ratePerSecond refill rate; <b>0 or less disables the limiter entirely</b>
   *     <p>Disabling has to be explicit, because without it a rate of 0 was the worst possible
   *     behaviour rather than the obvious one: the bucket starts full and refills by {@code elapsed
   *     * rate}, so at rate 0 it drains after `burst` requests and never refills again. Every
   *     caller was locked out permanently, from the hundred-and-first request until a restart.
   *     Measured: 150 sequential requests gave 94 × 200 then 56 × 429, and it had not recovered
   *     fifteen seconds later.
   *     <p>That was reachable by reading .env.example, which documents 0 as "disables pacing" for
   *     IIIF_UPSTREAM_RPS three lines above IIIF_RATE_LIMIT_RPS. Expecting the two to agree is not
   *     a mistake an operator should pay for with an outage.
   */
  public RateLimiter(double ratePerSecond, int burst) {
    this.ratePerSecond = ratePerSecond;
    this.burst = burst;
  }

  /** Whether the limiter is doing anything at all. See the constructor. */
  public boolean enabled() {
    return ratePerSecond > 0;
  }

  public boolean allow(String client) {
    if (!enabled()) {
      return true;
    }
    long now = System.nanoTime();
    Bucket bucket = buckets.computeIfAbsent(client, key -> new Bucket(burst, now));

    synchronized (bucket) {
      double elapsedSeconds = (now - bucket.lastRefillNanos) / 1_000_000_000.0;
      bucket.lastRefillNanos = now;
      bucket.tokens = Math.min(burst, bucket.tokens + elapsedSeconds * ratePerSecond);

      if (bucket.tokens < 1.0) {
        return false;
      }
      bucket.tokens -= 1.0;
      return true;
    }
  }

  /**
   * Seconds a refused caller should wait. Always at least one, so Retry-After is never "0" — and
   * bounded, because at rate 0 this computed 1.0/0 = Infinity and sent {@code Retry-After:
   * 9223372036854775807}, which is not a number any client can act on.
   */
  public long retryAfterSeconds() {
    if (!enabled()) {
      return 1;
    }
    return Math.max(1, Math.min(3600, (long) Math.ceil(1.0 / ratePerSecond)));
  }

  /**
   * Drops buckets that have refilled completely, so a scan does not leak an entry per source
   * address. Called on a timer rather than per request.
   */
  public void evictIdle() {
    long now = System.nanoTime();
    buckets
        .entrySet()
        .removeIf(
            entry -> {
              Bucket bucket = entry.getValue();
              synchronized (bucket) {
                double elapsed = (now - bucket.lastRefillNanos) / 1_000_000_000.0;
                return bucket.tokens + elapsed * ratePerSecond >= burst
                    && elapsed > Duration.ofMinutes(5).toSeconds();
              }
            });
  }
}
