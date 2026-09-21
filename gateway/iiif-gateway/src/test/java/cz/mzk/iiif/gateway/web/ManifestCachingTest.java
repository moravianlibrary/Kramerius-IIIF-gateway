/*
 * The caching policy in the HTTP layer, which nothing used to test.
 *
 * ManifestCacheTest covers the data structure — expiry, LRU, the byte ceiling. What it cannot cover is
 * the part that decides what goes into it, and those decisions live in WebServer:
 *
 *   1. Only a COMPLETE manifest is cached. A partial one carries nominal sizes for pages the worker has
 *      not reached, and remembering those would serve 1000x1000 canvases long after the real dimensions
 *      were measured — v1's central defect, reintroduced one layer up.
 *   2. A partial response is `no-store` and carries X-IIIF-Dimensions: partial, so nobody else caches it
 *      either, and it has no ETag to revalidate against.
 *   3. A hit answers a conditional request without one upstream call. That is the entire justification
 *      for the cache, and it can only be checked by counting requests at the far end.
 *   4. DELETE …/dimensions drops both layers. Evicting the sizes but leaving the rendered bytes would
 *      make the operator's escape hatch do nothing at all.
 *
 * All four were verified only by `make smoke` against a live gateway, which cannot fail `make check`.
 *
 * Not named …IT on purpose: it needs no database and no network beyond loopback, so it runs in
 * `make check` rather than behind `make test-integration`. The stub is a Kramerius, not a mock of
 * our own code — the assertions are about our behaviour in front of one.
 */
package cz.mzk.iiif.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cz.mzk.iiif.gateway.Config;
import cz.mzk.iiif.gateway.Libraries;
import cz.mzk.iiif.gateway.LibraryHealth;
import cz.mzk.iiif.gateway.Manifests;
import cz.mzk.iiif.gateway.testing.MemorySizeStore;
import cz.mzk.iiif.gateway.testing.StubKramerius;

class ManifestCachingTest {

  private static final String PID = "uuid:stub-monograph";

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private StubKramerius upstream;
  private WebServer server;
  private LibraryHealth health;
  private MemorySizeStore store;
  private int port;
  private int adminPort;

  /**
   * @param tiled false gives a complete manifest with no image server involved; true gives pages
   *     whose sizes are unknown, which with a zero wait budget is the partial branch
   * @param waitSeconds the inline-resolution budget. Zero means never resolve inline.
   */
  private void start(boolean tiled, int pages, int waitSeconds) throws IOException {
    start(tiled, pages, waitSeconds, false);
  }

  /**
   * @param restricted the pages' info.json answers 403 while their thumbnails answer, which is the
   *     only route to an approximate manifest
   */
  private void start(boolean tiled, int pages, int waitSeconds, boolean restricted)
      throws IOException {
    upstream = new StubKramerius(PID, pages, tiled, restricted);
    startAgainst(upstream, "7.2.2", waitSeconds);
  }

  /**
   * @param declaredVersion what IIIF_LIBRARIES claims, which is no longer what picks the adapter —
   *     the instance's own /info is. Separated from the stub's reported version so the two can be
   *     made to disagree.
   */
  private void startAgainst(StubKramerius stub, String declaredVersion) throws IOException {
    startAgainst(stub, declaredVersion, 5);
  }

