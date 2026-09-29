/*
 * The blocks every document carries, whatever its shape.
 *
 * Attribution, provider, links, thumbnail, navPlace, partOf, rendering, behavior. None of them depends on
 * whether the document is a book, a periodical or a sound recording, which is exactly why they are here
 * and not in the builder: a shape should contribute its items and nothing else.
 *
 * Descriptive text is not composed here either. Label, metadata and summary arrive from the adapter
 * already mapped and are copied by the builder; what this class builds is the material *about* the
 * document — who provides it, where else it can be found, where it is about.
 */
package cz.mzk.iiif.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import cz.mzk.iiif.document.BoundingBox;
import cz.mzk.iiif.document.DocumentBundle;
import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.document.LibraryProfile;
import cz.mzk.iiif.presentation.Iiif;

final class DocumentBlocks {

  /** Page placements that mean the document is a bound sequence rather than loose sheets. */
  private static final List<String> PAGED_PLACEMENTS = List.of("left", "right");

  private final ManifestConfig config;
  private final DocumentBundle bundle;
  private final ManifestIds ids;
  private final DocumentRecord document;

  DocumentBlocks(ManifestConfig config, DocumentBundle bundle, ManifestIds ids) {
    this.config = config;
    this.bundle = bundle;
    this.ids = ids;
    this.document = bundle.document();
  }

  /**
   * The document's access condition, where Kramerius supplies one.
   *
   * <p>The Kramerius 7 licence configuration supplies the licence's reader-facing name and its
   * access type. The adapter turns those into a short condition before this builder receives it;
   * its full unauthenticated notice remains in the Licence metadata row. IIIF clients must render
   * this block, which makes it the right place for the part a reader needs before attempting
   * access.
   *
   * <p>Older Kramerius instances publish no such configuration. For them, retain the former
   * attribution statement; the provider block still carries the institution in both cases.
   */
  Iiif.KeyValue requiredStatement() {
    if (document.requiredStatement() != null) {
      return document.requiredStatement();
    }
    var label = config.translations().lookup("attribution.label");
    if (label == null) {
      return null;
    }
    Map<String, List<String>> value = bundle.library().label();
    return value == null || value.isEmpty() ? null : new Iiif.KeyValue(label, value);
  }

  List<Iiif.Agent> provider() {
    LibraryProfile profile = bundle.library();
    var name = profile.label();

    List<Iiif.TextLink> homepage =
        profile
            .homepage()
            .map(url -> List.of(new Iiif.TextLink(url, "Text", name, "text/html")))
            .orElse(null);

    List<Iiif.Resource> logo =
        profile.logo().map(url -> List.of(new Iiif.Resource(url, "Image", null))).orElse(null);

    return List.of(new Iiif.Agent(profile.id(), "Agent", name, homepage, logo));
  }

  /**
   * The object in the Digital Library, and its catalogue record where the library has one.
   *
   * <p>v1 hardcoded a `library == "mzk"` check and an `MZK01-` prefix here. So, for a while, did
   * this rewrite — one file up, in `Libraries.catalogueFor`, while this comment already claimed
   * both were configuration. They are now: `catalogues.json` in the jar, plus a `catalogue` block
   * per library in IIIF_LIBRARIES_FILE. See CatalogueLink for why the mapping cannot be derived and
   * has to be stated per library.
   */
  List<Iiif.TextLink> homepage() {
    List<Iiif.TextLink> links = new ArrayList<>(2);
    links.add(
        new Iiif.TextLink(
            "%s/%s/uuid/%s"
                .formatted(config.digitalLibraryUrl(), bundle.library().code(), document.pid()),
            "Text",
            config.translations().lookup("link.digitalLibrary"),
            "text/html"));

    bundle
        .catalogueUrl()
        .ifPresent(
            url ->
                links.add(
                    new Iiif.TextLink(
                        url, "Text", config.translations().lookup("link.catalogue"), "text/html")));
    return links;
  }

