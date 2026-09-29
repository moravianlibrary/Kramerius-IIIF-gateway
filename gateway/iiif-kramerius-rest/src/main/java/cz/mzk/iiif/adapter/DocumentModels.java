/*
 * The document-model table, loaded from JSON.
 *
 * Same shape as Translations and for the same reason: this is data that changes when Kramerius changes,
 * not logic, so it lives in a file the host can override at startup rather than in a release.
 *
 * Validation happens HERE, at load, not at request time. A shape name that no IiifStructure matches, a
 * promoteTo pointing at a model that does not exist, a promoteWhen naming no child relation — all of them are
 * configuration errors, and all of them abort startup with the offending value quoted. The alternative is
 * a manifest request failing at three in the morning because someone typed "PAGESS".
 */
package cz.mzk.iiif.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.kramerius.IiifStructure;

public final class DocumentModels {

  private static final Logger LOG = LoggerFactory.getLogger(DocumentModels.class);

  /**
   * The floor of the table shipped today. Named for the first Kramerius version it describes, like
   * the adapters, because which models exist and what they contain changes between releases —
   * `article` and `internalpart` carry no part fields at all, `supplement` is the inverse. A table
   * without a version on it would be a claim that Kramerius' model set is fixed, which it is not.
   */
  public static final String KRAMERIUS_70 = "70";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> CHILD_RELATIONS =
      Set.of(
          "PAGES",
          "TRACKS",
          "SOUND_UNITS",
          "MONOGRAPH_UNITS",
          "PERIODICAL_VOLUMES",
          "PERIODICAL_ISSUES",
          "CONVOLUTE_PARTS");

  private final Map<String, DocumentModel> models;
  private final List<String> components;
  private final String pageModel;

  private DocumentModels(
      Map<String, DocumentModel> models, List<String> components, String pageModel) {
    this.models = models;
    this.components = components;
    this.pageModel = pageModel;
  }

