/*
 * One Kramerius library, reached over its REST API.
 *
 * This is where the adapter, the Solr client and the size source become the Repository that level 3
 * assembles bundles from. It holds no manifest knowledge at all: its whole job is to answer the SPI
 * with records whose shape and flags are already resolved.
 *
 * Which parent field a child hangs off is the one genuinely subtle thing here. An article does not OWN
 * its pages — the issue does, and the article is fostered onto a page range within it — so
 * own_parent.pid returns nothing for one. That is why v1 could not have served an article even if its
 * dispatch table had listed the model, and why fostersPages is in the model table.
 */
package cz.mzk.iiif.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import cz.mzk.iiif.adapter.v50.Kramerius5Order;
import cz.mzk.iiif.document.ContentLocator;
import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.document.LibraryProfile;
import cz.mzk.iiif.kramerius.ChildKind;
import cz.mzk.iiif.spi.CatalogueLink;
import cz.mzk.iiif.spi.DocumentSource;
import cz.mzk.iiif.spi.ImageSizeSource;
import cz.mzk.iiif.spi.Repository;

public final class KrameriusRepository implements Repository, DocumentSource {

  private final KrameriusAdapter adapter;
  private final SolrClient solr;
  private final RecordMapper records;
  private final ImageSizeSource sizes;
  private final ContentLocator urls;
  private final LibraryProfile library;
  private final CatalogueLink catalogue;

  public KrameriusRepository(
      KrameriusAdapter adapter,
      SolrClient solr,
      ImageSizeSource sizes,
      ContentLocator urls,
      LibraryProfile library,
      CatalogueLink catalogue,
      RecordMapper records) {
    this.adapter = adapter;
    this.solr = solr;
    this.records = records;
    this.sizes = sizes;
    this.urls = urls;
    this.library = library;
    this.catalogue = catalogue;
  }

  @Override
  public DocumentSource documents() {
    return this;
  }

  @Override
  public ImageSizeSource sizes() {
    return sizes;
  }

  @Override
  public ContentLocator urls() {
    return urls;
  }

  @Override
  public LibraryProfile library() {
    return library;
  }

  @Override
  public Optional<CatalogueLink> catalogue() {
    return Optional.ofNullable(catalogue);
  }

  @Override
  public Optional<Lookup> find(String pid) {
    Optional<JsonNode> doc =
        solr.findByPid(records.pidField(), pid, adapter.schema().documentFields());
    if (doc.isEmpty()) {
      return Optional.empty();
    }

    String model = field(doc.get(), modelField()).orElse("");
    return records
        .document(doc.get(), promotes(doc.get(), model))
        .<Lookup>map(Lookup.Document::new)
        // The pid exists — it is a page, a track, a sound unit, or a model this table does not
        // list. Not the same fact as "no such object", and not the same answer.
        .or(() -> Optional.of(new Lookup.UnsupportedModel(model)));
  }

  /**
   * Whether this document has the children that would promote it to another model.
   *
   * <p>A monograph with volumes is a multi-volume monograph. The count is already in the document
   * we just fetched, so the promotion costs no extra query.
   */
  /** K5 spells the model `fedora.model`; K7 spells it `model`. The mapper knows which. */
  private String modelField() {
    return adapter.schema().modelField();
  }

  private static Optional<String> field(JsonNode doc, String name) {
    JsonNode node = doc.get(name);
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    String value = node.isArray() ? (node.isEmpty() ? null : node.get(0).asText()) : node.asText();
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
  }

