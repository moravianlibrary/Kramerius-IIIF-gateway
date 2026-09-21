/*
 * Where one Kramerius instance keeps its streams.
 *
 * Pure string building, no I/O, so it is trivially assertable and the fixture tests can compare a whole
 * manifest without a network. Paths are the K7 client API's; the K5 adapter will need its own.
 *
 * Note iiifImage vs plainImage: a tiled page has an Image API service and gets /full/max/0/default.jpg,
 * an untiled one has only the raw stream. v1 emitted the service unconditionally, so an untiled page
 * advertised an endpoint that answers 404.
 */
package cz.mzk.iiif.adapter;

import cz.mzk.iiif.document.ContentLocator;

public record KrameriusUrls(String baseUrl, int imageApiVersion) implements ContentLocator {

  public KrameriusUrls {
    baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    if (imageApiVersion != 2 && imageApiVersion != 3) {
      throw new IllegalArgumentException("unsupported Image API version " + imageApiVersion);
    }
  }

  /** The conservative default, which every measured K7 instance serves. */
  public KrameriusUrls(String baseUrl) {
    this(baseUrl, 2);
  }

  private String item(String pid) {
    return baseUrl + "/search/api/client/v7.0/items/" + pid;
  }

  @Override
  public String thumbnail(String pid) {
    return item(pid) + "/image/thumb";
  }

  /**
   * The full-size image.
   *
   * <p>"max" is Image API 3 spelling and "full" is Image API 2; both happen to be accepted by every
   * K7 instance measured, so this is about matching the service block we declare rather than about
   * whether the request works.
   */
  @Override
  public String iiifImage(String pid) {
    return imageService(pid)
        + (imageApiVersion == 3 ? "/full/max" : "/full/full")
        + "/0/default.jpg";
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
    return item(pid) + "/metadata/mods";
  }
}
