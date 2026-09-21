/*
 * That a saved cursor is only ever used for the query that minted it.
 *
 * This is the whole reason the type exists. Solr does not reject a cursorMark handed to a different
 * query — it answers, with rows from wherever that token happens to point. A walk that reused one
 * across a changed `modified` bound would skip pages and report success, which is the worst shape a
 * backfill defect can take: silent, and invisible until someone notices a document with no sizes.
 */
package cz.mzk.iiif.gateway.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WalkCursorTest {

  private static final Instant BOUND = Instant.parse("2024-05-06T07:08:09.123Z");
  private static final String MARK = "AoIIP4AAACpjb21wb3NpdGVJZA==";

  @Test
  @DisplayName("a cursor survives a round trip through the store's text column")
  void roundTrips() {
    Optional<WalkCursor> decoded = WalkCursor.decode(new WalkCursor(BOUND, MARK).encode());

    assertThat(decoded).contains(new WalkCursor(BOUND, MARK));
  }

  @Test
  @DisplayName("it is used when the bound is the one it was minted for")
  void reusedForTheSameBound() {
    // The case that matters: a block of pages sharing one `modified` instant. The watermark cannot
    // advance past it, so the bound is unchanged and the cursor is the only thing that moves the
    // walk forward.
    String stored = new WalkCursor(BOUND, MARK).encode();

    assertThat(WalkCursor.markFor(Optional.of(stored), BOUND)).contains(MARK);
  }

  @Test
  @DisplayName("it is discarded when the bound has moved")
  void discardedForADifferentBound() {
    String stored = new WalkCursor(BOUND, MARK).encode();

    assertThat(WalkCursor.markFor(Optional.of(stored), BOUND.plusMillis(1))).isEmpty();
    assertThat(WalkCursor.markFor(Optional.of(stored), BOUND.minusSeconds(600))).isEmpty();
  }

  @Test
  @DisplayName("an unparseable cursor is discarded, not fatal")
  void garbageIsDiscarded() {
    // A row written by an older build, or edited by hand. The watermark can carry the walk, and
    // failing a 22-day backfill over a malformed token would be the wrong trade.
    assertThat(WalkCursor.markFor(Optional.of("not-a-cursor"), BOUND)).isEmpty();
    assertThat(WalkCursor.markFor(Optional.of(""), BOUND)).isEmpty();
    assertThat(WalkCursor.markFor(Optional.of(" " + MARK), BOUND)).isEmpty();
    assertThat(WalkCursor.markFor(Optional.of(BOUND + " "), BOUND)).isEmpty();
    assertThat(WalkCursor.markFor(Optional.empty(), BOUND)).isEmpty();
  }

  @Test
  @DisplayName("a base64 cursor keeps its padding, separator or not")
  void marksMayContainAwkwardCharacters() {
    // cursorMark is base64 and ends in '=' often enough that a naive split on the wrong character
    // would corrupt it. The bound is parsed off the front, and the rest is the token verbatim.
    String stored = new WalkCursor(BOUND, MARK).encode();

    assertThat(WalkCursor.decode(stored).orElseThrow().mark()).isEqualTo(MARK);
  }
}
