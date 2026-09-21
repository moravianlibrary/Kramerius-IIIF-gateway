/*
 * The URLs a manifest points at.
 *
 * Every one of these differs between K5 and K7 — /streams/BIBLIO_MODS against /metadata/mods, /thumb
 * against /image/thumb — and none of them is the gateway's own address, which comes from config. Keeping
 * them behind the SPI is what stops version differences leaking into the manifest logic.
 *
 * These are pure string construction: no request is made, and none of these URLs is verified to exist.
 * Whether a page has tiles is answered by the index, not by probing these.
 */
package cz.mzk.iiif.document;

public interface ContentLocator {

  /** Small preview image for a document or a page. */
  String thumbnail(String pid);

  /** Full-size image served through the Image API, for a page with tiles. */
  String iiifImage(String pid);

  /**
   * Which Image API version this library's image server speaks.
   *
   * <p>A property of the SERVER, not of a page, which is why it lives here and not on ImageSize.
   * Measured live across K7: mzk and knav (both 7.2.2) answer ImageService3 / level2 with an Image
   * API 3 context, while ntk (7.1.7), cbvk (7.0.39) and svkhk (7.0.41) answer Image API 2. That is
   * consistent with a 7.2 boundary but two instances cannot prove one — an operator can swap the
   * image server independently of the Kramerius release — so it is detected from info.json rather
   * than declared by an adapter, the same reasoning as the SolrCloud cursor tiebreaker.
   *
   * <p>The default is 2 because it is the safe one: every instance measured serves both /full/full/
   * and /full/max/, so a conservative declaration works everywhere, whereas claiming ImageService3
   * on a v2 server would advertise level2 capabilities the server does not have.
   */
  default int imageApiVersion() {
    return 2;
  }

  /**
   * Whether this library has a working IIIF Image API at all.
   *
   * <p>Kramerius 5 does not. Measured on ndk.cz and kramerius.vugtk.cz,
   * /search/iiif/{pid}/info.json answers 200 with an EMPTY body and every real image request under
   * it 404s, while the plain stream returns the image fine. Declaring a service there would be a
   * claim a viewer acts on — it would try to fetch tiles and fail — so a K5 canvas gets the plain
   * stream and no service, which is what it actually has.
   */
  default boolean hasImageService() {
    return true;
  }

  /** Base URI of the Image API service for a page — what an ImageService block points at. */
  String imageService(String pid);

  /** Full-size image straight from the repository, for a page with no tiles. */
  String plainImage(String pid);

  /** ALTO OCR for a page. */
  String alto(String pid);

  /** Audio stream for a track. */
  String audio(String pid);

  /** The PDF of a PDF-only object. */
  String pdf(String pid);

  /**
   * The document's MODS record, for a manifest-level seeAlso.
   *
   * <p>A LINK, NOT A PARSE. The K7 adapters read everything they need from Solr and deliberately
   * never fetch MODS — that decision stands. But a machine reading a manifest may want the full
   * descriptive record, and every peer surveyed publishes a manifest-level seeAlso: Wellcome, Yale
   * and the Internet Archive all do. Pointing at it costs one URL and no requests.
   *
   * <p>Public on both API generations, checked: K7 answers 4.9 KB of MODS at
   * items/{pid}/metadata/mods, K5 5.8 KB at item/{pid}/streams/BIBLIO_MODS.
   */
  String mods(String pid);
}
