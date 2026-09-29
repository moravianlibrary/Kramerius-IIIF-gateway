/*
 * Whether a request should resolve a document's page dimensions itself, or answer now and leave the work
 * to someone else.
 *
 * This is a policy, not a fact, so it is injected rather than decided here. The gateway implements it
 * from a per-library latency estimate against a wall-clock budget: uncached x observed latency / bounded
 * concurrency, compared with IIIF_DIMENSION_WAIT. A host with no worker to hand the work to implements
 * ALWAYS and resolves everything inline.
 *
 * The point of predicting rather than timing out is that p50 is twelve canvases: half of all documents
 * resolve completely in well under a second, while a 3000-page cold document would burn an entire time
 * budget and *still* come back partial. Deciding up front means nobody waits for an answer that was never
 * going to be complete.
 */
package cz.mzk.iiif.spi;

@FunctionalInterface
public interface DimensionPolicy {

  /** Resolve everything inline, however long it takes. Correct for any host without a worker. */
  DimensionPolicy ALWAYS = uncachedPages -> true;

  /** Never fetch on a request; serve what is cached and let the worker fill the rest. */
  DimensionPolicy NEVER = uncachedPages -> false;

  /**
   * @param uncachedPages how many of the document's tiled pages have no cached size
   * @return true to fetch them now, false to answer with what is cached and enqueue the rest
   */
  boolean resolveInline(int uncachedPages);
}
