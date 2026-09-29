/*
 * The rules that make a manifest cache safe rather than just fast.
 *
 * Two of these have the same failure mode as the bug this cache sits on top of: serving dimensions that
 * were true once. A cache that forgets to expire, or that remembers a partial manifest, reintroduces
 * v1's permanently-1000x1000 canvases one layer up — and it does so invisibly, because the response is
 * still valid IIIF.
 */
package cz.mzk.iiif.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cz.mzk.iiif.gateway.Manifests;

class ManifestCacheTest {

  private static final String LIBRARY = "mzk";
  private static final String PID = "uuid:doc";
  private static final Duration HOUR = Duration.ofHours(1);

  @Test
  @DisplayName("a stored manifest comes back with its ETag")
  void storedManifestIsReturned() {
    ManifestCache cache = new ManifestCache(10, HOUR);
    cache.put(LIBRARY, PID, "{\"id\":\"x\"}", "etag-1", Manifests.Dimensions.COMPLETE);

    assertThat(cache.get(LIBRARY, PID)).isPresent();
    assertThat(cache.get(LIBRARY, PID).orElseThrow().etag()).isEqualTo("etag-1");
  }

  @Test
  @DisplayName("a partial manifest is refused, whatever the caller asks for")
  void partialIsNeverStored() {
    // The rule this cache exists to not break: a partial manifest carries nominal sizes for pages
    // the worker has not reached, and remembering it reintroduces v1's permanent 1000x1000.
    ManifestCache cache = new ManifestCache(10, HOUR);
    cache.put(LIBRARY, PID, "{\"id\":\"x\"}", "etag-1", Manifests.Dimensions.PARTIAL);

    assertThat(cache.get(LIBRARY, PID)).isEmpty();
    assertThat(cache.bytes()).isZero();
  }

  @Test
  @DisplayName("an approximate manifest is stored, and remembers that it is approximate")
  void approximateSurvivesTheRoundTrip() {
    // Kept on the same terms as a complete one — the TTL, not a claim of permanence. What matters
    // here is that a hit which forgot the state would serve it without the header that says so, and
    // the same document would describe itself differently warm and cold.
    ManifestCache cache = new ManifestCache(10, HOUR);
    cache.put(LIBRARY, PID, "{\"id\":\"x\"}", "etag-1", Manifests.Dimensions.APPROXIMATE);

    assertThat(cache.get(LIBRARY, PID)).isPresent();
    assertThat(cache.get(LIBRARY, PID).orElseThrow().dimensions())
        .isEqualTo(Manifests.Dimensions.APPROXIMATE);
  }

  @Test
  @DisplayName("libraries do not collide: the same pid in two libraries is two manifests")
  void keyIncludesTheLibrary() {
    ManifestCache cache = new ManifestCache(10, HOUR);
    cache.put(LIBRARY, PID, "mzk-body", "etag-mzk", Manifests.Dimensions.COMPLETE);
    cache.put("nkp", PID, "nkp-body", "etag-nkp", Manifests.Dimensions.COMPLETE);

    assertThat(cache.get(LIBRARY, PID).orElseThrow().json()).isEqualTo("mzk-body");
    assertThat(cache.get("nkp", PID).orElseThrow().json()).isEqualTo("nkp-body");
  }

  @Test
  @DisplayName("an expired entry is a miss, not a stale hit")
  void ttlExpires() {
    // Zero TTL rather than a clock injection: an entry stored now is already past a zero window, so
    // this asserts the comparison happens at all.
    ManifestCache cache = new ManifestCache(10, Duration.ofNanos(1));
    cache.put(LIBRARY, PID, "{}", "etag", Manifests.Dimensions.COMPLETE);

    assertThat(cache.get(LIBRARY, PID)).isEmpty();
  }

  @Test
  @DisplayName("a zero TTL switches the cache off entirely")
  void zeroTtlDisablesIt() {
    // IIIF_MANIFEST_TTL_MINUTES=0 is how an operator says "do not cache manifests anywhere", and it
    // has to mean here too — otherwise the gateway would hold what it told clients not to hold.
    ManifestCache cache = new ManifestCache(500, Duration.ZERO);
    cache.put(LIBRARY, PID, "{}", "etag", Manifests.Dimensions.COMPLETE);

    assertThat(cache.enabled()).isFalse();
    assertThat(cache.get(LIBRARY, PID)).isEmpty();
    assertThat(cache.size()).isZero();
  }

