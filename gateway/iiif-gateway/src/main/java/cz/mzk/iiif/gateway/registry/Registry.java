/*
 * Reads the shared library registry.
 *
 * Fetched once and cached for IIIF_REGISTRY_TTL_MINUTES, not per request. v1 fetched it on every single
 * manifest request, which made the gateway's availability depend on the registry's on every page view.
 *
 * A failed refresh keeps serving the previous snapshot. That is deliberate: the registry going down
 * should not take fifty libraries with it, and the data changes on the order of months.
 */
package cz.mzk.iiif.gateway.registry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Registry {

  private static final Logger LOG = LoggerFactory.getLogger(Registry.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Institutions whose registry `name` is not Czech.
   *
   * <p>Small and explicit rather than guessed from the URL, because getting it wrong is worse than
   * not knowing: a Slovak name tagged `cs` is a false claim a viewer acts on. Everything else
   * defaults to Czech, which is true for the other fifty-odd entries.
   */
  private static final Map<String, String> NAME_LANGUAGE =
      Map.of("dikda", "sk", "snk", "sk", "cvti", "sk", "eod", "de");

  private final HttpClient http;
  private final URI url;
  private final Duration ttl;
  private final Duration timeout;

  private volatile Map<String, RegistryEntry> entries = Map.of();
  private volatile Instant fetchedAt = Instant.EPOCH;

  public Registry(HttpClient http, String url, Duration ttl, Duration timeout) {
    this.http = http;
    this.url = URI.create(url);
    this.ttl = ttl;
    this.timeout = timeout;
  }

  public Optional<RegistryEntry> find(String code) {
    return Optional.ofNullable(all().get(code));
  }

  public Map<String, RegistryEntry> all() {
    if (Instant.now().isAfter(fetchedAt.plus(ttl))) {
      refresh();
    }
    // Free: parse() already produced an immutable map, and Map.copyOf returns it unchanged.
    return Map.copyOf(entries);
  }

  /** Public so the health checker can force a refresh on its own schedule. */
  public synchronized void refresh() {
    try {
      HttpRequest request =
          cz.mzk.iiif.adapter.UserAgent.request(url)
              .timeout(timeout)
              .header("Accept", "application/json")
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("registry returned " + response.statusCode());
      }
      entries = parse(MAPPER.readTree(response.body()));
      fetchedAt = Instant.now();
      LOG.info("registry refreshed: {} libraries", entries.size());
    } catch (Exception e) {
      // Keep the previous snapshot. A registry outage must not take every library down with it.
      if (Thread.currentThread().isInterrupted()) {
        Thread.currentThread().interrupt();
      }
      LOG.warn(
          "registry refresh failed, keeping {} cached entries: {}", entries.size(), e.toString());
    }
  }

  static Map<String, RegistryEntry> parse(JsonNode root) {
    JsonNode rows = root.isArray() ? root : firstArray(root);
    Map<String, RegistryEntry> parsed = new LinkedHashMap<>();
    for (JsonNode row : rows) {
      String code = row.path("code").asText(null);
      String url = row.path("url").asText(null);
      if (code == null || url == null || code.isBlank() || url.isBlank()) {
        continue;
      }
      parsed.put(
          code,
          new RegistryEntry(
              code,
              row.path("name").asText(code),
              text(row, "name_en"),
              NAME_LANGUAGE.getOrDefault(code, "cs"),
              url,
              text(row, "version"),
              row.path("alive").asBoolean(true),
              text(row, "logo"),
              text(row, "web"),
              text(row, "new_client_url")));
    }
    return Map.copyOf(parsed);
  }

  /**
   * The API has wrapped the list in a envelope key before; find it rather than assume the shape.
   */
  private static JsonNode firstArray(JsonNode root) {
    for (String key : Set.of("data", "libraries", "items", "results")) {
      if (root.path(key).isArray()) {
        return root.path(key);
      }
    }
    throw new IllegalStateException("no library array in the registry response");
  }

  private static Optional<String> text(JsonNode row, String field) {
    String value = row.path(field).asText(null);
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
  }
}
