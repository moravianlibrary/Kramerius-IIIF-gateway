/*
 * Everything about one library that a manifest needs to say.
 *
 * A record rather than an interface, because it is data: whoever is hosting assembles it and hands it
 * over. The gateway builds it from a registry entry plus per-library configuration; Kramerius' own
 * application builds it from what it already knows about itself, and never implements anything.
 *
 * Note what is NOT here: the Kramerius base URL, the version, the Solr topology, the politeness caps,
 * the model table. Those are about *reaching* a library, which a manifest has no opinion about, and they
 * live with the adapters at level 4.
 *
 * `label` is a language map rather than a Czech name plus an English one, because the registry's `name`
 * is the institution's name in ITS OWN language — `dikda` is *Katalóg digitálnej knižnice*, which is
 * Slovak. Tagging that `cs` states something false that a viewer acts on.
 */
package cz.mzk.iiif.document;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record LibraryProfile(
    /** Registry code, and the first path segment of every id this gateway emits. */
    String code,

    /** Stable URI identifying the institution — its registry entry, or whatever the host uses. */
    String id,

    /** The institution's name, tagged with the languages it is actually in. */
    Map<String, List<String>> label,

    /** The institution's own website. */
    Optional<String> homepage,
    Optional<String> logo) {

  public LibraryProfile {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("a library profile needs a code");
    }
    label = Map.copyOf(label);
  }

  public static Builder of(String code, String id) {
    return new Builder(code, id);
  }

  public static final class Builder {
    private final String code;
    private final String id;
    private Map<String, List<String>> label = Map.of();
    private String homepage;
    private String logo;

    private Builder(String code, String id) {
      this.code = code;
      this.id = id;
    }

    public Builder label(Map<String, List<String>> value) {
      this.label = value == null ? Map.of() : value;
      return this;
    }

    public Builder homepage(String value) {
      this.homepage = value;
      return this;
    }

    public Builder logo(String value) {
      this.logo = value;
      return this;
    }

    public LibraryProfile build() {
      return new LibraryProfile(
          code, id, label, Optional.ofNullable(homepage), Optional.ofNullable(logo));
    }
  }
}
