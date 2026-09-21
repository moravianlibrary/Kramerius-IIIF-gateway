/*
 * Kramerius 5 Solr documents become records.
 *
 * A separate mapper rather than a subclass of the K7 one, because the differences are structural: a page
 * carries its number and type packed into one `##`-separated string, the uniqueKey is `PID`, and the
 * parent is multi-valued.
 *
 * The `##` convention is K5's throughout: `details` is "number##type", `browse_title` and `search_title`
 * are "sortform##displayform", and `browse_autor` is "sortform##displayform" too. The part BEFORE the
 * separator is a normalised sort form — accents stripped, uppercased — and showing it to a reader would
 * be wrong. So everything read here takes the part AFTER it.
 */
package cz.mzk.iiif.adapter.v50;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import cz.mzk.iiif.adapter.DocumentModel;
import cz.mzk.iiif.adapter.DocumentModels;
import cz.mzk.iiif.adapter.RecordMapper;
import cz.mzk.iiif.adapter.SolrRecords;
import cz.mzk.iiif.build.MetadataRows;
import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.i18n.Translations;
import cz.mzk.iiif.kramerius.DocumentShape;
import cz.mzk.iiif.presentation.Iiif;
import cz.mzk.iiif.presentation.LanguageMap;

public final class Kramerius5Records implements RecordMapper {

  /** K5 packs "sort form ## display form" into a single value. */
  private static final String PACKED = "##";

  private final DocumentModels models;
  private final Translations translations;

  /** Kramerius 5 publishes no licence config, so this is empty in practice — kept for symmetry. */
  private final java.util.Map<String, cz.mzk.iiif.adapter.LicenseConfig.License> licenses;

  public Kramerius5Records(
      DocumentModels models,
      Translations translations,
      java.util.Map<String, cz.mzk.iiif.adapter.LicenseConfig.License> licenses) {
    this.models = models;
    this.translations = translations;
    this.licenses = java.util.Map.copyOf(licenses);
  }

  @Override
  public String pidField() {
    return "PID";
  }

  @Override
  public Optional<DocumentRecord> document(JsonNode doc, boolean hasChildrenForPromotion) {
    String pid = text(doc, "PID").orElse(null);
    String model = text(doc, "fedora.model").orElse(null);
    if (pid == null || model == null) {
      return Optional.empty();
    }

    Optional<DocumentModel> found = models.find(model);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    DocumentModel resolved = found.get();
    if (resolved.promotes() && hasChildrenForPromotion) {
      resolved = models.find(resolved.promoteTo().orElseThrow()).orElse(resolved);
    }

    DocumentRecord.Builder record =
        DocumentRecord.of(pid, resolved.solrName(), resolved.shape())
            .excludesOwnModel(resolved.excludesOwnModel())
            .label(LanguageMap.none(title(doc)))
            .metadata(metadata(doc, resolved))
            .hasPdf(text(doc, "img_full_mime").filter("application/pdf"::equals).isPresent());

    resolved.children().ifPresent(record::childKind);
    integer(doc, "pages_count").ifPresent(record::pageCount);
    modified(doc).ifPresent(record::modified);
    navDate(doc).ifPresent(record::navDate);
    record.licenses(strings(doc, "dnnt-labels"));

    if (resolved.hasAncestors()) {
      parent(doc).ifPresent(p -> record.parent(p.pid(), p.iiifType(), p.label()));
    }
    return Optional.of(record.build());
  }

  @Override
  public DocumentRecord page(JsonNode doc) {
    String pid =
        text(doc, "PID").orElseThrow(() -> new IllegalArgumentException("page with no PID"));

    DocumentRecord.Builder record =
        DocumentRecord.of(pid, text(doc, "fedora.model").orElse("page"), DocumentShape.PAGES)
            // K5 has no has_tiles. `viewable` is the closest thing it indexes, and it is what
            // decides
            // whether an image can be shown at all.
            .hasTiles(bool(doc, "viewable").orElse(true));

    Optional.ofNullable(pageLabel(doc)).ifPresent(record::label);
    modified(doc).ifPresent(record::modified);
    // K5 indexes no page placement, so `behavior` is never asserted for a K5 document.
    return record.build();
  }

  @Override
  public DocumentRecord component(JsonNode doc) {
    String pid =
        text(doc, "PID").orElseThrow(() -> new IllegalArgumentException("component with no PID"));

    return DocumentRecord.of(
            pid, text(doc, "fedora.model").orElse("track"), DocumentShape.SOUND_RECORDING)
        .label(LanguageMap.none(title(doc)))
        .build();
  }

  /**
   * A page's label, unpacked from `details`.
   *
   * <p>K5 stores "4 \n ##NormalPage" — the number, whitespace, the separator, the type. Both halves
   * need trimming, and the number half often carries a non-breaking space.
   *
   * <p>The type is translated exactly as on K7, so a K5 reader gets "4 (přední obálka)" rather than
   * Kramerius' internal vocabulary. Untranslated types pass through under `none`, and `NormalPage`
   * is suppressed — also exactly as on K7, so the same document is labelled the same way whichever
   * version holds it.
   */
  private Map<String, List<String>> pageLabel(JsonNode doc) {
    Optional<String> details = text(doc, "details");
    if (details.isEmpty()) {
      return null;
    }
    String[] parts = details.get().split(PACKED, 2);
    String number = clean(parts[0]);
    String type = parts.length > 1 ? clean(parts[1]) : "";

    if (type.isEmpty() || SolrRecords.isDefaultPageType(type)) {
      return number.isEmpty() ? null : LanguageMap.none(number);
    }

    Map<String, List<String>> translated =
        translations.lookup("page.type." + type.toLowerCase(java.util.Locale.ROOT));

    if (translated == null) {
      return LanguageMap.none(number.isEmpty() ? type : number + " (" + type + ")");
    }

    // LinkedHashMap + LanguageMap.immutable, never Map.copyOf: copyOf does not preserve iteration
    // order, which makes the serialised bytes differ between runs — breaking the ETag — and lets a
    // viewer that picks the first key show a different language here than in the metadata rows.
    Map<String, List<String>> label = new java.util.LinkedHashMap<>();
    translated.forEach(
        (language, words) -> {
          String term = words.isEmpty() ? type : words.get(0);
          label.put(language, List.of(number.isEmpty() ? term : number + " (" + term + ")"));
        });
    return LanguageMap.immutable(label);
  }

