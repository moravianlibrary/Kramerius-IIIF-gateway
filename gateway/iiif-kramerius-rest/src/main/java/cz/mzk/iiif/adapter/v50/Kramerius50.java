/*
 * Kramerius 5.
 *
 * It does NOT extend Kramerius70. The API differs structurally — different Solr path, different field
 * names, different stream paths, a multi-valued ordering field that forbids cursorMark — so a subclass
 * would override everything and inherit nothing but the name. It sits beside the K7 chain instead.
 *
 * 11 alive libraries in the registry run K5, including nkp (the National Library) at 5.8.4.4, so this is
 * not a legacy nicety. Its capabilities are genuinely narrower and the manifests say so rather than
 * pretending: no image service, no real canvas dimensions, no `behavior`, no navPlace. Everything that
 * DOES work — labels, structure, licences, availability, valid IIIF — works.
 *
 * The image-service `false` is a blanket decision, not a measurement of all 11: three of them do run a
 * working Image API 2 and lose real dimensions to it. See Kramerius5Urls.hasImageService and docs/DECISIONS.md.
 */
package cz.mzk.iiif.adapter.v50;

import cz.mzk.iiif.adapter.DocumentModels;
import cz.mzk.iiif.adapter.KrameriusAdapter;
import cz.mzk.iiif.adapter.SolrSchema;
import cz.mzk.iiif.adapter.Version;

public final class Kramerius50 implements KrameriusAdapter {

  /** The K5 model table. Its model names overlap K7's but its shapes and flags are its own. */
  public static final String KRAMERIUS_50 = "50";

  private final DocumentModels models;
  private final Solr5Schema schema = new Solr5Schema();

  public Kramerius50() {
    this(DocumentModels.bundled(KRAMERIUS_50));
  }

  /** For a host that loads the table from IIIF_MODELS_DIR rather than the jar. */
  public Kramerius50(DocumentModels models) {
    this.models = models;
  }

  @Override
  public String name() {
    return "Kramerius50";
  }

  @Override
  public Version floor() {
    return Version.of(5, 0);
  }

  @Override
  public Version testedUpTo() {
    return Version.of(5, 8, 4, 5);
  }

  /**
   * Refuses anything at 6 or above.
   *
   * <p>Without this, K5 would also report support for K7 because the default is "at or above". The
   * registry currently selects the higher K7 floor first, but this exact check makes K5's contract
   * explicit. Kramerius went 5 -> 7 and there will be no 6. See docs/DECISIONS.md.
   */
  @Override
  public boolean supports(Version version) {
    return version.major() == 5;
  }

  @Override
  public DocumentModels models() {
    return models;
  }

  @Override
  public SolrSchema schema() {
    return schema;
  }

  @Override
  public cz.mzk.iiif.adapter.RecordMapper records(
      cz.mzk.iiif.i18n.Translations translations,
      java.util.Map<String, cz.mzk.iiif.adapter.LicenseConfig.License> licenses) {
    return new Kramerius5Records(models, translations, licenses);
  }

  /**
   * Both arguments matter here, and only here.
   *
   * <p>Whether a K5 instance has a usable Image API is a property of that deployment rather than of
   * the version, so it is probed and passed in. The version is read the same way, and every K5
   * instance measured that answers at all reports Image API 2 — but reading it is free, since it
   * comes from the same probe.
   */
  @Override
  public cz.mzk.iiif.document.ContentLocator urls(
      String baseUrl, boolean imageServiceAvailable, int imageApiVersion) {
    return new Kramerius5Urls(baseUrl, imageServiceAvailable, imageApiVersion);
  }

  /** K5's Image API is a deployment choice, not a given. See Kramerius5Urls. */
  @Override
  public boolean imageServiceIsCertain() {
    return false;
  }
}