  private void startAgainst(StubKramerius stub, String declaredVersion, int waitSeconds)
      throws IOException {
    upstream = stub;
    port = freePort();
    adminPort = freePort();

    Map<String, String> env = new HashMap<>();
    env.put("IIIF_LIBRARIES", "stub=" + upstream.url() + "|" + declaredVersion + "|cs:Zkušební");
    env.put("IIIF_PORT", String.valueOf(port));
    env.put("IIIF_ADMIN_PORT", String.valueOf(adminPort));
    env.put("IIIF_BASE_URL", "http://localhost:" + port);
    env.put("IIIF_LIBRARY_CHECK_MINUTES", "0");
    env.put("IIIF_HTTP_CONNECT_TIMEOUT_SECONDS", "5");
    env.put("IIIF_HTTP_READ_TIMEOUT_SECONDS", "10");
    env.put("IIIF_DIMENSION_WAIT_SECONDS", String.valueOf(waitSeconds));
    env.put("IIIF_MANIFEST_TTL_MINUTES", "10");
    env.put("IIIF_MANIFEST_CACHE_SIZE", "50");

    Config config = Config.from(env);
    HttpClient upstreamClient =
        HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
    store = new MemorySizeStore();
    Libraries libraries =
        new Libraries(config, Optional.empty(), Optional.of(store), upstreamClient);
    Manifests manifests = new Manifests(config, libraries, Optional.of(store));
    health = new LibraryHealth(config, libraries, upstreamClient);
    server = new WebServer(config, libraries, manifests, Optional.of(store), health);
    server.start();
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.close();
    }
    if (health != null) {
      health.close();
    }
    if (upstream != null) {
      upstream.close();
    }
  }

  @Test
  @DisplayName("a complete manifest is cached, and the hit costs the upstream nothing")
  void aHitTouchesNoUpstream() throws Exception {
    start(false, 4, 5);

    HttpResponse<String> first = get("/stub/" + PID);
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(first.headers().firstValue("ETag")).isPresent();
    int afterBuild = upstream.requests();
    assertThat(afterBuild).isGreaterThan(0);

    upstream.resetRequests();
    HttpResponse<String> second = get("/stub/" + PID);

    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(second.body()).isEqualTo(first.body());
    // The claim, stated as a number: the second answer came from memory.
    assertThat(upstream.requests()).isZero();
  }

  @Test
  @DisplayName("a cached manifest revalidates to 304 with an empty body")
  void revalidationIsA304() throws Exception {
    start(false, 4, 5);

    String etag = get("/stub/" + PID).headers().firstValue("ETag").orElseThrow();
    upstream.resetRequests();

    HttpResponse<String> revalidated =
        client.send(
            HttpRequest.newBuilder(uri("/stub/" + PID)).header("If-None-Match", etag).build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(revalidated.statusCode()).isEqualTo(304);
    assertThat(revalidated.body()).isEmpty();
    // A 304 for a manifest that can reach a couple of megabytes, and no upstream traffic at all.
    assertThat(upstream.requests()).isZero();
  }

  @Test
  @DisplayName("a complete manifest is served cacheable, with the configured max-age")
  void completeManifestIsCacheable() throws Exception {
    start(false, 4, 5);

    HttpResponse<String> response = get("/stub/" + PID);

    assertThat(response.headers().firstValue("Cache-Control")).contains("public, max-age=600");
    assertThat(response.headers().firstValue("X-IIIF-Dimensions")).isEmpty();
  }

  @Test
  @DisplayName("a restricted document is cacheable but admits its canvases are approximated")
  void approximateManifestSaysSo() throws Exception {
    // 87.6% of MZK's pages are `private`, so this is the ordinary case rather than an edge one. The
    // images answer 403 and the thumbnails answer, which is a real answer — it just is not a
    // measurement, and until now nothing in the response said which kind it was.
    start(true, 4, 5, true);

    HttpResponse<String> response = get("/stub/" + PID);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("X-IIIF-Dimensions")).contains("approximate");
    // Cacheable, unlike partial. Not because the approximation is permanent — it is not — but
    // because nothing is already on its way to replace it, and the TTL bounds it exactly as it
    // bounds a measured manifest.
    assertThat(response.headers().firstValue("Cache-Control")).contains("public, max-age=600");
    assertThat(response.headers().firstValue("ETag")).isNotEmpty();

    // The shape came from the thumbnail and the magnitude is nominal: 172x256 scaled to height
    // 1000.
    int expectedWidth =
        Math.round((float) StubKramerius.THUMB_WIDTH / StubKramerius.THUMB_HEIGHT * 1000);
    assertThat(response.body()).contains("\"width\":" + expectedWidth, "\"height\":1000");

    // And a cache HIT must say the same thing, or the document would describe itself one way warm
    // and another way cold.
    upstream.resetRequests();
    HttpResponse<String> cached = get("/stub/" + PID);

    assertThat(upstream.requests()).isZero();
    assertThat(cached.headers().firstValue("X-IIIF-Dimensions")).contains("approximate");
  }

  @Test
  @DisplayName("a partial manifest is never cached, and says so in three ways")
  void partialManifestIsNotCached() throws Exception {
    // Tiled pages with a zero budget: the policy declines to resolve inline, so the manifest
    // carries
    // nominal sizes and the rest goes to the worker.
    start(true, 6, 0);

    HttpResponse<String> first = get("/stub/" + PID);

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(first.headers().firstValue("X-IIIF-Dimensions")).contains("partial");
    assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
    // No ETag: there is nothing stable to revalidate against while the numbers are still moving.
    assertThat(first.headers().firstValue("ETag")).isEmpty();
    // And it was handed to the worker rather than forgotten.
    assertThat(store.queued()).contains("stub/" + PID);

    // The load-bearing assertion: nothing was remembered, so the next request rebuilds and will
    // pick
    // up real sizes as soon as they exist.
    upstream.resetRequests();
    HttpResponse<String> second = get("/stub/" + PID);

    assertThat(second.headers().firstValue("X-IIIF-Dimensions")).contains("partial");
    assertThat(upstream.requests()).isGreaterThan(0);
    assertThat(health()).contains("\"entries\":0");
  }

  @Test
  @DisplayName("DELETE …/dimensions drops the rendered manifest too, not only the sizes")
  void deletingDimensionsEvictsTheManifest() throws Exception {
    start(false, 4, 5);

    get("/stub/" + PID);
    upstream.resetRequests();
    // Proven cached first, so the eviction below is demonstrably what causes the rebuild.
    get("/stub/" + PID);
    assertThat(upstream.requests()).isZero();

    HttpResponse<String> deleted =
        client.send(
            HttpRequest.newBuilder(adminUri("/stub/" + PID + "/dimensions"))
                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(deleted.statusCode()).isEqualTo(204);

    HttpResponse<String> rebuilt = get("/stub/" + PID);

    assertThat(rebuilt.statusCode()).isEqualTo(200);
    // Evicting the sizes but keeping the bytes would make the escape hatch do nothing.
    assertThat(upstream.requests()).isGreaterThan(0);
  }

  @Test
  @DisplayName("/health reports the manifest cache, and does not assume a Postgres store")
  void healthReportsTheCache() throws Exception {
    start(false, 4, 5);
    get("/stub/" + PID);

    String body = health();

    assertThat(body).contains("\"entries\":1").contains("\"sizeStore\":\"ok\"");
    // This route used to cast the store to PostgresSizeStore to ask whether it was healthy, which
    // is
    // a ClassCastException for any other implementation — and the SPI exists so there can be
    // others.
    assertThat(body).contains("\"status\":\"ok\"");
  }

  @Test
  @DisplayName("the version the instance reports selects the adapter, not the declared one")
  void theInstanceOutranksTheDeclaration() throws Exception {
    // svkos, in miniature: the registry has it at 5.7.3 and it actually runs 7.1.3, which is not a
    // detail but a different adapter — different Solr schema, different field names, different API
    // paths. Declared K5 here, reports K7; if the declaration won, nothing below would resolve,
    // because this stub only speaks the K7 client API.
    StubKramerius stub = new StubKramerius(PID, 4, false);
    stub.reportVersion("7.2.2");
    startAgainst(stub, "5.7.3");

    assertThat(get("/stub/" + PID).statusCode()).isEqualTo(200);
    assertThat(get("/libraries").body())
        .contains("\"version\":\"7.2.2\"", "\"adapter\":\"Kramerius714\"");
  }

  @Test
  @DisplayName("an instance that reports no version leaves the declared one standing")
  void theDeclarationIsTheFallback() throws Exception {
    // Eight of the registry's 56 answer no /info. Every one is also unreachable at the root, so
    // this path is reached by instances that are down rather than coy — but a gateway that refused
    // to serve a library because it would not introduce itself would be strictly worse than one
    // that believes the operator.
    StubKramerius stub = new StubKramerius(PID, 4, false);
    stub.reportVersion(null);
    startAgainst(stub, "7.2.2");

    assertThat(get("/stub/" + PID).statusCode()).isEqualTo(200);
    assertThat(get("/libraries").body()).contains("\"version\":\"7.2.2\"");
  }

  @Test
  @DisplayName("…/dimensions and X-IIIF-Dimensions say the same thing about the same document")
  void theRouteAgreesWithTheHeader() throws Exception {
    // BEHAVIOUR.md: "the route and the header cannot disagree about one document". Asserted here
    // for all three states at once, because the two were computed independently — the header from
    // the assembled bundle, the route from two index counts — and only ever checked separately.

    // Untiled: nothing to measure. This is the case that was wrong. The route required `total > 0`
    // before it would say complete, so a document with no tiled page reported `partial` against a
    // manifest that carried no header at all. It is not hypothetical: at MZK a 363-page monograph
    // has not one tiled page.
    start(false, 4, 5);
    assertAgree("complete");
    stop();

    // Tiled, restricted, resolved from thumbnails.
    start(true, 4, 5, true);
    assertAgree("approximate");
    stop();

    // Tiled with a zero budget: left to the worker.
    start(true, 6, 0);
    assertAgree("partial");
  }

  /** Both answers for the same document, from one gateway, in one breath. */
  private void assertAgree(String expected) throws Exception {
    HttpResponse<String> manifest = get("/stub/" + PID);
    assertThat(manifest.statusCode()).isEqualTo(200);

    // COMPLETE is the absence of the header, so that is what it has to be compared as.
    String header = manifest.headers().firstValue("X-IIIF-Dimensions").orElse("complete");
    assertThat(header).isEqualTo(expected);
    assertThat(get("/stub/" + PID + "/dimensions").body())
        .contains("\"state\":\"" + expected + "\"");
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private String health() throws Exception {
    return get("/health").body();
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private URI adminUri(String path) {
    return URI.create("http://localhost:" + adminPort + path);
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
