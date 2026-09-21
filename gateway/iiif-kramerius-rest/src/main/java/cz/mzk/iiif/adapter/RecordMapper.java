/*
 * Turning one Solr document into a record, whichever Kramerius produced it.
 *
 * An interface because K5 and K7 do not differ by a few field names — they differ structurally. K5 keys
 * its uniqueKey `PID`, its model `fedora.model`, packs a page's number and type into one `##`-separated
 * `details` string, and makes `parent_pid` multi-valued. A single mapper parameterised by field names
 * would end up a switch on version at every field, which is the thing version-floor adapters exist to
 * avoid.
 */
package cz.mzk.iiif.adapter;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import cz.mzk.iiif.document.DocumentRecord;

public interface RecordMapper {

  /** A document, or empty when its model has no manifest. */
  Optional<DocumentRecord> document(JsonNode doc, boolean hasChildrenForPromotion);

  /** A page. */
  DocumentRecord page(JsonNode doc);

  /** A sound unit or a track — models with no manifest that still appear inside one. */
  DocumentRecord component(JsonNode doc);

  /** This Solr's uniqueKey field, which is also where a pid is read from. */
  String pidField();
}
