/*
 * Which Solr fields exist at one version floor, and how to ask for them.
 *
 * This is where the 7.1.4 boundary actually lives. Probed across eight live instances (see
 * docs/DESIGN.md), exactly four fields we care about appear there: has_alto_content,
 * has_text_ocr_content, coords.is_point and count_volume. Everything else we use — has_tiles,
 * page.placement, licenses, count_page, id_sysno, page.index, track.length, modified, and all four
 * coords.bbox fields — is present from 7.0 up.
 *
 * So Kramerius714 overrides three things and inherits the rest. The fallbacks in Kramerius70 are not
 * throwaway work either: K5 needs the same ones.
 *
 * Sorts end in the Solr uniqueKey because cursorMark is undefined without a total ordering. Which key
 * that is depends on the DEPLOYMENT, not the version — compositeId on SolrCloud, pid on standalone — so
 * it is passed in rather than declared here. See docs/DESIGN.md.
 */
package cz.mzk.iiif.adapter;

import java.util.List;

import cz.mzk.iiif.kramerius.ChildKind;

public class SolrSchema {

  /** Fields every K7 instance has, requested for any document. */
  protected static final List<String> DOCUMENT_FIELDS =
      List.of(
          "pid",
          "model",
          "title.search",
          "root.title",
          "own_parent.pid",
          "own_parent.model",
          "own_parent.title",
          "authors",
          "publishers.facet",
          "publication_places.facet",
          "date.str",
          "date.min",
          "part.number.str",
          "part.name",
          "languages.facet",
          "id_sysno",
          "licenses",
          "licenses_of_ancestors",
          "contains_licenses",
          "accessibility",
          "collection.desc",
          "count_page",
          "count_sound_unit",
          // Requesting a field the instance does not have is harmless in fl — it simply does not
          // come
          // back. That is NOT true of fq or sort, which is why the 7.1.4 boundary matters for those
          // and
          // these can be asked for unconditionally.
          "count_monograph_unit",
          "count_issue",
          "coords.bbox.corner_sw",
          "coords.bbox.corner_ne",
          "modified",
          "ds.img_full.mime");

  /** Fields requested for a page or a track. */
  protected static final List<String> PAGE_FIELDS =
      List.of(
          "pid",
          "model",
          "page.number",
          "page.type",
          "page.placement",
          "page.index",
          "has_tiles",
          "track.length",
          "modified");

  /**
   * Fields for a component — a sound unit or a track.
   *
   * <p>Separate from pageFields because a component is labelled by its title rather than by a page
   * number, and separate from documentFields because it is not a document and most of those fields
   * do not apply to one.
   */
  protected static final List<String> COMPONENT_FIELDS =
      List.of("pid", "model", "title.search", "track.length", "modified");

  public List<String> componentFields() {
    return COMPONENT_FIELDS;
  }

  public String pageModel() {
    return "page";
  }

  /**
   * Matches pages that have an Image API endpoint, and so a size worth asking for.
   *
   * <p>Owned by the schema because K5 indexes no has_tiles at all — see Solr5Schema. Two queries
   * used to spell this out inline as `has_tiles:true`, which silently returned nothing on a K5
   * instance: the field is absent, so the filter excludes every page, and a K5 sync walk found no
   * work to do rather than failing in a way anybody would notice.
   */
  public String tiledFilter() {
    return "has_tiles:true";
  }

  /**
   * Matches documents anyone may fetch without credentials.
   *
   * <p>For probing, where a restricted page is worse than no page: its 403 is indistinguishable
   * from "this server has no Image API" and from "this server speaks Image API 2", so a probe that
   * lands on one draws the wrong conclusion about the whole library. K5 spells the field
   * `dostupnost`.
   */
  public String publicFilter() {
    return "accessibility:public";
  }

