/*
 * Local declarations as an OVERLAY on the registry, rather than a replacement for it.
 *
 * Until now the two were mutually exclusive: setting IIIF_LIBRARIES or IIIF_LIBRARIES_FILE cleared
 * IIIF_REGISTRY_URL outright, and `codes()` returned only what was declared. So an operator who wanted
 * one thing the registry cannot express had to restate all fifty-six libraries and then own keeping
 * them current forever — for a rate an institution asked for by telephone, or a name in a language the
 * registry does not carry.
 *
 * And the registry is worth keeping. Asked of all 55 of its libraries, every reachable instance
 * reported the version the registry already had. What it does NOT carry is anything local:
 *
 *   rps    absent entirely — the knob an operator reaches for after a phone call
 *   name   exactly two, the institution's own language and English, across all 55 rows
 *   url    one, so no way to point a library at a test host or a mid-upgrade address
 *
 * Those three are what a declaration is for. Everything else it should be able to leave alone.
 */
package cz.mzk.iiif.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cz.mzk.iiif.gateway.registry.Registry;

class LibraryOverlayTest {

  private HttpServer server;

  /** Two libraries, shaped like the real registry rows. */
  private static final String REGISTRY_JSON =
      """
      [{"code":"mzk","name":"Moravská zemská knihovna","name_en":"Moravian Library",
        "url":"https://api.kramerius.mzk.cz","version":"7.2.2.3","alive":true},
       {"code":"vkol","name":"Vědecká knihovna v Olomouci","name_en":"Research Library Olomouc",
        "url":"https://kramerius.kr-olomoucky.cz","version":"7.2.2","alive":true},
       {"code":"svkos","name":"Moravskoslezská vědecká knihovna","name_en":"Research Library Ostrava",
        "url":"https://kramerius.msvk.cz","version":"7.1.3","alive":false}]
      """;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String registryUrl() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] bytes = REGISTRY_JSON.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.setExecutor(null);
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/libraries";
  }

  private Libraries librariesFor(Map<String, String> env) throws IOException {
    Config config = Config.from(env);
    HttpClient http = HttpClient.newHttpClient();
    Optional<Registry> registry =
        config
            .registryUrl()
            .map(url -> new Registry(http, url, config.registryTtl(), config.readTimeout()));
    return new Libraries(config, registry, Optional.empty(), http);
  }

  @Test
  @DisplayName("a declared library does not hide the registry's other libraries")
  void theRegistrySurvivesADeclaration() throws IOException {
    // The bug in one line. Declaring `mzk` used to drop `vkol` and `svkos` from codes() entirely,
    // so /libraries listed one library and the other two 404'd.
    Libraries libraries =
        librariesFor(
            Map.of(
                "IIIF_REGISTRY_URL",
                registryUrl(),
                "IIIF_LIBRARIES",
                "mzk=|" // url and version left to the registry
                ));

    assertThat(libraries.codes()).contains("mzk", "vkol", "svkos");
  }

  @Test
  @DisplayName("a declaration supplies only what it states; the registry supplies the rest")
  void statedFieldsWinAndTheRestIsInherited() throws IOException {
    Config config =
        Config.from(
            Map.of(
                "IIIF_REGISTRY_URL",
                registryUrl(),
                "IIIF_LIBRARIES",
                "mzk=http://localhost:8983||cs:Jiné jméno;de:Mährische Bibliothek"));

    Config.StaticLibrary declared = config.staticLibraries().get("mzk");

    assertThat(declared.url()).contains("http://localhost:8983");
    // Not stated, so the registry's 7.2.2.3 stands rather than an empty string overwriting it.
    assertThat(declared.version()).isEmpty();
    assertThat(declared.names()).containsOnlyKeys("cs", "de");
  }

  @Test
  @DisplayName("a file entry may carry nothing but a rate, which is the reason this exists")
  void rateOnlyOverlay(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("libraries.json");
    Files.writeString(file, "{\"libraries\": {\"mzk\": {\"rps\": 4}}}");

    Config config =
        Config.from(
            Map.of(
                "IIIF_REGISTRY_URL", registryUrl(),
                "IIIF_LIBRARIES_FILE", file.toString()));

    assertThat(config.registryUrl()).isNotEmpty();
    assertThat(config.staticLibraries().get("mzk").rps()).hasValue(4);
    assertThat(config.staticLibraries().get("mzk").url()).isEmpty();
  }

  @Test
  @DisplayName("declaring a library the registry calls dead serves it anyway")
  void aDeclarationOverrulesTheRegistrysAliveFlag() throws IOException {
    // `svkos` is why. The registry called it dead for eight months while the instance answered
    // perfectly well — and had its version wrong at the same time, by a whole major version. An
    // operator who can see that should not have to wait for the registry to agree.
    Libraries registryOnly = librariesFor(Map.of("IIIF_REGISTRY_URL", registryUrl()));
    assertThat(registryOnly.resolve("svkos")).isInstanceOf(Libraries.Resolution.Dead.class);

    Libraries declared =
        librariesFor(Map.of("IIIF_REGISTRY_URL", registryUrl(), "IIIF_LIBRARIES", "svkos=|"));

    // Not Dead. It will fail at the instance if the instance is really down, which is a different
    // answer arrived at honestly.
    assertThat(declared.resolve("svkos")).isNotInstanceOf(Libraries.Resolution.Dead.class);
  }

  @Test
  @DisplayName("with no registry, a declaration still has to be complete")
  void pureStaticModeIsUnchanged() throws IOException {
    // The deployment with no runtime dependency on a shared service: nothing to inherit from, so a
    // half-declared library is Unknown rather than a half-built one.
    Libraries libraries = librariesFor(Map.of("IIIF_LIBRARIES", "mzk=|7.2.2|cs:Bez adresy"));

    assertThat(libraries.resolve("mzk")).isInstanceOf(Libraries.Resolution.Unknown.class);
  }
}
