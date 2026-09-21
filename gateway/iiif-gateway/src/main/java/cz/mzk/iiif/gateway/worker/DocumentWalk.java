/*
 * Walks a library's pages in `modified` order and fills their dimensions, document by document.
 *
 * Pages are grouped by their own_parent.pid before fetching, because the cache is keyed by document: a
 * page-at-a-time walk would rewrite the same blob once per page. Grouping is also what lets a document
 * already complete be skipped in a single primary-key lookup.
 *
 * A pass is bounded by time rather than by count, so the worker returns to the job queue regularly
 * instead of disappearing into a 22-day walk and never serving an explicit warm-up request.
 */
package cz.mzk.iiif.gateway.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.adapter.KrameriusRepository;
import cz.mzk.iiif.document.ImageSize;
import cz.mzk.iiif.gateway.Config;
import cz.mzk.iiif.gateway.Libraries;
import cz.mzk.iiif.gateway.store.SizeStore;

final class DocumentWalk {

  private static final Logger LOG = LoggerFactory.getLogger(DocumentWalk.class);

  /** How long one pass runs before parking, so the job queue is not starved. */
  private static final Duration PASS_BUDGET = Duration.ofMinutes(10);

  record Progress(
      int documents,
      Optional<Instant> watermark,
      Optional<String> cursor,
      Optional<String> error) {}

  private final Libraries.Library library;
  private final SizeStore store;
  private final Config config;

  DocumentWalk(Libraries.Library library, SizeStore store, Config config) {
    this.library = library;
    this.store = store;
    this.config = config;
  }

  Progress run(Instant from, Optional<String> resumeCursor) {
    Instant deadline = Instant.now().plus(PASS_BUDGET);
    Instant watermark = from;
    int documents = 0;
    // Only if it belongs to this bound — see WalkCursor. A cursor from a different bound is not an
    // error to Solr, it is rows from the wrong part of the index.
    Optional<String> cursor = WalkCursor.markFor(resumeCursor, from);
    if (cursor.isPresent()) {
      LOG.info("{}: resuming at the stored cursor for {}", library.code(), from);
    }

    try {
      // Slices until the budget runs out, rather than one slice per pass. This is what lets the
      // walk
      // move through a block of pages that all share one `modified` instant: the watermark cannot
      // advance past such a block, so without carrying the cursor forward the same rows would be
      // read on every pass, for ever.
      while (true) {
        KrameriusRepository.PageWindow window =
            library.repository().pageWindow(from, config.workerBatch() * 32, cursor);

        // Grouped by document so the blob is written once per document rather than once per page.
        Map<String, Map<String, ImageSize>> pending = new LinkedHashMap<>();
        for (KrameriusRepository.PageRef page : window.pages()) {
          pending
              .computeIfAbsent(page.documentPid(), key -> new LinkedHashMap<>())
              .put(page.pid(), ImageSize.UNAVAILABLE);
        }

        boolean parked = false;
        for (Map.Entry<String, Map<String, ImageSize>> entry : pending.entrySet()) {
          if (Instant.now().isAfter(deadline)) {
            LOG.info("{}: pass budget reached, parking at {}", library.code(), watermark);
            parked = true;
            break;
          }
          documents += fill(entry.getKey(), List.copyOf(entry.getValue().keySet()));
        }

        Instant latest = latestModified(window.pages());
        if (latest != null && latest.isAfter(watermark)) {
          watermark = latest;
        }
        cursor = window.nextCursor();

        // Exhausted: nothing more for this bound, so the next pass starts from the new watermark
        // and
        // the cursor has nothing left to say.
        if (window.exhausted()) {
          return new Progress(
              documents, Optional.of(watermark), Optional.empty(), Optional.empty());
        }
        if (parked || Instant.now().isAfter(deadline)) {
          return new Progress(
              documents, Optional.of(watermark), saved(cursor, from), Optional.empty());
        }
      }

    } catch (RuntimeException e) {
      // Logged as well as recorded. The Progress error reaches GET /libraries and the sync row, but
      // a pass that aborts on its first window records zero documents and an unmoved watermark —
      // which reads exactly like a quiet pass with nothing to do unless something says otherwise.
      LOG.error(
          "{}: sync pass aborted after {} documents: {}", library.code(), documents, e.toString());
      // The watermark still advanced for whatever completed, so the next pass resumes rather than
      // repeating the whole walk. The cursor goes with it when it is still meaningful.
      return new Progress(
          documents, Optional.of(watermark), saved(cursor, from), Optional.of(e.toString()));
    }
  }

  /**
   * Stores the cursor only while the bound it belongs to is still the one we would query with.
   *
   * <p>Once the watermark has moved past `from`, the next pass builds a different filter and the
   * cursor is meaningless — keeping it would invite exactly the silent wrong-rows failure
   * WalkCursor exists to prevent.
   */
  private Optional<String> saved(Optional<String> cursor, Instant from) {
    return cursor.map(mark -> new WalkCursor(from, mark).encode());
  }

  private int fill(String documentPid, List<String> pagePids) {
    Map<String, ImageSize> known =
        new HashMap<>(
            store
                .find(library.code(), documentPid)
                .map(SizeStore.Document::sizes)
                .orElse(Map.of()));

    List<String> missing = pagePids.stream().filter(pid -> !known.containsKey(pid)).toList();
    if (missing.isEmpty()) {
      return 0;
    }

    known.putAll(library.repository().sizes().sizes(missing));
    store.put(library.code(), documentPid, known);
    return 1;
  }

  /** The greatest `modified` seen, which is where the next pass resumes. */
  private static Instant latestModified(List<KrameriusRepository.PageRef> pages) {
    Instant latest = null;
    for (KrameriusRepository.PageRef page : pages) {
      Instant modified = page.modified().orElse(null);
      if (modified != null && (latest == null || modified.isAfter(latest))) {
        latest = modified;
      }
    }
    return latest;
  }
}
