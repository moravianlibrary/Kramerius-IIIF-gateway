/*
 * Which catalogue a library's system numbers resolve to, from configuration rather than from code.
 *
 * This used to be `code.equals("mzk") ? new CatalogueLink(…) : null` in Libraries, with MZK's URL and
 * its two Aleph bases as Java literals — while a comment two files away claimed both were already
 * configuration. They were not: fifty-five libraries could not have a catalogue link at all without a
 * code change and a redeploy.
 *
 * WHAT IS LEFT HERE is the bundled default — MZK's, the only mapping anyone has verified — so the
 * common case needs no setup at all. Everything else is declared per library in IIIF_LIBRARIES_FILE,
 * beside that library's rps and its names, because a catalogue mapping is the same KIND of thing:
 * something local that the shared registry has no field for. There was an IIIF_CATALOGUES_DIR holding
 * one <code>.json per library; it is gone, because two mechanisms for one kind of setting is one more
 * than anyone can be expected to remember, and only one of them had a schema to check.
 *
 * Parsing stays here rather than moving into LibraryFile: it is the shape of a catalogue mapping, and
 * it is read from two places — the jar and the library file — which must not drift apart. It throws on
 * anything it cannot use, and BOTH callers let that through: a mapping nobody can apply stops the
 * gateway rather than costing one library a link nobody notices is missing.
 */
package cz.mzk.iiif.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.spi.CatalogueLink;

public final class Catalogues {

  private static final Logger LOG = LoggerFactory.getLogger(Catalogues.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String BUNDLED = "/catalogues.json";

  private final Map<String, CatalogueLink> byLibrary;

  private Catalogues(Map<String, CatalogueLink> byLibrary) {
    this.byLibrary = byLibrary;
  }

  /** The bundled mappings. A library file may override any of them, per library. */
  public static Catalogues load() {
    Map<String, CatalogueLink> links = bundled();
    LOG.info("bundled catalogue links for {}", links.keySet());
    return new Catalogues(Map.copyOf(links));
  }

  public Optional<CatalogueLink> forLibrary(String code) {
    return Optional.ofNullable(byLibrary.get(code));
  }

  private static Map<String, CatalogueLink> bundled() {
    try (InputStream in = Catalogues.class.getResourceAsStream(BUNDLED)) {
      if (in == null) {
        throw new IllegalStateException(BUNDLED + " missing from the jar");
      }
      JsonNode root = MAPPER.readTree(in).path("catalogues");
      Map<String, CatalogueLink> links = new LinkedHashMap<>();
      root.fieldNames()
          .forEachRemaining(
              code -> parse(code, root.get(code)).ifPresent(link -> links.put(code, link)));
      return links;
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + BUNDLED, e);
    }
  }

  /**
   * One catalogue mapping, from the jar or from a library file.
   *
   * <p>Empty when the node declares no `url`, which is how "this entry has no catalogue" is
   * spelled. Anything present but malformed throws — a rule with no capturing group cannot be
   * applied, and silently dropping it would leave a library with a catalogue link for some of its
   * documents and not others. The CALLER decides what that costs; both callers catch it and skip
   * that one library.
   */
  static Optional<CatalogueLink> parse(String code, JsonNode node) {
    if (node == null || !node.hasNonNull("url")) {
      return Optional.empty();
    }
    List<CatalogueLink.Rule> rules = new ArrayList<>();
    for (JsonNode rule : node.path("rules")) {
      String pattern = rule.path("pattern").asText(null);
      if (pattern == null || pattern.isBlank()) {
        throw new IllegalArgumentException("a catalogue rule for " + code + " has no \"pattern\"");
      }
      rules.add(CatalogueLink.Rule.of(pattern, rule.path("prefix").asText("")));
    }
    if (rules.isEmpty()) {
      // A url with nothing that matches produces no link for any document, which is
      // indistinguishable from having no mapping at all — except that somebody wrote one and
      // believes it works.
      throw new IllegalArgumentException("catalogue mapping for " + code + " has no usable rules");
    }
    return Optional.of(new CatalogueLink(node.get("url").asText(), rules));
  }
}
