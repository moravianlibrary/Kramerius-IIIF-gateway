/*
 * Which catalogue a library's system numbers resolve to, from configuration rather than from code.
 *
 * This used to be `code.equals("mzk") ? new CatalogueLink(…) : null` in Libraries, with MZK's URL and
 * its two Aleph bases as Java literals — while a comment two files away claimed both were already
 * configuration. They were not: fifty-five libraries could not have a catalogue link at all without a
 * code change and a redeploy.
 *
 * The shape follows TranslationDirectory, for the same reasons: bundled defaults in the jar so the
 * common case needs no setup, plus a directory an operator can add to. One file per library, named for
 * its registry code — mzk.json, vkol.json — so adding a library is adding a file, and nothing has to be
 * kept in step with a release.
 *
 * A missing directory is the normal case. An unreadable or malformed file is logged and skipped rather
 * than fatal: a bad catalogue mapping should cost that library its catalogue link, not the gateway its
 * ability to start.
 */
package cz.mzk.iiif.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

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

  /** The bundled mappings, with any from the configured directory added over them. */
  public static Catalogues load(Optional<String> directory) {
    Map<String, CatalogueLink> links = new LinkedHashMap<>(bundled());
    directory.ifPresent(dir -> links.putAll(fromDirectory(Path.of(dir))));
    LOG.info("catalogue links for {}", links.keySet());
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

  private static Map<String, CatalogueLink> fromDirectory(Path directory) {
    if (!Files.isDirectory(directory)) {
      LOG.warn("IIIF_CATALOGUES_DIR {} is not a directory; using the bundled mappings", directory);
      return Map.of();
    }
    Map<String, CatalogueLink> links = new LinkedHashMap<>();
    try (Stream<Path> files = Files.list(directory)) {
      for (Path file : files.filter(path -> name(path).endsWith(".json")).toList()) {
        String name = name(file);
        String code = name.substring(0, name.length() - ".json".length());
        try (InputStream in = Files.newInputStream(file)) {
          parse(code, MAPPER.readTree(in)).ifPresent(link -> links.put(code, link));
        } catch (IOException | RuntimeException e) {
          // One bad file costs that library its link, and nothing else.
          LOG.warn("skipping catalogue file {}: {}", file, e.toString());
        }
      }
      LOG.info("merging catalogue mappings from {} for {}", directory, links.keySet());
    } catch (IOException e) {
      LOG.warn("could not read {}: {}", directory, e.toString());
    }
    return links;
  }

  /** A path's last element, or empty for a root — which cannot be a catalogue file. */
  private static String name(Path path) {
    Path fileName = path.getFileName();
    return fileName == null ? "" : fileName.toString();
  }

  private static Optional<CatalogueLink> parse(String code, JsonNode node) {
    if (node == null || !node.hasNonNull("url")) {
      return Optional.empty();
    }
    List<CatalogueLink.Rule> rules = new ArrayList<>();
    for (JsonNode rule : node.path("rules")) {
      String pattern = rule.path("pattern").asText(null);
      if (pattern == null || pattern.isBlank()) {
        continue;
      }
      rules.add(CatalogueLink.Rule.of(pattern, rule.path("prefix").asText("")));
    }
    if (rules.isEmpty()) {
      LOG.warn("catalogue mapping for {} has no usable rules; ignoring it", code);
      return Optional.empty();
    }
    return Optional.of(new CatalogueLink(node.get("url").asText(), rules));
  }
}
