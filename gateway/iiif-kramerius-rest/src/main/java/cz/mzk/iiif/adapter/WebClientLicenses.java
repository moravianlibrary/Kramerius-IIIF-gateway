/*
 * Licence names and texts from the web client, for instances whose API does not serve them.
 *
 * The API route (LicenseConfig) only exists in newer 7.2.x: cdk-api on 7.2.2.1-dev7 answers it, MZK on
 * 7.2.2 answers 404 for every ui-config key. But MZK does publish its licences — through the client,
 * which serves one HTML notice per licence per language at a predictable path:
 *
 *     {client}/assets/shared/licences/{library}/{licence}.{language}.html
 *
 * and those notices carry the licence's own name in an <h2>:
 *
 *     <p><em>Přístup k tomuto dokumentu spadá pod následující licenci:</em></p>
 *     <h2><strong>Díla nedostupná na trhu – online</strong></h2>
 *
 * so the name and the text come from the same fetch, and nothing has to parse the client's JavaScript.
 * globals.js carries the same names in a `label` map, but it is a JS object literal with comments and
 * template placeholders — reading it would mean shipping a JavaScript parser to learn what the HTML
 * already says.
 *
 * Best-effort throughout: a library whose client does not follow this layout simply yields nothing and
 * keeps the bundled wording.
 */
package cz.mzk.iiif.adapter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebClientLicenses {

  private static final Logger LOG = LoggerFactory.getLogger(WebClientLicenses.class);

  /** The licence's own name, as the notice states it. */
  private static final Pattern HEADING = Pattern.compile("(?is)<h[12][^>]*>(.*?)</h[12]>");

  /**
   * How hard a licence is to exercise, as globals.js states it.
   *
   * <p>A narrow regex rather than a JavaScript parser: globals.js is an object literal with
   * comments and template placeholders, and the only thing needed from it is `id: { access: '…' }`.
   * The names come from the HTML notices, which say the same thing without needing to be parsed at
   * all.
   */
  /** The curated licence name, which the notices do not always agree with. */
  private static final Pattern LABEL_CS =
      Pattern.compile(
          "([A-Za-z][\\w.-]*)\\s*:\\s*\\{[^{}]*?label\\s*:\\s*\\{\\s*cs\\s*:\\s*'([^']*)'",
          Pattern.DOTALL);

  private static final Pattern ACCESS =
      Pattern.compile("([A-Za-z][\\w.-]*)\\s*:\\s*\\{\\s*access\\s*:\\s*'([a-z]+)'");

  private WebClientLicenses() {}

  /**
   * Fetches a notice per licence per language.
   *
   * @param clientBase the web client's origin, e.g. https://www.digitalniknihovna.cz
   * @param library the library code, which is part of the asset path
   * @param ids the licences this instance actually uses, so nothing is fetched speculatively
   */
  public static Map<String, LicenseConfig.License> fetch(
      HttpClient http,
      String clientBase,
      String library,
      List<String> ids,
      List<String> languages,
      Duration timeout) {

    if (clientBase == null || clientBase.isBlank() || ids.isEmpty()) {
      return Map.of();
    }
    String base =
        clientBase.endsWith("/") ? clientBase.substring(0, clientBase.length() - 1) : clientBase;

    String globals = get(http, base + "/assets/shared/globals.js", timeout);
    Map<String, String> access = matches(globals, ACCESS);
    Map<String, String> curated = matches(globals, LABEL_CS);
    Map<String, LicenseConfig.License> found = new LinkedHashMap<>();
    for (String id : ids) {
      Map<String, List<String>> labels = new LinkedHashMap<>();
      Map<String, List<String>> texts = new LinkedHashMap<>();

      for (String language : languages) {
        String html =
            get(
                http,
                base + "/assets/shared/licences/" + library + "/" + id + "." + language + ".html",
                timeout);
        if (html == null) {
          continue;
        }
        // The notice is taken whole, heading included: it is what the library wrote to explain this
        // licence, and the row shows that rather than a name of our choosing. The curated label is
        // kept only as a fallback for instances that publish a name but no notice at all.
        String name = "cs".equals(language) ? curated.get(id) : null;
        if (name == null) {
          name = heading(html).orElse(null);
        }
        if (name != null) {
          labels.put(language, List.of(name));
        }

        String body = LicenseConfig.stripTags(html);
        if (!body.isBlank()) {
          texts.put(language, List.of(body));
        }
      }
      if (!labels.isEmpty() || !texts.isEmpty()) {
        found.put(
            id,
            new LicenseConfig.License(
                id, labels, texts, java.util.Optional.ofNullable(access.get(id))));
      }
    }

    if (!found.isEmpty()) {
      LOG.info("{}: {} licences described by the web client at {}", library, found.size(), base);
    }
    return Map.copyOf(found);
  }

  /** First capture group to second, for whichever aspect of the client's config is wanted. */
  private static Map<String, String> matches(String source, Pattern pattern) {
    if (source == null) {
      return Map.of();
    }
    Map<String, String> found = new LinkedHashMap<>();
    Matcher m = pattern.matcher(source);
    while (m.find()) {
      found.putIfAbsent(m.group(1), m.group(2));
    }
    return Map.copyOf(found);
  }

  private static java.util.Optional<String> heading(String html) {
    Matcher m = HEADING.matcher(html);
    if (!m.find()) {
      return java.util.Optional.empty();
    }
    String name = LicenseConfig.stripTags(m.group(1)).replaceAll("\\s+", " ").trim();
    return name.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(name);
  }

  private static String get(HttpClient http, String url, Duration timeout) {
    try {
      HttpResponse<String> response =
          http.send(
              UserAgent.request(URI.create(url)).timeout(timeout).build(),
              HttpResponse.BodyHandlers.ofString());
      // The client is a single-page app: a missing asset is answered with index.html, not a 404, so
      // a
      // 200 that is not a licence notice has to be recognised by its content.
      if (response.statusCode() != 200 || response.body().contains("<!DOCTYPE html>")) {
        return null;
      }
      return response.body();
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return null;
    }
  }
}
