/*
 * IIIF language maps for values whose language we do not know.
 *
 * Everything this gateway *authors* is tagged by the file it came from — see Translations — so the only
 * maps built here are `none` ones: record content out of the catalogue, whose language nobody told us.
 * Titles, author names, dates, publication statements. `none` is the honest tag for those, and IIIF
 * defines it for exactly this.
 *
 * There was a bilingual(czech, english) helper here. It is gone, and the reason is worth keeping: it
 * hardcoded the tags `cs` and `en`, which was wrong the moment it met the registry's Slovak libraries —
 * `dikda` is *Katalóg digitálnej knižnice*, and calling that Czech is a claim a viewer acts on. Values
 * that come with a language now carry it from their source; values that do not are `none`.
 *
 * Every factory returns null rather than an empty map when there is nothing to say, because the model
 * records are serialised NON_NULL: absent means the key does not appear, which is what the spec wants.
 * The Ruby emitted {"none": [null]} instead.
 */
package cz.mzk.iiif.presentation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LanguageMap {

  /** Content whose language is not known — which is all of it, here. */
  public static final String NONE = "none";

  private LanguageMap() {}

  /** A single untagged value, or null when there is nothing to say. */
  public static Map<String, List<String>> none(String value) {
    return isBlank(value) ? null : Map.of(NONE, List.of(value));
  }

  /** Several untagged values — authors, languages — or null when the list is empty. */
  public static Map<String, List<String>> none(List<String> values) {
    List<String> present =
        values == null ? List.of() : values.stream().filter(v -> !isBlank(v)).toList();
    return present.isEmpty() ? null : Map.of(NONE, present);
  }

  /**
   * An immutable copy that keeps its insertion order.
   *
   * <p>Map.copyOf would be wrong here: it does not preserve order, so `en` could serialise before
   * `cs` and the same document would produce different bytes on different runs — which breaks ETags
   * and makes a byte-comparison against a stored manifest meaningless.
   */
  public static Map<String, List<String>> immutable(Map<String, List<String>> map) {
    if (map == null) {
      return null;
    }
    LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
    map.forEach((language, values) -> copy.put(language, List.copyOf(values)));
    return Collections.unmodifiableMap(copy);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
