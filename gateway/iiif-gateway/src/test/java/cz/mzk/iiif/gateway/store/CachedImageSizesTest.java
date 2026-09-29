/*
 * The cache's two expiry rules, both of which are invisible when they go wrong.
 *
 * A stale size and a frozen refusal both produce a perfectly valid manifest with the wrong numbers in it,
 * so neither shows up as an error — only as canvases that quietly stay 1000x1000, or dimensions that
 * describe an image that has since been replaced.
 */
package cz.mzk.iiif.gateway.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.document.ImageSize;
import cz.mzk.iiif.kramerius.IiifStructure;

class CachedImageSizesTest {

  private static final String LIBRARY = "mzk";
  private static final String DOCUMENT = "uuid:doc";
  private static final String PAGE = "uuid:page";
  private static final Instant EARLIER = Instant.now().minus(Duration.ofDays(30));

  /** A store holding one document, measured at a time the test chooses. */
  private static final class FixedStore implements SizeStore {
    private final Map<String, ImageSize> sizes;
    private final Instant fetchedAt;
    final Map<String, ImageSize> written = new LinkedHashMap<>();

    FixedStore(Map<String, ImageSize> sizes, Instant fetchedAt) {
      this.sizes = sizes;
      this.fetchedAt = fetchedAt;
    }

    @Override
    public Optional<Document> find(String library, String pid) {
      return sizes.isEmpty() ? Optional.empty() : Optional.of(new Document(sizes, fetchedAt));
    }

    @Override
    public void put(String library, String pid, Map<String, ImageSize> values) {
      written.putAll(values);
    }

    @Override
    public void evict(String library, String pid) {}

    @Override
    public void enqueue(String library, String pid) {}

    @Override
    public List<Job> claim(int limit) {
      return List.of();
    }

    @Override
    public void complete(String library, String pid) {}

    @Override
    public void fail(String library, String pid, String error) {}

    @Override
    public Optional<Sync> sync(String library) {
      return Optional.empty();
    }

    @Override
    public List<Sync> allSyncs() {
      return List.of();
    }

    @Override
    public void saveSync(Sync sync) {}

    @Override
    public boolean awaitJob(Duration timeout) {
      return false;
    }
  }

  private static CachedImageSizes cache(FixedStore store, Duration refusedRetry) {
    return new CachedImageSizes(store, pids -> Map.of(), LIBRARY, refusedRetry);
  }

  /** The page as the request path sees it: a record, which is where `modified` lives. */
  private static List<DocumentRecord> page(Instant modified) {
    DocumentRecord.Builder builder =
        DocumentRecord.of(PAGE, "page", IiifStructure.PAGED_MANIFEST).hasTiles(true);
    if (modified != null) {
      builder.modified(modified);
    }
    return List.of(builder.build());
  }

  /** A page with no known modification time — the cache cannot call that stale. */
  private static List<DocumentRecord> page() {
    return page(null);
  }

  @Test
  @DisplayName("a real size is a hit")
  void realSizeHits() {
    FixedStore store = new FixedStore(Map.of(PAGE, ImageSize.of(100, 200, 3)), Instant.now());

    assertThat(cache(store, Duration.ofHours(24)).cached(DOCUMENT, page())).containsKey(PAGE);
  }

  @Test
  @DisplayName(
      "a recent failure is honoured, so a restricted document is not re-probed every request")
  void recentFailureIsHonoured() {
    FixedStore store =
        new FixedStore(
            Map.of(PAGE, ImageSize.UNAVAILABLE), Instant.now().minus(Duration.ofMinutes(5)));

    assertThat(cache(store, Duration.ofHours(24)).cached(DOCUMENT, page())).containsKey(PAGE);
  }

  @Test
  @DisplayName("an old failure expires — no failure is permanent, whatever caused it")
  void oldFailureExpires() {
    // The bug this fixes: a 403 measured from outside the library network, or a 404 from an image
    // server that was mid-migration, would otherwise freeze the canvases at nominal size forever.
    FixedStore store =
        new FixedStore(
            Map.of(PAGE, ImageSize.UNAVAILABLE), Instant.now().minus(Duration.ofDays(2)));

    assertThat(cache(store, Duration.ofHours(24)).cached(DOCUMENT, page())).isEmpty();
  }

  @Test
  @DisplayName(
      "a fresh approximation is honoured, so the thumbnail is not re-fetched every request")
  void freshApproximationIsHonoured() {
    FixedStore store =
        new FixedStore(
            Map.of(PAGE, ImageSize.approximate(590, 1000)),
            Instant.now().minus(Duration.ofMinutes(5)));

    assertThat(cache(store, Duration.ofHours(24)).cached(DOCUMENT, page())).containsKey(PAGE);
  }

  @Test
  @DisplayName("an old approximation expires, so a page that becomes measurable is upgraded")
  void oldApproximationExpires() {
    // An approximation has the right shape and a nominal scale. Keeping it forever would freeze a
    // guess as though it were a measurement.
    FixedStore store =
        new FixedStore(
            Map.of(PAGE, ImageSize.approximate(590, 1000)),
            Instant.now().minus(Duration.ofDays(2)));

    assertThat(cache(store, Duration.ofHours(24)).cached(DOCUMENT, page())).isEmpty();
  }

  @Test
  @DisplayName("a real measurement never expires on its own")
  void measurementIsPermanent() {
    FixedStore store =
        new FixedStore(
            Map.of(PAGE, ImageSize.of(1274, 2166, 3)), Instant.now().minus(Duration.ofDays(3650)));

    assertThat(cache(store, Duration.ofHours(24)).cached(DOCUMENT, page())).containsKey(PAGE);
  }

  @Test
  @DisplayName("a size measured before the page changed is not a hit")
  void stalenessStillApplies() {
    Instant measured = Instant.now().minus(Duration.ofDays(10));
    FixedStore store = new FixedStore(Map.of(PAGE, ImageSize.of(100, 200, 3)), measured);

    assertThat(cache(store, Duration.ofHours(24)).cached(DOCUMENT, page(Instant.now()))).isEmpty();
  }

  @Test
  @DisplayName("a size measured after the page last changed is a hit")
  void freshMeasurementSurvivesStaleness() {
    // The other half of the rule, and the one that would go unnoticed if it broke: getting this
    // wrong re-measures every page of every document on every request rather than serving wrong
    // numbers, so it costs the upstream instead of the manifest.
    FixedStore store = new FixedStore(Map.of(PAGE, ImageSize.of(100, 200, 3)), Instant.now());

    assertThat(cache(store, Duration.ofHours(24)).cached(DOCUMENT, page(EARLIER)))
        .containsKey(PAGE);
  }

  @Test
  @DisplayName("what was measured is written back under the document it belongs to")
  void storeWritesThrough() {
    FixedStore store = new FixedStore(Map.of(), Instant.now());

    cache(store, Duration.ofHours(24)).store(DOCUMENT, Map.of(PAGE, ImageSize.of(100, 200, 3)));

    assertThat(store.written).containsKey(PAGE);
  }
}
