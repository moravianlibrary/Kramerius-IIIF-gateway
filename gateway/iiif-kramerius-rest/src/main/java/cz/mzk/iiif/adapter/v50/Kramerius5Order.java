/*
 * Reading K5's ordering index for the client-side sort it forces.
 *
 * rels_ext_index is multi-valued, so Solr will not sort on it and the rows come back unordered. v1 hit
 * the same wall and worked around it by comparing rels_ext_index against model_path; the values are
 * parallel arrays, and that pairing is what made the workaround fragile. Taking the first value is
 * correct for the single-parent case, which is every K5 document — K5 has no foster relationship.
 *
 * A document with no index sorts last rather than first, so a missing value never silently reorders the
 * pages before it.
 */
package cz.mzk.iiif.adapter.v50;

import com.fasterxml.jackson.databind.JsonNode;

public final class Kramerius5Order {

  private Kramerius5Order() {}

  public static int of(JsonNode doc) {
    return Kramerius5Records.order(doc);
  }
}
