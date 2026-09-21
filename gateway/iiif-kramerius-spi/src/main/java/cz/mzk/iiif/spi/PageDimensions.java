/*
 * Turns a document's pages into page sizes, deciding how much to pay for them.
 *
 * The shape of this is the whole cold-cache design in one method:
 *
 *   1. Pages with no tiles are skipped entirely — they have no Image API endpoint to ask.
 *   2. Ask the cache what it already has. This must be cheap; that is the ManagedImageSizeSource
 *      contract.
 *   3. Hand the shortfall to the policy. Under budget, fetch now and return a complete manifest; over
 *      it, return what we have and let the caller enqueue the rest.
 *
 * A source with no addressable cache skips step 2 and resolves everything inline, because there is
 * nothing to hand the work to.
 *
 * A failed lookup counts as resolved, not missing. It records that the image server was asked and
 * did not answer; treating it as missing would retry a broken page on every request forever, which is
 * the exact failure the sentinel exists to prevent. The canvas falls back to a nominal size.
 */
package cz.mzk.iiif.spi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.document.ImageSize;

final class PageDimensions {

  private static final Logger LOG = LoggerFactory.getLogger(PageDimensions.class);

  /**
   * @param sizes what we know, keyed by page pid; a pid may be absent
   * @param complete false when pages were deliberately left unresolved, so the manifest is
   *     provisional
   * @param unresolved the pids left for the worker, empty when complete
   */
  record Resolved(Map<String, ImageSize> sizes, boolean complete, List<String> unresolved) {

    static Resolved complete(Map<String, ImageSize> sizes) {
      return new Resolved(Map.copyOf(sizes), true, List.of());
    }

    /** The size to declare for a page, or empty to fall back to the nominal one. */
    Optional<ImageSize> known(String pid) {
      return Optional.ofNullable(sizes.get(pid)).filter(ImageSize::isKnown);
    }
  }

  private PageDimensions() {}

  static Resolved resolve(
      Repository repository,
      DimensionPolicy policy,
      String documentPid,
      List<DocumentRecord> pages) {
    List<String> tiled =
        pages.stream().filter(DocumentRecord::hasTiles).map(DocumentRecord::pid).toList();
    if (tiled.isEmpty()) {
      return Resolved.complete(Map.of());
    }

    Optional<ManagedImageSizeSource> managed = repository.managed();
    if (managed.isEmpty()) {
      // Nowhere to defer the work to, so the choice does not arise.
      return Resolved.complete(repository.sizes().sizes(tiled));
    }

    // The records, not just the pids: a cached size counts only if it was measured after the page
    // last changed, and `modified` is on the record.
    Map<String, ImageSize> known = new HashMap<>(managed.get().cached(documentPid, pages));
    List<String> missing = tiled.stream().filter(pid -> !known.containsKey(pid)).toList();
    if (missing.isEmpty()) {
      LOG.debug("all {} tiled pages served from cache", tiled.size());
      return Resolved.complete(known);
    }

    if (!policy.resolveInline(missing.size())) {
      LOG.debug(
          "{} of {} tiled pages uncached; answering partial and leaving them to the worker",
          missing.size(),
          tiled.size());
      return new Resolved(Map.copyOf(known), false, missing);
    }

    Map<String, ImageSize> measured = repository.sizes().sizes(missing);
    // Write through explicitly rather than as a side effect of reading, so the cache is asked to
    // remember only what this request actually measured.
    managed.get().store(documentPid, measured);
    known.putAll(measured);
    LOG.debug("resolved {} of {} tiled pages inline", missing.size(), tiled.size());
    return Resolved.complete(known);
  }
}