  /** Non-breaking spaces and newlines are common in these values, so a plain trim is not enough. */
  private static String clean(String value) {
    return value.replace(' ', ' ').replaceAll("\\s+", " ").trim();
  }

  /** dc.title is the display form; browse_title and search_title are `##`-packed sort forms. */
  private static String title(JsonNode doc) {
    return text(doc, "dc.title")
        .or(() -> text(doc, "browse_title").map(Kramerius5Records::display))
        .or(() -> text(doc, "root_title"))
        .orElse(null);
  }

  /** The half after `##` — the sort form before it has accents stripped and is uppercased. */
  private static String display(String packed) {
    int at = packed.indexOf(PACKED);
    return at < 0 ? packed : packed.substring(at + PACKED.length());
  }

  private List<Iiif.KeyValue> metadata(JsonNode doc, DocumentModel model) {
    MetadataRows rows =
        MetadataRows.using(translations)
            .text("metadata.title", title(doc))
            .translated("metadata.documentType", model.labelKey())
            .texts(
                "metadata.author",
                strings(doc, "dc.creator").stream().map(Kramerius5Records::display).toList())
            // K5 indexes no publisher or place at all, so the publication statement degenerates to
            // a
            // bare date — which MetadataRows already labels "Vydáno" rather than pretending.
            .publication(List.of(), List.of(), text(doc, "datum_str").orElse(null))
            .codes("metadata.language", "language.", strings(doc, "language"));

    return rows.licenses(licenses(doc)).build();
  }

  /**
   * The parent, from a multi-valued field where a document lists itself.
   *
   * <p>K5's parent_pid contains the document's OWN pid for a top-level object, so the
   * self-reference has to be skipped or every monograph becomes part of itself.
   */
  private Optional<DocumentRecord.Parent> parent(JsonNode doc) {
    String self = text(doc, "PID").orElse("");
    Optional<String> parentPid =
        strings(doc, "parent_pid").stream().filter(pid -> !pid.equals(self)).findFirst();
    if (parentPid.isEmpty()) {
      return Optional.empty();
    }

    // model_path is "periodical/periodicalvolume/periodicalitem/page"; the parent's model is the
    // second-to-last segment. K5 indexes no own_parent.model.
    List<String> path = List.of(text(doc, "model_path").orElse("").split("/"));
    String parentModel = path.size() >= 2 ? path.get(path.size() - 2) : "";

    return models
        .find(parentModel)
        .map(
            found ->
                new DocumentRecord.Parent(
                    parentPid.get(),
                    found.iiifType(),
                    LanguageMap.none(text(doc, "root_title").orElse(null))));
  }

  /**
   * K5's licences, with the same accessibility fallback as K7.
   *
   * <p>The field is `dnnt-labels`, with a hyphen — `dnnt_labels` is rejected by K5's Solr. K5
   * indexes no equivalent of `licenses_of_ancestors` or `contains_licenses`, so there is nothing to
   * union.
   */
  private static List<String> licenses(JsonNode doc) {
    List<String> own = strings(doc, "dnnt-labels");
    if (!own.isEmpty()) {
      return own;
    }
    return text(doc, "dostupnost").map(List::of).orElseGet(List::of);
  }

  /**
   * The first instant of the document's year, for navDate.
   *
   * <p>K5 indexes datum_begin as a bare integer year, and 0 when it does not know — vugtk's "[s.
   * a.]" records carry exactly that. A year 0 is not a date anybody wants on a timeline, so it is
   * treated as absent rather than as the year zero.
   */
  private static Optional<Instant> navDate(JsonNode doc) {
    java.util.OptionalInt year = integer(doc, "datum_begin");
    // 0 is K5's "unknown" — vugtk's "[s. a.]" records carry exactly that — and a four-digit bound
    // keeps a misindexed value from throwing rather than being ignored.
    if (year.isEmpty() || year.getAsInt() < 1 || year.getAsInt() > 9999) {
      return Optional.empty();
    }
    return Optional.of(
        java.time.LocalDate.of(year.getAsInt(), 1, 1)
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant());
  }

  private static Optional<Instant> modified(JsonNode doc) {
    return text(doc, "modified_date")
        .flatMap(
            raw -> {
              try {
                return Optional.of(Instant.parse(raw));
              } catch (DateTimeParseException e) {
                return Optional.empty();
              }
            });
  }

  // Field access, tolerating scalar or array as K7's mapper does.

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

  /** The ordering index, for the client-side sort K5 forces. */
  static int order(JsonNode doc) {
    JsonNode node = doc.get("rels_ext_index");
    if (node == null || node.isNull()) {
      return Integer.MAX_VALUE;
    }
    return node.isArray()
        ? (node.isEmpty() ? Integer.MAX_VALUE : node.get(0).asInt())
        : node.asInt();
  }
}
