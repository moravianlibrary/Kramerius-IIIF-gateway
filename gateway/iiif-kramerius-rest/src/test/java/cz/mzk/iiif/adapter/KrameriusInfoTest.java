/*
 * The version probe, which decides which adapter an instance gets.
 *
 * The cases that matter are the ones measured live against the registry's 56 libraries: a K7 instance
 * answering only the v7 path, a K5 instance answering only the v5 path, and the eight that answer
 * neither and must not take a library down with them.
 */
package cz.mzk.iiif.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KrameriusInfoTest {

  private static final String K7 = "/search/api/client/v7.0/info";
  private static final String K5 = "/search/api/v5.0/info";
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient http = HttpClient.newHttpClient();
  private final List<String> asked = new ArrayList<>();
  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  /**
   * @param answers path to body; anything else 404s.
   */
  private String serving(java.util.Map<String, String> answers) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          asked.add(path);
          String body = answers.get(path);
          byte[] bytes = (body == null ? "not found" : body).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.setExecutor(null);
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private Optional<Version> probe(String base) {
    return KrameriusInfo.version(http, base, TIMEOUT);
  }

  @Test
  @DisplayName("a Kramerius 7 instance is read from the v7 path, and costs one request")
  void kramerius7() throws Exception {
    String base = serving(java.util.Map.of(K7, "{\"version\":\"7.2.2.3\",\"indexerVersion\":25}"));

    assertThat(probe(base)).map(Version::raw).contains("7.2.2.3");
    // The majority case must not pay for the minority one.
    assertThat(asked).containsExactly(K7);
  }

  @Test
  @DisplayName("a Kramerius 5 instance is found on the second path")
  void kramerius5() throws Exception {
    // A real K5 404s the v7 path — verified against lmda.silvarium.cz — which is what makes trying
    // both safe rather than ambiguous.
    String base = serving(java.util.Map.of(K5, "{\"version\":\"5.8.1\",\"pdfMaxRange\":\"20\"}"));

    assertThat(probe(base)).map(Version::raw).contains("5.8.1");
    assertThat(asked).containsExactly(K7, K5);
  }

  @Test
  @DisplayName("an instance that answers no version leaves the caller to its fallback")
  void noInfo() throws Exception {
    String base = serving(java.util.Map.of());

    // Empty, not an exception and not a guess. Eight of the registry's 56 are in this state, and
    // every one of them is also unreachable at the root — so this is 'cannot ask', not 'refuse'.
    assertThat(probe(base)).isEmpty();
  }

  @Test
  @DisplayName("a 200 that is not JSON is not a version")
  void htmlIsNotAVersion() throws Exception {
    // Several instances answer their single-page web client for any unknown path rather than a
    // 404, so a 200 alone proves nothing.
    String base = serving(java.util.Map.of(K7, "<!DOCTYPE html><html><head><title>Kramerius"));

    assertThat(probe(base)).isEmpty();
  }

  @Test
  @DisplayName("a version field that is present but empty is not a version")
  void emptyVersion() throws Exception {
    String base =
        serving(java.util.Map.of(K7, "{\"version\":\"\"}", K5, "{\"version\":\"5.7.2\"}"));

    // Falls through to the other path rather than stopping at a useless answer.
    assertThat(probe(base)).map(Version::raw).contains("5.7.2");
  }
}
