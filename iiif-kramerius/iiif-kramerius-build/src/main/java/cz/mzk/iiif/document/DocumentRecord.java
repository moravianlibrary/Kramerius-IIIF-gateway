/*
 * One object, as the library needs to see it.
 *
 * The split here is the point of the type, and it is not the obvious one. Fields fall into two groups:
 *
 *   STRUCTURE — things the library makes decisions from. Which shape to build, how to number canvases,
 *   whether to say `paged`, whether to link a parent, what to cache and when it went stale. These are
 *   typed, because the library reasons about them.
 *
 *   PRESENTATION — label, metadata, summary. The library never inspects these; it copies them into the
 *   output. So they arrive already mapped to IIIF, and the adapter decides what to say.
 *
 * The earlier version typed the presentation fields too — title, partNumber, authors, publishers,
 * date — which meant DocumentRecord was really a Kramerius Solr schema with the field names changed.
 * Every new metadata field would have needed a new component and a change to every adapter, and a
 * source with richer metadata than K7 could not express it at all.
 *
 * Adapters should build their rows through MetadataRows so the common ones stay consistent and
 * translated; nothing forces them to.
 */
package cz.mzk.iiif.document;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import cz.mzk.iiif.kramerius.ChildKind;
import cz.mzk.iiif.kramerius.DocumentShape;
import cz.mzk.iiif.presentation.Iiif;
import cz.mzk.iiif.presentation.LanguageMap;

