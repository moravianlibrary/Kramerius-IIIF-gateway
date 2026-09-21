/*
 * The system-number-to-catalogue-URL rules.
 *
 * Every case here is a real value from MZK's index, and every expected URL was checked against
 * vufind.mzk.cz. The three shapes are all present in live data — 654 prefixed-with-hyphen, 307 bare and
 * 39 prefixed-without-hyphen in 1000 sampled records — so none of them is hypothetical.
 *
 * Two of these tests exist because a simpler rule passed a spot check and was wrong:
 *   - stripping the prefix and applying one base breaks mzk03 records (MZK01-000548262 is a 404);
 *   - taking the trailing digit run breaks mzk03000706629, because the base itself ends in digits.
 */
package cz.mzk.iiif.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CatalogueLinkTest {

  /** The same rules the bundled catalogues.json carries, so this tests what ships. */
  private static final CatalogueLink MZK =
      new CatalogueLink(
          "https://vufind.mzk.cz/Record/{id}",
          List.of(
              CatalogueLink.Rule.of("^mzk01-?(\\d{9})$", "MZK01-"),
              CatalogueLink.Rule.of("^mzk03-?(\\d{9})$", "MZK03-"),
              CatalogueLink.Rule.of("^(\\d{9})$", "MZK01-")));

  /** vkol's catalogue takes a bare number, which an empty prefix expresses. */
  private static final CatalogueLink VKOL =
      new CatalogueLink(
          "https://vufind.vkol.cz/Record/{id}",
          List.of(CatalogueLink.Rule.of("^vkol-?(\\d{9})$", "")));

  @Nested
  @DisplayName("the three shapes MZK actually indexes")
  class MzkShapes {

    @Test
    @DisplayName("prefix with hyphen keeps its own base")
    void prefixedWithHyphen() {
      assertThat(MZK.urlFor("mzk01-001506523"))
          .contains("https://vufind.mzk.cz/Record/MZK01-001506523");
    }

    @Test
    @DisplayName("mzk03 maps to MZK03, not to the default base")
    void secondBaseIsNotDiscarded() {
      // MZK01-000548262 is a 404; the prefix in the value is load-bearing.
      assertThat(MZK.urlFor("mzk03-000548262"))
          .contains("https://vufind.mzk.cz/Record/MZK03-000548262");
    }

    @Test
    @DisplayName("a bare number takes the configured default base")
    void bare() {
      assertThat(MZK.urlFor("000970198")).contains("https://vufind.mzk.cz/Record/MZK01-000970198");
    }

    @Test
    @DisplayName("prefix without a hyphen splits on the last nine digits, not the digit run")
    void prefixedWithoutHyphen() {
      // A greedy trailing-digit match reads this as "mzk" + "03000706629" and 404s.
      assertThat(MZK.urlFor("mzk03000706629"))
          .contains("https://vufind.mzk.cz/Record/MZK03-000706629");
    }
  }

  @Nested
  @DisplayName("other libraries")
  class Other {

    @Test
    @DisplayName("an empty prefix means the catalogue wants a bare number")
    void bareNumberCatalogue() {
      assertThat(VKOL.urlFor("vkol001054610")).contains("https://vufind.vkol.cz/Record/001054610");
    }
  }

  @Nested
  @DisplayName("refusing rather than guessing")
  class Refusals {

    @Test
    @DisplayName("a prefix no rule claims yields no link")
    void unknownPrefix() {
      assertThat(MZK.urlFor("mzk99-000123456")).isEmpty();
    }

    @Test
    @DisplayName("something that is not a system number yields no link")
    void notASystemNumber() {
      assertThat(MZK.urlFor("not-a-number")).isEmpty();
      assertThat(MZK.urlFor("12345")).isEmpty();
      assertThat(MZK.urlFor("")).isEmpty();
      assertThat(MZK.urlFor(null)).isEmpty();
    }

    @Test
    @DisplayName("a template with no placeholder is a configuration error, caught at construction")
    void templateMustHavePlaceholder() {
      assertThatThrownBy(
              () ->
                  new CatalogueLink(
                      "https://example.org/Record/",
                      List.of(CatalogueLink.Rule.of("^(\\d+)$", ""))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("{id}");
    }

    @Test
    @DisplayName("a pattern with no capturing group is a configuration error, not a silent no-link")
    void patternMustCaptureTheNumber() {
      // Without this, a mapping that looks right in a config file yields no links at all and says
      // nothing about why — the failure mode this whole class is written to avoid.
      assertThatThrownBy(() -> CatalogueLink.Rule.of("^mzk01-\\d{9}$", "MZK01-"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("group 1");
    }

    @Test
    @DisplayName("the first matching rule wins, so order is the operator's to decide")
    void firstRuleWins() {
      CatalogueLink ordered =
          new CatalogueLink(
              "https://example.org/Record/{id}",
              List.of(
                  CatalogueLink.Rule.of("^lib-(\\d{9})$", "FIRST-"),
                  CatalogueLink.Rule.of("^lib-?(\\d{9})$", "SECOND-")));

      assertThat(ordered.urlFor("lib-000000001"))
          .contains("https://example.org/Record/FIRST-000000001");
      // And the second still claims the shape the first does not.
      assertThat(ordered.urlFor("lib000000001"))
          .contains("https://example.org/Record/SECOND-000000001");
    }
  }
}