  /**
   * A date a client can navigate by, where the index carries one.
   *
   * <p>Yale publishes this and it is what puts a manifest on a timeline or a time-sliced map.
   * Second precision and a trailing Z: the spec's examples and every published manifest use that
   * form, and Kramerius' date.min carries milliseconds.
   *
   * <p>The document's period, not its modification time — the two are unrelated and confusing them
   * would put a 1922 volume in 2024.
   */
  String navDate() {
    return document
        .navDate()
        .map(at -> at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
        .map(java.time.format.DateTimeFormatter.ISO_INSTANT::format)
        .orElse(null);
  }

  /**
   * The full descriptive record, for a machine that wants more than the manifest says.
   *
   * <p>Every v3 peer surveyed carries a manifest-level seeAlso — Wellcome, Yale, the Internet
   * Archive — and MODS is the obvious target: the index this gateway reads is a summary of it. A
   * link only; nothing here fetches or parses MODS, which is the decision that let the K7 adapters
   * drop the XML parser entirely.
   */
  List<Iiif.SeeAlso> seeAlso() {
    var label = config.translations().lookup("seeAlso.mods");
    return List.of(Iiif.SeeAlso.mods(bundle.urls().mods(document.pid()), label));
  }

  List<Iiif.Resource> thumbnail() {
    return List.of(
        new Iiif.Resource(bundle.urls().thumbnail(document.pid()), "Image", "image/jpeg"));
  }

  /** A PDF is a rendering, whatever the object's model — never a replacement for its structure. */
  List<Iiif.TextLink> rendering() {
    if (!document.hasPdf()) {
      return null;
    }
    return List.of(
        new Iiif.TextLink(
            bundle.urls().pdf(document.pid()),
            "Text",
            config.translations().lookup("rendering.pdf"),
            "application/pdf"));
  }

  /**
   * Derived from the pages, never guessed from the model.
   *
   * <p>`individuals` is never asserted: it is a positive claim that the canvases are unrelated
   * items, as wrong for a multi-sheet graphic album as `paged` is. Omitting lets the viewer use its
   * own default.
   */
  static List<String> behavior(List<DocumentRecord> pages) {
    if (pages.size() <= 1) {
      return null;
    }
    boolean bound =
        pages.stream()
            .map(DocumentRecord::pagePlacement)
            .flatMap(Optional::stream)
            .anyMatch(PAGED_PLACEMENTS::contains);
    return bound ? List.of("paged") : null;
  }

  /** Where the object is about, for documents whose record carries cartographic coordinates. */
  Iiif.NavPlace navPlace() {
    Optional<BoundingBox> box = document.coordinates();
    if (box.isEmpty()) {
      return null;
    }
    BoundingBox extent = box.get();
    String featureBase = ids.feature(document.pid());

    Iiif.Geometry geometry =
        extent.isPoint()
            ? new Iiif.Geometry("Point", extent.centre())
            : new Iiif.Geometry("Polygon", List.of(extent.ring()));

    return new Iiif.NavPlace(
        featureBase,
        "FeatureCollection",
        List.of(
            new Iiif.Feature(
                featureBase + "/1",
                "Feature",
                new Iiif.FeatureProperties(document.label()),
                geometry)));
  }

  /**
   * The whole this object is a part of.
   *
   * <p>Linked when the parent is a model this gateway can serve, of whichever IIIF type that model
   * produces — rather than a hardcoded allowlist, which the newer models break: an article's parent
   * may be a supplement, and a picture's parent is an article. "Link to it if we can serve it" also
   * guarantees the URL resolves.
   */
  List<Iiif.Reference> partOf() {
    // The parent's presence is the whole signal: the adapter sets one only when it resolved a
    // parent this gateway can serve, so there is nothing further to gate on here.
    if (document.parent().isEmpty()) {
      return null;
    }
    DocumentRecord.Parent parent = document.parent().get();
    return List.of(
        new Iiif.Reference(ids.manifest(parent.pid()), parent.iiifType(), parent.label()));
  }
}
