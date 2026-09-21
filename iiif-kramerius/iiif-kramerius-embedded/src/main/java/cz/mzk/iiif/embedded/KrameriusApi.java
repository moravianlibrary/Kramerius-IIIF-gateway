/*
 * The slice of Kramerius' own internals this example pretends to have.
 *
 * Stubbed rather than depended on, so this module compiles anywhere without a Kramerius checkout — which
 * is the point: it proves the SEAM is implementable, not that any particular Kramerius release is.
 * docs/KRAMERIUS.md maps each method onto the real class that provides it.
 *
 * Notice what is NOT here: no HTTP, no adapter selection, no registry, no version negotiation. An
 * application talking to its own repository knows what version it is.
 */
package cz.mzk.iiif.embedded;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface KrameriusApi {

  /** One Solr document, as Kramerius' own SolrAccess would return it. */
  record SolrDocument(String pid, String model, Map<String, List<String>> fields) {

    public SolrDocument {
      fields = Map.copyOf(fields);
    }

    public Optional<String> first(String field) {
      List<String> values = fields.get(field);
      return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    public List<String> all(String field) {
      return fields.getOrDefault(field, List.of());
    }
  }

  Optional<SolrDocument> byPid(String pid);

  /** Children of one parent, already in reading order. */
  List<SolrDocument> childrenOf(String pid, String model);

  /**
   * The dimensions of a page image.
   *
   * <p>In a real deployment this is the one method whose cost varies: reading from Akubra locally
   * is nearly free, but most Czech libraries serve tiles from a separate image server, so this is
   * still an info.json per page. See docs/KRAMERIUS.md — it is why embedding does not remove the
   * need for a dimension cache.
   */
  Optional<int[]> imageSize(String pagePid);

  /** The base URL this Kramerius serves its own streams from. */
  String baseUrl();
}
