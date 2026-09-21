/*
 * Where documents come from. Implemented over the Kramerius REST API, or in-process against Kramerius'
 * own Solr client — the library does not care which.
 *
 * Ordering is the implementation's job, not the caller's: the sort field differs by child kind and by
 * Kramerius version, and on K7 it has to end in the Solr uniqueKey for cursorMark paging to be stable
 * (compositeId on SolrCloud, pid on standalone — see docs/DESIGN.md). Callers receive children already
 * in reading order.
 */
package cz.mzk.iiif.spi;

import java.util.List;
import java.util.Optional;

import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.kramerius.ChildKind;

public interface DocumentSource {

  /**
   * What this library holds at that pid.
   *
   * <p>Three outcomes rather than two, because "exists but has no manifest" is not an absence. A
   * page, a track and a sound unit are all real objects that no v3 document represents — they are
   * parts of one. Only the adapter can tell them apart, since it owns the model table, and
   * collapsing them into an empty Optional would make the gateway answer 404 "no such object" for
   * an object that is there.
   */
  Optional<Lookup> find(String pid);

  /** The outcome of a lookup that found something. */
  sealed interface Lookup {

    /** A model that maps to a shape, already resolved. */
    record Document(DocumentRecord record) implements Lookup {}

    /** A real object whose model has no v3 representation. */
    record UnsupportedModel(String model) implements Lookup {}
  }

  /**
   * The children of an object, in reading order.
   *
   * <p>For {@link ChildKind#PAGES} the implementation must consult the foster relationship rather
   * than ownership when the parent's model calls for it — an article's pages belong to its issue.
   * See {@code DocumentModel.pagesAreFostered()}.
   */
  List<DocumentRecord> children(String pid, ChildKind kind);

  /**
   * How many of an object's pages have an Image API endpoint — the number a dimension cache can
   * ever hold for it.
   *
   * <p>Separate from {@link #children} because the answer is a number, and an implementation backed
   * by an index can say it in one request no matter how large the document is. The default counts
   * the children, which is what every caller used to do: correct, and up to eight round trips for a
   * 3568-page document on a route whose whole point is to be cheap.
   *
   * <p>Not `count_page`, which the document record already carries: that counts every page, and a
   * page with no tiles has no endpoint to ask, so it can never be cached. Comparing a cache against
   * count_page would report a document with untiled pages as permanently incomplete.
   */
  default int tiledPageCount(String pid) {
    return (int) children(pid, ChildKind.PAGES).stream().filter(DocumentRecord::hasTiles).count();
  }

  /**
   * Every child that is itself a document, whatever its model — not pages, tracks or sound units.
   *
   * <p>Asked only when the children a document's model declares turn out not to exist, which is why
   * the default is empty: an implementation that cannot answer it simply keeps the old behaviour.
   *
   * <p>It exists because the model table names ONE child kind per model, and the real repository
   * does not honour that. Faceting `own_model_path` over MZK's 77.8 million documents shows
   * `periodical/periodicalvolume/page` on 84,559 of them — a year bound and digitised as one run
   * rather than as issues — plus `monograph/monograph/page`, `sheetmusic/sheetmusic/page`,
   * `map/map/page` and `periodical/monographunit/page`. Every one of those served an empty Manifest
   * or an empty Collection: a document that exists, has content, and renders as nothing.
   */
  default List<DocumentRecord> childDocuments(String pid) {
    return List.of();
  }

  /**
   * The members of a virtual collection.
   *
   * <p>Separate from {@link #children} because collection membership is a filter query rather than
   * a parent relation, and a member may be any model at all.
   */
  List<DocumentRecord> collectionMembers(String pid);
}
