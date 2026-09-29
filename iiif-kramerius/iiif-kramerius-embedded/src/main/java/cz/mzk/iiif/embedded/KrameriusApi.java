/*
 * The slice of Kramerius' own internals this example pretends to have.
 *
 * Stubbed rather than depended on, so this module compiles anywhere without a Kramerius checkout — which
 * is the point: it proves the SEAM is implementable, not that any particular Kramerius release is.
 * An embedding implements these operations with the Kramerius classes available to it.
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

  /** A sound unit and its ordered tracks. */
  record MediaUnit(SolrDocument unit, List<SolrDocument> tracks) {

    public MediaUnit {
      tracks = List.copyOf(tracks);
    }
  }

  Optional<SolrDocument> byPid(String pid);

  /** Pages of a manifest, in reading order. */
  List<SolrDocument> pagesOf(String pid);

  /** Members of a collection, in display order. */
  List<SolrDocument> membersOf(String pid);

  /** Sound units of a recording, each with its ordered tracks. */
  List<MediaUnit> mediaUnitsOf(String pid);

  /**
   * The dimensions of a page image.
   *
   * <p>In a real deployment this is the one method whose cost varies: reading from Akubra locally
   * is nearly free, but most Czech libraries serve tiles from a separate image server, so this is
   * still an info.json per page. Embedding therefore does not remove the need for a dimension
   * cache.
   */
  Optional<int[]> imageSize(String pagePid);

  /** The base URL this Kramerius serves its own streams from. */
  String baseUrl();
}
