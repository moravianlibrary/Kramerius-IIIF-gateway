/*
 * What an instance calls its own licences, and what it says about them.
 *
 * Solr gives licence IDs — `dnnto`, `onsite`, `mzk_public-contract`. Those are internal names; a reader
 * wants "Díla nedostupná na trhu - online". The instance publishes the mapping at
 *
 *     GET /search/api/client/v7.0/ui-config/licenses
 *
 * a CLIENT endpoint, not an admin one. That matters: /search/api/admin/v7.0/licenses also answers
 * anonymously and looks tempting, but its `description` field is an internal note for administrators —
 * one MZK licence describes which physical location and document type it applies to, in Slovak. Putting
 * that in a manifest would leak operational notes to readers.
 *
 * Availability is uneven and version-dependent: cdk-api (7.2.2.1-dev7) serves it, MZK (7.2.2) answers
 * 404 "No such config". So this is best-effort — an instance without it keeps the bundled fallback
 * wording, and nothing breaks.
 *
 * The message pages are best-effort within the best-effort: the config points at per-language HTML, and
 * on the instance measured those URLs answered 404 themselves. So a label is usually available and a
 * text often is not.
 */
package cz.mzk.iiif.adapter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LicenseConfig {

  private static final Logger LOG = LoggerFactory.getLogger(LicenseConfig.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * How a licence should read, what it says, and how hard it is to exercise.
   *
   * @param accessType "open", "login" or "terminal" — Kramerius' own vocabulary for what a reader
   *     has to do. Empty when the instance does not say.
   */
  public record License(
      String id,
      Map<String, List<String>> label,
      Map<String, List<String>> text,
      java.util.Optional<String> accessType) {

    public License {
      label = Map.copyOf(label);
      text = Map.copyOf(text);
    }

    /**
     * Where this licence sorts: the easiest route to the document first.
     *
     * <p>A document commonly carries several licences — at MZK, 153 008 monographs are both `dnnto`
     * and `onsite` — and they are alternative ways in, not competing claims. Listing the open one
     * before the one that needs a login, and that before the one that needs a visit, means a reader
     * reads the cheapest route they qualify for first.
     *
     * <p>An unranked licence sorts last rather than first: we do not know what it demands, so we do
     * not advertise it as the easy option.
     */
    public int rank() {
      return switch (accessType.orElse("")) {
        case "open" -> 0;
        case "login" -> 1;
        case "terminal" -> 2;
        default -> 3;
      };
    }
  }

  private LicenseConfig() {}

  /** Licences by id, or empty when this instance does not publish them. */
  public static Map<String, License> fetch(HttpClient http, String baseUrl, Duration timeout) {
    String url = baseUrl + "/search/api/client/v7.0/ui-config/licenses";
    JsonNode root;
    try {
      HttpResponse<String> response =
          http.send(
              UserAgent.request(URI.create(url))
                  .timeout(timeout)
                  .header("Accept", "application/json")
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.info(
            "{} publishes no licence config ({}); using bundled wording",
            baseUrl,
            response.statusCode());
        return Map.of();
      }
      root = MAPPER.readTree(response.body());
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOG.warn("could not read the licence config of {}: {}", baseUrl, e.toString());
      return Map.of();
    }

    Map<String, License> licenses = new LinkedHashMap<>();
    for (JsonNode entry : root.path("licenses")) {
      String id = entry.path("id").asText(null);
      if (id == null || id.isBlank()) {
        continue;
      }
      licenses.put(
          id,
          new License(
              id,
              languageMap(entry.path("label")),
              messages(http, entry, timeout),
              java.util.Optional.ofNullable(entry.path("accessType").asText(null))
                  .filter(a -> !a.isBlank())));
    }
    LOG.info("{} describes {} licences", baseUrl, licenses.size());
    return Map.copyOf(licenses);
  }

  /** {"cs": "…", "en": "…"} as a language map. */
  private static Map<String, List<String>> languageMap(JsonNode node) {
    Map<String, List<String>> map = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            field -> {
              String value = field.getValue().asText("");
              if (!value.isBlank()) {
                map.put(field.getKey(), List.of(value));
              }
            });
    return map;
  }

  /**
   * The licence text, fetched from the HTML page the config points at.
   *
   * <p>Only the `unauthenticated` message is used: it is the one addressed to a reader who has not
   * signed in, which is exactly who is reading an anonymous manifest.
   */
  private static Map<String, List<String>> messages(
      HttpClient http, JsonNode entry, Duration timeout) {

    Map<String, List<String>> text = new LinkedHashMap<>();
    for (JsonNode page : entry.path("messagePages")) {
      if (!"unauthenticated".equals(page.path("key").asText(""))) {
        continue;
      }
      page.path("page")
          .fields()
          .forEachRemaining(
              field -> {
                String body = html(http, field.getValue().asText(null), timeout);
                if (body != null && !body.isBlank()) {
                  text.put(field.getKey(), List.of(body));
                }
              });
    }
    return text;
  }

  private static String html(HttpClient http, String url, Duration timeout) {
    if (url == null || url.isBlank()) {
      return null;
    }
    try {
      HttpResponse<String> response =
          http.send(
              UserAgent.request(URI.create(url)).timeout(timeout).build(),
              HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200 ? stripTags(response.body()) : null;
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return null;
    }
  }

  /**
   * The readable text of a fragment of HTML.
   *
   * <p>A manifest value is a string, not markup, so the tags have to go. This is deliberately crude
   * — these are short licence notices written by librarians, not arbitrary documents — and anything
   * it mangles is visible as text rather than dangerous.
   */
  static String stripTags(String html) {
    String text =
        html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
            .replaceAll("(?is)<br\\s*/?>|</p>|</div>|</li>", "\n")
            .replaceAll("(?s)<[^>]+>", " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"");

    List<String> lines = new ArrayList<>();
    for (String line : text.split("\n")) {
      String trimmed = line.replaceAll("\\s+", " ").trim();
      if (!trimmed.isEmpty()) {
        lines.add(trimmed);
      }
    }
    return String.join("\n", lines);
  }
}
