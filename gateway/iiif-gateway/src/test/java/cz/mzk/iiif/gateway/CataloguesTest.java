/*
 * The catalogue mappings that ship in the jar.
 *
 * Only MZK's, because it is the only one anyone has verified, and it exists so the common case needs no
 * setup at all. Everything else is declared per library in IIIF_LIBRARIES_FILE and covered by
 * LibraryFileTest — including the three things this file used to test through IIIF_CATALOGUES_DIR:
 * adding a library, overriding a bundled mapping, and a malformed one costing that library its link and
 * nothing else. The directory is gone; those behaviours are not.
 */
package cz.mzk.iiif.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CataloguesTest {

  @Test
  @DisplayName("the bundled mapping resolves the four real MZK shapes")
  void bundledMzkMapping() {
    Catalogues catalogues = Catalogues.load();

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
    assertThat(Catalogues.load().forLibrary("cbvk")).isEmpty();
  }
}
