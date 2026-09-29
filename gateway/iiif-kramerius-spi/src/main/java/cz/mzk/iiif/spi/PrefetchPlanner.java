/*
 * Works out which pages a prefetch has to fetch dimensions for.
 *
 * "Prefetch this periodical" means every page of every issue of every volume, so the walk is recursive.
 * The recursion lives here rather than in the worker because it is the same knowledge the manifest
 * builder has — which children a shape needs — and having it in two places would let them drift.
 *
 * NO DEPTH OR SIZE LIMIT, on purpose. The caller records completion per document, so a re-run skips
 * finished subtrees in one lookup each and an interrupted walk resumes rather than restarting. Limits
 * would be protecting against a cost that idempotence already removes.
 *
 * Pure: it reads the document tree and returns a plan. Nothing is fetched or stored here.
 */
package cz.mzk.iiif.spi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.kramerius.IiifStructure;

public final class PrefetchPlanner {

  /** One document's worth of work: the pages whose dimensions can be established. */
  public record DocumentPages(String documentPid, List<String> tiledPagePids) {

    public DocumentPages {
      tiledPagePids = List.copyOf(tiledPagePids);
    }

    public boolean isEmpty() {
      return tiledPagePids.isEmpty();
    }
  }

  private PrefetchPlanner() {}

  /**
   * Every document at or beneath this pid that has tiled pages, in encounter order.
   *
   * <p>Documents with no tiled pages are omitted rather than returned empty: there is nothing to
   * fetch for them, and the caller would only have to filter them out again.
   */
  public static List<DocumentPages> plan(Repository repository, String pid) {
    List<DocumentPages> plan = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>();
    queue.add(pid);

    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      // Kramerius indexes a convolute as its own child, and a document can be fostered into more
      // than
      // one parent, so a plain recursive walk can revisit or loop without this.
      if (!visited.add(current)) {
        continue;
      }

      // A page or a track is not a document to plan for; it is what a plan collects.
      Optional<DocumentRecord> found =
          repository.documents().find(current).flatMap(PrefetchPlanner::record);
      if (found.isEmpty()) {
        continue;
      }
      DocumentRecord document = found.get();

      IiifStructure shape = document.shape();
      if (shape == IiifStructure.PAGED_MANIFEST || shape == IiifStructure.MEDIA_MANIFEST) {
        DocumentPages pages = pagesOf(repository, current);
        if (!pages.isEmpty()) {
          plan.add(pages);
        }
        continue;
      }

      childrenOf(repository, document).forEach(queue::addLast);
    }
    return plan;
  }

  /** The tiled pages of one document, without recursing. */
  public static DocumentPages pagesOf(Repository repository, String pid) {
    List<String> tiled =
        repository.documents().pages(pid).stream()
            .filter(DocumentRecord::hasTiles)
            .map(DocumentRecord::pid)
            .toList();
    return new DocumentPages(pid, tiled);
  }

  private static Optional<DocumentRecord> record(DocumentSource.Lookup lookup) {
    return lookup instanceof DocumentSource.Lookup.Document found
        ? Optional.of(found.record())
        : Optional.empty();
  }

  private static List<String> childrenOf(Repository repository, DocumentRecord document) {
    String pid = document.pid();
    List<DocumentRecord> children =
        switch (document.shape()) {
          case CHILD_COLLECTION -> repository.documents().members(document);
          case VIRTUAL_COLLECTION -> repository.documents().collectionMembers(pid);
          // The page-bearing shapes are handled by the caller and never reach here.
          case PAGED_MANIFEST, MEDIA_MANIFEST -> List.of();
        };
    return children.stream().map(DocumentRecord::pid).toList();
  }
}
