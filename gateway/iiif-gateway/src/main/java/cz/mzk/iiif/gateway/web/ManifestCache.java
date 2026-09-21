/*
 * Rendered manifests, kept for a while.
 *
 * The size cache made the DIMENSIONS free. This makes the rest free: with sizes warm, a 202-page MZK
 * monograph still costs 190-520 ms and 272 KB to rebuild — the Solr walk over its pages, the metadata
 * assembly, and serialising a quarter of a megabyte of JSON — and every one of those requests produces
 * byte-for-byte the same answer. That is a cache's definition, so this holds the rendered BYTES rather
 * than the document tree: serialisation is a real part of the cost, and the ETag is a hash of the bytes,
 * so a hit answers a conditional request without touching Kramerius at all.
 *
 * Three rules, each of which would be a bug if it were not here.
 *
 * NOTHING PARTIAL. A partial manifest carries nominal sizes for pages the worker has not reached, and
 * caching it would keep serving 1000x1000 canvases long after the real dimensions had been measured —
 * the exact defect v1 had, reintroduced one layer up. Partial responses are already `no-store` to
 * clients, and this agrees with that.
 *
 * An APPROXIMATE manifest is kept, on exactly the terms a complete one is. Its pages can become
 * measurable — a licence expires, the gateway is whitelisted, an image is re-derived — but so can a
 * measured page change, and the TTL is what covers both: nothing here claims to be permanent, only to be
 * no staler than IIIF_MANIFEST_TTL_MINUTES. What makes PARTIAL different is not that it is less certain
 * but that work is already queued against it, so an entry would outlive the answer it is waiting for.
 * The entry carries its own state so a hit reports X-IIIF-Dimensions the same way a miss does.
 *
 * A TTL, NOT JUST A CAPACITY. What is cached is derived from someone else's Solr index, and nothing tells
 * us when that changes. IIIF_MANIFEST_TTL_MINUTES already bounds how stale a client's copy may be; an
 * entry here must not outlive it, or the gateway would serve a fresher answer to a cold client than to a
 * warm one.
 *
 * BOUNDED BY ENTRY COUNT, WITH THE TAIL IN MIND. 500 entries is small in the ordinary case — the median
 * manifest is 12 canvases, about 7 KB — but the largest MZK document is 3568 canvases, so a pathological
 * working set of 500 of those would be ~2 GB. Hence maxBytes as well: whichever bound is hit first
 * evicts. The count is what an operator tunes; the byte ceiling is what stops the tail from mattering.
 */
package cz.mzk.iiif.gateway.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import cz.mzk.iiif.gateway.Manifests;

public final class ManifestCache {

  /** Never hold more than this, whatever the entry count allows. */
  private static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

  /**
   * @param dimensions stored with the bytes because it is a property OF those bytes. Left off, a
   *     cache hit would serve an approximate manifest without the header that says so, and the same
   *     document would describe itself differently warm and cold.
   */
  public record Entry(
      String json, String etag, Manifests.Dimensions dimensions, Instant storedAt) {}

  private final int maxEntries;
  private final long maxBytes;
  private final Duration ttl;

  private long bytes;

  /** Access-ordered, so eviction drops the least recently USED rather than the oldest. */
  private final Map<String, Entry> entries =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
          return false; // Eviction is done in put(), which also has to keep the byte count.
        }
      };

  public ManifestCache(int maxEntries, Duration ttl) {
    this(maxEntries, ttl, DEFAULT_MAX_BYTES);
  }

  public ManifestCache(int maxEntries, Duration ttl, long maxBytes) {
    this.maxEntries = maxEntries;
    this.ttl = ttl;
    this.maxBytes = maxBytes;
  }

  public boolean enabled() {
    return maxEntries > 0 && !ttl.isZero() && !ttl.isNegative();
  }

  public synchronized Optional<Entry> get(String library, String pid) {
    if (!enabled()) {
      return Optional.empty();
    }
    String key = key(library, pid);
    Entry entry = entries.get(key);
    if (entry == null) {
      return Optional.empty();
    }
    if (entry.storedAt().plus(ttl).isBefore(Instant.now())) {
      remove(key);
      return Optional.empty();
    }
    return Optional.of(entry);
  }

  /** A partial manifest is refused here rather than at the call site — see the header comment. */
  public synchronized void put(
      String library, String pid, String json, String etag, Manifests.Dimensions dimensions) {
    if (!enabled() || !dimensions.cacheable()) {
      return;
    }
    String key = key(library, pid);
    remove(key);
    entries.put(key, new Entry(json, etag, dimensions, Instant.now()));
    bytes += json.length();
    evict();
  }

  /** Drops a document, for DELETE …/dimensions: its canvases are about to change. */
  public synchronized void evict(String library, String pid) {
    remove(key(library, pid));
  }

  public synchronized int size() {
    return entries.size();
  }

  public synchronized long bytes() {
    return bytes;
  }

  private void evict() {
    Iterator<Map.Entry<String, Entry>> oldest = entries.entrySet().iterator();
    while ((entries.size() > maxEntries || bytes > maxBytes) && oldest.hasNext()) {
      bytes -= oldest.next().getValue().json().length();
      oldest.remove();
    }
  }

  private void remove(String key) {
    Entry removed = entries.remove(key);
    if (removed != null) {
      bytes -= removed.json().length();
    }
  }

  private static String key(String library, String pid) {
    return library + "/" + pid;
  }
}
