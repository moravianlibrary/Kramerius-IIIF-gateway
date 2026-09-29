/*
 * One Solr document becomes one DocumentRecord.
 *
 * This is the parse-don't-validate boundary, and the only place in the gateway that knows a Kramerius
 * model name. By the time a record leaves here its shape is resolved, its descriptive text is mapped to
 * IIIF, and nothing below level 4 needs the model table again.
 *
 * Every enriched field is Optional on purpose: core must never REQUIRE something only some instances
 * index. That constraint is what lets 7.0.x work without the 7.1.4 fields, and what will let K5 work at
 * all.
 *
 * The metadata row order is deliberate, not chosen by a viewer. Tests make that order an executable
 * contract.
 */
package cz.mzk.iiif.adapter;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import cz.mzk.iiif.build.MetadataRows;
import cz.mzk.iiif.document.BoundingBox;
import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.i18n.Translations;
import cz.mzk.iiif.kramerius.IiifStructure;
import cz.mzk.iiif.presentation.Iiif;
import cz.mzk.iiif.presentation.LanguageMap;

public final class SolrRecords implements RecordMapper {

  private final DocumentModels models;
  private final SolrSchema schema;
  private final Translations translations;

  @Override
  public String pidField() {
    return "pid";
  }

  /**
   * What this instance calls its own licences, empty when it does not publish them.
   *
   * <p>Per library rather than per adapter — a licence id means whatever the instance says it means
   * — which is why the mapper is built once per library.
   */
  private final Map<String, LicenseConfig.License> licenses;

  public SolrRecords(
      DocumentModels models,
      SolrSchema schema,
      Translations translations,
      Map<String, LicenseConfig.License> licenses) {
    this.models = models;
    this.schema = schema;
    this.translations = translations;
    this.licenses = Map.copyOf(licenses);
  }

  /**
   * Maps a document, or reports that its model has no manifest.
   *
   * <p>A page, a track and a sound unit all land in UnsupportedModel: they are real objects, but
   * parts of a document rather than documents. v1 answered 200 for them.
   */
  @Override
  public Optional<DocumentRecord> document(JsonNode doc, boolean hasChildrenForPromotion) {
    String pid = text(doc, "pid").orElse(null);
    String model = text(doc, "model").orElse(null);
    if (pid == null || model == null) {
      return Optional.empty();
    }

    Optional<DocumentModel> found = models.find(model);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    DocumentModel resolved = found.get();

    // A monograph that turns out to have volumes is a multi-volume monograph. The index says so
    // before
    // anything is fetched, so the promotion costs one count field rather than a second query.
    if (resolved.promotes() && hasChildrenForPromotion) {
      resolved = models.find(resolved.promoteTo().orElseThrow()).orElse(resolved);
    }

    DocumentRecord.Builder record =
        DocumentRecord.of(pid, resolved.solrName(), resolved.shape())
            .excludesOwnModel(resolved.excludesOwnModel())
            .label(LanguageMap.none(title(doc)))
            .metadata(metadata(doc, resolved))
            .requiredStatement(accessStatement(accessLicenses(doc)));

    text(doc, "collection.desc").map(LanguageMap::none).ifPresent(record::summary);
    text(doc, "id_sysno").ifPresent(record::systemNumber);
    integer(doc, "count_page").ifPresent(record::pageCount);
    bbox(doc).ifPresent(record::coordinates);
    modified(doc).ifPresent(record::modified);
    navDate(doc).ifPresent(record::navDate);
    record.licenses(licenses(doc));
    record.hasPdf(isPdf(doc));

    if (resolved.hasAncestors()) {
      parent(doc).ifPresent(p -> record.parent(p.pid(), p.iiifType(), p.label()));
    }
    return Optional.of(record.build());
  }

  /** A page or a track. Shape is inherited from whatever asked for it and never used. */
  @Override
  public DocumentRecord page(JsonNode doc) {
    String pid =
        text(doc, "pid").orElseThrow(() -> new IllegalArgumentException("page with no pid"));

    DocumentRecord.Builder record =
        DocumentRecord.of(pid, text(doc, "model").orElse("page"), IiifStructure.PAGED_MANIFEST)
            .hasTiles(bool(doc, "has_tiles").orElse(false));

    Optional.ofNullable(pageLabel(doc)).ifPresent(record::label);
    text(doc, "page.placement").ifPresent(record::pagePlacement);
    number(doc, "track.length").ifPresent(record::trackLength);
    modified(doc).ifPresent(record::modified);

    // Only present from 7.1.4; below that it stays empty and the ALTO link is decided by a probe.
    if (schema.hasAltoField()) {
      bool(doc, "has_alto_content").ifPresent(record::hasAltoContent);
    }
    return record.build();
  }

