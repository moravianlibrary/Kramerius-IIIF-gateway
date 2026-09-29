/*
 * A document with more children than IIIF_MAX_ITEMS.
 *
 * The ceiling is a sanity limit, an order of magnitude above the largest real document (3568 items at
 * MZK), so a misindexed or pathological object is visible rather than fatal — v1 had no ceiling and no
 * cap either, which is a heap exhaustion waiting for one bad record.
 *
 * Typed for the same reason as UpstreamFailure: this case has a status of its
 * own, and until this existed the web layer could not tell it apart from any other IllegalStateException
 * and answered 500. It is deliberately NOT an UpstreamFailure — the instance answered, correctly and
 * completely; the refusal is ours, and telling an operator their Kramerius is broken would send them
 * looking in the wrong place. What they need to do is raise the ceiling or fix the record.
 */
package cz.mzk.iiif.adapter;

public final class DocumentTooLarge extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int ceiling;

  public DocumentTooLarge(String filter, int ceiling) {
    super("document has more than " + ceiling + " children: " + filter);
    this.ceiling = ceiling;
  }

  /** The limit that was exceeded, so the error can name the knob that changes it. */
  public int ceiling() {
    return ceiling;
  }
}
