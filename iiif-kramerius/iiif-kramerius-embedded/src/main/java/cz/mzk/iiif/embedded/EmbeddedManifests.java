/*
 * Building a manifest inside Kramerius, using level 2 and nothing else.
 *
 * This is the whole example, and it is deliberately short. An application gathers its own records, fills
 * a DocumentBundle and calls the builder — there is no SPI to implement, no Repository, no adapter, no
 * registry, no per-library configuration. All of that exists because a gateway serves libraries it does
 * not own; an application serving its own repository has none of that problem.
 *
 * The one thing it does have to know is which children each shape needs, and DocumentShape.requires()
 * states that so it need not be reverse-engineered from the builder.
 */
package cz.mzk.iiif.embedded;

import java.util.ArrayList;
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
import cz.mzk.iiif.kramerius.ChildKind;
import cz.mzk.iiif.kramerius.DocumentShape;
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
   * <p>Driven by DocumentShape.requires() rather than by a switch here, so a host cannot forget a
   * part the builder will read.
   */
  private DocumentBundle gather(DocumentRecord document) {
    DocumentBundle.Builder bundle = DocumentBundle.of(library, urls, document);

    for (DocumentShape.Part part : document.shape().requires()) {
      switch (part) {
        case PAGES -> {
          List<DocumentRecord> pages = children(document.pid(), "page");
          bundle.pages(pages).sizes(sizes(pages));
        }
        case MEMBERS -> bundle.members(children(document.pid(), childModel(document.childKind())));
        case SOUND_UNITS -> bundle.soundUnits(soundUnits(document.pid()));
      }
    }
    return bundle.build();
  }

  private List<DocumentBundle.SoundUnit> soundUnits(String pid) {
    List<DocumentBundle.SoundUnit> units = new ArrayList<>();
    for (DocumentRecord unit : children(pid, "soundunit")) {
      units.add(new DocumentBundle.SoundUnit(unit, children(unit.pid(), "track")));
    }
    return units;
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

  private List<DocumentRecord> children(String pid, String model) {
    List<DocumentRecord> records = new ArrayList<>();
    for (KrameriusApi.SolrDocument child : kramerius.childrenOf(pid, model)) {
      records.add(toRecord(child));
    }
    return records;
  }

  /**
   * A Solr document becomes a record with its shape already decided.
   *
   * <p>The application knows its own model set, so this is a small switch rather than a
   * version-floor adapter and a configurable table. That is the simplification embedding buys.
   */
  private DocumentRecord toRecord(KrameriusApi.SolrDocument doc) {
    DocumentShape shape =
        switch (doc.model()) {
          case "periodical" -> DocumentShape.MEMBERS;
          case "periodicalvolume" -> DocumentShape.MEMBERS;
          case "collection" -> DocumentShape.COLLECTION_MEMBERS;
          case "soundrecording" -> DocumentShape.SOUND_RECORDING;
          default -> DocumentShape.PAGES;
        };

    DocumentRecord.Builder record =
        DocumentRecord.of(doc.pid(), doc.model(), shape)
            .label(LanguageMap.none(doc.first("title.search").orElse(null)))
            .hasTiles(doc.first("has_tiles").map(Boolean::parseBoolean).orElse(false))
            .metadata(metadata(doc));

    doc.first("page.placement").ifPresent(record::pagePlacement);
    doc.first("track.length").map(Double::parseDouble).ifPresent(record::trackLength);
    childKindFor(doc.model()).ifPresent(record::childKind);
    return record.build();
  }

  private List<Iiif.KeyValue> metadata(KrameriusApi.SolrDocument doc) {
    return MetadataRows.using(config.translations())
        .text("metadata.title", doc.first("title.search").orElse(null))
        .translated("metadata.documentType", "model." + doc.model())
        .texts("metadata.author", doc.all("authors"))
        .build();
  }

  private static Optional<ChildKind> childKindFor(String model) {
    return switch (model) {
      case "periodical" -> Optional.of(ChildKind.PERIODICAL_VOLUMES);
      case "periodicalvolume" -> Optional.of(ChildKind.PERIODICAL_ISSUES);
      default -> Optional.empty();
    };
  }

  private static String childModel(Optional<ChildKind> kind) {
    return kind.map(
            k ->
                switch (k) {
                  case PERIODICAL_VOLUMES -> "periodicalvolume";
                  case PERIODICAL_ISSUES -> "periodicalitem";
                  case MONOGRAPH_UNITS -> "monographunit";
                  case SOUND_UNITS -> "soundunit";
                  case TRACKS -> "track";
                  case CONVOLUTE_PARTS, PAGES -> "page";
                })
        .orElse("page");
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
      // In-process, MODS is a datastream read straight from Akubra rather than an HTTP round trip —
      // see docs/KRAMERIUS.md. The URL is still what a client outside would follow.
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
