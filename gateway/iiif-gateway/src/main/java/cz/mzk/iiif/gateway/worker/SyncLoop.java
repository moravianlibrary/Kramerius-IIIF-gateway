/*
 * One pass of a library's dimension walk.
 *
 * From `watermark`, page through
 *
 *     modified:[watermark TO *] AND model:page AND has_tiles:true
 *
 * by cursorMark in **modified asc** order, fetching info.json for pages that are missing — or cached but
 * whose `modified` is newer than our fetched_at — advancing the watermark to the greatest `modified` in
 * each batch AS THAT BATCH COMPLETES.
 *
 * The sort order is load-bearing. Because the walk is ordered by the same field the watermark tracks,
 * and the watermark moves after every batch, **the watermark alone is a valid resume point**: a run that
 * dies on day 12 of 22 restarts where it stopped even if the cursorMark is unusable — the query changed,
 * the cursor expired, the index was rebuilt. The persisted cursor is the fast path; the watermark is the
 * one that always works. Ordering by anything else would make the cursor the only resume point, and a
 * lost cursor would mean starting from zero.
 *
 * Ties are common — a whole document is ingested at one instant, so hundreds of pages share a `modified`
 * — so the watermark cannot advance past a tie boundary safely. The overlap window covers that, and
 * re-processing a handful of already-cached pages costs a bulk lookup and no upstream requests.
 *
 * `modified` and not `indexed`: what we cache is a function of the IMAGE, not of the index. A full
 * reindex bumps `indexed` on all 76 million MZK pages and would force a complete re-scan that fetches
 * nothing.
 */
package cz.mzk.iiif.gateway.worker;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.gateway.Config;
import cz.mzk.iiif.gateway.Libraries;
import cz.mzk.iiif.gateway.store.SizeStore;

final class SyncLoop {

  private static final Logger LOG = LoggerFactory.getLogger(SyncLoop.class);

  private final Config config;
  private final Libraries libraries;
  private final SizeStore store;

  SyncLoop(Config config, Libraries libraries, SizeStore store) {
    this.config = config;
    this.libraries = libraries;
    this.store = store;
  }

  void pass(SizeStore.Sync state) {
    if (!(libraries.resolve(state.library()) instanceof Libraries.Resolution.Ready ready)) {
      LOG.warn("sync skipped: {} does not resolve", state.library());
      return;
    }

    // Re-scan a small overlap rather than trusting the boundary exactly: a document committed
    // slightly out of order would otherwise be stranded on it forever.
    Instant from = state.watermark().minus(config.syncOverlap());
    LOG.info("{}: sync pass from {}", state.library(), from);

    DocumentWalk walk = new DocumentWalk(ready.library(), store, config);
    DocumentWalk.Progress progress = walk.run(from, state.cursor());

    store.saveSync(
        new SizeStore.Sync(
            state.library(),
            SizeStore.Sync.State.RUNNING,
            progress.cursor(),
            progress.watermark().orElse(state.watermark()),
            state.seen() + progress.documents(),
            Optional.of(Instant.now()),
            progress.error()));

    LOG.info(
        "{}: {} documents, watermark now {}",
        state.library(),
        progress.documents(),
        progress.watermark().orElse(state.watermark()));
  }
}
