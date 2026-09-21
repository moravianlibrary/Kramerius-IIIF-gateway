/*
 * Kramerius 7.1.4 and up.
 *
 * The whole delta is three index fields the release added, so this extends Kramerius70 and overrides
 * only the schema answers that changed. Everything else — URLs, model resolution, paging, sizes — is
 * inherited, which is what keeps the chain short enough to be safe.
 *
 * It also serves every version above it, including ones nobody has tested. That is the point of
 * floor-based selection: a 7.3 instance works out of the box, and `testedUpTo` is what makes the
 * optimism visible in GET /libraries instead of silent.
 */
package cz.mzk.iiif.adapter.v714;

import cz.mzk.iiif.adapter.DocumentModels;
import cz.mzk.iiif.adapter.SolrSchema;
import cz.mzk.iiif.adapter.Version;
import cz.mzk.iiif.adapter.v70.Kramerius70;

public class Kramerius714 extends Kramerius70 {

  public Kramerius714() {
    this(DocumentModels.bundled(DocumentModels.KRAMERIUS_70));
  }

  /** For a host that loads the table from IIIF_MODELS_DIR rather than the jar. */
  public Kramerius714(DocumentModels models) {
    super(models, new Schema714());
  }

  @Override
  public String name() {
    return "Kramerius714";
  }

  @Override
  public Version floor() {
    return Version.of(7, 1, 4);
  }

  @Override
  public Version testedUpTo() {
    return Version.of(7, 2, 2);
  }

  /** The three fields 7.1.4 added that we actually read. */
  static final class Schema714 extends SolrSchema {

    @Override
    public boolean hasAltoField() {
      return true;
    }

    @Override
    public boolean hasIsPointField() {
      return true;
    }

    @Override
    public boolean hasVolumeCountField() {
      return true;
    }

    @Override
    public java.util.List<String> documentFields() {
      java.util.List<String> fields = new java.util.ArrayList<>(super.documentFields());
      fields.add("coords.is_point");
      fields.add("count_volume");
      return java.util.List.copyOf(fields);
    }

    @Override
    public java.util.List<String> pageFields() {
      java.util.List<String> fields = new java.util.ArrayList<>(super.pageFields());
      fields.add("has_alto_content");
      fields.add("has_text_ocr_content");
      return java.util.List.copyOf(fields);
    }
  }
}
