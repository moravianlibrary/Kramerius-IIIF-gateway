/*
 * Kramerius 5's stream URLs. Verified live on ndk.cz and kramerius.vugtk.cz.
 *
 * Every path differs from K7's, which is why K5 sits beside Kramerius70 rather than extending it:
 *
 *   thumbnail   /search/api/v5.0/item/{pid}/thumb
 *   full image  /search/api/v5.0/item/{pid}/streams/IMG_FULL
 *   ALTO        /search/api/v5.0/item/{pid}/streams/ALTO
 *
 * WHETHER THERE IS AN IMAGE API IS PROBED, not assumed. Of the 11 alive K5 libraries in the registry,
 * three (lmda 5.8.1, dsmo 5.8.1, kvkli 5.8.3) serve a complete Image API 2 info.json with real
 * width/height; ndk.cz and vugtk answer 200 with an empty body and 404 every image request; three answer
 * 500. The Kramerius version does not predict which, so `imageServiceAvailable` is carried in rather
 * than decided here — see ImageApi.probe. Every K5 instance that answers at all reports Image API 2.
 */
package cz.mzk.iiif.adapter.v50;

import cz.mzk.iiif.document.ContentLocator;

public record Kramerius5Urls(String baseUrl, boolean imageServiceAvailable, int imageApiVersion)
    implements ContentLocator {

  public Kramerius5Urls {
    baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    if (imageApiVersion != 2 && imageApiVersion != 3) {
      throw new IllegalArgumentException("unsupported Image API version " + imageApiVersion);
    }
  }

  private String item(String pid) {
    return baseUrl + "/search/api/v5.0/item/" + pid;
  }

  @Override
  public String thumbnail(String pid) {
    return item(pid) + "/thumb";
  }

  /**
   * The Image API's own full-size URL where there is one, the plain stream where there is not.
   *
   * <p>Spelled per version for the same reason as K7: /full/max is Image API 3 and /full/full is 2,
   * and a URL in the other version's spelling is a 400 rather than a redirect.
   */
  @Override
  public String iiifImage(String pid) {
    if (!imageServiceAvailable) {
      return plainImage(pid);
    }
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
    return item(pid) + "/streams/IMG_FULL";
  }

  @Override
  public String alto(String pid) {
    return item(pid) + "/streams/ALTO";
  }

  @Override
  public String audio(String pid) {
    return item(pid) + "/streams/MP3";
  }

  @Override
  public String pdf(String pid) {
    return item(pid) + "/streams/IMG_FULL";
  }

  /** K5 serves datastreams by name; MODS is BIBLIO_MODS there. */
  @Override
  public String mods(String pid) {
    return item(pid) + "/streams/BIBLIO_MODS";
  }

  /**
   * What the probe found, not a constant.
   *
   * <p>False is still the common answer and still the right one: declaring an ImageService for
   * ndk.cz or vugtk is a claim a viewer acts on — it fetches tiles and gets 404s — so where the
   * probe found nothing the canvas gets the flat stream and no service, which is what the library
   * actually has. Where it found a working server, lmda and kvkli and dsmo get tiles and real
   * dimensions.
   */
  @Override
  public boolean hasImageService() {
    return imageServiceAvailable;
  }
}
