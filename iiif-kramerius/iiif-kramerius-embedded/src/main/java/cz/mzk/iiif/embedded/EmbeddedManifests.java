/*
 * Building a manifest inside Kramerius, using level 2 and nothing else.
 *
 * This is the whole example, and it is deliberately short. An application gathers its own records, fills
 * a DocumentBundle and calls the builder — there is no SPI to implement, no Repository, no adapter, no
 * registry, no per-library configuration. All of that exists because a gateway serves libraries it does
 * not own; an application serving its own repository has none of that problem.
 *
 * The one thing it does have to know is which children each shape needs, and IiifStructure.requires()
 * states that so it need not be reverse-engineered from the builder.
 */
package cz.mzk.iiif.embedded;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import cz.mzk.iiif.build.ManifestBuilder;
import cz.mzk.iiif.build.ManifestConfig;
import cz.mzk.iiif.build.MetadataRows;
import cz.mzk.iiif.document.ContentLocator;
import cz.mzk.iiif.document.DocumentBundle;
import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.document.ImageSize;
import cz.mzk.iiif.document.LibraryProfile;
import cz.mzk.iiif.i18n.Translations;
import cz.mzk.iiif.kramerius.IiifStructure;
import cz.mzk.iiif.presentation.Iiif;
import cz.mzk.iiif.presentation.LanguageMap;

public final class EmbeddedManifests {

  private final KrameriusApi kramerius;
  private final ManifestConfig config;
  private final LibraryProfile library;
  private final ContentLocator urls;

  public EmbeddedManifests(KrameriusApi kramerius, ManifestConfig config, LibraryProfile library) {
    this.kramerius = kramerius;
    this.config = config;
    this.library = library;
    this.urls = new EmbeddedUrls(kramerius.baseUrl());
  }

  /** The manifest for one object, or empty when this repository does not hold it. */
  public Optional<Iiif.Document> manifest(String pid) {
    return kramerius
        .byPid(pid)
        .map(this::toRecord)
        .map(record -> ManifestBuilder.build(config, gather(record)));
  }

  /**
   * Fills the bundle with whatever this document's shape needs.
   *
   * <p>Driven by IiifStructure.requires() rather than by a switch here, so a host cannot forget a
   * part the builder will read.
   */
  private DocumentBundle gather(DocumentRecord document) {
    DocumentBundle.Builder bundle = DocumentBundle.of(library, urls, document);

    for (IiifStructure.Part part : document.shape().requires()) {
      switch (part) {
        case PAGES -> {
          List<DocumentRecord> pages = records(kramerius.pagesOf(document.pid()));
          bundle.pages(pages).sizes(sizes(pages));
        }
        case MEMBERS -> bundle.members(records(kramerius.membersOf(document.pid())));
        case MEDIA_UNITS -> bundle.mediaUnits(mediaUnitsOf(document.pid()));
      }
    }
    return bundle.build();
  }

  private List<DocumentBundle.MediaUnit> mediaUnitsOf(String pid) {
    return kramerius.mediaUnitsOf(pid).stream()
        .map(unit -> new DocumentBundle.MediaUnit(toRecord(unit.unit()), records(unit.tracks())))
        .toList();
  }

  /**
   * Page dimensions.
   *
   * <p>Resolved inline with no cache, because an application embedding this either reads them
   * locally from Akubra or already has its own cache. That freedom is exactly what level 2 not
   * knowing about caching buys.
   */
  private Map<String, ImageSize> sizes(List<DocumentRecord> pages) {
    Map<String, ImageSize> sizes = new LinkedHashMap<>();
    for (DocumentRecord page : pages) {
      if (!page.hasTiles()) {
        continue;
      }
      kramerius
          .imageSize(page.pid())
          .ifPresent(wh -> sizes.put(page.pid(), ImageSize.of(wh[0], wh[1], 3)));
    }
    return sizes;
  }

  private List<DocumentRecord> records(List<KrameriusApi.SolrDocument> documents) {
    return documents.stream().map(this::toRecord).toList();
  }

  /**
   * A Solr document becomes a record with its shape already decided.
   *
   * <p>The application knows its own model set, so this is a small switch rather than a
   * version-floor adapter and a configurable table. That is the simplification embedding buys.
   */
  private DocumentRecord toRecord(KrameriusApi.SolrDocument doc) {
    IiifStructure shape =
        switch (doc.model()) {
          case "periodical" -> IiifStructure.CHILD_COLLECTION;
          case "periodicalvolume" -> IiifStructure.CHILD_COLLECTION;
          case "collection" -> IiifStructure.VIRTUAL_COLLECTION;
          case "soundrecording" -> IiifStructure.MEDIA_MANIFEST;
          default -> IiifStructure.PAGED_MANIFEST;
        };

    DocumentRecord.Builder record =
        DocumentRecord.of(doc.pid(), doc.model(), shape)
            .label(LanguageMap.none(doc.first("title.search").orElse(null)))
            .hasTiles(doc.first("has_tiles").map(Boolean::parseBoolean).orElse(false))
            .metadata(metadata(doc));

    doc.first("page.placement").ifPresent(record::pagePlacement);
    doc.first("track.length").map(Double::parseDouble).ifPresent(record::trackLength);
    return record.build();
  }

  private List<Iiif.KeyValue> metadata(KrameriusApi.SolrDocument doc) {
    return MetadataRows.using(config.translations())
        .text("metadata.title", doc.first("title.search").orElse(null))
        .translated("metadata.documentType", "model." + doc.model())
        .texts("metadata.author", doc.all("authors"))
        .build();
  }

  /** Kramerius' own stream URLs. The only thing an embedded host must spell out. */
  private record EmbeddedUrls(String baseUrl) implements ContentLocator {

    private String item(String pid) {
      return baseUrl + "/search/api/client/v7.0/items/" + pid;
    }

    @Override
    public String thumbnail(String pid) {
      return item(pid) + "/image/thumb";
    }

    @Override
    public String iiifImage(String pid) {
      return imageService(pid) + "/full/max/0/default.jpg";
    }

    @Override
    public String imageService(String pid) {
      return baseUrl + "/search/iiif/" + pid;
    }

    @Override
    public String plainImage(String pid) {
      return item(pid) + "/image";
    }

    @Override
    public String alto(String pid) {
      return item(pid) + "/ocr/alto";
    }

    @Override
    public String audio(String pid) {
      return item(pid) + "/audio/mp3";
    }

    @Override
    public String pdf(String pid) {
      return item(pid) + "/streams/IMG_FULL";
    }

    @Override
    public String mods(String pid) {
      // In-process, MODS comes from Akubra; clients outside still follow this URL.
      return item(pid) + "/streams/BIBLIO_MODS";
    }

    @Override
    public int imageApiVersion() {
      return 3;
    }
  }

  /** Defaults for a host that has not configured anything. */
  public static ManifestConfig defaultConfig(String baseUrl) {
    return new ManifestConfig(
        baseUrl, baseUrl, ManifestConfig.DEFAULT_FALLBACK_CANVAS_SIZE, Translations.bundled());
  }
}
