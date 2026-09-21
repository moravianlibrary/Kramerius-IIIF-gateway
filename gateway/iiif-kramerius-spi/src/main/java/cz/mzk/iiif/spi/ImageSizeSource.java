/*
 * Where page dimensions come from.
 *
 * A canvas must state width and height, and over HTTP the only source is the image server's info.json —
 * one request per page, ~250 ms each. That single fact drives more of this design than anything else,
 * so this is the SPI method whose cost matters.
 *
 * CACHING LIVES BEHIND THIS INTERFACE, not above it. The library calls sizes() and does not know or care
 * whether the answer came from Postgres, from an in-process store, or from a live fetch. That is why
 * there is no cache abstraction in the library at all.
 *
 * Batch, not per-page: a manifest needs every page at once, and one round trip for 300 pids is the
 * difference between a usable gateway and an unusable one.
 */
package cz.mzk.iiif.spi;

import java.util.List;
import java.util.Map;

import cz.mzk.iiif.document.ImageSize;

public interface ImageSizeSource {

  /**
   * Dimensions for as many of these pages as can be established, keyed by pid.
   *
   * <p>A pid may be absent from the result — the page has no tiles, or the image server did not
   * answer — and the caller falls back to a nominal size for it. Absence is normal, not an error.
   *
   * <p>Implementations must respect their own upstream politeness limits; the library imposes none.
   */
  Map<String, ImageSize> sizes(List<String> pagePids);
}
