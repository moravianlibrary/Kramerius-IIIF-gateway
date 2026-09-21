/*
 * What gathering a document costs, and what it does when it cannot finish.
 *
 * These are the decisions level 2 deliberately does not make: which children to fetch, whether to pay
 * for uncached dimensions now or hand them to the worker, and what to answer for a pid that is not a
 * document. Level 2 tests the manifest that comes out; this tests what it took to get there.
 */
package cz.mzk.iiif.spi;

import static cz.mzk.iiif.testing.Fixtures.BOOK;
import static cz.mzk.iiif.testing.Fixtures.PAGE_1;
import static cz.mzk.iiif.testing.Fixtures.PAGE_2;
import static cz.mzk.iiif.testing.Fixtures.book;
import static cz.mzk.iiif.testing.Fixtures.document;
import static cz.mzk.iiif.testing.Fixtures.none;
import static cz.mzk.iiif.testing.Fixtures.page;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cz.mzk.iiif.document.ImageSize;
import cz.mzk.iiif.kramerius.ChildKind;
import cz.mzk.iiif.kramerius.DocumentShape;
import cz.mzk.iiif.testing.FakeRepository;

class BundleAssemblerTest {

  private static BundleAssembler.Result.Found found(
      FakeRepository repository, DimensionPolicy policy) {
    BundleAssembler.Result result = BundleAssembler.assemble(repository, policy, BOOK);
    assertThat(result).isInstanceOf(BundleAssembler.Result.Found.class);
    return (BundleAssembler.Result.Found) result;
  }

  @Nested
  @DisplayName("resolving the requested pid")
  class Resolution {

    @Test
    @DisplayName("an unknown pid is NotFound, not an error")
    void unknownPid() {
      BundleAssembler.Result result =
          BundleAssembler.assemble(FakeRepository.mzk(), DimensionPolicy.ALWAYS, "uuid:nope");

      assertThat(result).isInstanceOf(BundleAssembler.Result.NotFound.class);
    }

    @Test
    @DisplayName("an object that exists but has no manifest is Unsupported, not NotFound")
    void unsupportedModel() {
      // v1 answered 200 for both of these. They are different facts and get different answers.
      FakeRepository repository = FakeRepository.mzk().withUnsupported("uuid:su", "soundunit");

      BundleAssembler.Result result =
          BundleAssembler.assemble(repository, DimensionPolicy.ALWAYS, "uuid:su");

      assertThat(result)
          .isInstanceOfSatisfying(
              BundleAssembler.Result.Unsupported.class,
              unsupported -> assertThat(unsupported.model()).isEqualTo("soundunit"));
    }
  }

  @Nested
  @DisplayName("gathering children by shape")
  class Gathering {

    @Test
    @DisplayName("a PAGES document gathers its pages and nothing else")
    void pages() {
      FakeRepository repository =
          FakeRepository.mzk()
              .with(book("Kniha").build())
              .withChildren(BOOK, ChildKind.PAGES, page(PAGE_1, "1").build())
              .withCachedSize(PAGE_1, ImageSize.of(10, 20, 3));

      var bundle = found(repository, DimensionPolicy.ALWAYS).bundle();

      assertThat(bundle.pages()).hasSize(1);
      assertThat(bundle.members()).isEmpty();
    }

    @Test
    @DisplayName("a MEMBERS document gathers the child kind its record names")
    void members() {
      FakeRepository repository =
          FakeRepository.mzk()
              .with(
                  document(BOOK, "monograph", DocumentShape.MEMBERS)
                      .label(none("Sebrané spisy"))
                      .childKind(ChildKind.MONOGRAPH_UNITS)
                      .build())
              .withChildren(
                  BOOK,
                  ChildKind.MONOGRAPH_UNITS,
                  document("uuid:v1", "monographunit", DocumentShape.PAGES)
                      .label(none("1. Básně"))
                      .build());

      var bundle = found(repository, DimensionPolicy.ALWAYS).bundle();

      assertThat(bundle.members()).hasSize(1);
      assertThat(bundle.pages()).isEmpty();
    }

    @Test
    @DisplayName("a MEMBERS document with no members but pages serves the pages")
    void membersFallsBackToPages() {
      // The shape is a prediction from the model table, and the repository does not honour it.
      // `periodical/periodicalvolume/page` covers 84,559 pages at MZK — a year bound and digitised
      // as one run rather than as separate issues. Those volumes served a Collection with
      // "items": [], which is valid IIIF describing nothing.
      FakeRepository repository =
          FakeRepository.mzk()
              .with(
                  document(BOOK, "periodicalvolume", DocumentShape.MEMBERS)
                      .label(none("1922"))
                      .childKind(ChildKind.PERIODICAL_ISSUES)
                      .build())
              .withChildren(
                  BOOK, ChildKind.PAGES, page(PAGE_1, "1").build(), page(PAGE_2, "2").build());

      var bundle = found(repository, DimensionPolicy.ALWAYS).bundle();

      assertThat(bundle.pages()).hasSize(2);
      assertThat(bundle.members()).isEmpty();
    }