  /**
   * The field the sync watermark tracks: when the object, and so possibly its image, last changed.
   *
   * <p>K5 spells it modified_date. Same reason as above, same consequence if hardcoded.
   */
  public String modifiedField() {
    return "modified";
  }

  /** The uniqueKey field by name. K5 spells it in capitals. */
  public String pidField() {
    return "pid";
  }

  /** The field naming a page's owning document. K5 has no foster relationship, so it has one. */
  public String parentField() {
    return "own_parent.pid";
  }

  /**
   * Whether this Solr can be walked with cursorMark.
   *
   * <p>True for K7, whose dedicated *.sort fields are single-valued. False for K5, whose only
   * ordering field is multi-valued — and cursorMark requires a total ordering on single-valued
   * fields.
   */
  public boolean supportsCursorMark() {
    return true;
  }

  /** Where this Kramerius answers Solr queries. K5 moved it. */
  public String searchPath() {
    return "/search/api/client/v7.0/search";
  }

  /** The field a model name is matched against. K5 spells it `fedora.model`. */
  public String modelField() {
    return "model";
  }

  /**
   * Restricts a child query to the models that kind can contain.
   *
   * <p>A convolute's parts are whatever they are, so that one matches everything and lets the model
   * table decide what each becomes.
   */
  public String modelFilter(ChildKind kind) {
    String field = modelField();
    return switch (kind) {
      case PAGES -> field + ":" + pageModel();
      case TRACKS -> field + ":track";
      case SOUND_UNITS -> field + ":soundunit";
      case MONOGRAPH_UNITS -> field + ":monographunit";
      case PERIODICAL_VOLUMES -> field + ":periodicalvolume";
      case PERIODICAL_ISSUES -> field + ":(periodicalitem OR supplement)";
      case CONVOLUTE_PARTS -> "*:*";
    };
  }

  /** The field a document's ordering index lives in, for the client-side sort K5 forces. */
  public String orderField() {
    return "page.index";
  }

  /** Present from 7.1.4. Below it the ALTO stream has to be probed per document. */
  public boolean hasAltoField() {
    return false;
  }

  /** Present from 7.1.4. Below it a point is derived from coincident bbox corners. */
  public boolean hasIsPointField() {
    return false;
  }

  /**
   * Present from 7.1.4. Below it there is no expected child total to check a walk against, so the
   * walk pages until the cursor repeats and cannot report drift.
   */
  public boolean hasVolumeCountField() {
    return false;
  }

  public List<String> documentFields() {
    return DOCUMENT_FIELDS;
  }

  public List<String> pageFields() {
    return PAGE_FIELDS;
  }

  /** The filter query selecting one kind of child. */
  public String childFilter(String parentPid, ChildKind kind, boolean fostered) {
    String parent = fostered ? "foster_parents.pids" : "own_parent.pid";
    return parent + ":" + quote(parentPid);
  }

  /**
   * The sort for one child kind, with the uniqueKey appended.
   *
   * <p>Sorting in Solr is what removes v1's multi-valued rels_ext_index special case entirely: the
   * dedicated *.sort fields are single-valued, which multi-valued fields are not, and cursorMark
   * requires single-valued sort fields.
   */
  public String childSort(ChildKind kind, String uniqueKey) {
    String primary =
        switch (kind) {
          case PAGES -> "page.index asc";
          case MONOGRAPH_UNITS -> "part.number.sort asc";
          case PERIODICAL_VOLUMES -> "part.number.sort asc, date.min asc";
          case PERIODICAL_ISSUES -> "date.min asc, issue.type.sort asc";
          case SOUND_UNITS, TRACKS, CONVOLUTE_PARTS -> "rels_ext_index.sort asc";
        };
    return primary + ", " + uniqueKey + " asc";
  }

  /**
   * Escapes a value used in a Solr query.
   *
   * <p>v1 interpolated the raw path segment straight into q=, so a crafted pid could inject query
   * clauses. Quoting and escaping is the whole fix.
   */
  public static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
