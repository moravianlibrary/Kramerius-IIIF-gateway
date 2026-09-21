/*
 * A size source whose cache can be asked about and written to.
 *
 * The gateway has one; an application reading dimensions locally does not, and implements only
 * ImageSizeSource. Everything that depends on a cache — answering partial, handing work to the worker,
 * reporting coverage — is then skipped rather than faked.
 *
 * Every method takes the DOCUMENT this is about, because that is how a cache is keyed: the access
 * pattern is always "every page of this document", so one row holds them all. An earlier version passed
 * only page pids and smuggled the document through a ThreadLocal, which broke in two ways — a hop to
 * another thread silently turned every read into a miss, and the staleness check never received the
 * timestamps it needed, so it never fired at all.
 *
 * cached() takes page RECORDS rather than pids for that second reason: a cached size is only a hit if it
 * was measured after the page last changed, and `modified` travels on the record. It must stay cheap —
 * one lookup, no fetching.
 */
package cz.mzk.iiif.spi;

import java.util.List;
import java.util.Map;

import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.document.ImageSize;

public interface ManagedImageSizeSource extends ImageSizeSource {

  /**
   * What the cache already holds for these pages, by pid.
   *
   * <p>A page is omitted when nothing is held for it, and when what is held is older than the page
   * itself — the image has been replaced since it was measured, so the size is not this page's
   * size.
   */
  Map<String, ImageSize> cached(String documentPid, List<DocumentRecord> pages);

  /** Records what was measured, so the next request does not measure it again. */
  void store(String documentPid, Map<String, ImageSize> measured);

  /**
   * @param complete whether every page asked about is held
   */
  record CacheStatus(int total, int cached, boolean complete) {}

  CacheStatus status(String documentPid, List<String> pagePids);

  /**
   * How many pages are held for a document, without being told which pages to look for.
   *
   * <p>This is what makes a status route cheap: the count is a property of what is stored, so
   * answering it needs no page list and therefore no walk of the document's children upstream. The
   * caller compares it against {@code DocumentSource.tiledPageCount}, which is one index query.
   */
  int cachedCount(String documentPid);
}
