/*
 * An in-memory Repository, so assembly tests need no network and no Kramerius.
 *
 * This exists to prove the SPI is small enough to fake — if implementing it for a test were hard, it
 * would be hard for Kramerius to implement in-process too, and the seam would be in the wrong place.
 * Every method here is a map lookup.
 *
 * The profile and the URLs come from Fixtures at level 2 rather than being rebuilt here, so a manifest
 * assembled through this repository is comparable to one a level-2 test built by hand.
 */
package cz.mzk.iiif.testing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import cz.mzk.iiif.document.ContentLocator;
import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.document.ImageSize;
import cz.mzk.iiif.document.LibraryProfile;
import cz.mzk.iiif.spi.CatalogueLink;
import cz.mzk.iiif.spi.DocumentSource;
import cz.mzk.iiif.spi.ImageSizeSource;
import cz.mzk.iiif.spi.ManagedImageSizeSource;
import cz.mzk.iiif.spi.Repository;

public final class FakeRepository implements Repository, DocumentSource, ManagedImageSizeSource {

  private LibraryProfile profile;

  private final Map<String, DocumentRecord> documents = new LinkedHashMap<>();
  private final Map<String, String> unsupported = new LinkedHashMap<>();
  private final Map<String, List<DocumentRecord>> children = new LinkedHashMap<>();
  private final Map<String, List<DocumentRecord>> childDocuments = new HashMap<>();
  private final List<DocumentRecord> collectionMembers = new ArrayList<>();
  private final Map<String, ImageSize> cached = new HashMap<>();
  private final Map<String, ImageSize> fetchable = new HashMap<>();
  private CatalogueLink catalogue;

  /** Pids passed to sizes(), so a test can assert that nothing was fetched. */
  private final List<String> fetched = new ArrayList<>();

  private FakeRepository(LibraryProfile profile) {
    this.profile = profile;
  }

  public static FakeRepository of(LibraryProfile profile) {
    return new FakeRepository(profile);
  }

  public static FakeRepository mzk() {
    return of(Fixtures.mzk());
  }

  // Fixture setup

  public FakeRepository with(DocumentRecord... records) {
    for (DocumentRecord record : records) {
      documents.put(record.pid(), record);
    }
    return this;
  }

  /** A real object whose model has no manifest — a page, a track, a sound unit. */
  public FakeRepository withUnsupported(String pid, String model) {
    unsupported.put(pid, model);
    return this;
  }

  public FakeRepository withChildren(String pid, String kind, DocumentRecord... records) {
    children.put(key(pid, kind), List.of(records));
    return with(records);
  }

  /**
   * Children that are documents, whatever their model — what the real adapter finds by excluding
   * the component models rather than by asking for an expected kind.
   */
  public FakeRepository withChildDocuments(String pid, DocumentRecord... records) {
    childDocuments.put(pid, List.of(records));
    return with(records);
  }

  public FakeRepository withCollectionMembers(DocumentRecord... records) {
    collectionMembers.addAll(List.of(records));
    return with(records);
  }

  /** A page whose size the cache already holds — no fetch should happen for it. */
  public FakeRepository withCachedSize(String pid, ImageSize size) {
    cached.put(pid, size);
    return this;
  }

  /** A page whose size the image server would report if asked. */
  public FakeRepository withFetchableSize(String pid, ImageSize size) {
    fetchable.put(pid, size);
    return this;
  }

  /** MZK's real catalogue mapping: two Aleph bases, plus a default for bare system numbers. */
  public FakeRepository withMzkCatalogue() {
    this.catalogue =
        new CatalogueLink(
            "https://vufind.mzk.cz/Record/{id}",
            List.of(
                CatalogueLink.Rule.of("^mzk01-?(\\d{9})$", "MZK01-"),
                CatalogueLink.Rule.of("^mzk03-?(\\d{9})$", "MZK03-"),
                CatalogueLink.Rule.of("^(\\d{9})$", "MZK01-")));
    return this;
  }

  public List<String> fetchedPids() {
    return List.copyOf(fetched);
  }

  // Repository

  @Override
  public DocumentSource documents() {
    return this;
  }

  @Override
  public ImageSizeSource sizes() {
    return this;
  }

  @Override
  public ContentLocator urls() {
    return Fixtures.urls();
  }

  @Override
  public LibraryProfile library() {
    return profile;
  }

  @Override
  public Optional<CatalogueLink> catalogue() {
    return Optional.ofNullable(catalogue);
  }

  // DocumentSource

  @Override
  public Optional<Lookup> find(String pid) {
    DocumentRecord record = documents.get(pid);
    if (record != null) {
      return Optional.of(new Lookup.Document(record));
    }
    String model = unsupported.get(pid);
    return model == null ? Optional.empty() : Optional.of(new Lookup.UnsupportedModel(model));
  }

  private List<DocumentRecord> children(String pid, String kind) {
    return children.getOrDefault(key(pid, kind), List.of());
  }

  @Override
  public List<DocumentRecord> pages(String pid) {
    return children(pid, "PAGES");
  }

  @Override
  public List<DocumentRecord> members(DocumentRecord document) {
    return java.util.stream.Stream.of(
            "MONOGRAPH_UNITS", "PERIODICAL_VOLUMES", "PERIODICAL_ISSUES", "CONVOLUTE_PARTS")
        .flatMap(kind -> children(document.pid(), kind).stream())
        .toList();
  }

  @Override
  public List<DocumentRecord> mediaUnits(String pid) {
    return children(pid, "MEDIA_UNITS");
  }

  @Override
  public List<DocumentRecord> tracks(String soundUnitPid) {
    return children(soundUnitPid, "TRACKS");
  }

  @Override
  public List<DocumentRecord> childDocuments(String pid) {
    return childDocuments.getOrDefault(pid, List.of());
  }

  @Override
  public List<DocumentRecord> collectionMembers(String pid) {
    return List.copyOf(collectionMembers);
  }

  // ManagedImageSizeSource

  @Override
  public Map<String, ImageSize> cached(String documentPid, List<DocumentRecord> pages) {
    Map<String, ImageSize> hits = new LinkedHashMap<>();
    for (DocumentRecord page : pages) {
      ImageSize size = cached.get(page.pid());
      if (size != null) {
        hits.put(page.pid(), size);
      }
    }
    return hits;
  }

  @Override
  public Map<String, ImageSize> sizes(List<String> pagePids) {
    fetched.addAll(pagePids);
    Map<String, ImageSize> found = new LinkedHashMap<>();
    for (String pid : pagePids) {
      ImageSize size = fetchable.get(pid);
      if (size != null) {
        found.put(pid, size);
      }
    }
    return found;
  }

  @Override
  public void store(String documentPid, Map<String, ImageSize> measured) {
    cached.putAll(measured);
  }

  @Override
  public CacheStatus status(String documentPid, List<String> pagePids) {
    int hits = (int) pagePids.stream().filter(cached::containsKey).count();
    return new CacheStatus(pagePids.size(), hits, hits == pagePids.size());
  }

  @Override
  public int cachedCount(String documentPid) {
    // The fake keys everything in one map, so every cached page counts — the fixtures never hold
    // two documents whose pages would need telling apart.
    return cached.size();
  }

  private static String key(String pid, String kind) {
    return pid + " " + kind;
  }
}