    @Test
    @DisplayName("a PAGES document with no pages but child documents serves them as members")
    void pagesFallsBackToMembers() {
      // The mirror: `monograph/monograph/page` and `sheetmusic/sheetmusic/page` are both real at
      // MZK. The outer document owns no pages, so it served an empty Manifest.
      FakeRepository repository =
          FakeRepository.mzk()
              .with(
                  document(BOOK, "sheetmusic", DocumentShape.PAGES)
                      .label(none("Partitura"))
                      .build())
              .withChildDocuments(
                  BOOK,
                  document("uuid:inner", "sheetmusic", DocumentShape.PAGES)
                      .label(none("První část"))
                      .build());

      var bundle = found(repository, DimensionPolicy.ALWAYS).bundle();

      assertThat(bundle.members()).hasSize(1);
      assertThat(bundle.pages()).isEmpty();
    }

    @Test
    @DisplayName("a MEMBERS document with neither its kind nor pages still collects what it has")
    void membersFallsBackToAnyChildDocuments() {
      // `monograph/monograph/page` — 1,254 pages at MZK. The outer monograph's child is another
      // monograph, not a monographunit, and it owns no pages of its own, so falling back to pages
      // alone left it empty too. It is still a collection; just of whatever is actually under it.
      FakeRepository repository =
          FakeRepository.mzk()
              .with(
                  document(BOOK, "monograph", DocumentShape.MEMBERS)
                      .label(none("Sebrané spisy"))
                      .childKind(ChildKind.MONOGRAPH_UNITS)
                      .build())
              .withChildDocuments(
                  BOOK,
                  document("uuid:inner", "monograph", DocumentShape.PAGES)
                      .label(none("Vnitřní svazek"))
                      .build());

      var bundle = found(repository, DimensionPolicy.ALWAYS).bundle();

      assertThat(bundle.members()).hasSize(1);
      assertThat(bundle.pages()).isEmpty();
    }

    @Test
    @DisplayName("a document with neither is still empty, and costs one extra query to find out")
    void genuinelyEmptyStaysEmpty() {
      // The fallback must not invent content. An empty answer was sometimes the truth.
      FakeRepository repository =
          FakeRepository.mzk()
              .with(
                  document(BOOK, "periodicalvolume", DocumentShape.MEMBERS)
                      .label(none("Prázdný svazek"))
                      .childKind(ChildKind.PERIODICAL_ISSUES)
                      .build());

      var bundle = found(repository, DimensionPolicy.ALWAYS).bundle();

      assertThat(bundle.members()).isEmpty();
      assertThat(bundle.pages()).isEmpty();
    }

    @Test
    @DisplayName("the members a record names win: the fallback never runs when they exist")
    void declaredMembersTakePrecedence() {
      // Otherwise a volume that has both issues and stray pages would serve the pages and lose the
      // issues — and the extra query would be paid on every members document in the registry.
      FakeRepository repository =
          FakeRepository.mzk()
              .with(
                  document(BOOK, "periodicalvolume", DocumentShape.MEMBERS)
                      .label(none("1923"))
                      .childKind(ChildKind.PERIODICAL_ISSUES)
                      .build())
              .withChildren(
                  BOOK,
                  ChildKind.PERIODICAL_ISSUES,
                  document("uuid:issue-1", "periodicalitem", DocumentShape.PAGES)
                      .label(none("č. 1"))
                      .build())
              .withChildren(BOOK, ChildKind.PAGES, page(PAGE_1, "1").build());

      var bundle = found(repository, DimensionPolicy.ALWAYS).bundle();

      assertThat(bundle.members()).hasSize(1);
      assertThat(bundle.pages()).isEmpty();
    }

    @Test
    @DisplayName("a sound recording gathers each unit with its tracks")
    void soundRecording() {
      FakeRepository repository =
          FakeRepository.mzk()
              .with(
                  document(BOOK, "soundrecording", DocumentShape.SOUND_RECORDING)
                      .label(none("Nahrávka"))
                      .build())
              .withChildren(
                  BOOK,
                  ChildKind.SOUND_UNITS,
                  document("uuid:side-a", "soundunit", DocumentShape.SOUND_RECORDING)
                      .label(none("Strana A"))
                      .build())
              .withChildren(
                  "uuid:side-a",
                  ChildKind.TRACKS,
                  document("uuid:t1", "track", DocumentShape.SOUND_RECORDING)
                      .label(none("Píseň"))
                      .build());

      var bundle = found(repository, DimensionPolicy.ALWAYS).bundle();

      assertThat(bundle.soundUnits()).hasSize(1);
      assertThat(bundle.soundUnits().get(0).tracks()).hasSize(1);
    }

    @Test
    @DisplayName("a catalogue link is resolved when the library has one and the record a sysno")
    void catalogueLink() {
      FakeRepository repository =
          FakeRepository.mzk()
              .withMzkCatalogue()
              .with(book("Kniha").systemNumber("mzk03-000706629").build());

      // mzk03, not the default mzk01 — the base is part of the number and picking the wrong one
      // 404s.
      assertThat(found(repository, DimensionPolicy.ALWAYS).bundle().catalogueUrl())
          .contains("https://vufind.mzk.cz/Record/MZK03-000706629");
    }

