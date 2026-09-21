/*
 * That the pacer paces, and — more importantly — that it terminates.
 *
 * The first version of this class deadlocked in production-shaped use: it kept a token count and also
 * pushed a refill clock forward, so every waiting caller made the clock run backwards for the next one.
 * The deficit grew faster than it drained and a 202-page manifest simply stopped answering. Nothing in
 * the unit tests noticed, because a single-caller test always takes the fast path.
 *
 * So the load-bearing test here is the concurrent one: N callers, and the whole thing has to finish in
 * something close to N/rate rather than never.
 */
package cz.mzk.iiif.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class UpstreamPacerTest {

  @Test
  @DisplayName("rate 0 does not pace at all")
  @Timeout(5)
  void unlimitedNeverWaits() {
    UpstreamPacer pacer = UpstreamPacer.UNLIMITED;

    long started = System.nanoTime();
    for (int i = 0; i < 10_000; i++) {
      pacer.acquire();
    }

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  @DisplayName("a burst goes straight through, so an ordinary manifest is not slowed down")
  @Timeout(10)
  void burstIsNotPaced() {
    // p50 is 12 canvases. If the median document had to wait on a rate limiter, the limiter would
    // be
    // costing latency on every request to protect against a load nobody is generating.
    UpstreamPacer pacer = new UpstreamPacer(50);

    long started = System.nanoTime();
    for (int i = 0; i < 50; i++) {
      pacer.acquire();
    }

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(300));
  }

  @Test
  @DisplayName("sustained calls are held to the rate")
  @Timeout(20)
  void sustainedCallsArePaced() {
    // 20 past the burst of 20, at 20/s, so about a second of pacing.
    UpstreamPacer pacer = new UpstreamPacer(20);

    long started = System.nanoTime();
    for (int i = 0; i < 40; i++) {
      pacer.acquire();
    }
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

    assertThat(elapsed).isBetween(Duration.ofMillis(700), Duration.ofSeconds(3));
  }

  @Test
  @DisplayName("many threads at once finish in queue time, not never")
  @Timeout(30)
  void concurrentCallersTerminate() throws InterruptedException {
    // THE regression test. 60 callers at 30/s past a burst of 30 is ~1s of real pacing; the broken
    // version's waits grew super-linearly with the number of waiters and never finished.
    UpstreamPacer pacer = new UpstreamPacer(30);
    int callers = 60;
    CountDownLatch go = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(callers);

    for (int i = 0; i < callers; i++) {
      Thread thread =
          new Thread(
              () -> {
                try {
                  go.await();
                  pacer.acquire();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
      thread.setDaemon(true);
      thread.start();
    }

    long started = System.nanoTime();
    go.countDown();

    assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("a Retry-After holds everyone, not just the caller that got the 429")
  @Timeout(10)
  void backOffHoldsTheWholePacer() {
    UpstreamPacer pacer = new UpstreamPacer(1000);
    pacer.acquire();

    pacer.backOff(Duration.ofMillis(400));
    long started = System.nanoTime();
    pacer.acquire();

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofMillis(300));
  }

  @Test
  @DisplayName("backing off an unpaced pacer does nothing")
  @Timeout(5)
  void backOffOnUnlimitedIsANoOp() {
    // An operator who set IIIF_UPSTREAM_RPS=0 asked for no pacing, and a 429 must not silently
    // reintroduce it — if they want backpressure they can set a rate.
    UpstreamPacer.UNLIMITED.backOff(Duration.ofMinutes(1));

    long started = System.nanoTime();
    UpstreamPacer.UNLIMITED.acquire();

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(100));
  }
}
