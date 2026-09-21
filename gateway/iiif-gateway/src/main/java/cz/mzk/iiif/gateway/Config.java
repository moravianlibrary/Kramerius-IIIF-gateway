/*
 * Every setting, read once at startup into one immutable record.
 *
 * 12-factor: the environment is read HERE and nowhere else, so there is no getenv scattered through the
 * code and no way for two call sites to disagree about a default. A bad value fails loudly at startup
 * rather than quietly at the first request that needs it.
 *
 * .env.example documents every key and is the file operators actually read; when this and that disagree,
 * that one is the bug.
 */
package cz.mzk.iiif.gateway;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record Config(
    String baseUrl,
    String digitalLibraryUrl,
    Optional<String> registryUrl,
    Map<String, StaticLibrary> staticLibraries,
    Duration registryTtl,
    Duration libraryCheckInterval,
    int port,
    int adminPort,
    Duration dimensionWait,
    int fallbackCanvasSize,
    int imageInfoConcurrency,
    Optional<Database> database,
    Duration syncInterval,
    Duration syncOverlap,
    int workerBatch,
    int upstreamConcurrency,

    /**
     * Requests per second this gateway will make to any ONE upstream image server.
     *
     * <p>Separate from upstreamConcurrency, which bounds requests in flight rather than requests
     * made. 0 disables pacing. See UpstreamPacer.
     */
    int upstreamRps,

    /** Solr rows per round trip. An internal batch size — never an output cap. */
    int solrPageSize,

    /** A sanity ceiling on children per document, far above any real one. */
    int maxItems,

    /** How many rendered manifests to keep in memory. 0 disables the cache. */
    int manifestCacheSize,
    Duration connectTimeout,
    Duration readTimeout,
    double rateLimitRps,
    int rateLimitBurst,
    /**
     * How long a failed page lookup is believed before being retried.
     *
     * <p>No failure is permanent: a 403 is about the caller, a 404 against a page Solr says has
     * tiles is an inconsistency, and a timeout is neither. Long enough that a restricted document
     * is not re-probed on every request, short enough that moving the worker onto the library
     * network fixes it within a day.
     */
    Duration failedRetry,
    Duration manifestTtl,

    /** Extra languages, merged over the bundled ones at startup. */
    Optional<String> translationsDir,

    /**
     * Per-library catalogue mappings, added over the bundled ones at startup.
     *
     * <p>One <code>&lt;code&gt;.json</code> per library. Only MZK ships built in, because it is the
     * only mapping anyone has verified — and it used to be a literal in Java, which meant no other
     * library could have a catalogue link without a code change.
     */
    Optional<String> cataloguesDir,

    /**
     * Model-table overrides, merged over the bundled tables at startup.
     *
     * <p>One <code>kramerius-&lt;floor&gt;.json</code> per version floor, holding only the models
     * that differ. Kramerius adds models between releases and an unknown one degrades to <code>
     * unsupported</code>, so this is what lets an operator describe a new model without waiting for
     * a gateway release. A table that does not parse stops the gateway — see ModelDirectory for why
     * that is stricter than the translations.
     */
    Optional<String> modelsDir,

    /**
     * Library codes this deployment refuses to serve, whatever the registry says.
     *
     * <p>The registry's `alive` flag is the registry's opinion, formed from wherever it runs, and
     * it says nothing about whether an instance can actually be served. `mendelu` 5.3.2 is the
     * worked example: its Solr answers 500 to an image probe and returns no `nextCursorMark` at
     * all, so it has no usable Image API and its dimension sync cannot be walked —
     * `scripts/libraries.py` already drops it from the generated static list for the first reason.
     * Registry mode reads no such file, so without this it would go on being served.
     *
     * <p>Empty by default, and no code names an institution: which libraries a deployment carries
     * is the deployment's business, not ours.
     */
    java.util.Set<String> excludedLibraries) {

  /**
   * A library declared by the operator rather than looked up, for a deployment that does not depend
   * on the registry at runtime.
   *
   * <p>From IIIF_LIBRARIES inline, or from IIIF_LIBRARIES_FILE — one record either way, so nothing
   * below this point knows which. Inline suits one library; a file suits fifty, and is what
   * scripts/libraries.py generates.
   *
   * @param names the institution's name by language tag, empty when the operator gave none. Without
   *     it there is nothing to call the institution but its code, which is not a name — see
   *     Libraries.profile.
   */
  public record StaticLibrary(
      String code,
      String url,
      String version,
      Map<String, List<String>> names,

      /**
       * Requests per second for THIS institution's image server, overriding IIIF_UPSTREAM_RPS.
       *
       * <p>One global number is the wrong shape for the thing it protects. The registry's 56
       * libraries range from the National Library to a forestry archive, and a rate a large
       * institution would not notice is a load one small one might. It is also the knob an operator
       * reaches for after a phone call, which means it has to be settable per library without
       * changing what every other library gets.
       *
       * <p>Empty means the global value. 0 means no pacing at all, as it does globally.
       */
      java.util.OptionalInt rps) {

    public StaticLibrary {
      names = Map.copyOf(names);
    }

    /** Without a rate, for callers that have no opinion. */
    public StaticLibrary(String code, String url, String version, Map<String, List<String>> names) {
      this(code, url, version, names, java.util.OptionalInt.empty());
    }
  }

  public record Database(String url, String user, String password, int poolSize) {}

  public Config {
    staticLibraries = Map.copyOf(staticLibraries);
    excludedLibraries = java.util.Set.copyOf(excludedLibraries);
  }

  public static Config fromEnvironment() {
    return from(System.getenv());
  }

  /** Takes the environment as a map so it can be tested without touching the real one. */
  public static Config from(Map<String, String> env) {
    Reader read = new Reader(env);

    Optional<String> registry = read.optional("IIIF_REGISTRY_URL");
    Map<String, StaticLibrary> declared = new LinkedHashMap<>();

    // A file first, then the inline value over it. Both describe the same thing, and the reason to
    // allow both is that the file is generated: overriding one library — a test host, a library
    // mid-upgrade — should not mean editing a file the next `make libraries` rewrites.
    read.optional("IIIF_LIBRARIES_FILE")
        .ifPresent(file -> declared.putAll(LibraryFile.load(Path.of(file))));
    declared.putAll(parseLibraries(read.optional("IIIF_LIBRARIES")));

    // Declaring libraries statically means the registry is not consulted at all, which is what
    // makes a deployment possible with no runtime dependency on a shared service.
    if (!declared.isEmpty()) {
      registry = Optional.empty();
    }
    if (registry.isEmpty() && declared.isEmpty()) {
      throw new IllegalStateException(
          "set IIIF_REGISTRY_URL, IIIF_LIBRARIES_FILE or IIIF_LIBRARIES: nothing to serve");
    }

    return new Config(
        read.string("IIIF_BASE_URL", "https://iiif.digitalniknihovna.cz"),
        read.string("IIIF_DIGITAL_LIBRARY_URL", "https://www.digitalniknihovna.cz"),
        registry,
        declared,
        Duration.ofMinutes(read.number("IIIF_REGISTRY_TTL_MINUTES", 360)),
        Duration.ofMinutes(read.number("IIIF_LIBRARY_CHECK_MINUTES", 30)),
        read.number("IIIF_PORT", 8080),
        read.number("IIIF_ADMIN_PORT", 8081),
        Duration.ofSeconds(read.number("IIIF_DIMENSION_WAIT_SECONDS", 10)),
        read.number("IIIF_FALLBACK_CANVAS_SIZE", 1000),
        read.number("IIIF_IMAGE_INFO_CONCURRENCY", 10),
        database(read),
        Duration.ofMinutes(read.number("IIIF_SYNC_INTERVAL_MINUTES", 60)),
        Duration.ofMinutes(read.number("IIIF_SYNC_OVERLAP_MINUTES", 10)),
        read.number("IIIF_WORKER_BATCH", 32),
        read.number("IIIF_UPSTREAM_CONCURRENCY", 10),
        read.number("IIIF_UPSTREAM_RPS", 20),
        read.number("IIIF_SOLR_PAGE_SIZE", 500),
        read.number("IIIF_MAX_ITEMS", 50_000),
        read.number("IIIF_MANIFEST_CACHE_SIZE", 500),
        Duration.ofSeconds(read.number("IIIF_HTTP_CONNECT_TIMEOUT_SECONDS", 10)),
        Duration.ofSeconds(read.number("IIIF_HTTP_READ_TIMEOUT_SECONDS", 60)),
        read.number("IIIF_RATE_LIMIT_RPS", 20),
        read.number("IIIF_RATE_LIMIT_BURST", 100),
        Duration.ofSeconds(read.number("IIIF_FAILED_LOOKUP_RETRY_SECONDS", 86_400)),
        Duration.ofMinutes(read.number("IIIF_MANIFEST_TTL_MINUTES", 60)),
        read.optional("IIIF_TRANSLATIONS_DIR"),
        read.optional("IIIF_CATALOGUES_DIR"),
        read.optional("IIIF_MODELS_DIR"),
        codes(read.optional("IIIF_EXCLUDED_LIBRARIES")));
  }

  /** A comma-separated list of library codes, trimmed and lower-cased. Empty when unset. */
  private static java.util.Set<String> codes(Optional<String> raw) {
    return raw.filter(value -> !value.isBlank())
        .map(
            value ->
                java.util.Arrays.stream(value.split(","))
                    .map(code -> code.trim().toLowerCase(java.util.Locale.ROOT))
                    .filter(code -> !code.isEmpty())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()))
        .orElseGet(java.util.Set::of);
  }

  /**
   * The size store is optional.
   *
   * <p>Without it the gateway still serves correct manifests — it just re-reads every dimension
   * from the image server on every request, which is fine for a small instance and unusable for a
   * large one. Making it optional is what lets the web role start before Postgres exists.
   */
  private static Optional<Database> database(Reader read) {
    return read.optional("IIIF_DB_URL")
        .map(
            url ->
                new Database(
                    url,
                    read.string("IIIF_DB_USER", "iiif"),
                    read.string("IIIF_DB_PASSWORD", ""),
                    read.number("IIIF_DB_POOL_SIZE", 10)));
  }

  /** `code=url|version`, comma-separated. */
  private static Map<String, StaticLibrary> parseLibraries(Optional<String> raw) {
    Map<String, StaticLibrary> libraries = new LinkedHashMap<>();
    raw.filter(value -> !value.isBlank())
        .ifPresent(
            value -> {
              for (String entry : value.split(",")) {
                List<String> parts = List.of(entry.trim().split("[=|]", 4));
                if (parts.size() < 3) {
                  throw new IllegalStateException(
                      "IIIF_LIBRARIES entries look like code=url|version[|cs:Name;en:Name], got: "
                          + entry);
                }
                libraries.put(
                    parts.get(0),
                    new StaticLibrary(
                        parts.get(0),
                        parts.get(1),
                        parts.get(2),
                        parts.size() > 3 ? parseNames(parts.get(3)) : Map.of()));
              }
            });
    return libraries;
  }

  /**
   * "cs:Moravská zemská knihovna;en:Moravian Library" into a language map.
   *
   * <p>Language-tagged because an institution's name is not one string: the registry carries a
   * Czech (or Slovak, or German) name and an English one, and a manifest should carry both. An
   * entry with no tag is rejected rather than guessed at — inventing a language is exactly the bug
   * this avoids.
   */
  private static Map<String, List<String>> parseNames(String raw) {
    Map<String, List<String>> names = new LinkedHashMap<>();
    for (String part : raw.split(";")) {
      String trimmed = part.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int colon = trimmed.indexOf(':');
      if (colon < 1) {
        throw new IllegalStateException(
            "a library name needs a language tag, like cs:Moravská zemská knihovna, got: " + part);
      }
      names.put(trimmed.substring(0, colon).trim(), List.of(trimmed.substring(colon + 1).trim()));
    }
    return Map.copyOf(names);
  }

  private record Reader(Map<String, String> env) {

    Optional<String> optional(String key) {
      return Optional.ofNullable(env.get(key)).map(String::trim).filter(v -> !v.isEmpty());
    }

    String string(String key, String fallback) {
      return optional(key).orElse(fallback);
    }

    int number(String key, int fallback) {
      return optional(key)
          .map(
              value -> {
                try {
                  return Integer.parseInt(value);
                } catch (NumberFormatException e) {
                  throw new IllegalStateException(key + " must be a number, got: " + value, e);
                }
              })
          .orElse(fallback);
    }
  }
}