  private boolean promotes(JsonNode doc, String model) {
    Optional<DocumentModel> found = adapter.models().find(model);
    if (found.isEmpty() || !found.get().promotes()) {
      return false;
    }
    // K5 indexes no per-kind child counts at all, so a promotion there has to be answered by
    // asking.
    // One query for a model that would otherwise be typed wrong is a fair trade, and monograph is
    // the
    // only model that promotes.
    ChildKind kind = found.get().promoteWhen().orElseThrow();
    if (!adapter.schema().supportsCursorMark()) {
      return !children(SolrRecords.text(doc, records.pidField()).orElse(""), kind).isEmpty();
    }

    return switch (kind) {
      case MONOGRAPH_UNITS -> SolrRecords.integer(doc, "count_monograph_unit").orElse(0) > 0;
      case PERIODICAL_VOLUMES -> SolrRecords.integer(doc, "count_volume").orElse(0) > 0;
      case PERIODICAL_ISSUES -> SolrRecords.integer(doc, "count_issue").orElse(0) > 0;
      case SOUND_UNITS -> SolrRecords.integer(doc, "count_sound_unit").orElse(0) > 0;
      case PAGES -> SolrRecords.integer(doc, "count_page").orElse(0) > 0;
      case TRACKS, CONVOLUTE_PARTS -> false;
    };
  }

  @Override
  public List<DocumentRecord> children(String pid, ChildKind kind) {
    SolrSchema schema = adapter.schema();
    boolean fostered = kind == ChildKind.PAGES && fostersPages(pid);

    String filter = schema.childFilter(pid, kind, fostered) + " AND " + schema.modelFilter(kind);
    return map(
        walkChildren(filter, schema.childSort(kind, solr.uniqueKey()), fieldsFor(schema, kind)),
        kind);
  }

  /** The same filter {@link #children} builds for pages, narrowed to the tiled ones and counted. */
  @Override
  public int tiledPageCount(String pid) {
    SolrSchema schema = adapter.schema();
    boolean fostered = fostersPages(pid);
    String filter =
        schema.childFilter(pid, ChildKind.PAGES, fostered)
            + " AND "
            + schema.modelFilter(ChildKind.PAGES)
            + " AND "
            + schema.tiledFilter();
    return solr.count(filter);
  }

  /**
   * Children that are documents in their own right, by exclusion rather than by expected model.
   *
   * <p>One query: the parent relation, minus the component models. Asking "what is actually under
   * here" is the only way to serve a nesting the table did not predict, and the table cannot
   * predict them all — the repository contains monographs inside monographs and volumes holding
   * pages.
   */
  @Override
  public List<DocumentRecord> childDocuments(String pid) {
    SolrSchema schema = adapter.schema();
    String components =
        adapter.models().components().stream()
            .map(model -> "-" + schema.modelField() + ":" + model)
            .collect(java.util.stream.Collectors.joining(" AND "));
    String filter =
        schema.childFilter(pid, ChildKind.PERIODICAL_VOLUMES, false)
            + (components.isEmpty() ? "" : " AND " + components);
    // No kind, so no per-kind sort: title order, the same as a virtual collection's members.
    return map(
        walkChildren(
            filter, "title.sort asc, " + solr.uniqueKey() + " asc", schema.documentFields()),
        null);
  }

  @Override
  public List<DocumentRecord> collectionMembers(String pid) {
    SolrSchema schema = adapter.schema();
    // Collection membership is a filter, not a parent relation, and a member may be any model at
    // all — including a page, which is why the mapper has to tolerate one here.
    return map(
        walkChildren(
            "in_collections.direct:" + SolrSchema.quote(pid),
            "title.sort asc, " + solr.uniqueKey() + " asc",
            schema.documentFields()),
        null);
  }

  /**
   * Walks children with whichever paging this Kramerius supports.
   *
   * <p>K7 uses cursorMark, which is stable while the index changes underneath. K5 cannot do so
   * HERE: children come back in document order, its only ordering field is rels_ext_index, and that
   * is multi-valued — asking Solr to sort on it is a 400 rather than a degradation. So this walk
   * pages with start/rows and sorts client-side. K5 cursors fine on a single-valued sort; the sync
   * walk does exactly that.
   */
  private List<JsonNode> walkChildren(String filter, String sort, List<String> fields) {
    if (adapter.schema().supportsCursorMark()) {
      return solr.walk(filter, sort, fields, -1);
    }
    List<JsonNode> docs = new ArrayList<>(solr.pageThrough(filter, null, fields, solr.maxItems()));
    docs.sort(java.util.Comparator.comparingInt(Kramerius5Order::of));
    return List.copyOf(docs);
  }