  @Test
  @DisplayName("a zero size switches the cache off entirely")
  void zeroSizeDisablesIt() {
    ManifestCache cache = new ManifestCache(0, HOUR);
    cache.put(LIBRARY, PID, "{}", "etag", Manifests.Dimensions.COMPLETE);

    assertThat(cache.enabled()).isFalse();
    assertThat(cache.get(LIBRARY, PID)).isEmpty();
  }

  @Test
  @DisplayName("the least recently used entry is evicted, not the oldest")
  void evictsLeastRecentlyUsed() {
    ManifestCache cache = new ManifestCache(2, HOUR);
    cache.put(LIBRARY, "uuid:a", "a", "etag-a", Manifests.Dimensions.COMPLETE);
    cache.put(LIBRARY, "uuid:b", "b", "etag-b", Manifests.Dimensions.COMPLETE);

    // Touching `a` makes `b` the eviction candidate even though `a` was stored first.
    cache.get(LIBRARY, "uuid:a");
    cache.put(LIBRARY, "uuid:c", "c", "etag-c", Manifests.Dimensions.COMPLETE);

    assertThat(cache.get(LIBRARY, "uuid:a")).isPresent();
    assertThat(cache.get(LIBRARY, "uuid:b")).isEmpty();
    assertThat(cache.get(LIBRARY, "uuid:c")).isPresent();
  }

  @Test
  @DisplayName("the byte ceiling evicts even when the entry count allows more")
  void byteCeilingEvicts() {
    // The tail is what this is for: the median manifest is ~7 KB, but MZK's largest is 3568
    // canvases,
    // so 500 entries of THAT would be gigabytes. Whichever bound is hit first has to win.
    ManifestCache cache = new ManifestCache(100, HOUR, 20);
    cache.put(LIBRARY, "uuid:a", "0123456789", "etag-a", Manifests.Dimensions.COMPLETE);
    cache.put(LIBRARY, "uuid:b", "0123456789", "etag-b", Manifests.Dimensions.COMPLETE);
    cache.put(LIBRARY, "uuid:c", "0123456789", "etag-c", Manifests.Dimensions.COMPLETE);

    assertThat(cache.size()).isEqualTo(2);
    assertThat(cache.bytes()).isEqualTo(20);
    assertThat(cache.get(LIBRARY, "uuid:a")).isEmpty();
  }

  @Test
  @DisplayName("the byte ceiling counts the UTF-8 response body, not Java characters")
  void byteCeilingCountsUtf8() {
    // Czech manifest text is commonly multi-byte UTF-8. Four č characters occupy eight response
    // bytes, so a seven-byte cache cannot retain this entry. String.length() would incorrectly
    // report four and let it through.
    ManifestCache cache = new ManifestCache(10, HOUR, 7);
    cache.put(LIBRARY, PID, "čččč", "etag", Manifests.Dimensions.COMPLETE);

    assertThat(cache.get(LIBRARY, PID)).isEmpty();
    assertThat(cache.bytes()).isZero();
  }

  @Test
  @DisplayName("evicting a document drops it, so DELETE …/dimensions is not defeated by this cache")
  void evictionByDocument() {
    // Without this, the one operator escape hatch for an image re-derived without its timestamp
    // moving would discard the sizes and keep serving the manifest built from them.
    ManifestCache cache = new ManifestCache(10, HOUR);
    cache.put(LIBRARY, PID, "{}", "etag", Manifests.Dimensions.COMPLETE);

    cache.evict(LIBRARY, PID);

    assertThat(cache.get(LIBRARY, PID)).isEmpty();
    assertThat(cache.bytes()).isZero();
  }

  @Test
  @DisplayName("re-storing a document does not double-count its bytes")
  void overwriteKeepsTheByteCountHonest() {
    ManifestCache cache = new ManifestCache(10, HOUR);
    cache.put(LIBRARY, PID, "0123456789", "etag-1", Manifests.Dimensions.COMPLETE);
    cache.put(LIBRARY, PID, "01234", "etag-2", Manifests.Dimensions.COMPLETE);

    assertThat(cache.size()).isEqualTo(1);
    assertThat(cache.bytes()).isEqualTo(5);
  }
}
