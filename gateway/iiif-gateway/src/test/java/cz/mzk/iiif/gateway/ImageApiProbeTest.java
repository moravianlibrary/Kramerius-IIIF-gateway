/*
 * What the image probe concludes, against each thing a real instance actually answers.
 *
 * This decides two properties of every canvas in a library: whether an ImageService is declared at all,
 * and which version it claims. Both were previously constants — K5 got a hardcoded `false` and K7 a
 * version read from the first page that replied — and both were wrong for real libraries. Of the 11 alive
 * K5 instances, three serve a complete Image API 2 while five demonstrably do not, and the Kramerius
 * version predicts neither.
 *
 * The cases below are the answers those instances give, not invented ones:
 *
 *   200 + width/height     lmda 5.8.1, dsmo 5.8.1, kvkli 5.8.3
 *   200 + empty body       ndk.cz 5.8.4.4, vugtk 5.4.2
 *   500                    nlk 5.8.4.5, cuni_fsv 5.7.2, mendelu 5.3.2
 *   403                    any restricted page, on any instance — 64 of MZK's 73 million
 *   Image API 3 context    MZK 7.2.2
 *
 * The stub is a Kramerius, not a mock of our own code: it answers Solr and info.json, and the assertions
 * are about what we conclude in front of one.
 */
package cz.mzk.iiif.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cz.mzk.iiif.adapter.SolrClient;
import cz.mzk.iiif.adapter.SolrSchema;
import cz.mzk.iiif.adapter.v50.Solr5Schema;

class ImageApiProbeTest {

  private HttpServer server;
  private final List<String> solrQueries = new ArrayList<>();

  /** What info.json answers, given the page pid. Null means "500". */
  private interface InfoJson extends Function<String, String> {}

  private static final String IMAGE_API_2 =
      "{\"@context\":\"http://iiif.io/api/image/2/context.json\",\"width\":2417,\"height\":3416}";
  private static final String IMAGE_API_3 =
      "{\"@context\":\"http://iiif.io/api/image/3/context.json\",\"width\":3911,\"height\":5514}";

  private String start(SolrSchema schema, int pages, InfoJson info) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          String query =
              exchange.getRequestURI().getQuery() == null
                  ? ""
                  : exchange.getRequestURI().getQuery();

          if (path.contains("/search/iiif/")) {
            String pid = path.replaceAll(".*/search/iiif/(.+)/info\\.json", "$1");
            String body = info.apply(pid);
            if (body == null) {
              respond(exchange, 500, "<html>500</html>");
            } else {
              respond(exchange, 200, body);
            }
            return;
          }

