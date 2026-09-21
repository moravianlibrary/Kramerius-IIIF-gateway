/*
 * The strings this gateway writes into manifests, in every language it knows.
 *
 * One file per language, named for it: i18n/cs.json, i18n/en.json. The set of languages emitted is the
 * set of files loaded, so adding a language is adding a file — no code changes, no enum to extend, and
 * no rebuild, because the gateway also reads a configured directory at startup and merges it over the
 * bundled defaults.
 *
 * A key missing from one language is simply not emitted for that language rather than falling back to
 * another. A half-translated label is worse than an absent one: a viewer showing "Provided by" under a
 * Czech locale looks broken, whereas a viewer with only the Czech value picks it and reads correctly.
 *
 * Immutable once built. Loading is the host's business — this class parses what it is given and does no
 * file I/O of its own beyond its own classpath defaults, which keeps the library free of filesystem
 * policy.
 */
package cz.mzk.iiif.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Translations {

  private static final Logger LOG = LoggerFactory.getLogger(Translations.class);

  /** Languages bundled in the jar. Anything else has to be supplied by the host. */
  public static final List<String> BUNDLED = List.of("cs", "en");

  /** Keys beginning with this are documentation for whoever edits the file, not content. */
  private static final String COMMENT_PREFIX = "_";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** language -> key -> text. Sorted so output key order is stable across runs. */
  private final Map<String, Map<String, String>> byLanguage;

  private Translations(Map<String, Map<String, String>> byLanguage) {
    this.byLanguage = byLanguage;
  }

  /** The defaults shipped in the jar. */
  public static Translations bundled() {
    Map<String, Map<String, String>> loaded = new TreeMap<>();
    for (String language : BUNDLED) {
      String resource = "/i18n/" + language + ".json";
      try (InputStream in = Translations.class.getResourceAsStream(resource)) {
        if (in == null) {
          throw new IllegalStateException("bundled translation missing from the jar: " + resource);
        }
        loaded.put(language, parse(in));
      } catch (IOException e) {
        throw new UncheckedIOException("could not read " + resource, e);
      }
    }
    return new Translations(loaded);
  }

  /**
   * These translations with {@code overrides} merged over them, key by key.
   *
   * <p>Merging per key rather than per language means an operator can correct one label without
   * having to copy a whole file and then keep it in step with future releases.
   */
  public Translations mergedWith(Map<String, Map<String, String>> overrides) {
    Map<String, Map<String, String>> merged = new TreeMap<>();
    byLanguage.forEach((language, entries) -> merged.put(language, new TreeMap<>(entries)));

    overrides.forEach(
        (language, entries) -> {
          Map<String, String> target = merged.computeIfAbsent(language, key -> new TreeMap<>());
          entries.forEach(
              (key, value) -> {
                if (!key.startsWith(COMMENT_PREFIX)) {
                  target.put(key, value);
                }
              });
        });

    LOG.info("translations loaded for {}", merged.keySet());
    return new Translations(
        Collections.unmodifiableMap(
            merged.entrySet().stream()
                .collect(
                    TreeMap::new,
                    (map, entry) -> map.put(entry.getKey(), Map.copyOf(entry.getValue())),
                    TreeMap::putAll)));
  }

  /**
   * Parses one language file. Public so a host can read a directory without duplicating the format.
   */
  public static Map<String, String> parse(InputStream json) {
    try {
      JsonNode root = MAPPER.readTree(json);
      Map<String, String> entries = new TreeMap<>();
      root.fields()
          .forEachRemaining(
              field -> {
                if (!field.getKey().startsWith(COMMENT_PREFIX) && field.getValue().isTextual()) {
                  entries.put(field.getKey(), field.getValue().asText());
                }
              });
      return entries;
    } catch (IOException e) {
      throw new UncheckedIOException("not a readable translation file", e);
    }
  }

  /**
   * A language map for one key: every language that has it.
   *
   * <p>Null when no language does — the caller omits the block rather than emitting an empty one,
   * which is the same rule the rest of the model follows.
   */
  public Map<String, List<String>> lookup(String key) {
    Map<String, List<String>> map = new LinkedHashMap<>();
    byLanguage.forEach(
        (language, entries) -> {
          String text = entries.get(key);
          if (text != null && !text.isBlank()) {
            map.put(language, List.of(text));
          }
        });
    return map.isEmpty() ? null : Collections.unmodifiableMap(map);
  }

  /** A language map for a key whose text has {0}, {1} … placeholders. */
  public Map<String, List<String>> format(String key, Object... arguments) {
    Map<String, List<String>> map = new LinkedHashMap<>();
    byLanguage.forEach(
        (language, entries) -> {
          String template = entries.get(key);
          if (template != null && !template.isBlank()) {
            map.put(language, List.of(substitute(template, arguments)));
          }
        });
    return map.isEmpty() ? null : Collections.unmodifiableMap(map);
  }

  /**
   * A language map built by translating each of several values through a key prefix.
   *
   * <p>Used for lists whose members are codes rather than text — MARC language codes, model names.
   * A code with no entry shows through as itself, which keeps the gap visible rather than dropping
   * data.
   */
  public Map<String, List<String>> lookupAll(String keyPrefix, List<String> codes) {
    if (codes.isEmpty()) {
      return null;
    }
    Map<String, List<String>> map = new LinkedHashMap<>();
    byLanguage.forEach(
        (language, entries) ->
            map.put(
                language,
                codes.stream().map(code -> entries.getOrDefault(keyPrefix + code, code)).toList()));
    return map.isEmpty() ? null : Collections.unmodifiableMap(map);
  }

  private static String substitute(String template, Object... arguments) {
    String text = template;
    for (int i = 0; i < arguments.length; i++) {
      text = text.replace("{" + i + "}", String.valueOf(arguments[i]));
    }
    return text;
  }
}
