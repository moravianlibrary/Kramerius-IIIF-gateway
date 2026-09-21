/*
 * Real page dimensions, one info.json at a time.
 *
 * There is no bulk dimension source over HTTP — Solr indexes no dimensions and info.json is one request
 * per image — which is the single fact that shapes the whole caching design. At MZK that is 75.7 million
 * requests for full coverage, so this is deliberately polite: a bounded pool, and a per-library cap the
 * worker shares, because the gateway is a client of fifty third-party servers that never agreed to it.
 *
 * Results are correlated BY REQUEST INDEX, not by matching the @id the server returns. v1 compared an
 * https:// URL it had built against the http:// @id the image server actually reports, so nothing ever
 * matched and every canvas came out with "width": null. That defect is live in production today, and
 * index correlation makes the whole class of it impossible.
 *
 * A page with no usable answer is recorded rather than omitted: without that, it is indistinguishable
 * from a page nobody has asked about, and a permanently broken image would be re-requested on every
 * manifest request forever.
 *
 * The record EXPIRES rather than being permanent, because no failure here is a settled fact about the
 * image. A 403 is about the caller — Kramerius grants access by licence, and `onsite` means by IP, so the
 * same page answers differently depending on where the gateway runs. A 404 against a page Solr says has
 * tiles is an inconsistency, not a fact. A timeout is neither. Measured at MZK: one document answered for
 * 6 of its 202 pages anonymously and for all 202 from inside the library network.
 */
package cz.mzk.iiif.adapter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.document.ContentLocator;
import cz.mzk.iiif.document.ImageSize;
import cz.mzk.iiif.spi.ImageSizeSource;

public final class InfoJsonSizes implements ImageSizeSource {

