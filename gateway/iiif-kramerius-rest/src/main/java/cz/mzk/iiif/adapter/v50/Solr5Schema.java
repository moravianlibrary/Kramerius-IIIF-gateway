/*
 * Kramerius 5's index, which shares almost no field name with Kramerius 7.
 *
 * Every name here was read off live instances (ndk.cz 5.8.4.4 and kramerius.vugtk.cz 5.4.2), not
 * inferred. The differences are structural rather than cosmetic:
 *
 *   uniqueKey       PID        (uppercase)          vs pid
 *   model           fedora.model                    vs model
 *   title           dc.title                        vs title.search
 *   author          dc.creator                      vs authors
 *   parent          parent_pid  (MULTI-VALUED)      vs own_parent.pid
 *   ordering        rels_ext_index (MULTI-VALUED)   vs page.index
 *   accessibility   dostupnost                      vs accessibility
 *   licences        dnnt-labels                     vs licenses
 *   page count      pages_count                     vs count_page
 *   timestamps      modified_date / created_date    vs modified / created
 *   tiles           viewable                        vs has_tiles
 *   page number     details, "number##type" packed  vs page.number + page.type
 *
 * The multi-valued fields are the expensive difference. Document order lives in rels_ext_index, which
 * Solr will not sort on at all, so child walks page with start/rows and order client-side — see
 * supportsCursorMark. Walks with a single-valued sort, the dimension sync among them, cursor normally.
 */
package cz.mzk.iiif.adapter.v50;

import java.util.List;

import cz.mzk.iiif.adapter.SolrSchema;

public final class Solr5Schema extends SolrSchema {

  /** K5's Solr endpoint. K7 moved it under /client/. */
  public static final String SEARCH_PATH = "/search/api/v5.0/search";

  private static final List<String> DOCUMENT_FIELDS_5 =
      List.of(
          "PID",
          "fedora.model",
          "dc.title",
          "dc.creator",
          "root_title",
          "root_pid",
          "parent_pid",
          "model_path",
          "datum_str",
          "datum_begin",
          "language",
          "dostupnost",
          "dnnt-labels",
          "pages_count",
          "modified_date",
          "img_full_mime",
          "details");

  private static final List<String> PAGE_FIELDS_5 =
      List.of(
          "PID",
          "fedora.model",
          "details",
          "viewable",
          "rels_ext_index",
          "img_full_mime",
          "modified_date");

  @Override
  public List<String> documentFields() {
    return DOCUMENT_FIELDS_5;
  }

  @Override
  public List<String> pageFields() {
    return PAGE_FIELDS_5;
  }

  @Override
  public List<String> componentFields() {
    return List.of("PID", "fedora.model", "dc.title", "details", "rels_ext_index", "modified_date");
  }

  /**
   * False, but narrower than the name suggests: K5 cannot cursor a walk ORDERED BY DOCUMENT ORDER.
   *
   * <p>Its only ordering field, rels_ext_index, is multi-valued, and Solr refuses to sort on one —
   * verified live, `sort=rels_ext_index asc` is a 400 on ndk.cz, with or without a tiebreaker. That
   * is also why v1 had a special case comparing rels_ext_index against model_path. So walkChildren
   * pages with start/rows and sorts client-side, and promotes() asks a query where K7 reads a count
   * field.
   *
   * <p>It does NOT mean K5's Solr lacks cursorMark. Given a single-valued sort it works fine: the
   * sync walk sorts by `modified_date asc, PID asc` and cursors happily on 7 of the 8 reachable
   * instances. SolrClient.window sends cursorMark unconditionally and never consults this flag,
   * which is correct. `mendelu` 5.3.2 is the one instance that returns no nextCursorMark at all,
   * and is excluded from service rather than worked around — see IIIF_EXCLUDED_LIBRARIES.
   */
  @Override
  public boolean supportsCursorMark() {
    return false;
  }

  @Override
  public String searchPath() {
    return SEARCH_PATH;
  }

  @Override
  public String orderField() {
    return "rels_ext_index";
  }

  @Override
  public String childFilter(String parentPid, String kind, boolean fostered) {
    // K5 has no foster relationship: a page belongs to exactly one parent.
    return "parent_pid:" + quote(parentPid);
  }

  /**
   * Sorting is done client-side.
   *
   * <p>Returning null tells the walk to ask for no sort at all, because asking Solr to sort on a
   * multi-valued field is an error rather than a degradation. The caller orders by rels_ext_index
   * once the rows are in hand.
   */
  @Override
  public String childSort(String kind, String uniqueKey) {
    return null;
  }

  @Override
  public String pageModel() {
    return "page";
  }

  /** `viewable` is the closest thing K5 indexes, and it decides whether an image can be shown. */
  @Override
  public String tiledFilter() {
    return "viewable:true";
  }

  /** K5's accessibility field, with the same `public` value. */
  @Override
  public String publicFilter() {
    return "dostupnost:public";
  }

  @Override
  public String modifiedField() {
    return "modified_date";
  }

  @Override
  public String pidField() {
    return "PID";
  }

  @Override
  public String parentField() {
    return "parent_pid";
  }

  @Override
  public String modelField() {
    return "fedora.model";
  }
}
