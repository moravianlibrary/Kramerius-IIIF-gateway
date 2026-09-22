/*
 * Configuration, read from a map so the real environment is never touched.
 *
 * The cases that matter are the ones where a wrong answer is silent: a typo'd number, a library list
 * that does not parse, and no source of libraries at all. All three should stop the process at startup
 * rather than produce a gateway that serves nothing and says nothing.
 */
package cz.mzk.iiif.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

  private static final Map<String, String> MINIMAL =
      Map.of("IIIF_REGISTRY_URL", "https://api.registr.digitalniknihovna.cz/api/libraries");

  @Test
  @DisplayName("defaults come from .env.example, not from scattered call sites")
  void defaults() {
    Config config = Config.from(MINIMAL);

    assertThat(config.port()).isEqualTo(8080);
    assertThat(config.adminPort()).isEqualTo(8081);
    assertThat(config.fallbackCanvasSize()).isEqualTo(1000);
    assertThat(config.dimensionWait().toSeconds()).isEqualTo(10);
    assertThat(config.database()).isEmpty();
  }

  @Test
  @DisplayName("excluded libraries are parsed as a set, trimmed and case-folded")
  void excludedLibraries() {
    // A code arrives from a URL path, where nobody guarantees its case. Comparing raw would make
    // /MENDELU/uuid:… serve a library /mendelu/uuid:… refuses.
    Config config =
        Config.from(
            Map.of(
                "IIIF_REGISTRY_URL", "https://example.org/libraries",
                "IIIF_EXCLUDED_LIBRARIES", " MENDELU , nfa,"));

    assertThat(config.excludedLibraries()).containsExactlyInAnyOrder("mendelu", "nfa");
    assertThat(Config.from(MINIMAL).excludedLibraries()).isEmpty();
  }

  @Test
  @DisplayName("an empty overlay and no registry is still a startup failure")
  void nothingToServeIsRefused(@TempDir Path directory) throws IOException {
    // LibraryFile used to refuse an empty file itself, back when a file WAS the library list. It no
    // longer does, because an empty overlay is the ordinary state alongside a registry. The
    // guarantee has to survive the move: a gateway with no registry and nothing declared serves
    // nothing, and should say so at startup rather than 404 every request.
    Path file = directory.resolve("libraries.json");
    Files.writeString(file, "{\"libraries\": {}}");

    assertThatThrownBy(() -> Config.from(Map.of("IIIF_LIBRARIES_FILE", file.toString())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nothing to serve");
  }

  @Test
  @DisplayName("declaring a library does NOT switch the registry off — they overlay")
  void declarationsOverlayTheRegistry() {
    // It used to. That made the two mutually exclusive, so an operator who wanted one library
    // rate-limited had to restate all fifty-six and then own keeping them current. Both set means
    // the registry supplies the set and the facts, and declarations are applied over it.
    Config config =
        Config.from(
            Map.of(
                "IIIF_REGISTRY_URL", "https://example.org/registry",
                "IIIF_LIBRARIES", "mzk=https://api.kramerius.mzk.cz|7.2.2"));

    assertThat(config.registryUrl()).contains("https://example.org/registry");
    assertThat(config.staticLibraries())
        .containsOnlyKeys("mzk")
        .extractingByKey("mzk")
        .extracting(library -> library.version().orElseThrow())
        .isEqualTo("7.2.2");
  }

  @Test
  @DisplayName("several inline libraries parse")
  void severalStaticLibraries() {
    Config config =
        Config.from(Map.of("IIIF_LIBRARIES", "mzk=https://a|7.2.2, ntk=https://b|7.1.7"));

    assertThat(config.staticLibraries()).containsOnlyKeys("mzk", "ntk");
  }

  @Test
  @DisplayName("a declared library carries its name, language-tagged")
  void staticLibraryNames() {
    Config config =
        Config.from(
            Map.of(
                "IIIF_LIBRARIES",
                "mzk=https://api.kramerius.mzk.cz|7.2.2"
                    + "|cs:Moravská zemská knihovna;en:Moravian Library"));

    assertThat(config.staticLibraries().get("mzk").names())
        .containsEntry("cs", List.of("Moravská zemská knihovna"))
        .containsEntry("en", List.of("Moravian Library"));
  }

  @Test
  @DisplayName("a library with no declared name has none, rather than a guessed one")
  void staticLibraryWithoutNames() {
    // The profile then attributes the document to the bare code, untagged — see Libraries.profile.
    Config config = Config.from(Map.of("IIIF_LIBRARIES", "mzk=https://a|7.2.2"));

    assertThat(config.staticLibraries().get("mzk").names()).isEmpty();
  }

  @Test
  @DisplayName("a name without a language tag is refused rather than assumed Czech")
  void nameNeedsALanguage() {
    // Guessing here is the same bug as v1 tagging everything "cz".
    assertThatThrownBy(
            () ->
                Config.from(
                    Map.of("IIIF_LIBRARIES", "mzk=https://a|7.2.2|Moravská zemská knihovna")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("language tag");
  }

  @Test
  @DisplayName("a library file is a source of libraries, and also stops the registry being read")
  void libraryFile(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("libraries.json");
    Files.writeString(
        file,
        """
        {"libraries": {"mzk": {"url": "https://api.kramerius.mzk.cz", "version": "7.2.2.1"},
                       "vkol": {"url": "https://kramerius.vkol.cz", "version": "7.2.2"}}}
        """);

    Config config =
        Config.from(
            Map.of(
                "IIIF_REGISTRY_URL",
                "https://example.org/registry",
                "IIIF_LIBRARIES_FILE",
                file.toString()));

    assertThat(config.registryUrl()).contains("https://example.org/registry");
    assertThat(config.staticLibraries()).containsOnlyKeys("mzk", "vkol");
  }

  @Test
  @DisplayName("an inline library overrides the file's entry for the same code")
  void inlineOverridesTheFile(@TempDir Path directory) throws IOException {
    // The file is generated, so overriding one library must not mean editing something the next
    // `make libraries` rewrites.
    Path file = directory.resolve("libraries.json");
    Files.writeString(
        file,
        """
        {"libraries": {"mzk": {"url": "https://api.kramerius.mzk.cz", "version": "7.2.2.1"}}}
        """);

    Config config =
        Config.from(
            Map.of(
                "IIIF_LIBRARIES_FILE",
                file.toString(),
                "IIIF_LIBRARIES",
                "mzk=http://localhost:8983|7.2.2"));

    assertThat(config.staticLibraries())
        .containsOnlyKeys("mzk")
        .extractingByKey("mzk")
        .extracting(Config.StaticLibrary::url)
        .isEqualTo(java.util.Optional.of("http://localhost:8983"));
  }

  @Test
  @DisplayName("a library may declare its own upstream rate, and most do not")
  void perLibraryRps(@TempDir Path directory) throws IOException {
    // 56 institutions of wildly different size behind one global number is the wrong shape. This is
    // the knob an operator reaches for after a phone call from one of them.
    Path file = directory.resolve("libraries.json");
    Files.writeString(
        file,
        """
        {"libraries": {"lmda": {"url": "https://lmda.silvarium.cz", "version": "5.8.1", "rps": 4},
                       "mzk": {"url": "https://api.kramerius.mzk.cz", "version": "7.2.2"}}}
        """);

    Config config = Config.from(Map.of("IIIF_LIBRARIES_FILE", file.toString()));

    assertThat(config.staticLibraries().get("lmda").rps()).hasValue(4);
    assertThat(config.staticLibraries().get("mzk").rps()).isEmpty();
  }

  @Test
  @DisplayName("a non-integer rate is a startup failure, not a silent fallback to the global")
  void malformedRps(@TempDir Path directory) throws IOException {
    // Reverting to 20 because someone typed "5 " is the kind of thing nobody notices until the
    // phone rings a second time.
    Path file = directory.resolve("libraries.json");
    Files.writeString(
        file,
        """
        {"libraries": {"lmda": {"url": "https://x", "version": "5.8.1", "rps": "gently"}}}
        """);

    assertThatThrownBy(() -> Config.from(Map.of("IIIF_LIBRARIES_FILE", file.toString())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("rps");
  }

  @Test
  @DisplayName("nothing to serve is a startup failure, not a gateway that 404s everything")
  void noLibrarySource() {
    assertThatThrownBy(() -> Config.from(Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nothing to serve");
  }

  @Test
  @DisplayName("a malformed library entry names itself")
  void malformedLibraries() {
    assertThatThrownBy(() -> Config.from(Map.of("IIIF_LIBRARIES", "mzk=https://a")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mzk=https://a");
  }

  @Test
  @DisplayName("a non-numeric number fails loudly at startup, naming the key")
  void badNumber() {
    // Otherwise it fails at the first request that needed the value, hours later, somewhere else.
    assertThatThrownBy(
            () ->
                Config.from(
                    Map.of(
                        "IIIF_REGISTRY_URL", "https://example.org",
                        "IIIF_PORT", "eighty-eighty")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("IIIF_PORT");
  }

  @Test
  @DisplayName("a database URL brings the rest of the database settings with it")
  void database() {
    Config config =
        Config.from(
            Map.of(
                "IIIF_REGISTRY_URL", "https://example.org",
                "IIIF_DB_URL", "jdbc:postgresql://localhost:5432/iiif",
                "IIIF_DB_USER", "iiif",
                "IIIF_DB_POOL_SIZE", "20"));

    assertThat(config.database())
        .get()
        .satisfies(
            database -> {
              assertThat(database.poolSize()).isEqualTo(20);
              assertThat(database.user()).isEqualTo("iiif");
            });
  }
}