  /**
   * A sound unit or a track.
   *
   * <p>These are component models: they have no manifest of their own, so they are not in the
   * document table and document() correctly refuses them. But they still appear INSIDE a
   * recording's manifest — a unit becomes a Range and a track becomes a Canvas — so they need a
   * mapping that does not consult the table at all. Labelled by title.search, because a track has
   * no page number.
   */
  @Override
  public DocumentRecord component(JsonNode doc) {
    String pid =
        text(doc, "pid").orElseThrow(() -> new IllegalArgumentException("component with no pid"));

    DocumentRecord.Builder record =
        DocumentRecord.of(pid, text(doc, "model").orElse("track"), IiifStructure.MEDIA_MANIFEST)
            .label(LanguageMap.none(text(doc, "title.search").orElse(null)));

    number(doc, "track.length").ifPresent(record::trackLength);
    modified(doc).ifPresent(record::modified);
    return record.build();
  }

  /**
   * A page's label: its number, with its type in brackets.
   *
   * <p>The number is record content and the same in every language; the TYPE is a controlled
   * vocabulary we can translate, so the label comes out per language — "[1a] (přední obálka)" and
   * "[1a] (front cover)" — rather than showing Kramerius' internal `FrontCover` to a reader.
   *
   * <p>Lookup is case-insensitive because Kramerius spells the same concept both ways: `NormalPage`
   * covers 69 937 719 pages at MZK and `normalPage` another 491 725, and `Blank`/`blank`,
   * `FrontCover`/`frontCover` and a dozen others split the same way. A case-sensitive key would
   * silently leave a third of the long tail untranslated.
   *
   * <p>An untranslated type falls back to the raw value under `none`, which is honest: we are
   * passing through a term we do not have words for, and claiming it is Czech would be wrong.
   *
   * <p>A page with neither number nor type gets NO label rather than an empty one — v1 emitted
   * {"none": [null]}, which is not a valid language map.
   *
   * <p>`NormalPage` is SUPPRESSED, so a normal page reads "12" and not "12 (NormalPage)". It is the
   * default — about 90% of pages carry it, 69 937 719 at MZK — and it is Kramerius' internal
   * vocabulary telling a reader nothing the label did not already say. Every other type is kept,
   * because `FrontCover`, `TitlePage`, `Blank` and `Advertisement` genuinely distinguish a page.
   */
  private Map<String, List<String>> pageLabel(JsonNode doc) {
    Optional<String> number = text(doc, "page.number");
    Optional<String> type = text(doc, "page.type").filter(t -> !isDefaultPageType(t));

    if (type.isEmpty()) {
      return number.map(LanguageMap::none).orElse(null);
    }

    Map<String, List<String>> translated =
        translations.lookup("page.type." + type.get().toLowerCase(java.util.Locale.ROOT));

    if (translated == null) {
      // No words for this type: pass it through as record content rather than invent a language.
      String raw = number.map(n -> n + " (" + type.get() + ")").orElseGet(type::get);
      return LanguageMap.none(raw);
    }

    // LinkedHashMap + LanguageMap.immutable, never Map.copyOf: copyOf does not preserve iteration
    // order, which makes the serialised bytes differ between runs — breaking the ETag — and lets a
    // viewer that picks the first key show a different language here than in the metadata rows.
    Map<String, List<String>> label = new java.util.LinkedHashMap<>();
    translated.forEach(
        (language, words) -> {
          String term = words.isEmpty() ? type.get() : words.get(0);
          label.put(language, List.of(number.map(n -> n + " (" + term + ")").orElse(term)));
        });
    return LanguageMap.immutable(label);
  }

  /** title.search already has the subtitle glued in, verified in Kramerius. */
  private String title(JsonNode doc) {
    return text(doc, "title.search").or(() -> text(doc, "root.title")).orElse(null);
  }

  private List<Iiif.KeyValue> metadata(JsonNode doc, DocumentModel model) {
    MetadataRows rows =
        MetadataRows.using(translations)
            .text("metadata.title", title(doc))
            .translated("metadata.documentType", model.labelKey())
            .texts("metadata.author", strings(doc, "authors"))
            .text("metadata.partNumber", text(doc, "part.number.str").orElse(null))
            .text("metadata.partName", text(doc, "part.name").orElse(null))
            .publication(
                places(doc), strings(doc, "publishers.facet"), text(doc, "date.str").orElse(null))
            .codes("metadata.language", "language.", strings(doc, "languages.facet"));

    return rows.licenses(licenses(doc), licenseValues(licenses(doc))).build();
  }