  /**
   * The table shipped in the jar for one version floor.
   *
   * <p>An adapter asks for its own floor, so the model table is versioned with the adapter that
   * reads it rather than being one shared file that every release has to agree on.
   */
  public static DocumentModels bundled(String versionFloor) {
    String resource = "/models/kramerius-" + versionFloor + ".json";
    try (InputStream in = DocumentModels.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("bundled model table missing from the jar: " + resource);
      }
      return parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + resource, e);
    }
  }

  /**
   * The bundled table for a floor, with an operator's table merged over it, model by model.
   *
   * <p>MERGED RATHER THAN REPLACED, for the same reason the translations are: an operator who wants
   * to describe a model Kramerius added after this release should write a file containing that one
   * model, not copy a table of twenty and then owe us a re-copy every release. A key present in
   * both takes the operator's definition whole — a model is a coherent unit and half-overriding one
   * would produce a shape nobody wrote.
   *
   * <p>`components` and `pageModel` replace when present, because they are single statements about
   * the table rather than collections of independent entries.
   *
   * <p>Validation happens after the merge, so an operator's table is checked against the merged
   * result: a model promoting to one only the bundled table defines is legal, and a typo is still
   * caught.
   */
  public static DocumentModels bundledMergedWith(String versionFloor, InputStream json) {
    JsonNode overrides;
    try {
      overrides = MAPPER.readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException("not a readable model table", e);
    }

    String resource = "/models/kramerius-" + versionFloor + ".json";
    ObjectNode merged;
    try (InputStream in = DocumentModels.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("bundled model table missing from the jar: " + resource);
      }
      merged = (ObjectNode) MAPPER.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + resource, e);
    }

    ObjectNode models = (ObjectNode) merged.path("models");
    overrides.path("models").fields().forEachRemaining(e -> models.set(e.getKey(), e.getValue()));
    if (overrides.has("components")) {
      merged.set("components", overrides.get("components"));
    }
    if (overrides.has("pageModel")) {
      merged.set("pageModel", overrides.get("pageModel"));
    }

    try {
      return parse(new java.io.ByteArrayInputStream(MAPPER.writeValueAsBytes(merged)));
    } catch (IOException e) {
      throw new UncheckedIOException("could not re-read the merged model table", e);
    }
  }

  /** Parses and validates a table. Public so a host can load one from a configured directory. */
  public static DocumentModels parse(InputStream json) {
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException("not a readable model table", e);
    }

    Map<String, DocumentModel> models = new LinkedHashMap<>();
    root.path("models")
        .fields()
        .forEachRemaining(
            entry -> models.put(entry.getKey(), read(entry.getKey(), entry.getValue())));

    if (models.isEmpty()) {
      throw new IllegalArgumentException("model table has no `models`");
    }

    // Cross-references are checked once everything is loaded, so order in the file does not matter.
    models
        .values()
        .forEach(
            model ->
                model
                    .promoteTo()
                    .ifPresent(
                        target -> {
                          if (!models.containsKey(target)) {
                            throw new IllegalArgumentException(
                                "model '%s' promotes to '%s', which is not in the table"
                                    .formatted(model.solrName(), target));
                          }
                        }));

    List<String> components = new ArrayList<>();
    root.path("components").forEach(node -> components.add(node.asText()));

    String pageModel = root.path("pageModel").asText("page");

    LOG.info("{} document models loaded, {} component models", models.size(), components.size());
    return new DocumentModels(Map.copyOf(models), List.copyOf(components), pageModel);
  }

  private static DocumentModel read(String solrName, JsonNode node) {
    String shapeName = node.path("shape").asText(null);
    if (shapeName == null || shapeName.isBlank()) {
      throw new IllegalArgumentException("model '%s' has no shape".formatted(solrName));
    }

    IiifStructure shape;
    try {
      shape = IiifStructure.valueOf(shapeName);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "model '%s' has shape '%s', which is not one of %s — shapes are code, not configuration"
              .formatted(solrName, shapeName, List.of(IiifStructure.values())),
          e);
    }

    Optional<String> children =
        childRelation(solrName, node.path("children").asText(null), "children");
    if (shape == IiifStructure.CHILD_COLLECTION && children.isEmpty()) {
      throw new IllegalArgumentException(
          "model '%s' has shape %s, which must name the `children` it collects"
              .formatted(solrName, shape));
    }

    Optional<String> promoteWhen =
        childRelation(solrName, node.path("promoteWhen").asText(null), "promoteWhen");

    return new DocumentModel(
        solrName,
        shape,
        children,
        node.path("fostersPages").asBoolean(false),
        node.path("hasAncestors").asBoolean(false),
        node.path("excludesOwnModel").asBoolean(false),
        Optional.ofNullable(node.path("promoteTo").asText(null)).filter(s -> !s.isBlank()),
        promoteWhen);
  }

  private static Optional<String> childRelation(String solrName, String name, String field) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    if (!CHILD_RELATIONS.contains(name)) {
      throw new IllegalArgumentException(
          "model '%s' names `%s: %s`, which is not one of %s"
              .formatted(solrName, field, name, CHILD_RELATIONS));
    }
    return Optional.of(name);
  }

  /** The model for a Solr `model` value, or empty when this gateway cannot serve it. */
  public Optional<DocumentModel> find(String solrName) {
    return solrName == null ? Optional.empty() : Optional.ofNullable(models.get(solrName));
  }

  /** Looked up by name, for a cross-reference the table already validated. */
  public DocumentModel require(String solrName) {
    return find(solrName)
        .orElseThrow(() -> new IllegalStateException("no such model in the table: " + solrName));
  }

  /** Models that are only ever parts of a document — page, track, sound unit. */
  public boolean isComponent(String solrName) {
    return components.contains(solrName);
  }

  /** The same list, for a query that needs to exclude them all at once. */
  public List<String> components() {
    return components;
  }

  public String pageModel() {
    return pageModel;
  }
}
