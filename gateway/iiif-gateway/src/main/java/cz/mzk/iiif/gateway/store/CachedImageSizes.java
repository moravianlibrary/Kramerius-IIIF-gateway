/*
 * The size cache in front of the image server.
 *
 * Two things here are the whole caching design:
 *
 * The cache is keyed by DOCUMENT but asked about by PAGE, so cached() reads one row and filters. That is
 * what makes step 2 of PageDimensions.resolve cheap enough to do on every request.
 *
 * Staleness is checked on every READ, not only by the sync loop: a cached size counts as a hit only if
 * fetched_at >= the page's `modified`. This costs nothing, because `modified` is already in the fl= of
 * the query that fetched the page's label and number. It matters because sync is opt-in per library —
 * for most libraries the request path is the ONLY thing that will ever notice a replaced image.
 *
 * The document and the timestamps arrive as arguments. An earlier version carried them in a ThreadLocal
 * set by the caller before assembly, which failed at exactly the moment it mattered: the request had no
 * page list yet when it set the context, so the map was empty, and the staleness check above compared
 * against nothing and never fired once.
 */
package cz.mzk.iiif.gateway.store;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.document.ImageSize;
import cz.mzk.iiif.spi.ImageSizeSource;
import cz.mzk.iiif.spi.ManagedImageSizeSource;

public final class CachedImageSizes implements ManagedImageSizeSource {

  private final SizeStore store;
  private final ImageSizeSource upstream;
  private final String library;

  /** How long a failed lookup is believed before it is worth asking again. */
  private final Duration failedRetry;

  public CachedImageSizes(
      SizeStore store, ImageSizeSource upstream, String library, Duration failedRetry) {
    this.store = store;
    this.upstream = upstream;
    this.library = library;
    this.failedRetry = failedRetry;
  }

  @Override
  public Map<String, ImageSize> cached(String documentPid, List<DocumentRecord> pages) {
    Optional<SizeStore.Document> row = store.find(library, documentPid);
    if (row.isEmpty()) {
      return Map.of();
    }

    Instant fetchedAt = row.get().fetchedAt();
    Map<String, ImageSize> stored = row.get().sizes();
    Map<String, ImageSize> hits = new LinkedHashMap<>();

    Instant provisionalExpireBefore = Instant.now().minus(failedRetry);

    for (DocumentRecord page : pages) {
      ImageSize size = stored.get(page.pid());
      if (size == null) {
        continue;
      }
      // The image has been replaced since we measured it, so what we hold is not this page's size.
      if (page.modified().map(fetchedAt::isBefore).orElse(false)) {
        continue;
      }
      // Anything that is not a measurement expires: a failed lookup, and an approximation derived
      // from a thumbnail. Both are honoured briefly, so a restricted document is not re-probed on
      // every request, and both are re-asked later, so a page becoming measurable upgrades instead
      // of keeping a guess forever. Only a real measurement is permanent.
      if ((!size.isKnown() || size.isApproximate())
          && fetchedAt.isBefore(provisionalExpireBefore)) {
        continue;
      }
      hits.put(page.pid(), size);
    }
    return Map.copyOf(hits);
  }

  /**
   * Straight through to the image server.
   *
   * <p>Reading does not write: the caller says what to remember and for which document, because
   * only it knows that. See store().
   */
  @Override
  public Map<String, ImageSize> sizes(List<String> pagePids) {
    return upstream.sizes(pagePids);
  }

  /**
   * Merges what was just measured into the document's row.
   *
   * <p>Read-modify-write rather than append, because the blob is written whole. A single changed
   * page rewrites its document's blob, which is rare and ~85 KB at the very largest.
   */
  @Override
  public void store(String documentPid, Map<String, ImageSize> measured) {
    if (measured.isEmpty()) {
      return;
    }
    Map<String, ImageSize> merged =
        new HashMap<>(
            store.find(library, documentPid).map(SizeStore.Document::sizes).orElse(Map.of()));
    merged.putAll(measured);
    store.put(library, documentPid, merged);
  }

  @Override
  public CacheStatus status(String documentPid, List<String> pagePids) {
    Map<String, ImageSize> stored =
        store.find(library, documentPid).map(SizeStore.Document::sizes).orElse(Map.of());
    int hits = (int) pagePids.stream().filter(stored::containsKey).count();
    return new CacheStatus(pagePids.size(), hits, hits == pagePids.size() && !pagePids.isEmpty());
  }

  @Override
  public int cachedCount(String documentPid) {
    // One primary-key lookup. The blob is the whole document's sizes, so its entry count is the
    // answer — no page list to be handed, and nothing asked of the library's Kramerius.
    return store.find(library, documentPid).map(document -> document.sizes().size()).orElse(0);
  }

  /**
   * How many of those are shapes read from a thumbnail rather than measurements.
   *
   * <p>Counted so /dimensions can say the same thing X-IIIF-Dimensions does. Without it a fully
   * restricted document reports `complete` from the route while the header on the very same request
   * says `approximate` — the two surfaces describing one document differently, which is the kind of
   * disagreement that costs an afternoon.
   *
   * <p>Same single lookup as cachedCount: the blob is already in hand.
   */
  public int approximateCount(String documentPid) {
    return store
        .find(library, documentPid)
        .map(
            document ->
                (int) document.sizes().values().stream().filter(ImageSize::isApproximate).count())
        .orElse(0);
  }
}