    @Test
    @DisplayName("no catalogue configured means no link, not a guessed one")
    void noCatalogue() {
      FakeRepository repository =
          FakeRepository.mzk().with(book("Kniha").systemNumber("mzk01-000210291").build());

      assertThat(found(repository, DimensionPolicy.ALWAYS).bundle().catalogueUrl()).isEmpty();
    }
  }

  @Nested
  @DisplayName("the cold-cache decision")
  class Dimensions {

    private FakeRepository twoUncachedPages() {
      return FakeRepository.mzk()
          .with(book("Kniha").build())
          .withChildren(BOOK, ChildKind.PAGES, page(PAGE_1, "1").build(), page(PAGE_2, "2").build())
          .withFetchableSize(PAGE_1, ImageSize.of(2480, 3508, 3))
          .withFetchableSize(PAGE_2, ImageSize.of(2470, 3500, 3));
    }

    @Test
    @DisplayName("under budget: fetch now, report the bundle complete")
    void resolvesInline() {
      FakeRepository repository = twoUncachedPages();

      BundleAssembler.Result.Found result = found(repository, DimensionPolicy.ALWAYS);

      assertThat(result.dimensionsComplete()).isTrue();
      assertThat(result.unresolvedPages()).isEmpty();
      assertThat(repository.fetchedPids()).containsExactly(PAGE_1, PAGE_2);
      assertThat(result.bundle().sizes()).containsKeys(PAGE_1, PAGE_2);
    }

    @Test
    @DisplayName("over budget: fetch nothing, and name the pages for the worker")
    void defersToWorker() {
      FakeRepository repository = twoUncachedPages();

      BundleAssembler.Result.Found result = found(repository, DimensionPolicy.NEVER);

      assertThat(result.dimensionsComplete()).isFalse();
      assertThat(result.unresolvedPages()).containsExactly(PAGE_1, PAGE_2);
      assertThat(repository.fetchedPids()).isEmpty();
      // The bundle is still buildable — the canvases just fall back to a nominal size.
      assertThat(result.bundle().sizes()).isEmpty();
    }

    @Test
    @DisplayName("a cached page is never fetched again")
    void cacheHitsAreNotRefetched() {
      FakeRepository repository = twoUncachedPages().withCachedSize(PAGE_1, ImageSize.of(1, 2, 3));

      found(repository, DimensionPolicy.ALWAYS);

      assertThat(repository.fetchedPids()).containsExactly(PAGE_2);
    }

    @Test
    @DisplayName("a page the image server refused counts as resolved, and is not retried")
    void unavailableIsNotRetried() {
      // Without the sentinel this is indistinguishable from an unfetched page, and a broken image
      // would be re-requested on every single manifest request forever.
      FakeRepository repository = twoUncachedPages().withCachedSize(PAGE_1, ImageSize.UNAVAILABLE);

      BundleAssembler.Result.Found result = found(repository, DimensionPolicy.ALWAYS);

      assertThat(repository.fetchedPids()).containsExactly(PAGE_2);
      assertThat(result.dimensionsComplete()).isTrue();
    }

    @Test
    @DisplayName("a page with no tiles is never asked about — there is no endpoint to ask")
    void untiledPagesAreSkipped() {
      FakeRepository repository =
          FakeRepository.mzk()
              .with(book("Kniha").build())
              .withChildren(
                  BOOK,
                  ChildKind.PAGES,
                  document(PAGE_1, "page", DocumentShape.PAGES).hasTiles(false).build());

      BundleAssembler.Result.Found result = found(repository, DimensionPolicy.ALWAYS);

      assertThat(repository.fetchedPids()).isEmpty();
      assertThat(result.dimensionsComplete()).isTrue();
    }

    @Test
    @DisplayName(
        "a source with no addressable cache resolves everything, having nowhere to defer to")
    void unmanagedSourceAlwaysResolves() {
      Repository unmanaged = new UnmanagedRepository(twoUncachedPages());

      BundleAssembler.Result result =
          BundleAssembler.assemble(unmanaged, DimensionPolicy.NEVER, BOOK);

      assertThat(((BundleAssembler.Result.Found) result).dimensionsComplete()).isTrue();
      assertThat(((BundleAssembler.Result.Found) result).bundle().sizes())
          .containsKeys(PAGE_1, PAGE_2);
    }
  }

  /**
   * A host whose sizes are local and free — an Akubra-backed Kramerius. It implements only
   * ImageSizeSource, so there is no cache to consult and nothing to hand work to.
   */
  private record UnmanagedRepository(FakeRepository delegate) implements Repository {

    @Override
    public DocumentSource documents() {
      return delegate.documents();
    }

    @Override
    public ImageSizeSource sizes() {
      return delegate::sizes;
    }

    @Override
    public cz.mzk.iiif.document.ContentLocator urls() {
      return delegate.urls();
    }

    @Override
    public cz.mzk.iiif.document.LibraryProfile library() {
      return delegate.library();
    }

    @Override
    public java.util.Optional<ManagedImageSizeSource> managed() {
      return java.util.Optional.empty();
    }
  }
}
