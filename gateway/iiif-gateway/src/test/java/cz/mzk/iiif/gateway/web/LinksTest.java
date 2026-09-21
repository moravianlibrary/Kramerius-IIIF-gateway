/*
 * The ?link= resolver, against the URL shapes people actually paste.
 *
 * v1 returned a manifest for the WRONG document when the path did not match what it expected, which is
 * worse than refusing: the caller gets a plausible answer about something else. Everything unparseable
 * here must come back empty so the route can answer 400.
 */
package cz.mzk.iiif.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LinksTest {

  /** The libraries this gateway serves. "search" is deliberately not one of them. */
  private static final java.util.Set<String> KNOWN = java.util.Set.of("mzk", "ntk", "knav");

  private static java.util.Optional<Links.Target> parse(String link) {
    return Links.parse(link, KNOWN::contains);
  }

  private static final String PID = "uuid:71d66fe0-d986-11e2-9923-005056827e52";

  @Test
  @DisplayName("the canonical Digital Library URL")
  void canonical() {
    assertThat(parse("https://www.digitalniknihovna.cz/mzk/uuid/" + PID))
        .contains(new Links.Target("mzk", PID));
  }

  @Test
  @DisplayName("a ?page= parameter names a page inside the document, which has no manifest")
  void pageParameterIsIgnored() {
    // The first pid in the URL is the document. Returning the page's pid would 404, because a page
    // is
    // part of a document rather than one.
    String link =
        "https://www.digitalniknihovna.cz/mzk/view/"
            + PID
            + "?page=uuid:fa0572d0-2b61-11e3-b62e-005056825209";

    assertThat(parse(link)).contains(new Links.Target("mzk", PID));
  }

  @Test
  @DisplayName("another library's code is read from the path, not assumed")
  void otherLibrary() {
    assertThat(parse("https://www.digitalniknihovna.cz/ntk/uuid/" + PID))
        .contains(new Links.Target("ntk", PID));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "   ",
        "not a url",
        "https://www.digitalniknihovna.cz/mzk/",
        "https://kramerius.mzk.cz/search/handle/" + "uuid:71d66fe0-d986-11e2-9923-005056827e52"
      })
  @DisplayName("anything without both a code and a pid is refused rather than guessed")
  void refuses(String link) {
    // The last one has a pid, but "search" is not a library — v1 would have answered for it.
    assertThat(parse(link)).isEmpty();
  }
}
