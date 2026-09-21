/*
 * A Solr that answered a cursorMark query without a cursorMark.
 *
 * WHY THIS IS AN EXCEPTION AND NOT A FALLBACK. Solr's contract is that a cursored response carries
 * `nextCursorMark`, and that the walk is finished when that value equals the one sent. A response with no
 * `nextCursorMark` at all satisfies neither — it means this Solr does not implement cursorMark, and the
 * request was answered as an ordinary `rows=` query: the first page, and only the first page.
 *
 * Read as "the cursor did not move", which is what the code used to do, that is indistinguishable from
 * "the walk is complete". A sync pass over 76 million pages would fetch one batch, report itself finished,
 * and advance its watermark past everything it never looked at. Silent, permanent, and invisible in every
 * log line — the same failure shape as querying K7 field names against a K5 index, which is the bug this
 * codebase already carries a scar from.
 *
 * Measured: of the 11 alive K5 libraries, `mendelu` 5.3.2 is the one that does this. The other seven that
 * answer at all return a real nextCursorMark and page correctly, so this is a property of one deployment
 * rather than of Kramerius 5 — which is exactly why it cannot be decided from the version and has to be
 * detected from the response.
 *
 * A start/rows fallback would be the other answer. It is not taken here because it would silently change
 * the consistency guarantee: cursorMark is stable while the index moves underneath and start/rows is not,
 * so a library would be walked with weaker semantics than the one beside it and nothing would say so.
 * Failing names the instance and lets an operator decide.
 */
package cz.mzk.iiif.adapter;

public final class CursorMarkUnsupported extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String baseUrl;

  public CursorMarkUnsupported(String baseUrl, String filter) {
    super(
        baseUrl
            + " answered a cursorMark query with no nextCursorMark, so it does not support"
            + " cursorMark; walking it would silently return only the first batch. Filter: "
            + filter);
    this.baseUrl = baseUrl;
  }

  /** Which instance, so an operator is not left guessing which of fifty-six it was. */
  public String baseUrl() {
    return baseUrl;
  }
}
