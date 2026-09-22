/*
 * The library file, which is the list of things the process exists to serve.
 *
 * So the cases that matter are the ones where being lenient would produce a gateway that starts and
 * then cannot serve what the operator configured: a missing file, a typo in the JSON, a library with no
 * version (the field that picks the adapter), an empty list. Every one of them stops startup and names
 * the file, because the alternative is discovering it as a 404 per library in production.
 *
 * The shipped config/libraries.json is checked here too. It is generated from a live survey, so this
 * does not assert what is in it — only that what the generator writes is what the reader accepts.
 */
package cz.mzk.iiif.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryFileTest {

  @TempDir Path directory;

  private Path write(String json) throws IOException {
    Path file = directory.resolve("libraries.json");
    Files.writeString(file, json, StandardCharsets.UTF_8);
    return file;
  }

  @Test
  @DisplayName("a library carries its URL, its version and its name by language")
  void reads() throws IOException {
    Path file =
        write(
            """
            {"libraries": {
              "mzk": {"url": "https://api.kramerius.mzk.cz", "version": "7.2.2.1",
                      "name": {"cs": "Moravská zemská knihovna", "en": "Moravian Library"}}}}
            """);

    Map<String, Config.StaticLibrary> libraries = LibraryFile.load(file);

    assertThat(libraries).containsOnlyKeys("mzk");
    assertThat(libraries.get("mzk").url()).contains("https://api.kramerius.mzk.cz");
    assertThat(libraries.get("mzk").version()).contains("7.2.2.1");
    assertThat(libraries.get("mzk").names())
        .containsEntry("cs", List.of("Moravská zemská knihovna"))
        .containsEntry("en", List.of("Moravian Library"));
  }

  @Test
  @DisplayName("every declared library is read, not just the first")
  void readsSeveral() throws IOException {
    Path file =
        write(
            """
            {"libraries": {
              "vkol": {"url": "https://kramerius.vkol.cz", "version": "7.2.2"},
              "mzk":  {"url": "https://api.kramerius.mzk.cz", "version": "7.2.2.1"},
              "ntk":  {"url": "https://kramerius.techlib.cz", "version": "7.1.7"}}}
            """);

    assertThat(LibraryFile.load(file)).containsOnlyKeys("vkol", "mzk", "ntk");
  }

  @Test
  @DisplayName("keys beginning with _ are notes, not libraries")
  void ignoresUnderscoreKeys() throws IOException {
    // The generator writes _excluded next to the list so a missing library explains itself.
    Path file =
        write(
            """
            {"_comment": ["generated"],
             "libraries": {"mzk": {"url": "https://a", "version": "7.2.2"}},
             "_excluded": {"nacr": "TLS: certificate has expired"}}
            """);

    assertThat(LibraryFile.load(file)).containsOnlyKeys("mzk");
  }

  @Test
  @DisplayName("a library with no name has none, rather than one guessed from its code")
  void nameIsOptional() throws IOException {
    Path file =
        write(
            """
        {"libraries": {"mzk": {"url": "https://a", "version": "7.2.2"}}}
        """);

    assertThat(LibraryFile.load(file).get("mzk").names()).isEmpty();
  }

  @Test
  @DisplayName("a name that is not language-tagged is refused rather than assumed Czech")
  void nameNeedsLanguageTags() throws IOException {
    Path file =
        write(
            """
            {"libraries": {"mzk": {"url": "https://a", "version": "7.2.2",
                                   "name": "Moravská zemská knihovna"}}}
            """);

    assertThatThrownBy(() -> LibraryFile.load(file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("language");
  }

  @Test
  @DisplayName("a catalogue mapping is read from the entry, beside the rps and the names")
  void catalogueIsPartOfTheEntry() throws IOException {
    // It used to live in IIIF_CATALOGUES_DIR, one <code>.json per library — a second mechanism for
    // the same kind of thing, and the one without a schema to check it.
    Path file =
        write(
            """
        {"libraries": {"vkol": {"rps": 4, "catalogue": {
            "url": "https://vufind.vkol.cz/Record/{id}",
            "rules": [{"pattern": "^vkol01-?(\\\\d{9})$", "prefix": "VKOL01-"}]}}}}
        """);

    Config.StaticLibrary vkol = LibraryFile.load(file).get("vkol");

    assertThat(vkol.rps()).hasValue(4);
    assertThat(vkol.catalogue()).isPresent();
    assertThat(vkol.catalogue().orElseThrow().urlFor("vkol01-000913055"))
        .contains("https://vufind.vkol.cz/Record/VKOL01-000913055");
  }

  @Test
  @DisplayName(
      "a broken catalogue mapping costs that library its link, not the gateway its startup")
  void aBrokenCatalogueIsSkipped() throws IOException {
    // The one place this file is not strict, and deliberately. Everything else here is fatal
    // because a library list that cannot be read is the list of things the process exists to serve.
    // A regex with no capturing group is not that: it costs one library one link, the manifests are
    // otherwise complete, and refusing to start would be the worse outcome. Keeping that difference
    // is what folding the catalogue directory in here had to preserve.
    Path file =
        write(
            """
        {"libraries": {"vkol": {"rps": 4, "catalogue": {
            "url": "https://vufind.vkol.cz/Record/{id}",
            "rules": [{"pattern": "^vkol01-\\\\d{9}$", "prefix": "VKOL01-"}]}}}}
        """);

    Config.StaticLibrary vkol = LibraryFile.load(file).get("vkol");

    // The entry survives, and everything else in it still applies.
    assertThat(vkol).isNotNull();
    assertThat(vkol.rps()).hasValue(4);
    assertThat(vkol.catalogue()).isEmpty();
  }

  @Test
  @DisplayName("url and version are optional: an entry may be an overlay on the registry")
  void urlAndVersionAreOptional() throws IOException {
    // The case this exists for: an institution telephones and asks for a gentler rate. Saying so
    // used to mean restating its url, its version and both its names — and turning the registry off
    // for the other fifty-five libraries, since a declaration replaced the whole set.
    Path file =
        write(
            """
        {"libraries": {"mzk": {"rps": 4},
                       "vkol": {"name": {"de": "Wissenschaftliche Bibliothek Olmütz"}}}}
        """);

    Map<String, Config.StaticLibrary> libraries = LibraryFile.load(file);

    assertThat(libraries.get("mzk").url()).isEmpty();
    assertThat(libraries.get("mzk").version()).isEmpty();
    assertThat(libraries.get("mzk").rps()).hasValue(4);
    assertThat(libraries.get("vkol").names()).containsOnlyKeys("de");
    // Whether an entry with neither url nor registry entry can be served is decided at resolve
    // time, where it is possible to know what the registry covers. Config cannot see that.
  }

  @Test
  @DisplayName("a file with no libraries object says so, rather than serving nothing quietly")
  void wrongShape() throws IOException {
    Path file =
        write(
            """
        {"mzk": {"url": "https://a", "version": "7.2.2"}}
        """);

    assertThatThrownBy(() -> LibraryFile.load(file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("libraries");
  }

  @Test
  @DisplayName("malformed JSON names the file, so the operator knows which one to fix")
  void malformedJson() throws IOException {
    Path file = write("{\"libraries\": {");

    assertThatThrownBy(() -> LibraryFile.load(file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("libraries.json")
        .hasMessageContaining("not valid JSON");
  }

  @Test
  @DisplayName("a missing file is a startup failure rather than an empty library list")
  void missingFile() {
    assertThatThrownBy(() -> LibraryFile.load(directory.resolve("absent.json")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not exist");
  }

  @Test
  @DisplayName("the shipped config/libraries.json is an OVERLAY, not a copy of the registry")
  void shippedFileIsAnOverlay() {
    Path shipped = Path.of("..", "..", "config", "libraries.json").toAbsolutePath().normalize();
    if (!Files.isReadable(shipped)) {
      return; // Built from a source archive without the repository around it.
    }

    Map<String, Config.StaticLibrary> libraries = LibraryFile.load(shipped);

    // Empty is correct and is the shipped state: there is nothing local to say yet. The file used
    // to hold 38 libraries, each restating the url, version and name the registry already had and
    // carrying not one local setting — a second copy of somebody else's data, going stale from the
    // moment it was written.
    //
    // The assertion that matters is the one below: whatever ends up here must be LOCAL. A url or a
    // version is legitimate for a test host or an instance that answers no /info, so those are not
    // banned — but an entry that states nothing the registry lacks is a copy creeping back in.
    assertThat(libraries.values())
        .allSatisfy(
            library ->
                assertThat(
                        library.rps().isPresent()
                            || !library.names().isEmpty()
                            || library.url().isPresent()
                            || library.version().isPresent())
                    .as(
                        "%s states nothing; an empty entry is a copy of the registry",
                        library.code())
                    .isTrue());
  }

  @Test
  @DisplayName("an empty overlay is valid: a deployment may have nothing local to say")
  void anEmptyOverlayIsFine() throws IOException {
    // It used to be a startup failure, back when a file was the whole library list. With the
    // registry supplying the set, refusing to start over an empty overlay would refuse the
    // ordinary case. Config still fails when there is no registry AND nothing declared.
    Path file =
        write(
            """
        {"libraries": {}}
        """);

    assertThat(LibraryFile.load(file)).isEmpty();
  }
}