  /**
   * Licence ids rendered as the instance names them, with the licence text under the name.
   *
   * <p>`dnnto` is an internal id; the instance calls it "Díla nedostupná na trhu - online". Where a
   * text is published too, name and text are joined with a newline so the value reads as a heading
   * followed by the notice.
   *
   * <p>Null when the instance publishes no licence config, which leaves the ids to be rendered by
   * the bundled wording instead.
   */
  private Map<String, List<String>> licenseValues(List<String> ids) {
    if (ids.isEmpty() || licenses.isEmpty()) {
      return null;
    }
    // Easiest route first: open, then login, then terminal. A document commonly carries several
    // licences — 153 008 MZK monographs are both dnnto and onsite — and they are alternative ways
    // in
    // rather than competing claims, so the one a reader is most likely to be able to use leads.
    List<String> ordered =
        ids.stream()
            .sorted(
                java.util.Comparator.comparingInt(
                    id -> licenses.containsKey(id) ? licenses.get(id).rank() : 3))
            .toList();

    Map<String, java.util.List<String>> byLanguage = new java.util.LinkedHashMap<>();
    for (String id : ordered) {
      LicenseConfig.License license = licenses.get(id);
      if (license == null) {
        continue;
      }
      // Every language the instance says anything in, whether that is a notice or only a name.
      java.util.Set<String> languages = new java.util.LinkedHashSet<>(license.text().keySet());
      languages.addAll(license.label().keySet());

      for (String language : languages) {
        List<String> text = license.text().get(language);
        List<String> name = license.label().get(language);

        // The notice as the library wrote it, and nothing else. Its own opening line already says
        // which licence it is, so prefixing our name printed that twice. A name appears only where
        // there is no notice — cdk-api publishes labels whose message pages 404.
        String value =
            text != null && !text.isEmpty()
                ? paragraphs(text.get(0))
                : (name == null || name.isEmpty() ? null : name.get(0));

        if (value != null) {
          byLanguage.computeIfAbsent(language, l -> new java.util.ArrayList<>()).add(value);
        }
      }
    }
    if (byLanguage.isEmpty()) {
      return null;
    }
    Map<String, List<String>> result = new java.util.LinkedHashMap<>();
    byLanguage.forEach((language, values) -> result.put(language, List.copyOf(values)));
    return LanguageMap.immutable(result);
  }

  /**
   * The short access condition viewers must expose when a Kramerius 7 licence configuration is
   * available. The licence label is the library's own wording; accessType adds the concise action a
   * reader has to take. The longer unauthenticated notice belongs in metadata, where it remains
   * available without turning a viewer's required-statement banner into a legal notice.
   */
  private Iiif.KeyValue accessStatement(List<String> ids) {
    if (ids.isEmpty() || licenses.isEmpty()) {
      return null;
    }
    Map<String, List<String>> label = translations.lookup("access.conditions");
    if (label == null) {
      return null;
    }

    List<String> ordered =
        ids.stream()
            .filter(licenses::containsKey)
            .sorted(java.util.Comparator.comparingInt(id -> licenses.get(id).rank()))
            .toList();
    Map<String, List<String>> values = new java.util.LinkedHashMap<>();
    for (String id : ordered) {
      LicenseConfig.License license = licenses.get(id);
      Map<String, List<String>> access =
          translations.lookup("access." + license.accessType().orElse("restricted"));
      license
          .label()
          .forEach(
              (language, names) -> {
                if (names.isEmpty()) {
                  return;
                }
                String name = names.get(0);
                String condition =
                    access == null
                        ? null
                        : access.getOrDefault(language, List.of()).stream()
                            .findFirst()
                            .orElse(null);
                values
                    .computeIfAbsent(language, ignored -> new java.util.ArrayList<>())
                    .add(condition == null ? name : name + " — " + condition);
              });
    }
    return values.isEmpty() ? null : new Iiif.KeyValue(label, LanguageMap.immutable(values));
  }