  private static List<String> fieldsFor(SolrSchema schema, ChildKind kind) {
    return switch (kind) {
      case PAGES -> schema.pageFields();
      case TRACKS, SOUND_UNITS -> schema.componentFields();
      default -> schema.documentFields();
    };
  }

  private List<DocumentRecord> map(List<JsonNode> docs, ChildKind kind) {
    List<DocumentRecord> mapped = new ArrayList<>(docs.size());
    for (JsonNode doc : docs) {
      if (kind == ChildKind.PAGES) {
        mapped.add(records.page(doc));
      } else if (kind == ChildKind.TRACKS || kind == ChildKind.SOUND_UNITS) {
        // Components have no manifest, so the model table refuses them — correctly. They still
        // belong inside a recording's manifest, which is why they get their own mapping.
        mapped.add(records.component(doc));
      } else {
        records.document(doc, false).ifPresent(mapped::add);
      }
    }
    return List.copyOf(mapped);
  }

  /**
   * Pages modified at or after a timestamp, in `modified` order, for the sync walk.
   *
   * <p>Ordered by the same field the watermark tracks, which is what makes the watermark a valid
   * resume point on its own — see SyncLoop. Only tiled pages, because a page with no tiles has no
   * Image API endpoint to measure.
   */
  public List<PageRef> pagesModifiedSince(java.time.Instant from, int limit) {
    return pageWindow(from, limit, Optional.empty()).pages();
  }

  /**
   * @param exhausted no rows remain for this bound, so the next pass should move the watermark on
   */
  public record PageWindow(List<PageRef> pages, Optional<String> nextCursor, boolean exhausted) {

    /** Copied on the way in, for the same reason as SolrClient.Window. */
    public PageWindow {
      pages = List.copyOf(pages);
    }
  }

  /** The same walk, told where to resume and reporting where it stopped. */
  public PageWindow pageWindow(java.time.Instant from, int limit, Optional<String> cursor) {
    SolrSchema schema = adapter.schema();
    String filter =
        schema.modelField()
            + ":"
            + schema.pageModel()
            + " AND "
            + schema.tiledFilter()
            + " AND "
            + schema.modifiedField()
            + ":["
            + from.toString()
            + " TO *]";

    // Field names from the schema throughout. Every one of these was spelled K7's way, and on a K5
    // instance that does not fail — it returns rows whose pid reads as null, every row is skipped,
    // and the walk reports honestly that it found nothing to do.
    String pidField = schema.pidField();
    String parentField = schema.parentField();
    String modifiedField = schema.modifiedField();

    SolrClient.Window window =
        solr.window(
            filter,
            modifiedField + " asc, " + solr.uniqueKey() + " asc",
            List.of(pidField, parentField, modifiedField),
            limit,
            cursor);

    List<PageRef> refs = new ArrayList<>();
    for (JsonNode doc : window.docs()) {

      String pid = SolrRecords.text(doc, pidField).orElse(null);
      String parent = SolrRecords.text(doc, parentField).orElse(null);
      if (pid == null || parent == null) {
        continue;
      }
      refs.add(new PageRef(pid, parent, SolrRecords.instant(doc, modifiedField)));
    }
    return new PageWindow(List.copyOf(refs), window.nextCursor(), window.exhausted());
  }

  /**
   * A page as the sync walk sees it: which document owns it, and when it last changed.
   *
   * <p>A typed record rather than a raw JsonNode, so Solr's response shape stops at this level like
   * every other Kramerius detail.
   */
  public record PageRef(String pid, String documentPid, Optional<java.time.Instant> modified) {}

  /**
   * Whether this model's pages hang off a foster relationship rather than ownership.
   *
   * <p>Always false on K5, which has no foster relationship at all — an article's pages are found
   * through parent_pid like everything else. One of the few places K5 is simpler than K7.
   */
  private boolean fostersPages(String pid) {
    return solr.findByPid(records.pidField(), pid, List.of(records.pidField(), modelField()))
        .flatMap(doc -> field(doc, modelField()))
        .flatMap(adapter.models()::find)
        .map(DocumentModel::fostersPages)
        .orElse(false);
  }
}
