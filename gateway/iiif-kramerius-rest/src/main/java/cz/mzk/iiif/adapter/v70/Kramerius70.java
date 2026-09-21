/*
 * Kramerius 7.0 and up to 7.1.3.
 *
 * The baseline K7 adapter. Everything it needs is present on every 7.0.x instance probed — including all
 * four coords.bbox fields, which is why navPlace needs no fallback at all, and has_tiles, which is why
 * the canvas image service does not either.
 *
 * Three things ARE missing below 7.1.4, and each has a fallback rather than a missing feature:
 *
 *   has_alto_content / has_text_ocr_content  probe the ALTO stream once per document (what v1 did)
 *   coords.is_point                          derive it: a point is a bbox whose corners coincide
 *   count_volume                             no expected total, so a walk cannot report index drift
 *
 * None of that work is wasted: K5 needs the same fallbacks.
 */
package cz.mzk.iiif.adapter.v70;

import cz.mzk.iiif.adapter.DocumentModels;
import cz.mzk.iiif.adapter.KrameriusAdapter;
import cz.mzk.iiif.adapter.SolrSchema;
import cz.mzk.iiif.adapter.Version;

public class Kramerius70 implements KrameriusAdapter {

  private final DocumentModels models;
  private final SolrSchema schema;

  public Kramerius70() {
    this(DocumentModels.bundled(DocumentModels.KRAMERIUS_70), new SolrSchema());
  }

  /** For a host that loads the table from IIIF_MODELS_DIR rather than the jar. */
  public Kramerius70(DocumentModels models) {
    this(models, new SolrSchema());
  }

  protected Kramerius70(DocumentModels models, SolrSchema schema) {
    this.models = models;
    this.schema = schema;
  }

  @Override
  public String name() {
    return "Kramerius70";
  }

  @Override
  public Version floor() {
    return Version.of(7, 0);
  }

  @Override
  public Version testedUpTo() {
    return Version.of(7, 1, 3, 1);
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
    return new cz.mzk.iiif.adapter.SolrRecords(models, schema, translations, licenses);
  }

  /** K7 always has an Image API, so availability is not consulted — only the version is. */
  @Override
  public cz.mzk.iiif.document.ContentLocator urls(
      String baseUrl, boolean imageServiceAvailable, int imageApiVersion) {
    return new cz.mzk.iiif.adapter.KrameriusUrls(baseUrl, imageApiVersion);
  }
}
