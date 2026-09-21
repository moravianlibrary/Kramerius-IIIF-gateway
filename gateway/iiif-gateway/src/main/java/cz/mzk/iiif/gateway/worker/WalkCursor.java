/*
 * A saved cursorMark, together with the query bound it is valid for.
 *
 * A cursorMark means nothing on its own. Solr defines it only for the query that minted it — same q,
 * same sort, same rows — and handing one back with a different filter is undefined behaviour, not an
 * error: no exception, just rows from somewhere else in the index. The sync walk's filter contains its
 * lower bound on `modified`, and that bound moves as the watermark advances, so a cursor saved by one
 * pass is worthless to the next unless the bound happens to be the same.
 *
 * It IS the same in exactly the case where the cursor is the only thing that helps. When more pages
 * share one `modified` instant than a pass can read, the greatest `modified` in the pass equals the one
 * it started from, the watermark does not move, and a watermark-only walk re-reads those same rows
 * forever. Whole documents are ingested at a single instant and MZK's largest is 3568 pages, so this
 * is a real way for a backfill to stop making progress rather than a theoretical one.
 *
 * So this is stored, and checked before use. The alternative was a second column, which is honest but
 * means migrating a table for a value nothing outside this walk can interpret — and the cursor column
 * is already an opaque token as far as the store is concerned. If it does not parse, or the bound does
 * not match, it is discarded and the walk resumes from the watermark, which is what the design says the
 * watermark is for.
 */
package cz.mzk.iiif.gateway.worker;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

record WalkCursor(Instant from, String mark) {

  private static final String SEPARATOR = " ";

  /** The stored form: the bound, a space, then Solr's token. */
  String encode() {
    return from.toString() + SEPARATOR + mark;
  }

  /** The cursor to use for this bound, or empty when there is nothing usable. */
  static Optional<String> markFor(Optional<String> stored, Instant from) {
    return stored
        .flatMap(WalkCursor::decode)
        .filter(saved -> saved.from().equals(from))
        .map(WalkCursor::mark);
  }

  static Optional<WalkCursor> decode(String stored) {
    int separator = stored.indexOf(SEPARATOR);
    if (separator <= 0 || separator == stored.length() - 1) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new WalkCursor(
              Instant.parse(stored.substring(0, separator)), stored.substring(separator + 1)));
    } catch (DateTimeParseException e) {
      // A cursor written by an older build, or a hand-edited row. Not worth failing a 22-day walk
      // over when the watermark can carry it.
      return Optional.empty();
    }
  }
}
