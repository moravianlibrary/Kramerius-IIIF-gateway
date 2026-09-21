/*
 * A Solr that answers a cursored query without a cursor.
 *
 * This is the one failure a walk cannot be allowed to swallow. Solr signals end-of-results by echoing the
 * cursorMark it was sent; an ABSENT nextCursorMark means the server never implemented cursorMark and
 * answered a plain `rows=` query, so the caller holds the first batch and nothing else. Treated as the end
 * — which is what the code did until this test existed — a sync over 76 million pages fetches one batch,
 * reports itself complete, and no log line disagrees.
 *
 * Measured: `mendelu` 5.3.2 is the live instance that does this. The other seven reachable K5 instances
 * cursor correctly, so it cannot be predicted from the version and has to be caught from the response.
 *
 * The stub is a Solr, not a mock of our own code: the assertion is about what SolrClient does in front of
 * one that behaves this way.
 */
package cz.mzk.iiif.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CursorMarkUnsupportedTest {

  private HttpServer server;
  private final AtomicInteger requests = new AtomicInteger();

  /**
   * @param cursorMark what to put in nextCursorMark, or null to omit the field entirely
   */
  private SolrClient solr(String cursorMark) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          String cursor = cursorMark == null ? "" : ",\"nextCursorMark\":\"" + cursorMark + "\"";
          String body =
              "{\"responseHeader\":{\"status\":0},\"response\":{\"numFound\":9,\"docs\":"
                  + "[{\"pid\":\"uuid:a\"},{\"pid\":\"uuid:b\"}]}"
                  + cursor
                  + "}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.setExecutor(null);
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    return new SolrClient(HttpClient.newHttpClient(), url, Duration.ofSeconds(5));
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("a missing nextCursorMark fails loudly rather than ending the walk")
  void absentCursorMarkThrows() throws IOException {
    SolrClient client = solr(null);

    assertThatThrownBy(() -> client.walk("model:page", "pid asc", List.of("pid"), -1))
        .isInstanceOf(CursorMarkUnsupported.class)
        .hasMessageContaining("does not support cursorMark")
        .hasMessageContaining("model:page");
  }

  @Test
  @DisplayName("the sync window fails the same way, because that is where it would truncate")
  void absentCursorMarkThrowsForWindows() throws IOException {
    SolrClient client = solr(null);

    assertThatThrownBy(
            () ->
                client.window(
                    "model:page", "modified asc, pid asc", List.of("pid"), 500, Optional.empty()))
        .isInstanceOf(CursorMarkUnsupported.class)
        .satisfies(e -> assertThat(((CursorMarkUnsupported) e).baseUrl()).contains("127.0.0.1"));
  }

  @Test
  @DisplayName("an echoed cursorMark is the end of the walk, not a failure")
  void echoedCursorMarkEndsTheWalk() throws IOException {
    // The distinction the bug turned on: Solr's documented end-of-results signal is the cursor it
    // was sent coming back unchanged. "*" is what the first request sends.
    SolrClient client = solr("*");

    List<com.fasterxml.jackson.databind.JsonNode> docs =
        client.walk("model:page", "pid asc", List.of("pid"), -1);

    assertThat(docs).hasSize(2);
    // One request, then it stopped — no exception, and no second round trip.
    assertThat(requests.get()).isEqualTo(1);
  }
}
