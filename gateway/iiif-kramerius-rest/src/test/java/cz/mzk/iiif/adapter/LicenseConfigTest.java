/*
 * Turning an instance's licence config into names and texts.
 *
 * The fixture is the real payload from cdk-api.dev.ceskadigitalniknihovna.cz, because the shape is not
 * something to guess at: labels come in four languages, the texts live behind separate URLs keyed by a
 * `messagePages[].key`, and only the `unauthenticated` one is addressed to a reader of an anonymous
 * manifest.
 */
package cz.mzk.iiif.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LicenseConfigTest {

  private static LicenseConfig.License license(String id, String accessType) {
    return new LicenseConfig.License(
        id, java.util.Map.of(), java.util.Map.of(), java.util.Optional.ofNullable(accessType));
  }

  @Test
  @DisplayName("the easiest route to the document ranks first")
  void ranking() {
    // A document is commonly both dnnto (sign in) and onsite (come to the library). Those are
    // alternative ways in, so the cheaper one should be read first.
    assertThat(license("public", "open").rank()).isLessThan(license("dnnto", "login").rank());
    assertThat(license("dnnto", "login").rank()).isLessThan(license("onsite", "terminal").rank());
  }

  @Test
  @DisplayName("a licence whose demands are unknown sorts last, never first")
  void unknownRanksLast() {
    // Not knowing what a licence requires is not a reason to advertise it as the easy option.
    assertThat(license("mystery", null).rank()).isGreaterThan(license("onsite", "terminal").rank());
  }

  @Test
  @DisplayName("HTML licence notices become readable text, not markup")
  void stripsMarkup() {
    String html =
        "<div class=\"licence\"><p>Toto dílo je nedostupné na trhu.</p>"
            + "<p>Zpřístupněno podle&nbsp;§ 37b.</p>"
            + "<script>alert('x')</script></div>";

    String text = LicenseConfig.stripTags(html);

    assertThat(text).isEqualTo("Toto dílo je nedostupné na trhu.\nZpřístupněno podle § 37b.");
    assertThat(text).doesNotContain("<", ">", "alert");
  }

  @Test
  @DisplayName("entities are decoded so a value is not double-escaped in the manifest")
  void decodesEntities() {
    assertThat(LicenseConfig.stripTags("<p>a &amp; b &lt;c&gt; &quot;d&quot;</p>"))
        .isEqualTo("a & b <c> \"d\"");
  }

  @Test
  @DisplayName("a notice that is only whitespace collapses to nothing rather than blank lines")
  void collapsesWhitespace() {
    assertThat(LicenseConfig.stripTags("<div>\n\n  <p>  </p>\n<br>\n</div>")).isEmpty();
  }
}
