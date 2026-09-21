/*
 * The pixel dimensions of one page image, and the Image API version the server that reported them speaks.
 *
 * The api version travels with the size because it decides whether a canvas gets an ImageService2 or an
 * ImageService3 block, and it is a property of the image server rather than of the page — but a cache hit
 * has to yield a complete answer, so it is stored per entry rather than looked up again.
 *
 * UNAVAILABLE is the "asked, no usable answer" record. Without it a page whose lookup failed is
 * indistinguishable from one nobody has asked about, and gets re-probed on every single request. Pages
 * with no tiles are simply absent from the map instead: they have no Image API endpoint to ask.
 *
 * NOTHING here is permanent, and that is deliberate. An earlier version treated 404 as a settled fact
 * about the image and only expired 403s, which is wrong twice over:
 *
 *   - If Solr says has_tiles and the image server says 404, that is an INCONSISTENCY, not a fact.
 *     Kramerius derives tile pyramids asynchronously, so a 404 can simply precede a 200 — and an image
 *     server that is misconfigured or mid-migration answers 404 for everything, which would poison every
 *     page touched while it was broken.
 *   - A page that is genuinely deleted loses its Solr record too, so it is never asked about again. The
 *     permanent case it was protecting against cannot arise.
 *
 * Probed live: 40 of 40 public tiled pages at MZK answer 200, so the category was empty in practice as
 * well as wrong in principle. Every failure now expires — see CachedImageSizes — and the cost of that is
 * bounded by the retry window rather than by the number of requests.
 */
package cz.mzk.iiif.document;

public record ImageSize(int width, int height, int imageApiVersion) {

  /**
   * The image server was asked and gave no usable answer. Distinct from "not asked yet", and never
   * permanent: the reason may be about the caller, the moment, or the server, not about the image.
   */
  public static final ImageSize UNAVAILABLE = new ImageSize(0, 0, 0);

  public ImageSize {
    if (width < 0 || height < 0) {
      throw new IllegalArgumentException("negative dimensions: %d x %d".formatted(width, height));
    }
  }

  public static ImageSize of(int width, int height, int imageApiVersion) {
    return new ImageSize(width, height, imageApiVersion);
  }

  /**
   * A size whose SHAPE is right and whose scale is nominal.
   *
   * <p>Derived where the image itself could not be measured but its thumbnail could. Measured at
   * MZK over 25 pages, a thumbnail's aspect matches the full image to within 0.82% (mean 0.17%) —
   * the residual is integer rounding at 256 px tall — while the scale factor is useless, running
   * from 8.19 to 12.77 because thumbnails are generated to a fixed height.
   *
   * <p>That trade is the right way round: a canvas is a coordinate space and viewers scale the
   * image to fit it, so being wrong about magnitude costs nothing and being wrong about aspect
   * distorts the page.
   *
   * <p>Marked by an api version of 0, which a measured size never has — it is 2 or 3 — so the cache
   * can expire an approximation and upgrade it if the image becomes measurable, instead of freezing
   * a guess as though it were a measurement.
   */
  public static ImageSize approximate(int width, int height) {
    return new ImageSize(width, height, 0);
  }

  /** Whether the shape is trustworthy but the scale is not. */
  public boolean isApproximate() {
    return isKnown() && imageApiVersion == 0;
  }

  /** False for {@link #UNAVAILABLE}: there is a record of asking, but no usable answer. */
  public boolean isKnown() {
    return width > 0 && height > 0;
  }
}
