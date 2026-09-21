/*
 * Serialises a document to JSON.
 *
 * The mapper is configured once and shared: Jackson's ObjectMapper is thread-safe after configuration
 * and expensive to build, so a new one per request would be a real cost on a service whose whole job is
 * emitting JSON.
 *
 * Key order comes from the record component order in Iiif, not from anything here — see that file.
 * Nothing else is configured, deliberately: no custom serialisers, no annotations beyond NON_NULL, so
 * what a record says is what the JSON says.
 */
package cz.mzk.iiif.presentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

public final class ManifestWriter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final ObjectWriter COMPACT = MAPPER.writer();

  private ManifestWriter() {}

  /** What the service returns. */
  public static String toJson(Iiif.Document document) {
    return write(COMPACT, document);
  }

  private static String write(ObjectWriter writer, Iiif.Document document) {
    try {
      return writer.writeValueAsString(document);
    } catch (JsonProcessingException e) {
      // Every component is a record of plain types, so this cannot happen without a programming
      // error.
      throw new IllegalStateException("could not serialise manifest " + document.id(), e);
    }
  }
}
