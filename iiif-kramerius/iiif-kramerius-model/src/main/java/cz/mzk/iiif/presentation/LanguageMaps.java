/*
 * Combining language maps.
 *
 * Needed because a value can be assembled from several sources that do not share a language set — an
 * institution's name is in Czech and English, a license text might be in Slovak only, and the result has
 * to say something sensible in each language either provides.
 *
 * A language that only one side has still gets an entry, built from that side alone: a partial answer in
 * the reader's language beats a complete one in a language they do not read.
 */
package cz.mzk.iiif.presentation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class LanguageMaps {

  private LanguageMaps() {}

  /**
   * Joins the values of each map, per language, with {@code separator}.
   *
   * <p>Null and empty maps are ignored, and the result is null when nothing was contributed, so a
   * caller can omit the block on the same rule the rest of the model follows.
   */
  public static Map<String, List<String>> join(
      String separator, List<Map<String, List<String>>> maps) {

    TreeSet<String> languages = new TreeSet<>();
    for (Map<String, List<String>> map : maps) {
      if (map != null) {
        languages.addAll(map.keySet());
      }
    }
    if (languages.isEmpty()) {
      return null;
    }

    Map<String, List<String>> joined = new LinkedHashMap<>();
    for (String language : languages) {
      StringBuilder text = new StringBuilder();
      for (Map<String, List<String>> map : maps) {
        if (map == null) {
          continue;
        }
        List<String> values = map.get(language);
        if (values == null || values.isEmpty()) {
          continue;
        }
        if (!text.isEmpty()) {
          text.append(separator);
        }
        text.append(String.join(separator, values));
      }
      if (!text.isEmpty()) {
        joined.put(language, List.of(text.toString()));
      }
    }
    return joined.isEmpty() ? null : Collections.unmodifiableMap(joined);
  }
}
