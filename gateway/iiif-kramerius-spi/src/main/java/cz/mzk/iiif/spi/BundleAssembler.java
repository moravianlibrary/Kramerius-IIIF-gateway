/*
 * Gathers what a manifest needs, so the builder can be handed it.
 *
 * This is the level-3 half of the split: the builder is a pure function of a DocumentBundle, and this is
 * what fills one from a Repository. An application that already has its records skips this entirely and
 * fills a bundle itself — which is the whole reason the builder was made to take one.
 *
 * Which children to gather comes from the document's own resolved shape, so nothing here needs the model
 * table. That is what lets a version-specific table live with the adapters without this level knowing.
 */
package cz.mzk.iiif.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.document.DocumentBundle;
import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.kramerius.IiifStructure;

public final class BundleAssembler {

  private static final Logger LOG = LoggerFactory.getLogger(BundleAssembler.class);

  /** What came of asking for a document. Neither failure is a server fault. */
  public sealed interface Result {

    /**
     * @param dimensionsComplete false when pages were deliberately left unresolved, so the manifest
     *     is provisional and must not be cached as final
     * @param unresolvedPages the pids left for the worker, empty when complete
     */
    record Found(DocumentBundle bundle, boolean dimensionsComplete, List<String> unresolvedPages)
        implements Result {

      public Found {
        unresolvedPages = List.copyOf(unresolvedPages);
      }
    }

    /** The library does not hold this pid. */
    record NotFound(String library, String pid) implements Result {}

    /** The pid exists but its model has no v3 representation — a page, a track, a sound unit. */
    record Unsupported(String library, String pid, String model) implements Result {}
  }

  private BundleAssembler() {}

  public static Result assemble(Repository repository, DimensionPolicy policy, String pid) {
    String library = repository.library().code();

    Optional<DocumentSource.Lookup> found = repository.documents().find(pid);
    if (found.isEmpty()) {
      return new Result.NotFound(library, pid);
    }
    if (found.get() instanceof DocumentSource.Lookup.UnsupportedModel unsupported) {
      return new Result.Unsupported(library, pid, unsupported.model());
    }
    DocumentRecord declared = ((DocumentSource.Lookup.Document) found.get()).record();

    // Before the bundle is built, because the builder types the output from the record's shape: a
    // volume gathered as pages but still shaped CHILD_COLLECTION comes out as an empty Collection
    // with the pages thrown away. The shape has to be corrected on the record, not just in the
    // gathering.
    DocumentRecord document = actualShape(repository, declared);

    DocumentBundle.Builder bundle =
        DocumentBundle.of(repository.library(), repository.urls(), document);

    repository
        .catalogue()
        .flatMap(link -> document.systemNumber().flatMap(link::urlFor))
        .ifPresent(bundle::catalogueUrl);

    return switch (document.shape()) {
      case PAGED_MANIFEST -> withPages(repository, policy, bundle, pid);
      case MEDIA_MANIFEST -> withMediaUnits(repository, policy, bundle, pid);
      case CHILD_COLLECTION -> withMembers(repository, bundle, document);
      case VIRTUAL_COLLECTION ->
          complete(bundle.members(repository.documents().collectionMembers(pid)).build());
    };
  }

  /**
   * The shape the document turns out to have, which is not always the one its model predicts.
   *
   * <p>THE MODEL TABLE NAMES ONE CHILD KIND PER MODEL, AND THE REPOSITORY DOES NOT OBLIGE. Faceting
   * `own_model_path` over MZK's 77.8 million documents: `periodical/periodicalvolume/page` on
   * 84,559 of them — a year bound and digitised as one run rather than as issues — plus
   * `monograph/monograph/page`, `sheetmusic/sheetmusic/page`, `map/map/page` and
   * `periodical/monographunit/page`. Every one of those served an empty Manifest or an empty
   * Collection: valid IIIF describing nothing, which is the defect this rewrite exists to remove.
   *
   * <p>Costs one query, and only when the declared children are absent — so nothing is paid on the
   * documents that are what they claim to be. When there is nothing underneath either, the empty
   * answer was the truth and the shape stands.
   */
  private static DocumentRecord actualShape(Repository repository, DocumentRecord declared) {
    String pid = declared.pid();
    return switch (declared.shape()) {
      case CHILD_COLLECTION -> {
        if (!repository.documents().members(declared).isEmpty()) {
          yield declared;
        }
        if (!repository.documents().pages(pid).isEmpty()) {
          LOG.debug("{}: no {} but it has pages; serving it as a manifest", pid, "members");
          yield declared.withShape(IiifStructure.PAGED_MANIFEST);
        }
        // Neither the declared relation nor pages, but children all the same: a monograph whose
        // child is another monograph rather than a monographunit (`monograph/monograph/page`,
        // 1,254 pages at MZK). Still a collection — of whatever is actually under it.
        if (repository.documents().childDocuments(pid).isEmpty()) {
          yield declared;
        }
        LOG.debug("{}: no {} but other child documents; collecting those", pid, "members");
        yield declared.withShape(IiifStructure.CHILD_COLLECTION);
      }
      case PAGED_MANIFEST -> {
        if (!repository.documents().pages(pid).isEmpty()) {
          yield declared;
        }
        List<DocumentRecord> members = repository.documents().childDocuments(pid);
        if (members.isEmpty()) {
          yield declared;
        }
        LOG.debug(
            "{}: no pages but {} child documents; serving it as a collection", pid, members.size());
        yield declared.withShape(IiifStructure.CHILD_COLLECTION);
      }
      // A sound recording gathers units AND pages, so an empty half is not a wrong shape; a virtual
      // collection's membership is a filter rather than a parent relation and cannot be confused.
      case MEDIA_MANIFEST, VIRTUAL_COLLECTION -> declared;
    };
  }

  private static Result withPages(
      Repository repository, DimensionPolicy policy, DocumentBundle.Builder bundle, String pid) {

    List<DocumentRecord> pages = repository.documents().pages(pid);
    PageDimensions.Resolved sizes = PageDimensions.resolve(repository, policy, pid, pages);
    return new Result.Found(
        bundle.pages(pages).sizes(sizes.sizes()).build(), sizes.complete(), sizes.unresolved());
  }

  /**
   * The members a CHILD_COLLECTION document collects — first through its declared relation, then
   * through every direct child when that relation has no results.
   */
  private static Result withMembers(
      Repository repository, DocumentBundle.Builder bundle, DocumentRecord document) {

    List<DocumentRecord> members = repository.documents().members(document);
    if (members.isEmpty()) {
      members = repository.documents().childDocuments(document.pid());
    }
    return complete(bundle.members(members).build());
  }

  private static Result withMediaUnits(
      Repository repository, DimensionPolicy policy, DocumentBundle.Builder bundle, String pid) {

    List<DocumentRecord> pages = repository.documents().pages(pid);
    PageDimensions.Resolved sizes = PageDimensions.resolve(repository, policy, pid, pages);

    List<DocumentBundle.MediaUnit> units = new ArrayList<>();
    for (DocumentRecord unit : repository.documents().mediaUnits(pid)) {
      units.add(new DocumentBundle.MediaUnit(unit, repository.documents().tracks(unit.pid())));
    }

    return new Result.Found(
        bundle.pages(pages).mediaUnits(units).sizes(sizes.sizes()).build(),
        sizes.complete(),
        sizes.unresolved());
  }

  private static Result complete(DocumentBundle bundle) {
    return new Result.Found(bundle, true, List.of());
  }
}