public record DocumentRecord(
    String pid,

    /**
     * Raw Kramerius model name.
     *
     * <p>Kept only for identity comparisons — a convolute skipping a member of its own model.
     * Nothing here interprets it, because what a model *means* changes between Kramerius versions.
     */
    String model,

    /**
     * What to build from this document, resolved by the adapter.
     *
     * <p>The model-to-shape table used to live here. It moved to the adapters because Kramerius
     * adds and removes models over releases, so the mapping is version-specific knowledge; this
     * level is told the answer rather than looking it up.
     */
    DocumentShape shape,

    /**
     * Which children a MEMBERS document collects — volumes, issues, convolute parts.
     *
     * <p>Resolved by the adapter alongside the shape. The builder never needs it, because by then
     * the members are gathered; whoever assembles the bundle does.
     */
    Optional<ChildKind> childKind,

    /** Skip members of this same model — Kramerius indexes a convolute as its own child. */
    boolean excludesOwnModel,

    // Presentation: copied into the manifest, never inspected
    Map<String, List<String>> label,
    List<Iiif.KeyValue> metadata,
    Map<String, List<String>> summary,

    // Structure: the library decides things with these
    Optional<Parent> parent,

    /** Whether this object's content is a PDF file. Only the fact matters, not the mime string. */
    boolean hasPdf,

    /** count_page, where the index carries it. Drives cold-cache prediction and `paged`. */
    OptionalInt pageCount,

    /** page.placement — left, right or single. The only honest basis for `behavior: paged`. */
    Optional<String> pagePlacement,
    boolean hasTiles,

    /** Empty below 7.1.4, where the ALTO stream must be probed instead. */
    Optional<Boolean> hasAltoContent,

    /** Seconds. Required on a Sound canvas, and K5 does not index it. */
    OptionalDouble trackLength,
    Optional<BoundingBox> coordinates,

    /** License names the document carries. Resolved to text through LibraryProfile. */
    List<String> licenses,

    /**
     * Raw indexed system number, if any — the key to the library's own catalogue.
     *
     * <p>Structural rather than presentational: the library builds a link from it via
     * CatalogueLink, rather than printing it. The adapter passes it through untouched, prefix and
     * all, because the prefix is load-bearing and only the per-library mapping knows what to do
     * with it.
     */
    Optional<String> systemNumber,

    /** Fedora lastModifiedDate. Decides cache staleness; see docs/DESIGN.md. */
    Optional<Instant> modified,

    /**
     * When the document is *about*, for navDate — not when it changed.
     *
     * <p>Structural rather than presentational: a client navigates by it, so it is typed here and
     * formatted once by the builder. K7 indexes date.min as a full UTC dateTime; K5 indexes
     * datum_begin as a bare year, which becomes the first instant of that year. Both are the
     * beginning of the document's period, which is what a timeline wants.
     */
    Optional<Instant> navDate) {

  /**
   * The object's own parent, for partOf.
   *
   * <p>Carried on the document rather than fetched, because K7 indexes own_parent.pid,
   * own_parent.model and own_parent.title on the child itself — so a partOf block costs no extra
   * query. It carries the parent's resolved IIIF type for the same reason the document carries its
   * own shape: the adapter knows what the parent's model produces, and this level should not ask.
   */
  public record Parent(String pid, String iiifType, Map<String, List<String>> label) {
    public Parent {
      label = LanguageMap.immutable(label);
    }
  }

  public DocumentRecord {
    if (pid == null || pid.isBlank()) {
      throw new IllegalArgumentException("a document record needs a pid");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("a document record needs a model (pid " + pid + ")");
    }
    metadata = List.copyOf(metadata);
    licenses = List.copyOf(licenses);
    label = LanguageMap.immutable(label);
    summary = LanguageMap.immutable(summary);
  }

  /**
   * The same document, shaped differently, because the shape turned out to be wrong.
   *
   * <p>The shape comes from the model table, which names one child kind per model — and the
   * repository does not oblige. A periodical volume can hold pages instead of issues (84,559 such
   * pages at MZK), and a sheet-music item can hold another sheet-music item. The assembler
   * discovers this only by asking, and the builder types the output from the shape, so the
   * correction has to travel on the record or the manifest comes out as an empty Collection anyway.
   *
   * <p>The child kind goes with it: a document serving pages collects nothing else, and leaving a
   * stale PERIODICAL_ISSUES on it would be a claim about children it does not have.
   *
   * <p>Every other component is carried across unchanged. {@code DocumentRecordTest} asserts that
   * one by one, because same-typed neighbours in a nineteen-component record are exactly where a
   * hand-written copy goes wrong silently.
   */
  public DocumentRecord withShape(DocumentShape newShape, Optional<ChildKind> newChildKind) {
    return new DocumentRecord(
        pid,
        model,
        newShape,
        newChildKind,
        excludesOwnModel,
        label,
        metadata,
        summary,
        parent,
        hasPdf,
        pageCount,
        pagePlacement,
        hasTiles,
        hasAltoContent,
        trackLength,
        coordinates,
        licenses,
        systemNumber,
        modified,
        navDate);
  }

  public static Builder of(String pid, String model, DocumentShape shape) {
    return new Builder(pid, model, shape);
  }

  /** Collections are Collections; everything else is a Manifest. */
  public String iiifType() {
    return shape.isCollection() ? "Collection" : "Manifest";
  }

  public static final class Builder {
    private final String pid;
    private final String model;
    private final DocumentShape shape;
    private ChildKind childKind;
    private boolean excludesOwnModel;

    private Map<String, List<String>> label;
    private List<Iiif.KeyValue> metadata = List.of();
    private Map<String, List<String>> summary;
    private Parent parent;
    private boolean hasPdf;
    private OptionalInt pageCount = OptionalInt.empty();
    private Optional<String> pagePlacement = Optional.empty();
    private boolean hasTiles;
    private Optional<Boolean> hasAltoContent = Optional.empty();
    private OptionalDouble trackLength = OptionalDouble.empty();
    private BoundingBox coordinates;
    private List<String> licenses = List.of();
    private String systemNumber;
    private Instant modified;
    private Instant navDate;

    private Builder(String pid, String model, DocumentShape shape) {
      this.pid = pid;
      this.model = model;
      this.shape = shape;
    }

    public Builder childKind(ChildKind value) {
      this.childKind = value;
      return this;
    }

    public Builder excludesOwnModel(boolean value) {
      this.excludesOwnModel = value;
      return this;
    }

    public Builder label(Map<String, List<String>> value) {
      this.label = LanguageMap.immutable(value);
      return this;
    }

    public Builder metadata(List<Iiif.KeyValue> value) {
      this.metadata = value == null ? List.of() : value;
      return this;
    }

    public Builder summary(Map<String, List<String>> value) {
      this.summary = LanguageMap.immutable(value);
      return this;
    }

    /** Null iiifType means a parent this gateway cannot serve, so no partOf is emitted. */
    public Builder parent(
        String parentPid, String parentIiifType, Map<String, List<String>> label) {
      this.parent =
          parentPid == null || parentPid.isBlank() || parentIiifType == null
              ? null
              : new Parent(parentPid, parentIiifType, label);
      return this;
    }

    public Builder hasPdf(boolean value) {
      this.hasPdf = value;
      return this;
    }

    public Builder pageCount(int value) {
      this.pageCount = OptionalInt.of(value);
      return this;
    }

    public Builder pagePlacement(String value) {
      this.pagePlacement = value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
      return this;
    }

    public Builder hasTiles(boolean value) {
      this.hasTiles = value;
      return this;
    }

    public Builder hasAltoContent(Boolean value) {
      this.hasAltoContent = Optional.ofNullable(value);
      return this;
    }

    public Builder trackLength(double value) {
      this.trackLength = OptionalDouble.of(value);
      return this;
    }

    public Builder coordinates(BoundingBox value) {
      this.coordinates = value;
      return this;
    }

    public Builder licenses(List<String> values) {
      this.licenses = values == null ? List.of() : values;
      return this;
    }

    public Builder systemNumber(String value) {
      this.systemNumber = value;
      return this;
    }

    public Builder modified(Instant value) {
      this.modified = value;
      return this;
    }

    public Builder navDate(Instant value) {
      this.navDate = value;
      return this;
    }

    public DocumentRecord build() {
      return new DocumentRecord(
          pid,
          model,
          shape,
          Optional.ofNullable(childKind),
          excludesOwnModel,
          label,
          metadata,
          summary,
          Optional.ofNullable(parent),
          hasPdf,
          pageCount,
          pagePlacement,
          hasTiles,
          hasAltoContent,
          trackLength,
          Optional.ofNullable(coordinates),
          licenses,
          Optional.ofNullable(systemNumber),
          Optional.ofNullable(modified),
          Optional.ofNullable(navDate));
    }
  }
}
