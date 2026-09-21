/*
 * Resolves every page dimension beneath one pid, and records it.
 *
 * The walk is recursive and has NO depth or size limit, on purpose. Completion is recorded per document,
 * so a re-run skips finished subtrees in one primary-key lookup each and an interrupted walk resumes
 * rather than restarting. Limits would protect against a cost that idempotence already removes.
 *
 * A genuinely uncached 500 000-page periodical is therefore expensive ONCE, bounded by the worker pool,
 * and free thereafter — which is the behaviour a size cache exists to provide.
 */
package cz.mzk.iiif.gateway.worker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.document.ImageSize;
import cz.mzk.iiif.gateway.Libraries;
import cz.mzk.iiif.gateway.store.SizeStore;
import cz.mzk.iiif.spi.PrefetchPlanner;

final class DimensionFiller {

  private static final Logger LOG = LoggerFactory.getLogger(DimensionFiller.class);

  private final Libraries.Library library;
  private final SizeStore store;

  DimensionFiller(Libraries.Library library, SizeStore store) {
    this.library = library;
    this.store = store;
  }

  /** Returns how many pages were newly measured. */
  int fill(String pid) {
    List<PrefetchPlanner.DocumentPages> plan = PrefetchPlanner.plan(library.repository(), pid);

    int measured = 0;
    for (PrefetchPlanner.DocumentPages document : plan) {
      measured += fillDocument(document);
    }
    return measured;
  }

  private int fillDocument(PrefetchPlanner.DocumentPages document) {
    Map<String, ImageSize> known =
        new HashMap<>(
            store
                .find(library.code(), document.documentPid())
                .map(SizeStore.Document::sizes)
                .orElse(Map.of()));

    List<String> missing =
        document.tiledPagePids().stream().filter(pid -> !known.containsKey(pid)).toList();

    if (missing.isEmpty()) {
      // Already complete. One lookup, no upstream requests — this is what makes a re-run cheap and
      // why the recursive walk needs no limits.
      return 0;
    }

    // Goes straight to the image server rather than through the request-path cache, because there
    // is
    // no per-request document context here and the write is explicit below.
    Map<String, ImageSize> fetched = library.repository().sizes().sizes(missing);
    known.putAll(fetched);
    store.put(library.code(), document.documentPid(), known);

    LOG.debug("{}/{}: measured {} pages", library.code(), document.documentPid(), fetched.size());
    return fetched.size();
  }
}