          solrQueries.add(query);
          List<String> docs = new ArrayList<>();
          for (int i = 0; i < pages; i++) {
            docs.add("{\"%s\":\"uuid:page-%d\"}".formatted(schema.pidField(), i));
          }
          // nextCursorMark echoed: Solr's end-of-results signal, and required — SolrClient now
          // refuses
          // a cursored response without one. See CursorMarkUnsupportedTest.
          respond(
              exchange,
              200,
              "{\"responseHeader\":{\"status\":0},\"response\":{\"numFound\":%d,\"docs\":[%s]},\"nextCursorMark\":\"*\"}"
                  .formatted(pages, String.join(",", docs)));
        });
    server.setExecutor(null);
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private ImageApi.Profile probe(SolrSchema schema, int pages, InfoJson info) throws IOException {
    String url = start(schema, pages, info);
    // Built the way Libraries.build builds one: with the SCHEMA's pid field. The short constructor
    // defaults it to K7's `pid`, which on a K5 index is not the uniqueKey — and the probe's sort is
    // built from exactly that field, so a harness using the default would disagree with production
    // about the one thing these tests are here to check.
    SolrClient solr =
        new SolrClient(
            HttpClient.newHttpClient(),
            url,
            Duration.ofSeconds(5),
            schema.searchPath(),
            schema.pidField(),
            500,
            50_000);
    return ImageApi.probe(HttpClient.newHttpClient(), url, solr, schema, Duration.ofSeconds(5));
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Nested
  @DisplayName("Kramerius 5, where whether there is a service at all is the question")
  class Kramerius5 {

    @Test
    @DisplayName("a working Image API 2 is found — lmda, dsmo and kvkli really do have one")
    void workingImageApi() throws IOException {
      ImageApi.Profile profile = probe(new Solr5Schema(), 5, pid -> IMAGE_API_2);

      assertThat(profile.available()).isTrue();
      assertThat(profile.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("200 with an empty body is not a service — ndk.cz and vugtk")
    void emptyBodyIsNotAService() throws IOException {
      // The failure the old hardcoded `false` was generalised from. It is a 200, so only looking at
      // the status would call this a working image server and every canvas would declare one.
      ImageApi.Profile profile = probe(new Solr5Schema(), 5, pid -> "");

      assertThat(profile.available()).isFalse();
    }

    @Test
    @DisplayName("a 200 that describes no image is not a service either")
    void zeroDimensionsIsNotAService() throws IOException {
      ImageApi.Profile profile = probe(new Solr5Schema(), 5, pid -> "{\"width\":0,\"height\":0}");

      assertThat(profile.available()).isFalse();
    }

    @Test
    @DisplayName("500 is not a service — nlk, cuni_fsv and mendelu")
    void serverErrorIsNotAService() throws IOException {
      ImageApi.Profile profile = probe(new Solr5Schema(), 5, pid -> null);

      assertThat(profile.available()).isFalse();
    }

    @Test
    @DisplayName("the query uses K5's field names, or it would match nothing and prove nothing")
    void queriesWithK5FieldNames() throws IOException {
      // The bug this guards is silent: spelled K7's way, the filter matches zero rows on a K5
      // index,
      // the probe finds no page to ask about, and it reports "no image service" for a library that
      // has one. Same shape as the sync walk's has_tiles/modified failure.
      probe(new Solr5Schema(), 5, pid -> IMAGE_API_2);

      // By content, not by position: the first query on the wire is the uniqueKey detection
      // (`q=*:*&rows=0`), which the probe now needs because a cursorMark sort has to name the
      // index's own key. Asserting on get(0) made this test about call order.
      String query =
          solrQueries.stream()
              .map(raw -> java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8))
              .filter(decoded -> decoded.contains("page"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no page query was sent: " + solrQueries));
      assertThat(query).contains("fedora.model:page", "viewable:true", "dostupnost:public");
      assertThat(query).doesNotContain("accessibility:public", "has_tiles:true");
      // K5 is standalone, so its uniqueKey is the pid field and the sort is spelled PID.
      assertThat(query).contains("sort=PID asc");
    }
  }

  @Nested
  @DisplayName("Kramerius 7, where only the version is in question")
  class Kramerius7 {

    @Test
    @DisplayName("an Image API 3 context is read as 3 — MZK 7.2.2")
    void detectsVersionThree() throws IOException {
      ImageApi.Profile profile = probe(new SolrSchema(), 5, pid -> IMAGE_API_3);

      assertThat(profile.available()).isTrue();
      assertThat(profile.version()).isEqualTo(3);
    }

    @Test
    @DisplayName("a mixed instance resolves down to 2 rather than claiming capabilities")
    void mixedVersionsResolveDown() throws IOException {
      // Two say 3, three say 2. Claiming 3 on a v2 server advertises level2 the server has not got;
      // claiming 2 on a v3 server merely asks for less than it could give.
      ImageApi.Profile profile =
          probe(
              new SolrSchema(),
              5,
              pid -> pid.endsWith("0") || pid.endsWith("1") ? IMAGE_API_3 : IMAGE_API_2);

      assertThat(profile.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("one broken page does not decide the library — the other four outvote it")
    void oneBadPageDoesNotDecide() throws IOException {
      // Why five pages are sampled instead of stopping at the first answer: the first three tiled
      // pages MZK returns for an unfiltered query are all restricted.
      ImageApi.Profile profile =
          probe(new SolrSchema(), 5, pid -> pid.endsWith("0") ? "" : IMAGE_API_3);

      assertThat(profile.available()).isTrue();
      assertThat(profile.version()).isEqualTo(3);
    }

    @Test
    @DisplayName("a probe that finds nothing is an unlucky sample, not a missing service")
    void assumingAvailableIsTheAdaptersCall() throws IOException {
      // An entirely restricted library answers 403 on every page. The probe cannot tell that from a
      // server with no Image API — so it reports what it saw, and the ADAPTER decides what that
      // means. K7 says the service is a given; K5 says the probe is the answer.
      ImageApi.Profile probed = probe(new SolrSchema(), 5, pid -> null);

      assertThat(probed.available()).isFalse();
      assertThat(probed.assumingAvailable().available()).isTrue();
      assertThat(probed.assumingAvailable().version()).isEqualTo(probed.version());
    }
  }

  @Test
  @DisplayName("no public page to ask about means no claim, on either version")
  void noPublicPages() throws IOException {
    assertThat(probe(new Solr5Schema(), 0, pid -> IMAGE_API_2).available()).isFalse();
  }

  @Test
  @DisplayName("the sample query uses the index's uniqueKey, so SolrCloud does not 400 it")
  void solrCloudIsSampledWithItsOwnUniqueKey() throws IOException {
    // MZK, exactly. Solr rejects a cursorMark query whose sort does not contain the uniqueKey, and
    // on SolrCloud that is `compositeId`, not `pid`. The probe used to sort by the schema's pid
    // field, so on every SolrCloud instance it got a 400, listed no pages, and concluded nothing.
    //
    // Which is why this needs a test rather than a look: a failed probe is not an error anybody
    // sees. It falls back to Image API 2, and MZK serves Image API 3 — so the symptom was a
    // perfectly well-formed manifest quietly declaring ImageService2 against a v3 server.
    List<String> rejected = new ArrayList<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String query =
              exchange.getRequestURI().getQuery() == null
                  ? ""
                  : exchange.getRequestURI().getQuery();

          if (exchange.getRequestURI().getPath().contains("/search/iiif/")) {
            respond(exchange, 200, IMAGE_API_3);
            return;
          }
          // zkConnected is how SolrClient recognises a cloud, and the reason uniqueKey differs.
          if (query.contains("rows=0")) {
            respond(
                exchange,
                200,
                "{\"responseHeader\":{\"status\":0,\"zkConnected\":true},"
                    + "\"response\":{\"numFound\":9,\"docs\":[]}}");
            return;
          }
          if (query.contains("cursorMark") && !query.contains("compositeId")) {
            rejected.add(query);
            respond(exchange, 400, "{\"error\":\"cursor requires a uniqueKey tiebreaker\"}");
            return;
          }
          respond(
              exchange,
              200,
              "{\"responseHeader\":{\"status\":0},\"response\":{\"numFound\":1,"
                  + "\"docs\":[{\"pid\":\"uuid:page-0\"}]},\"nextCursorMark\":\"*\"}");
        });
    server.setExecutor(null);
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort();

    SolrSchema schema = new SolrSchema();
    SolrClient solr =
        new SolrClient(HttpClient.newHttpClient(), url, Duration.ofSeconds(5), schema.searchPath());
    ImageApi.Profile probed =
        ImageApi.probe(HttpClient.newHttpClient(), url, solr, schema, Duration.ofSeconds(5));

    assertThat(rejected).as("a query sorted without the uniqueKey").isEmpty();
    assertThat(probed.available()).isTrue();
    assertThat(probed.version()).isEqualTo(3);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
