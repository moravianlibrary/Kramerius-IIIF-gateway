/*
 * Kramerius 5, against fixtures captured live from ndk.cz (the National Library, 5.8.4.4).
 *
 * K5 matters: 11 alive libraries in the registry run it, including nkp. What it can do is narrower than
 * K7 and the tests say so explicitly, because the useful failure here would be quietly claiming a
 * capability that is not there — v1's habit.
 */
package cz.mzk.iiif.adapter.v50;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cz.mzk.iiif.adapter.AdapterRegistry;
import cz.mzk.iiif.adapter.KrameriusAdapter;
import cz.mzk.iiif.adapter.Version;
import cz.mzk.iiif.adapter.v70.Kramerius70;
import cz.mzk.iiif.adapter.v714.Kramerius714;
import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.i18n.Translations;
import cz.mzk.iiif.kramerius.IiifStructure;

class Kramerius50Test {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Kramerius50 ADAPTER = new Kramerius50();

  private static final AdapterRegistry REGISTRY =
      AdapterRegistry.of(new Kramerius50(), new Kramerius70(), new Kramerius714());

  private static JsonNode fixture(String name) {
    try (InputStream in = Kramerius50Test.class.getResourceAsStream("/solr/" + name + ".json")) {
      if (in == null) {
        throw new IllegalStateException("missing fixture " + name);
      }
      return MAPPER.readTree(in).path("response").path("docs").get(0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Kramerius5Records mapper() {
    return new Kramerius5Records(ADAPTER.models(), Translations.bundled(), java.util.Map.of());
  }

  @Nested
  @DisplayName("selection")
  class Selection {

    @Test
    @DisplayName("K5 versions route to Kramerius50 and K7 versions never do")
    void routing() {
      assertThat(REGISTRY.forVersion(Version.parse("5.8.4.4").orElseThrow()))
          .get()
          .extracting(KrameriusAdapter::name)
          .isEqualTo("Kramerius50");
      assertThat(REGISTRY.forVersion(Version.parse("5.4.2").orElseThrow()))
          .get()
          .extracting(KrameriusAdapter::name)
          .isEqualTo("Kramerius50");

      assertThat(REGISTRY.forVersion(Version.parse("7.0.39").orElseThrow()))
          .get()
          .extracting(KrameriusAdapter::name)
          .isEqualTo("Kramerius70");
    }

    @Test
    @DisplayName("K5 is the one adapter that is not open-ended upwards")
    void notOpenEnded() {
      // Floor-based selection means "at or above", so without an explicit bound the 5.0 floor would
      // claim every K7 instance as well. There is no Kramerius 6, so major == 5 is the honest rule.
      assertThat(ADAPTER.supports(Version.parse("7.2.2").orElseThrow())).isFalse();
      assertThat(ADAPTER.supports(Version.parse("6.0.0").orElseThrow())).isFalse();
      assertThat(ADAPTER.supports(Version.parse("5.8.4.5").orElseThrow())).isTrue();
    }

    @Test
    @DisplayName("K4 still resolves to nothing")
    void k4() {
      assertThat(REGISTRY.forVersion(Version.parse("4.6.2").orElseThrow())).isEmpty();
    }
  }

  @Nested
  @DisplayName("the index, which shares almost no field name with K7")
  class Index {

    @Test
    @DisplayName("its own Solr path, uniqueKey and model field")
    void names() {
      assertThat(ADAPTER.schema().searchPath()).isEqualTo("/search/api/v5.0/search");
      assertThat(ADAPTER.schema().modelField()).isEqualTo("fedora.model");
      assertThat(mapper().pidField()).isEqualTo("PID");
    }

    @Test
    @DisplayName("cursorMark is refused, because the only ordering field is multi-valued")
    void noCursorMark() {
      // rels_ext_index is an array. cursorMark needs a total ordering on single-valued fields, so
      // K5
      // has to fall back to start/rows and sort client-side.
      assertThat(ADAPTER.schema().supportsCursorMark()).isFalse();
      assertThat(ADAPTER.schema().childSort("PAGES", "PID")).isNull();
    }

    @Test
    @DisplayName("children hang off parent_pid, and there is no foster relationship")
    void childFilter() {
      String owned = ADAPTER.schema().childFilter("uuid:x", "PAGES", false);
      String fostered = ADAPTER.schema().childFilter("uuid:x", "PAGES", true);

      assertThat(owned).isEqualTo("parent_pid:\"uuid:x\"");
      // K5 has no foster relationship at all, so asking for one changes nothing.
      assertThat(fostered).isEqualTo(owned);
    }
  }

  @Nested
  @DisplayName("mapping a real K5 monograph")
  class Monograph {

    private DocumentRecord record() {
      return mapper().document(fixture("k5_doc_monograph"), false).orElseThrow();
    }

    @Test
    @DisplayName("title comes from dc.title, not the ##-packed sort form")
    void title() {
      // browse_title and search_title are "SORTFORM##Display form" — showing the first half would
      // print accent-stripped uppercase at the reader.
      assertThat(record().label().get("none")).containsExactly("Macharova čítanka");
    }

    @Test
    @DisplayName("author, date, availability and shape all map")
    void fields() {
      DocumentRecord record = record();

      assertThat(record.shape()).isEqualTo(IiifStructure.PAGED_MANIFEST);
      assertThat(record.metadata())
          .anySatisfy(
              row -> assertThat(row.value().get("none")).contains("Machar, Josef Svatopluk"));
      assertThat(record.metadata())
          .anySatisfy(row -> assertThat(row.value().get("none")).contains("[1908]"));
    }

    @Test
    @DisplayName("a top-level document does not become its own parent")
    void noSelfParent() {
      // K5's parent_pid contains the document's OWN pid for a top-level object. Taking the first
      // value blindly would make every monograph part of itself.
      assertThat(record().parent()).isEmpty();
    }

    @Test
    @DisplayName("no publisher or place is indexed, so the date is labelled Vydáno not a statement")
    void publicationDegenerates() {
      assertThat(record().metadata())
          .anySatisfy(row -> assertThat(row.label().get("cs")).containsExactly("Vydáno"));
    }
  }

  @Nested
  @DisplayName("mapping a real K5 page")
  class Page {

    private DocumentRecord record() {
      return mapper().page(fixture("k5_doc_page"));
    }

    @Test
    @DisplayName("the ##-packed details field unpacks into number and translated type")
    void packedDetails() {
      // Stored as "[1] \n                        ##TitlePage" — whitespace and a non-breaking space
      // on the left, the type on the right. The type is then translated exactly as on K7.
      assertThat(record().label().get("cs")).containsExactly("[1] (titulní strana)");
      assertThat(record().label().get("en")).containsExactly("[1] (title page)");
    }

    @Test
    @DisplayName("viewable stands in for has_tiles, which K5 does not index")
    void viewable() {
      assertThat(record().hasTiles()).isTrue();
    }

    @Test
    @DisplayName("K5 indexes no page placement, so behavior is never asserted")
    void noPlacement() {
      // `paged` is a positive claim about how a book opens. K5 has no basis for it, so it says
      // nothing
      // rather than guessing from the model — the same rule as K7, applied to less data.
      assertThat(record().pagePlacement()).isEmpty();
    }
  }

  @Nested
  @DisplayName("capabilities K5 does not have")
  class Limits {

    @Test
    @DisplayName("no image service where the probe found none — the flat stream instead")
    void noImageService() {
      // Measured on ndk.cz and kramerius.vugtk.cz: info.json is 0 bytes and every image request
      // under /search/iiif/ is a 404, while streams/IMG_FULL serves the image. Declaring a service
      // would be a claim a viewer acts on.
      Kramerius5Urls urls = new Kramerius5Urls("https://www.ndk.cz", false, 2);

      assertThat(urls.hasImageService()).isFalse();
      assertThat(urls.iiifImage("uuid:x")).endsWith("/streams/IMG_FULL");
    }

    @Test
    @DisplayName("an image service where the probe found one — K5 is not uniform")
    void imageServiceWhenPresent() {
      // lmda 5.8.1, dsmo 5.8.1 and kvkli 5.8.3 serve a complete Image API 2 with real width/height,
      // so a blanket false would cost those three libraries tiles they actually have.
      Kramerius5Urls urls = new Kramerius5Urls("https://lmda.silvarium.cz", true, 2);

      assertThat(urls.hasImageService()).isTrue();
      assertThat(urls.iiifImage("uuid:x"))
          .isEqualTo("https://lmda.silvarium.cz/search/iiif/uuid:x/full/full/0/default.jpg");
    }

    @Test
    @DisplayName("stream URLs are K5's own, which is why this does not extend Kramerius70")
    void urls() {
      Kramerius5Urls urls = new Kramerius5Urls("https://www.ndk.cz", false, 2);
      String pid = "uuid:x";

      assertThat(urls.thumbnail(pid)).endsWith("/search/api/v5.0/item/uuid:x/thumb");
      assertThat(urls.plainImage(pid)).endsWith("/streams/IMG_FULL");
      assertThat(urls.alto(pid)).endsWith("/streams/ALTO");
    }

    @Test
    @DisplayName("the model table lists only models K5 actually has")
    void modelTable() {
      // Measured across nkp, nlk and vugtk. K5 has no audio models at all, and no collections or
      // convolutes — listing them would be inventing capability.
      assertThat(ADAPTER.models().find("monograph")).isPresent();
      assertThat(ADAPTER.models().find("periodicalvolume")).isPresent();
      assertThat(ADAPTER.models().find("soundrecording")).isEmpty();
      assertThat(ADAPTER.models().find("collection")).isEmpty();
      assertThat(ADAPTER.models().find("convolute")).isEmpty();
    }
  }
}
