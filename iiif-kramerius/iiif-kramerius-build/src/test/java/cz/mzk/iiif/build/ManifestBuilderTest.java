/*
 * The manifest contract, shape by shape.
 *
 * Every test does two things: asserts the document says what docs/BEHAVIOUR.md says it should, and runs
 * it through the official IIIF v3 schema. The second is what stops a plausible-looking manifest from
 * being invalid IIIF, which is how the v1 gateway shipped `"cz"` language tags and Manifests with no
 * `items` for years without anyone noticing.
 *
 * Bundles are built directly here — no repository, no fake, no I/O of any kind. That is the point of the
 * level: these are the construction rules, and they are a pure function of what was gathered. Where the
 * records came from, and what it cost to get them, is tested at level 3 where those decisions live.
 */
package cz.mzk.iiif.build;

import static cz.mzk.iiif.testing.Fixtures.BOOK;
import static cz.mzk.iiif.testing.Fixtures.PAGE_1;
import static cz.mzk.iiif.testing.Fixtures.PAGE_2;
import static cz.mzk.iiif.testing.Fixtures.bbox;
import static cz.mzk.iiif.testing.Fixtures.book;
import static cz.mzk.iiif.testing.Fixtures.bundle;
import static cz.mzk.iiif.testing.Fixtures.document;
import static cz.mzk.iiif.testing.Fixtures.none;
import static cz.mzk.iiif.testing.Fixtures.page;
import static cz.mzk.iiif.testing.Fixtures.track;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cz.mzk.iiif.document.DocumentBundle;
import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.document.ImageSize;
import cz.mzk.iiif.i18n.Translations;
import cz.mzk.iiif.kramerius.DocumentShape;
import cz.mzk.iiif.presentation.Iiif;
import cz.mzk.iiif.presentation.ManifestWriter;
import cz.mzk.iiif.testing.Fixtures;
import cz.mzk.iiif.testing.IiifSchema;

class ManifestBuilderTest {

  private static final ManifestConfig CONFIG =
      ManifestConfig.of("https://iiif.example.org", "https://www.digitalniknihovna.cz");

  /** Build and validate. Nothing gets asserted on a document the schema would reject. */
  private static Iiif.Document build(DocumentBundle bundle) {
    Iiif.Document document = ManifestBuilder.build(CONFIG, bundle);
    IiifSchema.assertValid(ManifestWriter.toJson(document));
    return document;
  }

  private static Iiif.Document build(DocumentBundle.Builder bundle) {
    return build(bundle.build());
  }

  /** A book with these pages, all sized, which is the ordinary case. */
  private static Iiif.Document bookWith(DocumentRecord... pages) {
    Map<String, ImageSize> sizes = new LinkedHashMap<>();
    for (DocumentRecord page : pages) {
      sizes.put(page.pid(), ImageSize.of(2480, 3508, 3));
    }
    return build(bundle(book("Kniha").build()).pages(List.of(pages)).sizes(sizes));
  }

  // Pages

  @Nested
  @DisplayName("the pages shape")
  class Pages {

    @Test
    @DisplayName(
        "canvases carry real dimensions, an image service and an annotation targeting them")
    void canvases() {
      Iiif.Document document =
          build(
              bundle(book("Kniha").build())
                  .pages(List.of(page(PAGE_1, "1 (TitlePage)").build(), page(PAGE_2, "2").build()))
                  .sizes(
                      Map.of(
                          PAGE_1, ImageSize.of(2480, 3508, 3),
                          PAGE_2, ImageSize.of(2470, 3500, 3))));

      assertThat(document.items()).hasSize(2);
      Iiif.Canvas first = (Iiif.Canvas) document.items().get(0);

      assertThat(first.id()).isEqualTo("https://iiif.example.org/mzk/" + BOOK + "/canvases/0");
      assertThat(first.width()).isEqualTo(2480);
      assertThat(first.height()).isEqualTo(3508);
      assertThat(first.label().get("none")).containsExactly("1 (TitlePage)");

      Iiif.Annotation annotation = first.items().get(0).items().get(0);
      assertThat(annotation.target()).isEqualTo(first.id());
      assertThat(annotation.body().service()).hasSize(1);
    }

    @Test
    @DisplayName("a page with no number and no type gets no label rather than a null one")
    void pageWithoutLabel() {
      Iiif.Document document =
          build(
              bundle(book("Kniha").build())
                  .pages(
                      List.of(document(PAGE_1, "page", DocumentShape.PAGES).hasTiles(true).build()))
                  .sizes(Map.of(PAGE_1, ImageSize.of(100, 200, 3))));

      assertThat(((Iiif.Canvas) document.items().get(0)).label()).isNull();
    }

