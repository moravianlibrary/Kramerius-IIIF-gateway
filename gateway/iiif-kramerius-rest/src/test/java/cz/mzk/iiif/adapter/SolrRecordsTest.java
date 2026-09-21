/*
 * Mapping real Solr responses, captured live from api.kramerius.mzk.cz.
 *
 * Fixtures rather than invented JSON, because the things that go wrong here are things nobody would
 * think to invent: publication_places.facet holding a MARC country code alongside the place name, a
 * field arriving as a scalar in one document and an array in another, id_sysno simply absent on most
 * documents. Hand-written fixtures agree with whatever the mapper does, which is why they find nothing.
 *
 * Parameterised over both adapters where the answer should not depend on the floor — which is what stops
 * Kramerius714 silently breaking a path it inherits.
 */
package cz.mzk.iiif.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import cz.mzk.iiif.adapter.v70.Kramerius70;
import cz.mzk.iiif.adapter.v714.Kramerius714;
import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.i18n.Translations;
import cz.mzk.iiif.kramerius.DocumentShape;
import cz.mzk.iiif.presentation.Iiif;

class SolrRecordsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The captured monograph: Ivan Kraus, "Snídaně v poledne", 202 pages, private, onsite licence.
   */
  private static final String MONOGRAPH = "uuid_71d66fe0-d986-11e2-9923-005056827e52";

  /** The captured manuscript, 493 pages, public. */
  private static final String MANUSCRIPT = "uuid_8aff1e7b-4a68-47f5-a98d-6ed9a2faf28a";

  static Stream<KrameriusAdapter> adapters() {
    return Stream.of(new Kramerius70(), new Kramerius714());
  }

  private static SolrRecords mapper(KrameriusAdapter adapter) {
    return new SolrRecords(
        adapter.models(), adapter.schema(), Translations.bundled(), java.util.Map.of());
  }

  private static JsonNode fixture(String name) {
    String path = "/solr/doc_" + name + ".json";
    try (InputStream in = SolrRecordsTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("missing fixture " + path);
      }
      return MAPPER.readTree(in).path("response").path("docs").get(0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static DocumentRecord map(KrameriusAdapter adapter, String fixture) {
    return mapper(adapter).document(fixture(fixture), false).orElseThrow();
  }

  private static String row(DocumentRecord record, String labelCs) {
    return record.metadata().stream()
        .filter(r -> r.label().getOrDefault("cs", List.of()).contains(labelCs))
        .findFirst()
        .map(Iiif.KeyValue::value)
        .map(v -> String.join(" | ", v.values().iterator().next()))
        .orElse(null);
  }

  @Nested
  @DisplayName("a real monograph")
  class Monograph {

    @ParameterizedTest
    @MethodSource("cz.mzk.iiif.adapter.SolrRecordsTest#adapters")
    @DisplayName("resolves to a PAGES manifest with the indexed title and page count")
    void basics(KrameriusAdapter adapter) {
      DocumentRecord record = map(adapter, MONOGRAPH);

      assertThat(record.shape()).isEqualTo(DocumentShape.PAGES);
      assertThat(record.iiifType()).isEqualTo("Manifest");
      assertThat(record.label().get("none")).containsExactly("Snídaně v poledne");
      assertThat(record.pageCount()).hasValue(202);
      assertThat(record.hasPdf()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("cz.mzk.iiif.adapter.SolrRecordsTest#adapters")
    @DisplayName("the publication statement names Praha, not the MARC country code Xr")
    void publicationPlace(KrameriusAdapter adapter) {
      // The document is indexed publication_places.facet = ["Xr", "Praha"]. Taking the first
      // element
      // would print "Xr: Garamond, 2002" — a country code where a city belongs. v1 read MODS and
      // got
      // this right; reading the index only gets it right because the codes are filtered.
      assertThat(row(map(adapter, MONOGRAPH), "Nakladatelské údaje"))
          .isEqualTo("Praha: Garamond, 2002");
    }

    @ParameterizedTest
    @MethodSource("cz.mzk.iiif.adapter.SolrRecordsTest#adapters")
    @DisplayName("carries the licence v1 never saw, and no separate availability row")
    void rights(KrameriusAdapter adapter) {
      DocumentRecord record = map(adapter, MONOGRAPH);

      assertThat(record.licenses()).containsExactly("onsite");
      // One statement, not two: `accessibility` says the same thing in a vocabulary Kramerius
      // stopped
      // acting on during 7.x, so it is a fallback for documents with no licence rather than a row.
      assertThat(row(record, "Licence")).isNotNull();
      assertThat(row(record, "Dostupnost")).isNull();
    }

    @ParameterizedTest
    @MethodSource("cz.mzk.iiif.adapter.SolrRecordsTest#adapters")
    @DisplayName("an absent field is absent, never an empty or null-valued row")
    void absentFieldsAreOmitted(KrameriusAdapter adapter) {
      // This document has no id_sysno, no coordinates and no parent. v1's answer to a missing value
      // was {"none": [null]}, which is not a valid language map.
      DocumentRecord record = map(adapter, MONOGRAPH);

      assertThat(record.systemNumber()).isEmpty();
      assertThat(record.coordinates()).isEmpty();
      assertThat(record.parent()).isEmpty();
      assertThat(record.metadata())
          .allSatisfy(
              r ->
                  assertThat(r.value().values())
                      .allSatisfy(v -> assertThat(v).isNotEmpty().doesNotContainNull()));
    }

    @Test
    @DisplayName("metadata rows come out in the order docs/BEHAVIOUR.md specifies")
    void rowOrder() {
      List<String> labels =
          map(new Kramerius714(), MONOGRAPH).metadata().stream()
              .map(r -> r.label().get("cs").get(0))
              .toList();

      assertThat(labels)
          .containsExactly(
              "Název", "Typ dokumentu", "Autor", "Nakladatelské údaje", "Jazyk", "Licence");
    }

    @Test
    @DisplayName("the same rows v1 emitted still say the same thing")
    void agreesWithV1WhereItWasRight() {
      // v1's manifest for this pid is in the corpus. It had five rows; these four are the ones both
      // versions produce, and they must not have drifted. The two extra rows (availability,
      // licence)
      // are deliberate additions, not disagreements.
      DocumentRecord record = map(new Kramerius714(), MONOGRAPH);

      assertThat(row(record, "Název")).isEqualTo("Snídaně v poledne");
      assertThat(row(record, "Autor")).isEqualTo("Kraus, Ivan, 1939-");
      assertThat(row(record, "Nakladatelské údaje")).isEqualTo("Praha: Garamond, 2002");
      assertThat(row(record, "Jazyk")).isEqualTo("čeština");
    }
  }

  @Nested
  @DisplayName("a real manuscript")
  class Manuscript {

    @ParameterizedTest
    @MethodSource("cz.mzk.iiif.adapter.SolrRecordsTest#adapters")
    @DisplayName("maps on both floors without needing a 7.1.4 field")
    void mapsOnBothFloors(KrameriusAdapter adapter) {
      DocumentRecord record = map(adapter, MANUSCRIPT);

      assertThat(record.shape()).isEqualTo(DocumentShape.PAGES);
      assertThat(record.pageCount()).hasValue(493);
      assertThat(record.label().get("none")).isNotEmpty();
    }
  }

  @Nested
  @DisplayName("page labels")
  class PageLabels {

    private static JsonNode page(String number, String type) {
      var doc = MAPPER.createObjectNode().put("pid", "uuid:p").put("model", "page");
      if (number != null) {
        doc.put("page.number", number);
      }
      if (type != null) {
        doc.put("page.type", type);
      }
      return doc;
    }

    @Test
    @DisplayName("the type is translated, so a reader does not see Kramerius' vocabulary")
    void translated() {
      var label = mapper(new Kramerius714()).page(page("[1a]", "FrontCover")).label();

      assertThat(label.get("cs")).containsExactly("[1a] (přední desky)");
      assertThat(label.get("en")).containsExactly("[1a] (front cover)");
    }

    @Test
    @DisplayName("lookup is case-insensitive, because Kramerius spells it both ways")
    void caseInsensitive() {
      // Blank/blank, FrontCover/frontCover and a dozen others split the same way.
      var upper = mapper(new Kramerius714()).page(page("7", "FrontCover")).label();
      var lower = mapper(new Kramerius714()).page(page("7", "frontCover")).label();

      assertThat(upper).isEqualTo(lower);
      assertThat(upper.get("en")).containsExactly("7 (front cover)");
    }

    @Test
    @DisplayName("NormalPage is suppressed — it is the default and says nothing")
    void normalPageIsSuppressed() {
      // 69 937 719 pages at MZK carry NormalPage and another 491 725 carry normalPage: about 90% of
      // every page label in the country would otherwise read "12 (NormalPage)".
      for (String spelling : List.of("NormalPage", "normalPage")) {
        var label = mapper(new Kramerius714()).page(page("12", spelling)).label();

        assertThat(label).containsOnlyKeys("none");
        assertThat(label.get("none")).containsExactly("12");
      }
    }

    @Test
    @DisplayName("a NormalPage with no number gets no label at all, not an empty one")
    void normalPageWithoutNumber() {
      // Suppressing the only thing there was to say leaves nothing to say. {"none": [null]} is not
      // a valid language map, and "" is not a label.
      assertThat(mapper(new Kramerius714()).page(page(null, "NormalPage")).label()).isNull();
    }

    @Test
    @DisplayName("a type we have no words for passes through, untagged")
    void untranslatedFallsBack() {
      // Claiming an unknown Kramerius term is Czech would be the "cz" bug in a new costume.
      var label = mapper(new Kramerius714()).page(page("12", "CustomInclude")).label();

      assertThat(label).containsOnlyKeys("none");
      assertThat(label.get("none")).containsExactly("12 (CustomInclude)");
    }

    @Test
    @DisplayName("a page with no type keeps its bare number")
    void numberOnly() {
      var label = mapper(new Kramerius714()).page(page("42", null)).label();

      assertThat(label).containsOnlyKeys("none");
      assertThat(label.get("none")).containsExactly("42");
    }

    @Test
    @DisplayName("a page with a type but no number is labelled by the type alone")
    void typeOnly() {
      var label = mapper(new Kramerius714()).page(page(null, "Blank")).label();

      assertThat(label.get("cs")).containsExactly("prázdná strana");
    }

    @Test
    @DisplayName("languages come out in the same order as every other label")
    void languageOrderIsStable() {
      // Map.copyOf does NOT preserve insertion order. Using it here made page labels serialise
      // ["en","cs"] while metadata serialised ["cs","en"], so a viewer that picks the first key
      // showed
      // English page labels beside Czech metadata — and, worse, made the bytes differ between runs,
      // which breaks the ETag. LanguageMap.immutable keeps the order.
      var page = mapper(new Kramerius714()).page(page("7", "FrontCover")).label();
      var document = map(new Kramerius714(), MONOGRAPH);

      assertThat(page.keySet()).containsExactly("cs", "en");
      assertThat(document.metadata().get(0).label().keySet()).containsExactly("cs", "en");
    }

    @Test
    @DisplayName("the same input always serialises to the same bytes")
    void deterministic() {
      // What the ETag rests on.
      var first = mapper(new Kramerius714()).page(page("7", "NormalPage")).label();
      var second = mapper(new Kramerius714()).page(page("7", "NormalPage")).label();

      assertThat(first.toString()).isEqualTo(second.toString());
    }

    @Test
    @DisplayName("a page with neither gets no label, not an empty one")
    void neither() {
      assertThat(mapper(new Kramerius714()).page(page(null, null)).label()).isNull();
    }
  }

  @Nested
  @DisplayName("field access")
  class Fields {

    @Test
    @DisplayName("a field is read whether Solr returned a scalar or an array")
    void scalarOrArray() {
      // multiValued is a schema property, so the same logical field arrives both ways across
      // instances. Assuming either shape breaks on the other.
      JsonNode scalar = MAPPER.createObjectNode().put("f", "one");
      JsonNode array = MAPPER.createObjectNode().set("f", MAPPER.createArrayNode().add("one"));

      assertThat(SolrRecords.text(scalar, "f")).contains("one");
      assertThat(SolrRecords.text(array, "f")).contains("one");
      assertThat(SolrRecords.strings(scalar, "f")).containsExactly("one");
      assertThat(SolrRecords.strings(array, "f")).containsExactly("one");
    }

    @Test
    @DisplayName("a blank value is the same as an absent one")
    void blankIsAbsent() {
      JsonNode blank = MAPPER.createObjectNode().put("f", "   ");

      assertThat(SolrRecords.text(blank, "f")).isEmpty();
      assertThat(SolrRecords.strings(blank, "f")).isEmpty();
    }
  }
}
