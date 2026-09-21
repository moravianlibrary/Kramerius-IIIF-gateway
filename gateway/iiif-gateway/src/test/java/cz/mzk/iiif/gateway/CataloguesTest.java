/*
 * That the shipped catalogue configuration is the configuration that was verified.
 *
 * CatalogueLinkTest proves the rules work; it builds them in Java, so it would stay green if
 * catalogues.json had a typo in it. This loads the file that actually ships and asserts the same four
 * real MZK values resolve to the same four URLs that were checked against vufind.mzk.cz — which is the
 * only way a regex in a resource file is covered at all.
 *
 * It also covers the override directory, because "per library, without a code change" is the whole
 * point of moving this out of Java and is worth more than one assertion.
 */
package cz.mzk.iiif.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CataloguesTest {

  @Test
  @DisplayName("the bundled mapping resolves the four real MZK shapes")
  void bundledMzkMapping() {
    Catalogues catalogues = Catalogues.load(Optional.empty());

    var mzk = catalogues.forLibrary("mzk").orElseThrow();

    assertThat(mzk.urlFor("mzk01-001506523"))
        .contains("https://vufind.mzk.cz/Record/MZK01-001506523");
    // MZK01-000548262 is a 404; the prefix in the value decides the base.
    assertThat(mzk.urlFor("mzk03-000548262"))
        .contains("https://vufind.mzk.cz/Record/MZK03-000548262");
    assertThat(mzk.urlFor("000970198")).contains("https://vufind.mzk.cz/Record/MZK01-000970198");
    // The base ends in digits, so a greedy trailing-digit match reads this as mzk + 03000706629.
    assertThat(mzk.urlFor("mzk03000706629"))
        .contains("https://vufind.mzk.cz/Record/MZK03-000706629");
  }

  @Test
  @DisplayName("a library with no mapping has no catalogue link, rather than a guessed one")
  void unmappedLibrary() {
    assertThat(Catalogues.load(Optional.empty()).forLibrary("cbvk")).isEmpty();
  }

  @Test
  @DisplayName("a directory adds a library without touching the jar")
  void directoryAddsALibrary(@TempDir Path directory) throws IOException {
    // The thing that was impossible before: vkol's catalogue takes a bare number, and saying so
    // used
    // to require editing Java and redeploying.
    Files.writeString(
        directory.resolve("vkol.json"),
        """
        {"url": "https://vufind.vkol.cz/Record/{id}",
         "rules": [{"pattern": "^vkol-?(\\\\d{9})$", "prefix": ""}]}
        """);

    Catalogues catalogues = Catalogues.load(Optional.of(directory.toString()));

    assertThat(catalogues.forLibrary("vkol").orElseThrow().urlFor("vkol001054610"))
        .contains("https://vufind.vkol.cz/Record/001054610");
    // And the bundled ones are still there.
    assertThat(catalogues.forLibrary("mzk")).isPresent();
  }

  @Test
  @DisplayName("a file overrides the bundled mapping for the same library")
  void directoryOverridesBundled(@TempDir Path directory) throws IOException {
    // So an operator whose VuFind moves does not have to wait for a release.
    Files.writeString(
        directory.resolve("mzk.json"),
        """
        {"url": "https://katalog.example.org/{id}",
         "rules": [{"pattern": "^mzk01-?(\\\\d{9})$", "prefix": "X-"}]}
        """);

    var mzk = Catalogues.load(Optional.of(directory.toString())).forLibrary("mzk").orElseThrow();

    assertThat(mzk.urlFor("mzk01-001506523")).contains("https://katalog.example.org/X-001506523");
  }

  @Test
  @DisplayName("a malformed file costs that library its link and nothing else")
  void malformedFileIsSkipped(@TempDir Path directory) throws IOException {
    // Failing to start over one bad catalogue mapping would be a poor trade — the same call the
    // translation loader makes.
    Files.writeString(directory.resolve("broken.json"), "{ not json");
    Files.writeString(
        directory.resolve("nopattern.json"), "{\"url\": \"https://example.org/{id}\"}");

    Catalogues catalogues = Catalogues.load(Optional.of(directory.toString()));

    assertThat(catalogues.forLibrary("broken")).isEmpty();
    assertThat(catalogues.forLibrary("nopattern")).isEmpty();
    assertThat(catalogues.forLibrary("mzk")).isPresent();
  }

  @Test
  @DisplayName("a missing directory is the normal case, not an error")
  void missingDirectoryIsFine() {
    assertThat(Catalogues.load(Optional.of("/nonexistent/iiif/catalogues")).forLibrary("mzk"))
        .isPresent();
  }
}
