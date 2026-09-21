/*
 * What version an instance says it is, asked of the instance.
 *
 * The version selects the adapter, so it is the single most load-bearing fact about a library — and
 * until now it arrived second-hand, from the shared registry or from a config file somebody typed.
 * Both go stale, and the registry measurably has: asked of all 56 registry libraries on 2026-09-21,
 * 48 answered /info and 3 of those disagreed with the registry —
 *
 *     svkos   registry 5.7.3     actually 7.1.3      <- a DIFFERENT ADAPTER
 *     snk     registry 7.0.23    actually 7.0.39
 *     knav    registry 7.2.2.1   actually 7.2.2.3
 *
 * svkos is the one that matters: the registry has it a whole major version out, which would pick the
 * Kramerius50 adapter for an instance running Kramerius 7 — a different Solr schema, different field
 * names, different API paths, and a manifest that cannot be built. (It does not happen today only
 * because the registry also flags svkos as dead, so it is refused before any of this runs. That is
 * luck, not design: the same registry that has the version wrong is what decides it is dead.)
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
