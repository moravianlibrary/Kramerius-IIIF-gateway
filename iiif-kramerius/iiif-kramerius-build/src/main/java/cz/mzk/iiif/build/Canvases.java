/*
 * Builds the canvases of one document.
 *
 * Stateful, and deliberately so: canvas numbering is a running counter shared across a document's
 * sections, because a sound recording numbers its track canvases after its page canvases. One instance
 * per request keeps that counter off any static, so two concurrent requests cannot interleave numbering.
 *
 * A canvas, its annotation page and its annotation form one unit whose three ids have to agree — the
 * annotation targets the canvas — which is why they are built together here rather than assembled by a
 * caller.
 */
package cz.mzk.iiif.build;

import java.util.List;
import java.util.Optional;

import cz.mzk.iiif.document.DocumentBundle;
import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.document.ImageSize;
import cz.mzk.iiif.presentation.Iiif;

final class Canvases {

  private final ManifestConfig config;
  private final DocumentBundle bundle;
  private final ManifestIds ids;
  private final String documentPid;

  private int nextIndex;

  Canvases(ManifestConfig config, DocumentBundle bundle, ManifestIds ids) {
    this.config = config;
    this.bundle = bundle;
    this.ids = ids;
    this.documentPid = bundle.document().pid();
  }

  /** A page image, with its real dimensions where they are known. */
  Iiif.Canvas page(DocumentRecord page) {
    // Absent, or present but unusable — a page the image server was asked about and did not answer.
    Optional<ImageSize> size =
        Optional.ofNullable(bundle.sizes().get(page.pid())).filter(ImageSize::isKnown);
    int width = size.map(ImageSize::width).orElse(config.fallbackCanvasSize());
    int height = size.map(ImageSize::height).orElse(config.fallbackCanvasSize());
    String canvasId = ids.canvas(documentPid, nextIndex++);

    return new Iiif.Canvas(
        canvasId,
        "Canvas",
        page.label(),
        width,
        height,
        null,
        List.of(thumbnailOf(page.pid())),
        page.hasAltoContent().orElse(true) ? List.of(alto(page.pid())) : null,
        List.of(annotationPage(canvasId, pageBody(page, size, width, height))));
  }

  /**
   * An audio track.
   *
   * <p>The thumbnail is its sound unit's: a track has no image of its own.
   */
  Iiif.Canvas track(DocumentRecord track, DocumentRecord unit) {
    // Duration is required on a Sound canvas, and K5 does not index it.
    double duration = track.trackLength().orElse(1.0);
    String canvasId = ids.canvas(documentPid, nextIndex++);

    Iiif.Body body =
        new Iiif.Body(
            bundle.urls().audio(track.pid()), "Sound", "audio/mpeg", null, null, duration, null);

    return new Iiif.Canvas(
        canvasId,
        "Canvas",
        track.label(),
        null,
        null,
        duration,
        List.of(thumbnailOf(unit.pid())),
        null,
        List.of(annotationPage(canvasId, body)));
  }

  /**
   * The image painted onto a page canvas.
   *
   * <p>A tiled page is served through the Image API and can declare its size and a service; a page
   * without tiles has only the plain stream, and nothing true to say about its dimensions.
   */
  private Iiif.Body pageBody(DocumentRecord page, Optional<ImageSize> size, int width, int height) {
    // No tiles, or no Image API on this library at all — either way there is only the plain stream
    // and
    // nothing true to say about a service.
    if (!page.hasTiles() || !bundle.urls().hasImageService()) {
      return new Iiif.Body(
          bundle.urls().plainImage(page.pid()), "Image", "image/jpeg", null, null, null, null);
    }

    // The library's version, not the page's. Taking it per-page from whatever info.json happened to
    // be cached would let one manifest declare ImageService2 on some canvases and ImageService3 on
    // others, and emit v3 URL syntax under a v2 declaration.
    String service = bundle.urls().imageService(page.pid());
    Object description =
        bundle.urls().imageApiVersion() == 3
            ? Iiif.ImageService3.of(service)
            : Iiif.ImageService2.of(service);

    return new Iiif.Body(
        bundle.urls().iiifImage(page.pid()),
        "Image",
        "image/jpeg",
        width,
        height,
        null,
        List.of(description));
  }

  private Iiif.Resource thumbnailOf(String pid) {
    return new Iiif.Resource(bundle.urls().thumbnail(pid), "Image", "image/jpeg");
  }

  /**
   * OCR for a page. Typed Dataset — "Alto", which v1 emitted, is not an IIIF type.
   *
   * <p>Absent means EMIT, not omit. has_alto_content exists in the schema from 7.1.4 but is
   * populated on essentially nothing: measured live, 0 of MZK's 76 455 203 pages and 3 of KNAV's 6
   * 228 108. The data is there regardless — 73 839 443 MZK pages have text_ocr indexed, and the
   * ALTO stream returns a valid document even for a front jacket with no text. So treating "the
   * index did not say" as "no OCR" would drop the link from every page in the country.
   *
   * <p>An instance that DOES populate the field is still believed: an explicit false omits the
   * link. That is what the three-state Optional is for.
   */
  private Iiif.SeeAlso alto(String pid) {
    return new Iiif.SeeAlso(
        bundle.urls().alto(pid),
        "Dataset",
        Iiif.ALTO_PROFILE,
        config.translations().lookup("seeAlso.alto"),
        "text/xml");
  }

  private static Iiif.AnnotationPage annotationPage(String canvasId, Iiif.Body body) {
    return new Iiif.AnnotationPage(
        ManifestIds.annotationPage(canvasId),
        "AnnotationPage",
        List.of(
            new Iiif.Annotation(
                ManifestIds.annotation(canvasId), "Annotation", "painting", body, canvasId)));
  }
}
