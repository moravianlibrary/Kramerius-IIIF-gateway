/*
 * The per-IP bucket.
 *
 * Tested for the two properties that matter operationally: a burst is allowed through (a viewer opens
 * many canvases at once and must not be throttled into failure), and one caller cannot starve another.
 */
package cz.mzk.iiif.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

  @Test
  @DisplayName("a burst passes, and then the bucket is empty")
  void burst() {
    RateLimiter limiter = new RateLimiter(1, 5);

    long allowed = 0;
    for (int i = 0; i < 10; i++) {
      if (limiter.allow("10.0.0.1")) {
        allowed++;
      }
    }
    // Five from the bucket, plus at most a token or two refilled while the loop ran.
    assertThat(allowed).isBetween(5L, 6L);
  }

  @Test
  @DisplayName("one caller exhausting its bucket does not affect another")
  void perClient() {
    RateLimiter limiter = new RateLimiter(1, 2);

    while (limiter.allow("10.0.0.1")) {
      // drain the first caller
    }

    assertThat(limiter.allow("10.0.0.2")).isTrue();
  }

  @Test
  @DisplayName("Retry-After is never zero, which would invite an immediate retry")
  void retryAfter() {
    assertThat(new RateLimiter(1000, 10).retryAfterSeconds()).isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("a rate of 0 disables the limiter instead of locking everyone out")
  void zeroDisables() {
    // It used to do the opposite, and silently: the bucket starts full and refills by elapsed *
    // rate, so at rate 0 it drained after `burst` requests and never refilled. Measured against a
    // running gateway — 150 sequential requests gave 94 × 200 then 56 × 429, still 429 fifteen
    // seconds later. Reachable by reading .env.example, which documents 0 as "disables pacing" for
    // the upstream knob three lines above this one.
    RateLimiter limiter = new RateLimiter(0, 2);

    for (int i = 0; i < 500; i++) {
      assertThat(limiter.allow("10.0.0.1")).as("request %d", i).isTrue();
    }
    assertThat(limiter.enabled()).isFalse();
  }

  @Test
  @DisplayName("Retry-After is a number a client can act on, even when disabled")
  void retryAfterIsBounded() {
    // 1.0/0 is Infinity, and (long) Math.ceil(Infinity) is Long.MAX_VALUE — we were sending
    // Retry-After: 9223372036854775807.
    assertThat(new RateLimiter(0, 10).retryAfterSeconds()).isEqualTo(1);
    assertThat(new RateLimiter(0.0001, 10).retryAfterSeconds()).isLessThanOrEqualTo(3600);
    assertThat(new RateLimiter(20, 100).retryAfterSeconds()).isEqualTo(1);
  }

  @Test
  @DisplayName("idle clients are removed once their bucket has refilled")
  void evictsIdleBuckets() {
    RateLimiter limiter = new RateLimiter(1, 2);
    limiter.allow("10.0.0.1");

    limiter.evictIdle(System.nanoTime() + Duration.ofMinutes(6).toNanos(), Duration.ofMinutes(5));

    assertThat(limiter.bucketCount()).isZero();
  }
}