  private static final Logger LOG = LoggerFactory.getLogger(InfoJsonSizes.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final ContentLocator urls;
  private final Duration timeout;

  /**
   * Politeness toward the upstream image server, shared by the web and worker roles.
   *
   * <p>Two limits, because one does not imply the other. The semaphore bounds how many requests are
   * IN FLIGHT; the pacer bounds how many are MADE. Ten concurrent lookups against a server
   * answering in 20 ms is 500 requests a second — inside the cap and nowhere near polite.
   */
  private final Semaphore concurrency;

  private final UpstreamPacer pacer;

  /**
   * The height an approximated canvas is scaled to.
   *
   * <p>Arbitrary, and that is fine: only the ratio between width and height carries meaning,
   * because a viewer scales the image to whatever the canvas declares.
   */
  private static final int NOMINAL_HEIGHT = 1000;

  /** What a 429 with no Retry-After is worth. Long enough to matter, short enough to recover. */
  private static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(5);

  public InfoJsonSizes(
      HttpClient http, ContentLocator urls, Duration timeout, int maxConcurrentRequests) {
    this(http, urls, timeout, maxConcurrentRequests, UpstreamPacer.UNLIMITED);
  }

  public InfoJsonSizes(
      HttpClient http,
      ContentLocator urls,
      Duration timeout,
      int maxConcurrentRequests,
      UpstreamPacer pacer) {
    this.http = http;
    this.urls = urls;
    this.timeout = timeout;
    this.concurrency = new Semaphore(maxConcurrentRequests);
    this.pacer = pacer;
  }

  @Override
  public Map<String, ImageSize> sizes(List<String> pagePids) {
    if (pagePids.isEmpty()) {
      return Map.of();
    }

    List<CompletableFuture<ImageSize>> pending = pagePids.stream().map(this::fetch).toList();

    Map<String, ImageSize> resolved = new LinkedHashMap<>();
    for (int i = 0; i < pagePids.size(); i++) {
      // By index. Never by matching the returned @id — see the header comment.
      resolved.put(pagePids.get(i), pending.get(i).join());
    }
    return Map.copyOf(resolved);
  }

  private CompletableFuture<ImageSize> fetch(String pid) {
    // Before the permit, not after: pacing while holding one would idle the whole pool waiting for
    // a token, so the concurrency cap would silently become the rate limit.
    pacer.acquire();
    try {
      concurrency.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      // We never got as far as asking, so this says nothing about the image.
      return CompletableFuture.completedFuture(ImageSize.UNAVAILABLE);
    }

    return measure(pid)
        // The thumbnail is the fallback, not the primary: it gives the shape but not the scale, and
        // it
        // is only worth a second request when the image itself would not answer.
        .thenCompose(
            size -> size.isKnown() ? CompletableFuture.completedFuture(size) : approximate(pid))
        .whenComplete((size, error) -> concurrency.release());
  }

  /** The real dimensions, from the Image API. */
  private CompletableFuture<ImageSize> measure(String pid) {
    HttpRequest request =
        UserAgent.request(URI.create(urls.imageService(pid) + "/info.json"))
            .timeout(timeout)
            .header("Accept", "application/json")
            .build();

    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .handle(
            (response, error) -> {
              if (error != null) {
                // A timeout or a connection failure is not a fact about the image.
                LOG.debug("info.json for {} failed: {}", pid, error.toString());
                return ImageSize.UNAVAILABLE;
              }
              if (response.statusCode() != 200) {
                observe(pid, response);
                return ImageSize.UNAVAILABLE;
              }
              return parse(response.body(), pid);
            });
  }

  /**
   * The shape, from the thumbnail, when the image itself would not answer.
   *
   * <p>Kramerius serves thumbnails from a different endpoint with different access rules — measured
   * at MZK, a page whose info.json answers 403 still serves a 2.7 KB thumbnail — so this recovers
   * the aspect ratio for material the gateway cannot otherwise measure.
   *
   * <p>Scaled to a nominal height, because the thumbnail's own scale carries no information: they
   * are generated to a fixed 256 px tall, so the ratio to the full image runs from 8.19 to 12.77.
   */
  private CompletableFuture<ImageSize> approximate(String pid) {
    HttpRequest request =
        UserAgent.request(URI.create(urls.thumbnail(pid)))
            .timeout(timeout)
            .header("Accept", "image/jpeg")
            .build();

    return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
        .handle(
            (response, error) -> {
              if (error != null || response.statusCode() != 200) {
                // Neither the image nor its thumbnail. Nothing is known about this page.
                if (error == null) {
                  observe(pid, response);
                }
                return ImageSize.UNAVAILABLE;
              }
              int[] size = JpegHeader.dimensions(response.body());
              if (size == null || size[1] <= 0) {
                LOG.debug("unreadable thumbnail for {}", pid);
                return ImageSize.UNAVAILABLE;
              }
              int height = NOMINAL_HEIGHT;
              int width = Math.max(1, Math.round((float) size[0] / size[1] * height));
              LOG.debug("{}: shape {}x{} from its thumbnail", pid, width, height);
              return ImageSize.approximate(width, height);
            });
  }

  /**
   * What a refusal means, and what to do about it.
   *
   * <p>Every one of these still records UNAVAILABLE — which expires, so nothing here freezes a
   * canvas at nominal size forever. The difference is in how loudly it is logged and whether we
   * slow down, because these are not the same event:
   *
   * <ul>
   *   <li><b>429, 503</b> — the server is telling us our rate is too high. That is better
   *       information than IIIF_UPSTREAM_RPS, which is only our guess about what an institution can
   *       take, so the pacer honours Retry-After and every other in-flight caller waits too.
   *   <li><b>422</b> — the server understood the request and cannot produce this image: a source
   *       file that is corrupt, truncated, or in a format the tiler will not accept. Unlike a 403
   *       it is not about who is asking, and unlike a 404 the object is there — so it is worth
   *       seeing in the log at INFO rather than being buried with the ordinary refusals, because it
   *       usually means something needs re-deriving on the library's side. We still try the
   *       thumbnail: it comes from a different pipeline and frequently still exists, which is
   *       exactly the case where an approximate shape beats a square guess.
   *   <li><b>401, 403</b> — about the caller, not the image. Ordinary here.
   *   <li>anything else — logged at DEBUG.
   * </ul>
   */
  private void observe(String pid, HttpResponse<?> response) {
    int status = response.statusCode();
    switch (status) {
      case 429, 503 -> {
        Duration wait = retryAfter(response);
        LOG.warn("{} asked us to slow down ({}), pausing {}s", pid, status, wait.toSeconds());
        pacer.backOff(wait);
      }
      case 422 -> LOG.info("{}: the image server cannot process this image (422)", pid);
      default -> LOG.debug("no image for {}: {}", pid, status);
    }
  }

  /**
   * Retry-After, in either of its two spellings, with a floor.
   *
   * <p>The header is optional and is a delta-seconds or an HTTP date; a server that sends neither
   * still gets a pause, because the 429 alone is enough to know the rate was wrong.
   */
  private static Duration retryAfter(HttpResponse<?> response) {
    return response
        .headers()
        .firstValue("Retry-After")
        .map(
            value -> {
              try {
                return Duration.ofSeconds(Long.parseLong(value.trim()));
              } catch (NumberFormatException notSeconds) {
                try {
                  return Duration.between(
                      java.time.Instant.now(),
                      java.time.ZonedDateTime.parse(
                              value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                          .toInstant());
                } catch (java.time.format.DateTimeParseException notADate) {
                  return DEFAULT_BACKOFF;
                }
              }
            })
        .filter(wait -> !wait.isNegative() && !wait.isZero())
        .orElse(DEFAULT_BACKOFF);
  }

  /**
   * Reads width and height, and which Image API version answered.
   *
   * <p>v2 uses a @context of iiif.io/api/image/2/context.json and v3 of image/3; both spell width
   * and height the same way, so only the version detection differs.
   */
  private static ImageSize parse(String body, String pid) {
    try {
      JsonNode info = MAPPER.readTree(body);
      int width = info.path("width").asInt(0);
      int height = info.path("height").asInt(0);
      if (width <= 0 || height <= 0) {
        // The server answered and described no image. That is a fact about the image.
        return ImageSize.UNAVAILABLE;
      }
      return ImageSize.of(width, height, apiVersion(info));
    } catch (Exception e) {
      // Some Kramerius 5 instances answer 200 with an empty body; a proxy can return an HTML error
      // page. Neither tells us the image is gone.
      LOG.debug("unreadable info.json for {}: {}", pid, e.toString());
      return ImageSize.UNAVAILABLE;
    }
  }

  private static int apiVersion(JsonNode info) {
    String context = info.path("@context").asText("");
    if (context.contains("/image/3/")) {
      return 3;
    }
    return context.contains("/image/2/") ? 2 : 3;
  }
}