    @Test
    @DisplayName("a page with no tiles gets the plain stream, no service and the fallback size")
    void pageWithoutTiles() {
      Iiif.Document document =
          build(
              bundle(book("Kniha").build())
                  .pages(
                      List.of(
                          document(PAGE_1, "page", DocumentShape.PAGES)
                              .label(none("1"))
                              .hasTiles(false)
                              .build())));

      Iiif.Canvas canvas = (Iiif.Canvas) document.items().get(0);
      Iiif.Body body = canvas.items().get(0).items().get(0).body();

      assertThat(body.service()).isNull();
      assertThat(body.id()).endsWith("/image");
      assertThat(canvas.width()).isEqualTo(ManifestConfig.DEFAULT_FALLBACK_CANVAS_SIZE);
    }

    @Test
    @DisplayName("a page with no known size falls back rather than declaring a null one")
    void pageWithoutSize() {
      Iiif.Document document =
          build(bundle(book("Kniha").build()).pages(List.of(page(PAGE_1, "1").build())));

      Iiif.Canvas canvas = (Iiif.Canvas) document.items().get(0);
      assertThat(canvas.width()).isEqualTo(ManifestConfig.DEFAULT_FALLBACK_CANVAS_SIZE);
      assertThat(canvas.height()).isEqualTo(ManifestConfig.DEFAULT_FALLBACK_CANVAS_SIZE);
    }

    @Test
    @DisplayName("a page the image server refused falls back too, and is not special-cased")
    void unavailableSize() {
      Iiif.Document document =
          build(
              bundle(book("Kniha").build())
                  .pages(List.of(page(PAGE_1, "1").build()))
                  .sizes(Map.of(PAGE_1, ImageSize.UNAVAILABLE)));

      assertThat(((Iiif.Canvas) document.items().get(0)).width())
          .isEqualTo(ManifestConfig.DEFAULT_FALLBACK_CANVAS_SIZE);
    }

    @Test
    @DisplayName("ALTO appears as seeAlso only when the index says the page has OCR")
    void altoOnlyWhenPresent() {
      Iiif.Document document =
          bookWith(
              page(PAGE_1, "1").hasAltoContent(true).build(),
              page(PAGE_2, "2").hasAltoContent(false).build());

      Iiif.SeeAlso alto = ((Iiif.Canvas) document.items().get(0)).seeAlso().get(0);
      assertThat(alto.type()).isEqualTo("Dataset");
      assertThat(alto.profile()).isEqualTo(Iiif.ALTO_PROFILE);
      assertThat(((Iiif.Canvas) document.items().get(1)).seeAlso()).isNull();
    }

    @Test
    @DisplayName("a page the index says nothing about still gets its ALTO link")
    void altoWhenIndexIsSilent() {
      // has_alto_content is in the K7 schema from 7.1.4 but populated on essentially nothing:
      // 0 of MZK's 76 455 203 pages. Treating silence as "no OCR" would drop the link everywhere.
      Iiif.Document document = bookWith(page(PAGE_1, "1").build());

      assertThat(((Iiif.Canvas) document.items().get(0)).seeAlso()).hasSize(1);
    }

    @Test
    @DisplayName("a PDF document with no pages is still a valid Manifest with empty items")
    void pdfWithNoPages() {
      Iiif.Document document =
          build(
              bundle(
                  document(BOOK, "article", DocumentShape.PAGES)
                      .label(none("Článek"))
                      .hasPdf(true)
                      .build()));

      assertThat(document.type()).isEqualTo("Manifest");
      assertThat(document.rendering()).hasSize(1);
      assertThat(document.rendering().get(0).format()).isEqualTo("application/pdf");
      // Required by the schema even with no canvases — the defect that made every v1 PDF invalid.
      assertThat(document.items()).isEmpty();
    }
  }

  // behavior

  @Nested
  @DisplayName("behavior is derived, never guessed")
  class Behavior {

    @Test
    @DisplayName("pages placed left and right make a bound sequence: paged")
    void bound() {
      assertThat(
              bookWith(
                      page(PAGE_1, "1").pagePlacement("left").build(),
                      page(PAGE_2, "2").pagePlacement("right").build())
                  .behavior())
          .containsExactly("paged");
    }

