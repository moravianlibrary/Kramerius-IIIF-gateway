/*
 * The IIIF Presentation 3.0 vocabulary, as far as this gateway emits it.
 *
 * Records rather than a modelling library: the output is a fixed, small subset of the spec, and Jackson
 * serialises record components in declaration order, so the component order below *is* the JSON key
 * order. Null components are omitted, which is how optional blocks disappear.
 *
 * Two constraints from the schema shape these types, and both are easy to get wrong:
 *
 *   - A Manifest requires id, type, label AND items. There is no minItems, so an empty array is legal,
 *     but omitting the key is not — which is why Document.items is never null for a Manifest. A
 *     Collection does not require items. Canvas, Range and AnnotationPage require items too.
 *   - A Manifest is additionalProperties:false. There is no room for private extensions in the body;
 *     anything not in this file would fail validation.
 *
 * See docs/BEHAVIOUR.md for which blocks each document shape populates.
 */
package cz.mzk.iiif.presentation;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Iiif {

  /** The spec spells the context URI with http, not https. */
  public static final String PRESENTATION_CONTEXT =
      "http://iiif.io/api/presentation/3/context.json";

  public static final String NAVPLACE_CONTEXT =
      "http://iiif.io/api/extension/navplace/context.json";

  /** The ALTO namespace. The Ruby used the XSD location, which is not what a profile is for. */
  public static final String ALTO_PROFILE = "http://www.loc.gov/standards/alto/ns-v3#";

  /** The MODS 3 namespace, which is what a MODS record declares itself as. */
  public static final String MODS_PROFILE = "http://www.loc.gov/mods/v3";

  public static final String IMAGE_2_PROFILE = "http://iiif.io/api/image/2/level2.json";

  private Iiif() {}

  /**
   * A Manifest or a Collection.
   *
   * <p>One record serves both: they differ only in `type` and in which blocks are populated.
   * `context` is Object because a document carrying navPlace needs an array of two URIs where
   * everything else needs a single string.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Document(
      @JsonProperty("@context") Object context,
      String id,
      String type,
      Map<String, List<String>> label,
      KeyValue requiredStatement,
      List<KeyValue> metadata,
      Map<String, List<String>> summary,
      List<Agent> provider,
      List<TextLink> homepage,
      List<Resource> thumbnail,
      List<String> behavior,

      /**
       * A date a client may navigate by — a timeline, a map with a time slider.
       *
       * <p>An xsd:dateTime in UTC, second precision: Kramerius' date.min carries milliseconds
       * (2002-01-01T00:00:00.001Z) and every published example truncates them.
       */
      String navDate,
      NavPlace navPlace,
      List<SeeAlso> seeAlso,
      List<TextLink> rendering,
      List<Object> items,
      List<Range> structures,
      List<Reference> partOf) {}

  /** A metadata row, and also the shape of requiredStatement. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record KeyValue(Map<String, List<String>> label, Map<String, List<String>> value) {}

  /** The providing institution, as the registry describes it. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Agent(
      String id,
      String type,
      Map<String, List<String>> label,
      List<TextLink> homepage,
      List<Resource> logo) {}

  /** A typed link: a catalogue record, a digital library landing page, a PDF rendering. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TextLink(String id, String type, Map<String, List<String>> label, String format) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Resource(String id, String type, String format) {}

  /** A member of a collection, or an ancestor in partOf. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Reference(String id, String type, Map<String, List<String>> label) {}

  /**
   * A page or a track. `items` is required by the schema even though it is always populated here.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Canvas(
      String id,
      String type,
      Map<String, List<String>> label,
      Integer width,
      Integer height,
      Double duration,
      List<Resource> thumbnail,
      List<SeeAlso> seeAlso,
      List<AnnotationPage> items) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AnnotationPage(String id, String type, List<Annotation> items) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Annotation(String id, String type, String motivation, Body body, String target) {}

  /** What is painted onto a canvas: an image for a page, a sound file for a track. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Body(
      String id,
      String type,
      String format,
      Integer width,
      Integer height,
      Double duration,
      List<Object> service) {}

  /** Image API 2.x service, which uses the @-prefixed keys. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ImageService2(
      @JsonProperty("@id") String id, @JsonProperty("@type") String type, String profile) {

    public static ImageService2 of(String imageUrl) {
      return new ImageService2(imageUrl, "ImageService2", IMAGE_2_PROFILE);
    }
  }

  /** Image API 3.0 service. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ImageService3(String id, String type, String profile) {

    public static ImageService3 of(String imageUrl) {
      return new ImageService3(imageUrl, "ImageService3", "level2");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SeeAlso(
      String id, String type, String profile, Map<String, List<String>> label, String format) {

    /**
     * The ALTO OCR for a page. Typed Dataset — "Alto", which the Ruby emitted, is not an IIIF type.
     */
    public static SeeAlso alto(String url) {
      return new SeeAlso(url, "Dataset", ALTO_PROFILE, LanguageMap.none("ALTO XML"), "text/xml");
    }

    /**
     * The document's MODS record, on the manifest.
     *
     * <p>Every v3 peer surveyed publishes a manifest-level seeAlso — Wellcome, Yale and the
     * Internet Archive — and MODS is what a machine reading a Kramerius manifest would want: the
     * full descriptive record the index only summarises. A link, not a parse: the K7 adapters still
     * read everything from Solr and never fetch this.
     */
    public static SeeAlso mods(String url, Map<String, List<String>> label) {
      return new SeeAlso(url, "Dataset", MODS_PROFILE, label, "application/xml");
    }
  }

  /** A structural division. Used to group the tracks of a sound recording by sound unit. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Range(
      String id, String type, Map<String, List<String>> label, List<Reference> items) {}

  // navPlace extension

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record NavPlace(String id, String type, List<Feature> features) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Feature(String id, String type, FeatureProperties properties, Geometry geometry) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FeatureProperties(Map<String, List<String>> label) {}

  /**
   * GeoJSON geometry.
   *
   * <p>`coordinates` is Object because a Point nests one level ([lon, lat]) and a Polygon three
   * ([[[lon, lat], …]]).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Geometry(String type, Object coordinates) {}
}
