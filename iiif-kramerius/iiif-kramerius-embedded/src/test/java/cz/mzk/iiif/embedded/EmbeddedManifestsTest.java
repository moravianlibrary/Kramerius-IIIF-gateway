/*
 * Proves the claim the whole layering rests on: an application needs level 2 and nothing else.
 *
 * The test is as much about what it does NOT do as what it does. There is no SPI implementation, no
 * Repository, no adapter, no registry, no HTTP and no database — and this module's POM depends only on
 * iiif-kramerius-build, so if that ever stops being true the build fails rather than the claim quietly
 * becoming false.
 */
package cz.mzk.iiif.embedded;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cz.mzk.iiif.document.LibraryProfile;
import cz.mzk.iiif.presentation.Iiif;
import cz.mzk.iiif.presentation.ManifestWriter;
import cz.mzk.iiif.testing.IiifSchema;

class EmbeddedManifestsTest {

  private static final String BASE = "https://kramerius.example.org";

  /** An in-memory stand-in for Kramerius' own Solr and image access. */
  private static final class FakeKramerius implements KrameriusApi {
    private final Map<String, SolrDocument> documents = new LinkedHashMap<>();
    private final Map<String, List<SolrDocument>> pages = new LinkedHashMap<>();
    private final Map<String, List<SolrDocument>> members = new LinkedHashMap<>();
    private final Map<String, List<MediaUnit>> mediaUnits = new LinkedHashMap<>();
    private final Map<String, int[]> sizes = new LinkedHashMap<>();

    FakeKramerius with(String pid, String model, Map<String, List<String>> fields) {
      documents.put(pid, new SolrDocument(pid, model, fields));
      return this;
    }

    FakeKramerius withPage(String parent, SolrDocument child) {
      pages.computeIfAbsent(parent, key -> new ArrayList<>()).add(child);
      documents.put(child.pid(), child);
      return this;
    }

    FakeKramerius withMember(String parent, SolrDocument child) {
      members.computeIfAbsent(parent, key -> new ArrayList<>()).add(child);
      documents.put(child.pid(), child);
      return this;
    }

    FakeKramerius withMediaUnit(String parent, SolrDocument unit, SolrDocument... tracks) {
      mediaUnits
          .computeIfAbsent(parent, key -> new ArrayList<>())
          .add(new MediaUnit(unit, List.of(tracks)));
      documents.put(unit.pid(), unit);
      for (SolrDocument track : tracks) {
        documents.put(track.pid(), track);
      }
      return this;
    }

    FakeKramerius withSize(String pid, int width, int height) {
      sizes.put(pid, new int[] {width, height});
      return this;
    }

    @Override
    public Optional<SolrDocument> byPid(String pid) {
      return Optional.ofNullable(documents.get(pid));
    }

    @Override
    public List<SolrDocument> pagesOf(String pid) {
      return pages.getOrDefault(pid, List.of());
    }

    @Override
    public List<SolrDocument> membersOf(String pid) {
      return members.getOrDefault(pid, List.of());
    }

    @Override
    public List<MediaUnit> mediaUnitsOf(String pid) {
      return mediaUnits.getOrDefault(pid, List.of());
    }

    @Override
    public Optional<int[]> imageSize(String pagePid) {
      return Optional.ofNullable(sizes.get(pagePid));
    }

    @Override
    public String baseUrl() {
      return BASE;
    }
  }

  private static KrameriusApi.SolrDocument page(String pid, String number) {
    return new KrameriusApi.SolrDocument(
        pid, "page", Map.of("title.search", List.of(number), "has_tiles", List.of("true")));
  }

  private static EmbeddedManifests manifests(FakeKramerius kramerius) {
    return new EmbeddedManifests(
        kramerius,
        EmbeddedManifests.defaultConfig("https://iiif.example.org"),
        LibraryProfile.of("mzk", "https://example.org/mzk")
            .label(Map.of("cs", List.of("Moravská zemská knihovna")))
            .build());
  }

  private static Iiif.Document build(FakeKramerius kramerius, String pid) {
    Iiif.Document document = manifests(kramerius).manifest(pid).orElseThrow();
    // The same schema harness the library's own tests use — an embedded host gets the same
    // guarantee.
    IiifSchema.assertValid(ManifestWriter.toJson(document));
    return document;
  }

  @Test
  @DisplayName("a monograph becomes a Manifest with real canvas dimensions")
  void monograph() {
    FakeKramerius kramerius =
        new FakeKramerius()
            .with("uuid:book", "monograph", Map.of("title.search", List.of("Kniha")))
            .withPage("uuid:book", page("uuid:p1", "1"))
            .withPage("uuid:book", page("uuid:p2", "2"))
            .withSize("uuid:p1", 2480, 3508)
            .withSize("uuid:p2", 2470, 3500);

    Iiif.Document document = build(kramerius, "uuid:book");

    assertThat(document.type()).isEqualTo("Manifest");
    assertThat(document.items()).hasSize(2);
    Iiif.Canvas first = (Iiif.Canvas) document.items().get(0);
    assertThat(first.width()).isEqualTo(2480);
    assertThat(first.height()).isEqualTo(3508);
  }

  @Test
  @DisplayName("a periodical becomes a Collection of its volumes")
  void periodical() {
    FakeKramerius kramerius =
        new FakeKramerius()
            .with("uuid:per", "periodical", Map.of("title.search", List.of("Lidové noviny")))
            .withMember(
                "uuid:per",
                new KrameriusApi.SolrDocument(
                    "uuid:vol", "periodicalvolume", Map.of("title.search", List.of("1935"))));

    Iiif.Document document = build(kramerius, "uuid:per");

    assertThat(document.type()).isEqualTo("Collection");
    assertThat(document.items()).hasSize(1);
  }

  @Test
  @DisplayName("a sound recording gets track canvases and one range per unit")
  void soundRecording() {
    FakeKramerius kramerius =
        new FakeKramerius()
            .with("uuid:rec", "soundrecording", Map.of("title.search", List.of("Nahrávka")))
            .withMediaUnit(
                "uuid:rec",
                new KrameriusApi.SolrDocument(
                    "uuid:side-a", "soundunit", Map.of("title.search", List.of("Strana A"))),
                new KrameriusApi.SolrDocument(
                    "uuid:t1",
                    "track",
                    Map.of("title.search", List.of("Píseň"), "track.length", List.of("184.0"))));

    Iiif.Document document = build(kramerius, "uuid:rec");

    assertThat(document.items()).hasSize(1);
    assertThat(document.structures()).hasSize(1);
    assertThat(((Iiif.Canvas) document.items().get(0)).duration()).isEqualTo(184.0);
  }

  @Test
  @DisplayName("a pid this repository does not hold is empty, not an error")
  void unknownPid() {
    assertThat(manifests(new FakeKramerius()).manifest("uuid:nope")).isEmpty();
  }

  @Test
  @DisplayName("a page with no measured size falls back rather than declaring a null one")
  void unmeasuredPage() {
    // An embedded host with no cache and a slow image server still produces a valid manifest.
    FakeKramerius kramerius =
        new FakeKramerius()
            .with("uuid:book", "monograph", Map.of("title.search", List.of("Kniha")))
            .withPage("uuid:book", page("uuid:p1", "1"));

    Iiif.Canvas canvas = (Iiif.Canvas) build(kramerius, "uuid:book").items().get(0);

    assertThat(canvas.width()).isEqualTo(1000);
  }
}
