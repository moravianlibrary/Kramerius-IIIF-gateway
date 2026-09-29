/*
 * The recursive walk a prefetch performs.
 *
 * The cycle test is the one that matters: Kramerius really does index a convolute as its own child, and
 * a document really can be fostered into more than one parent, so a naive recursion loops forever on
 * live data. It is cheap to assert and expensive to discover in production.
 */
package cz.mzk.iiif.spi;

import static cz.mzk.iiif.testing.Fixtures.document;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.kramerius.IiifStructure;
import cz.mzk.iiif.testing.FakeRepository;

class PrefetchPlannerTest {

  private static DocumentRecord page(String pid, boolean tiled) {
    return document(pid, "page", IiifStructure.PAGED_MANIFEST).hasTiles(tiled).build();
  }

  /** A document that collects children of one kind. */
  private static DocumentRecord collects(String pid, String model, String kind) {
    return document(pid, model, IiifStructure.CHILD_COLLECTION).build();
  }

  /** A document that holds pages directly. */
  private static DocumentRecord holdsPages(String pid, String model) {
    return document(pid, model, IiifStructure.PAGED_MANIFEST).build();
  }

  @Test
  @DisplayName("a plain document yields its tiled pages, and skips those without tiles")
  void singleDocument() {
    FakeRepository repository =
        FakeRepository.mzk()
            .with(holdsPages("uuid:book", "monograph"))
            .withChildren("uuid:book", "PAGES", page("uuid:p1", true), page("uuid:p2", false));

    List<PrefetchPlanner.DocumentPages> plan = PrefetchPlanner.plan(repository, "uuid:book");

    assertThat(plan).hasSize(1);
    assertThat(plan.get(0).tiledPagePids()).containsExactly("uuid:p1");
  }

  @Test
  @DisplayName("a periodical is walked down to the pages of every issue")
  void recursesThroughVolumesAndIssues() {
    FakeRepository repository =
        FakeRepository.mzk()
            .with(collects("uuid:per", "periodical", "PERIODICAL_VOLUMES"))
            .withChildren(
                "uuid:per",
                "PERIODICAL_VOLUMES",
                collects("uuid:vol", "periodicalvolume", "PERIODICAL_ISSUES"))
            .withChildren(
                "uuid:vol",
                "PERIODICAL_ISSUES",
                holdsPages("uuid:iss1", "periodicalitem"),
                holdsPages("uuid:iss2", "periodicalitem"))
            .withChildren("uuid:iss1", "PAGES", page("uuid:a", true))
            .withChildren("uuid:iss2", "PAGES", page("uuid:b", true));

    List<PrefetchPlanner.DocumentPages> plan = PrefetchPlanner.plan(repository, "uuid:per");

    assertThat(plan).hasSize(2);
    assertThat(plan.stream().map(PrefetchPlanner.DocumentPages::documentPid))
        .containsExactly("uuid:iss1", "uuid:iss2");
  }

  @Test
  @DisplayName("a document listed as its own child does not loop")
  void cycleTerminates() {
    FakeRepository repository =
        FakeRepository.mzk()
            .with(collects("uuid:conv", "convolute", "CONVOLUTE_PARTS"))
            .withChildren(
                "uuid:conv",
                "CONVOLUTE_PARTS",
                collects("uuid:conv", "convolute", "CONVOLUTE_PARTS"),
                holdsPages("uuid:part", "monograph"))
            .withChildren("uuid:part", "PAGES", page("uuid:p", true));

    List<PrefetchPlanner.DocumentPages> plan = PrefetchPlanner.plan(repository, "uuid:conv");

    assertThat(plan).hasSize(1);
    assertThat(plan.get(0).documentPid()).isEqualTo("uuid:part");
  }

  @Test
  @DisplayName("a document with nothing to fetch yields no work rather than an empty entry")
  void noTiledPages() {
    FakeRepository repository =
        FakeRepository.mzk()
            .with(
                document("uuid:pdf", "monograph", IiifStructure.PAGED_MANIFEST)
                    .hasPdf(true)
                    .build());

    assertThat(PrefetchPlanner.plan(repository, "uuid:pdf")).isEmpty();
  }

  @Test
  @DisplayName("a pid that is a page, not a document, contributes nothing")
  void unsupportedModel() {
    FakeRepository repository = FakeRepository.mzk().withUnsupported("uuid:p", "page");

    assertThat(PrefetchPlanner.plan(repository, "uuid:p")).isEmpty();
  }
}