  /**
   * Escapes text being placed inside an HTML metadata value.
   *
   * <p>These notices are written by librarians and already contain quotes and ampersands; unescaped
   * they would make the fragment malformed, and the spec requires well-formed XML.
   */
  /**
   * A multi-paragraph notice as an HTML metadata value.
   *
   * <p>IIIF permits HTML in a metadata VALUE — never in a label — restricted to a, b, br, i, img,
   * p, small, span, sub and sup, with the string beginning '<' and ending '>'. Without it these
   * notices arrive as one wall of text: a newline in a plain string is collapsed by every viewer.
   *
   * <p>Escaped, because the notices are written by librarians and already contain ampersands and
   * quotes, and the spec requires well-formed XML.
   */
  private static String paragraphs(String text) {
    StringBuilder html = new StringBuilder();
    for (String line : text.split("\n")) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty()) {
        html.append("<p>").append(escape(trimmed)).append("</p>");
      }
    }
    return html.isEmpty() ? null : html.toString();
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /**
   * Publication places, with the MARC country codes stripped.
   *
   * <p>publication_places.facet mixes both: a Prague monograph is indexed ["Xr", "Praha"], and "Xr"
   * is the MARC code for Czechia rather than anywhere anyone has been. Measured over all 414 323
   * values at MZK, 109 658 are codes — "Xr" alone is 102 984, the second most common value in the
   * field — so taking the first element would print a country code as the place of publication on
   * roughly a quarter of documents. v1 read MODS and did not have this problem.
   *
   * <p>MATCHED AGAINST THE ACTUAL CODE LIST, not against a shape. The first rule here was {@code
   * [A-Z][a-z]{1,2}}, which is exact on the common values and wrong in the tail: measured over all
   * 6 394 distinct values at MZK it also ate sixteen real places — Ulm, Dux, Rom, Vie, Zug, Ams,
   * Brn and Augsburg's "Aug" among them. A place is not a code because it is short.
   *
   * <p>The list is generated by dev/country-codes.py: 334 current codes from id.loc.gov plus the
   * deprecated ones the libraries still hold (Xxr, Yu, Cs, Uk, Us), which the published vocabulary
   * does not enumerate and which are still codes rather than places. The fallback survives for the
   * case the list cannot help with — a document whose ONLY value is a code still has to say
   * something.
   */
  private static List<String> places(JsonNode doc) {
    List<String> all = strings(doc, "publication_places.facet");
    List<String> named = all.stream().filter(place -> !isCountryCode(place)).toList();
    return named.isEmpty() ? all : named;
  }

  /**
   * The page type that says nothing: `NormalPage`, and the `normalPage` Kramerius also spells it.
   *
   * <p>Shared with the K5 mapper, which unpacks the same vocabulary out of a different field. See
   * pageLabel for why it is dropped rather than translated.
   */
  public static boolean isDefaultPageType(String type) {
    return type.equalsIgnoreCase("normalpage");
  }

  private static boolean isCountryCode(String value) {
    // "S.l" is sine loco — a bibliographic marker for "no place", not a place either.
    if (value.equalsIgnoreCase("s.l") || value.equalsIgnoreCase("s.l.")) {
      return true;
    }
    return MarcCountryCodes.contains(value);
  }

  /**
   * Every licence that says something about this document.
   *
   * <p>Three fields, because Kramerius splits the question by direction: `licenses` is the
   * document's own, `licenses_of_ancestors` is what it inherits — a licence on a volume applies to
   * its issues — and `contains_licenses` is what its children carry. The last one matters more than
   * it sounds: a periodical has no licence of its own, so without it a periodical manifest would
   * say nothing about rights while every issue inside it is restricted.
   *
   * <p>Falling back to `accessibility` covers the 1.9% of MZK documents that carry no licence at
   * all. It is a fallback rather than a row of its own because the two are the same statement in
   * two vocabularies — and `accessibility` is the stale one, no longer what decides access in 7.x.
   */
  private static List<String> licenses(JsonNode doc) {
    LinkedHashSet<String> all = new LinkedHashSet<>(strings(doc, "licenses"));
    all.addAll(strings(doc, "licenses_of_ancestors"));
    all.addAll(strings(doc, "contains_licenses"));

    if (all.isEmpty()) {
      text(doc, "accessibility").ifPresent(all::add);
    }
    return List.copyOf(all);
  }

  /**
   * Licences that restrict this document itself, rather than one of its children.
   *
   * <p>`contains_licenses` belongs in metadata: a periodical with one terminal-only issue should
   * disclose that fact without presenting the whole periodical as terminal-only. A required
   * statement is a condition viewers must display for the object being viewed, so it only draws
   * from the object's own and inherited licences.
   */
  private static List<String> accessLicenses(JsonNode doc) {
    LinkedHashSet<String> all = new LinkedHashSet<>(strings(doc, "licenses"));
    all.addAll(strings(doc, "licenses_of_ancestors"));
    if (all.isEmpty()) {
      text(doc, "accessibility").ifPresent(all::add);
    }
    return List.copyOf(all);
  }

  private boolean isPdf(JsonNode doc) {
    return text(doc, "ds.img_full.mime").filter("application/pdf"::equals).isPresent();
  }

  private Optional<DocumentRecord.Parent> parent(JsonNode doc) {
    Optional<String> pid = text(doc, "own_parent.pid");
    Optional<String> model = text(doc, "own_parent.model");
    if (pid.isEmpty() || model.isEmpty()) {
      return Optional.empty();
    }
    // A parent whose model this gateway cannot serve is omitted rather than linked to a 404. v1
    // pushed an empty object into the array instead.
    return models
        .find(model.get())
        .map(
            parentModel ->
                new DocumentRecord.Parent(
                    pid.get(),
                    parentModel.iiifType(),
                    LanguageMap.none(text(doc, "own_parent.title").orElse(null))));
  }

  /**
   * The bounding box, from the two corners every K7 version indexes.
   *
   * <p>Stored as "lat,lon" — latitude first, which is the opposite of GeoJSON's order. Getting this
   * backwards puts Brno in the Indian Ocean, and v1's navPlace was wrong in exactly this way.
   */
  private Optional<BoundingBox> bbox(JsonNode doc) {
    Optional<double[]> sw = point(doc, "coords.bbox.corner_sw");
    Optional<double[]> ne = point(doc, "coords.bbox.corner_ne");
    if (sw.isEmpty() || ne.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new BoundingBox(sw.get()[1], ne.get()[1], sw.get()[0], ne.get()[0]));
  }

  private Optional<double[]> point(JsonNode doc, String field) {
    return text(doc, field)
        .flatMap(
            raw -> {
              String[] parts = raw.split(",");
              if (parts.length != 2) {
                return Optional.empty();
              }
              try {
                return Optional.of(
                    new double[] {
                      Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())
                    });
              } catch (NumberFormatException e) {
                return Optional.empty();
              }
            });
  }

  /**
   * The first instant of the document's period, for navDate.
   *
   * <p>date.min is indexed as a full UTC dateTime, carrying milliseconds (2002-01-01T00:00:00.001Z
   * — the .001 is Kramerius avoiding a zero). Passed through as it is: navDate's second precision
   * is a presentation decision the builder makes, so no adapter can get it wrong on its own.
   */
  static Optional<Instant> navDate(JsonNode doc) {
    return instant(doc, "date.min");
  }

  private Optional<Instant> modified(JsonNode doc) {
    return instant(doc, "modified");
  }

  static Optional<Instant> instant(JsonNode doc, String field) {
    return text(doc, field)
        .flatMap(
            raw -> {
              try {
                return Optional.of(Instant.parse(raw));
              } catch (DateTimeParseException e) {
                return Optional.empty();
              }
            });
  }

  // Field access. Solr returns a scalar or an array for the same field depending on multiValued, so
  // every read has to tolerate both.

  static Optional<String> text(JsonNode doc, String field) {
    JsonNode node = doc.get(field);
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    String value = node.isArray() ? (node.isEmpty() ? null : node.get(0).asText()) : node.asText();
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
  }

  static List<String> strings(JsonNode doc, String field) {
    JsonNode node = doc.get(field);
    if (node == null || node.isNull()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    if (node.isArray()) {
      node.forEach(item -> add(values, item.asText()));
    } else {
      add(values, node.asText());
    }
    return List.copyOf(values);
  }

  private static void add(List<String> values, String value) {
    if (value != null && !value.isBlank()) {
      values.add(value);
    }
  }

  static Optional<Boolean> bool(JsonNode doc, String field) {
    JsonNode node = doc.get(field);
    return node == null || node.isNull() ? Optional.empty() : Optional.of(node.asBoolean());
  }

  static java.util.OptionalInt integer(JsonNode doc, String field) {
    JsonNode node = doc.get(field);
    return node == null || node.isNull()
        ? java.util.OptionalInt.empty()
        : java.util.OptionalInt.of(node.asInt());
  }

  static java.util.OptionalDouble number(JsonNode doc, String field) {
    JsonNode node = doc.get(field);
    return node == null || node.isNull()
        ? java.util.OptionalDouble.empty()
        : java.util.OptionalDouble.of(node.asDouble());
  }
}
