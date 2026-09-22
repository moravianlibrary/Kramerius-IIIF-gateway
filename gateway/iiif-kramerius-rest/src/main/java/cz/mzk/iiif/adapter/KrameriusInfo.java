/*
 * What version an instance says it is, asked of the instance.
 *
 * The version selects the adapter, so it is the most load-bearing fact about a library — and it used to
 * arrive second-hand, from the shared registry or from a version somebody typed into a config file.
 *
 * NOT because the registry is unreliable. It is not: asked of all 55 of its libraries on 2026-09-22, the
 * 47 that answered /info reported exactly what the registry already said. Zero disagreements. (An earlier
 * measurement here claimed three, including svkos at 5.7.3 against a 7.1.3 instance. That was taken from
 * api.registr.digitalniknihovna.cz, a second deployment of the registry which is months stale for some
 * rows — different database, different ids. The live one is registr.digitalniknihovna.cz/api/libraries,
 * and it is what IIIF_REGISTRY_URL now names.)
 *
 * It is because the registry is a CACHE of this, refreshed on its own schedule, and we are one request
 * away from the source. Between a Kramerius upgrade and the registry noticing, an instance is served by
 * whatever adapter its old version chose; across a K5/K7 boundary that is not a degraded manifest but no
 * manifest at all. One request per library at resolve time removes the window entirely, and removes the
 * need for anyone to regenerate a file after a release round.
 *
 * Two paths, because the client API moved and the old one was not kept:
 *
 *     Kramerius 7   /search/api/client/v7.0/info
 *     Kramerius 5   /search/api/v5.0/info
 *
 * They discriminate cleanly — a K7 instance 404s the K5 path and a K5 instance 404s the K7 path, both
 * verified live — so which one answers is itself evidence of the family, independent of the number in
 * the body. K7 is tried first because it is the majority and because a K5 answer is the interesting
 * case worth the second request.
 *
 * Best-effort, like every other probe here. An instance that does not answer leaves the caller with
 * whatever it was configured with. That costs nothing in practice: of the 8 libraries that did not
 * answer /info, every single one also failed on `GET /` — they are down, behind Cloudflare, gone
 * (410), or Kramerius 4, which has no such endpoint and is refused anyway. There is no instance that
 * serves documents but withholds its version.
 */
package cz.mzk.iiif.adapter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KrameriusInfo {

  private static final Logger LOG = LoggerFactory.getLogger(KrameriusInfo.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Newest first: the majority case costs one request, K5 costs two. */
  private static final List<String> PATHS =
      List.of("/search/api/client/v7.0/info", "/search/api/v5.0/info");

  private KrameriusInfo() {}

  /**
   * The version this instance reports, or empty if it did not answer one.
   *
   * <p>Never throws. A library that cannot be asked is not a library that cannot be served — the
   * caller falls back to what it was told.
   */
  public static Optional<Version> version(HttpClient http, String baseUrl, Duration timeout) {
    for (String path : PATHS) {
      Optional<Version> found = ask(http, baseUrl + path, timeout);
      if (found.isPresent()) {
        LOG.debug("{} reports version {}", baseUrl, found.get().raw());
        return found;
      }
    }
    return Optional.empty();
  }

  private static Optional<Version> ask(HttpClient http, String url, Duration timeout) {
    try {
      HttpResponse<String> response =
          http.send(
              UserAgent.request(URI.create(url))
                  .timeout(timeout)
                  .header("Accept", "application/json")
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return Optional.empty();
      }
      // A 200 that is not JSON is the ordinary shape of "this path is handled by the web client":
      // several instances answer the single-page app for any unknown path rather than a 404.
      return Version.parse(MAPPER.readTree(response.body()).path("version").asText(""));
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOG.debug("{} did not answer a version: {}", url, e.toString());
      return Optional.empty();
    }
  }
}