    @Test
    @DisplayName("single-sheet placement asserts nothing — not `individuals`")
    void loose() {
      assertThat(
              bookWith(
                      page(PAGE_1, "1").pagePlacement("single").build(),
                      page(PAGE_2, "2").pagePlacement("single").build())
                  .behavior())
          .isNull();
    }

    @Test
    @DisplayName("one canvas is never paged, whatever its placement says")
    void singleCanvas() {
      assertThat(bookWith(page(PAGE_1, "1").pagePlacement("left").build()).behavior()).isNull();
    }
  }

  // Shared blocks

  @Nested
  @DisplayName("blocks every document carries")
  class SharedBlocks {

    @Test
    @DisplayName("navDate is the document's period, at second precision")
    void navDate() {
      // Yale publishes this and it is what puts a manifest on a timeline. Kramerius' date.min
      // carries milliseconds (2002-01-01T00:00:00.001Z — the .001 is Kramerius avoiding a zero),
      // and every published example, the spec's included, uses whole seconds.
      Iiif.Document document =
          build(
              bundle(
                  book("Kniha")
                      .navDate(java.time.Instant.parse("1922-01-01T00:00:00.001Z"))
                      .build()));

      assertThat(document.navDate()).isEqualTo("1922-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("no navDate when the index carries no date, rather than an invented one")
    void navDateOmitted() {
      // K5 indexes 0 for an unknown year, and a document with no date at all is common. Neither is
      // the year zero.
      assertThat(build(bundle(book("Kniha").build())).navDate()).isNull();
    }

    @Test
    @DisplayName("navDate is the period, not the modification time")
    void navDateIsNotModified() {
      // Confusing the two would put a 1922 volume in whatever year it was last reindexed.
      Iiif.Document document =
          build(
              bundle(
                  book("Kniha")
                      .modified(java.time.Instant.parse("2024-05-06T07:08:09Z"))
                      .navDate(java.time.Instant.parse("1922-01-01T00:00:00Z"))
                      .build()));

      assertThat(document.navDate()).isEqualTo("1922-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("the manifest points at its MODS record, typed Dataset")
    void seeAlsoMods() {
      // Every v3 peer surveyed carries a manifest-level seeAlso — Wellcome, Yale, the Internet
      // Archive. A link only: nothing here fetches or parses MODS.
      Iiif.SeeAlso mods = build(bundle(book("Kniha").build())).seeAlso().get(0);

      assertThat(mods.id()).endsWith("/metadata/mods");
      // "MODS" is not an IIIF type, the same mistake v1 made with "Alto" on the ALTO link.
      assertThat(mods.type()).isEqualTo("Dataset");
      assertThat(mods.profile()).isEqualTo("http://www.loc.gov/mods/v3");
      assertThat(mods.format()).isEqualTo("application/xml");
      assertThat(mods.label()).containsOnlyKeys("cs", "en");
    }

    @Test
    @DisplayName("labels this gateway wrote are cs+en; record content is `none`")
    void languageTags() {
      Iiif.Document document = build(bundle(book("Kniha").build()));

      // The document's own label is record content in an unknown language.
      assertThat(document.label()).containsOnlyKeys("none");
      // Anything this gateway wrote is emitted in every language it has a translation for.
      assertThat(document.requiredStatement().label()).containsOnlyKeys("cs", "en");
      assertThat(document.requiredStatement().label().get("cs")).containsExactly("Poskytuje");
      assertThat(document.requiredStatement().label().get("en")).containsExactly("Provided by");
      assertThat(document.homepage().get(0).label()).containsOnlyKeys("cs", "en");
    }

    @Test
    @DisplayName("metadata is passed through from the adapter, not composed here")
    void metadataIsAdapterSupplied() {
      var rows =
          MetadataRows.using(Translations.bundled())
              .text("metadata.title", "Kniha")
              .translated("metadata.documentType", "model.monograph")
              .codes("metadata.language", "language.", List.of("cze", "ger"))
              .licenses(List.of("onsite"))
              .build();

      Iiif.Document document = build(bundle(book("Kniha").metadata(rows).build()));

      assertThat(document.metadata()).hasSize(4);
      assertThat(document.metadata().get(0).label().get("cs")).containsExactly("Název");
      assertThat(document.metadata().get(1).value().get("en")).containsExactly("Monograph");
      assertThat(document.metadata().get(2).value().get("cs"))
          .containsExactly("čeština", "němčina");
      assertThat(document.metadata().get(3).value().get("cs")).containsExactly("pouze v knihovně");
    }

    @Test
    @DisplayName("a resolved catalogue URL becomes a second homepage entry")
    void catalogueLink() {
      Iiif.Document linked =
          build(
              bundle(book("Kniha").build())
                  .catalogueUrl("https://vufind.mzk.cz/Record/MZK01-000210291"));
      assertThat(linked.homepage()).hasSize(2);
      assertThat(linked.homepage().get(1).id())
          .isEqualTo("https://vufind.mzk.cz/Record/MZK01-000210291");

      // No catalogue resolved — the sysno alone is not a link, and nothing is invented from it.
      Iiif.Document unlinked = build(bundle(book("Kniha").systemNumber("000000123").build()));
      assertThat(unlinked.homepage()).hasSize(1);
    }

    @Test
    @DisplayName("a Slovak library's name is tagged sk, not cs")
    void institutionNameKeepsItsOwnLanguage() {
      Iiif.Document document = build(bundle(Fixtures.slovak(), book("Kniha").build()));

      // The registry's `name` is the institution's name in ITS language. Tagging Slovak as Czech is
      // a claim a Slovak viewer acts on, which is why nothing hardcodes a language tag any more.
      assertThat(document.provider().get(0).label())
          .containsEntry("sk", List.of("Katalóg digitálnej knižnice"))
          .containsEntry("en", List.of("Slovak Digital Library Catalogue"))
          .doesNotContainKey("cs");
    }

    @Test
    @DisplayName("navPlace polygons are closed and wound, and add the extension context")
    void navPlace() {
      Iiif.Document document =
          build(
              bundle(
                  document(BOOK, "map", DocumentShape.PAGES)
                      .label(none("Mapa"))
                      .coordinates(bbox(10.5, 10.6, 48.9, 49.0))
                      .build()));

      assertThat(document.context()).isInstanceOf(List.class);
      Iiif.Geometry geometry = document.navPlace().features().get(0).geometry();
      assertThat(geometry.type()).isEqualTo("Polygon");

      @SuppressWarnings("unchecked")
      List<List<List<Double>>> rings = (List<List<List<Double>>>) geometry.coordinates();
      List<List<Double>> ring = rings.get(0);
      assertThat(ring).hasSize(5);
      assertThat(ring.get(0)).isEqualTo(ring.get(4));
    }

    @Test
    @DisplayName("a bbox with coincident corners is a Point, not a degenerate Polygon")
    void navPlacePoint() {
      Iiif.Document document =
          build(
              bundle(
                  document(BOOK, "map", DocumentShape.PAGES)
                      .label(none("Mapa"))
                      .coordinates(bbox(16.6, 16.6, 49.2, 49.2))
                      .build()));

      assertThat(document.navPlace().features().get(0).geometry().type()).isEqualTo("Point");
    }

    @Test
    @DisplayName("partOf links to a parent this gateway can serve, and omits one it cannot")
    void partOf() {
      Iiif.Document linked =
          build(
              bundle(
                  document(BOOK, "article", DocumentShape.PAGES)
                      .label(none("Článek"))
                      .parent("uuid:issue", "Manifest", none("Lidové noviny"))
                      .build()));
      assertThat(linked.partOf()).hasSize(1);
      assertThat(linked.partOf().get(0).type()).isEqualTo("Manifest");

      Iiif.Document unlinked =
          build(
              bundle(document(BOOK, "article", DocumentShape.PAGES).label(none("Článek")).build()));
      assertThat(unlinked.partOf()).isNull();
    }

    @Test
    @DisplayName("requiredStatement names the institution and nothing else")
    void requiredStatementIsAttributionOnly() {
      // Licences used to be joined in here. Viewers render this as a banner that must be shown, and
      // a
      // licence notice runs to hundreds of words — it made the banner unreadable and duplicated the
      // Licence metadata row word for word.
      Iiif.Document document = build(bundle(book("Kniha").licenses(List.of("dnnto")).build()));

      assertThat(document.requiredStatement().value().get("cs"))
          .containsExactly("Moravská zemská knihovna");
      assertThat(document.requiredStatement().value().get("en"))
          .containsExactly("Moravian Library");
    }
  }

  // Sound

  @Nested
  @DisplayName("the sound recording shape")
  class SoundRecording {

    private DocumentBundle recording(DocumentRecord... tracks) {
      DocumentRecord unit =
          document("uuid:side-a", "soundunit", DocumentShape.SOUND_RECORDING)
              .label(none("Strana A"))
              .build();
      return bundle(
              document(BOOK, "soundrecording", DocumentShape.SOUND_RECORDING)
                  .label(none("Nahrávka"))
                  .build())
          .soundUnits(List.of(new DocumentBundle.SoundUnit(unit, List.of(tracks))))
          .build();
    }

    @Test
    @DisplayName("tracks become canvases with a duration, grouped into one range per sound unit")
    void tracksAndRanges() {
      Iiif.Document document =
          build(recording(track("uuid:t1", "Píseň").trackLength(184.0).build()));

      Iiif.Canvas trackCanvas = (Iiif.Canvas) document.items().get(0);
      assertThat(trackCanvas.duration()).isEqualTo(184.0);
      assertThat(trackCanvas.width()).isNull();
      assertThat(trackCanvas.items().get(0).items().get(0).body().format()).isEqualTo("audio/mpeg");

      assertThat(document.structures()).hasSize(1);
      assertThat(document.structures().get(0).label().get("none")).containsExactly("Strana A");
      assertThat(document.structures().get(0).items().get(0).id()).isEqualTo(trackCanvas.id());
    }

    @Test
    @DisplayName(
        "a track with no indexed length still gets a duration, because the schema needs one")
    void defaultDuration() {
      Iiif.Document document = build(recording(track("uuid:t1", "Píseň").build()));

      assertThat(((Iiif.Canvas) document.items().get(0)).duration()).isEqualTo(1.0);
    }
  }

  // Collections

  @Nested
  @DisplayName("the collection shapes")
  class Collections {

    @Test
    @DisplayName("a monograph gathered as members becomes a Collection, not a Manifest")
    void membersMakeACollection() {
      Iiif.Document document =
          build(
              bundle(
                      document(BOOK, "monograph", DocumentShape.MEMBERS)
                          .label(none("Sebrané spisy"))
                          .build())
                  .members(
                      List.of(
                          document("uuid:v1", "monographunit", DocumentShape.PAGES)
                              .label(none("1. Básně"))
                              .build())));

      assertThat(document.type()).isEqualTo("Collection");
      assertThat(document.items()).hasSize(1);
      assertThat(((Iiif.Reference) document.items().get(0)).label().get("none"))
          .containsExactly("1. Básně");
    }

    @Test
    @DisplayName("a nested collection is typed Collection; other members are Manifests")
    void nestedCollection() {
      Iiif.Document document =
          build(
              bundle(
                      document(BOOK, "collection", DocumentShape.COLLECTION_MEMBERS)
                          .label(none("Sbírka"))
                          .build())
                  .members(
                      List.of(
                          document("uuid:sub", "collection", DocumentShape.COLLECTION_MEMBERS)
                              .label(none("Podsbírka"))
                              .build(),
                          document("uuid:mono", "monograph", DocumentShape.PAGES)
                              .label(none("Kniha"))
                              .build())));

      assertThat(document.items()).hasSize(2);
      // v1 typed every member from the parent, so every nested collection came out a Manifest.
      assertThat(((Iiif.Reference) document.items().get(0)).type()).isEqualTo("Collection");
      assertThat(((Iiif.Reference) document.items().get(1)).type()).isEqualTo("Manifest");
    }

    @Test
    @DisplayName("a page added to a collection points at the document containing it")
    void pageMember() {
      Iiif.Document document =
          build(
              bundle(
                      document(BOOK, "collection", DocumentShape.COLLECTION_MEMBERS)
                          .label(none("Sbírka"))
                          .build())
                  .members(
                      List.of(
                          document(PAGE_1, "page", DocumentShape.PAGES)
                              .label(none("Kniha (strana 14)"))
                              .parent("uuid:parent", "Manifest", none("Kniha"))
                              .build())));

      Iiif.Reference member = (Iiif.Reference) document.items().get(0);

      assertThat(member.id()).endsWith("/uuid:parent");
      assertThat(member.label().get("none")).containsExactly("Kniha (strana 14)");
    }

    @Test
    @DisplayName("a convolute does not list itself")
    void convoluteSkipsItself() {
      Iiif.Document document =
          build(
              bundle(
                      document(BOOK, "convolute", DocumentShape.MEMBERS)
                          .label(none("Konvolut"))
                          .excludesOwnModel(true)
                          .build())
                  .members(
                      List.of(
                          document(BOOK, "convolute", DocumentShape.MEMBERS)
                              .label(none("Konvolut"))
                              .build(),
                          document("uuid:part", "monograph", DocumentShape.PAGES)
                              .label(none("Část"))
                              .build())));

      assertThat(document.items()).hasSize(1);
    }
  }
}
