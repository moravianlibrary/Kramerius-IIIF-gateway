/*
 * What a library's image server actually offers, asked once per library.
 *
 * Two questions, one set of requests: IS there a usable Image API, and WHICH version does it speak.
 * They are the same probe because the evidence is the same — a page's info.json — and asking twice
 * would double the load on somebody else's server for no new information.
 *
 * WHICH VERSION. Measured live across K7: 7.0.39, 7.0.41 and 7.1.7 answer Image API 2, while both 7.2.2
 * instances tested answer ImageService3 / level2. That is consistent with a 7.2 boundary, but two
 * instances cannot prove one and an operator can replace the image server independently of the Kramerius
 * release — so this is detected rather than declared by an adapter, exactly like the SolrCloud cursor
 * tiebreaker.
 *
 * WHETHER AT ALL. This is the K5 question. Of the 11 alive K5 libraries, lmda, dsmo and kvkli serve a
 * complete Image API 2 with real dimensions; ndk.cz and vugtk answer 200 with a zero-byte body and 404
 * every image request; nlk, cuni_fsv and mendelu answer 500. The Kramerius version does not predict which
 * — 5.8.1 works, 5.8.4.4 is empty, 5.3.2 is a 500 — so it is a property of the deployment and has to be
 * measured. K7 is not asked this question: an adapter whose Image API is a given says so, and a probe
 * that failed there would mean the sample was unlucky, not that the service is missing.
 *
 * The pages probed must be PUBLIC, and that is the whole subtlety. A restricted page's info.json answers
 * 403, which is indistinguishable here from every other answer — and it is not a corner case: the first
 * three tiled pages MZK returns for an unfiltered query are all `private`, so detection fell back to 2
 * against a 7.2.2 instance that actually serves ImageService3, and every canvas then declared the wrong
 * service.
 *
 * Five pages are sampled and all of them vote, because one public page can be broken, unrepresentative,
 * or mid-migration — and because a disagreement is worth saying out loud rather than resolving by
 * whichever request finished first.
 */
package cz.mzk.iiif.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.adapter.SolrClient;
import cz.mzk.iiif.adapter.SolrSchema;

final class ImageApi {

  private static final Logger LOG = LoggerFactory.getLogger(ImageApi.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** How many public pages to ask. Every one that answers gets a vote. */
  private static final int SAMPLES = 5;

  /**
   * What the probe found.
   *
   * @param available whether any sampled page described an image, i.e. whether declaring an
   *     ImageService on a canvas would be a true claim
   * @param version the Image API version the majority of answering pages reported; 2 when nothing
   *     answered, which every instance measured accepts for both /full/full/ and /full/max/
   */
  record Profile(boolean available, int version) {

    static Profile unavailable() {
      return new Profile(false, 2);
    }

    /** For an adapter whose Image API is not in question — only the version is. */
    Profile assumingAvailable() {
      return new Profile(true, version);
    }
  }

  private ImageApi() {}

  /**
   * Ask one library's image server what it offers.
   *
   * <p>Falls back to {@link Profile#unavailable()} whenever the evidence does not support a claim:
   * no public page to ask about, nothing answered, or every answer unreadable. A caller whose
   * adapter guarantees the service calls {@link Profile#assumingAvailable()} on the result — a
   * probe that finds nothing on K7 means the sample was unlucky, not that the Image API vanished.
   */
  static Profile probe(
      HttpClient http, String baseUrl, SolrClient solr, SolrSchema schema, Duration timeout) {
    List<String> pids;
    try {
      pids = publicTiledPages(solr, schema);
    } catch (Exception e) {
      interruptIfNeeded(e);
      LOG.warn("could not list pages of {}: {}", baseUrl, e.toString());
      return Profile.unavailable();
    }

    if (pids.isEmpty()) {
      // Nothing public to ask about. Also the case for a library that is entirely restricted.
      LOG.info("{} has no public tiled page to probe", baseUrl);
      return Profile.unavailable();
    }

    int saidTwo = 0;
    int saidThree = 0;
    for (String pid : pids) {
      try {
        JsonNode info =
            MAPPER.readTree(get(http, baseUrl + "/search/iiif/" + pid + "/info.json", timeout));
        // A 200 with an empty body parses to a missing node, and a server that describes no image
        // has not got one. Both are the K5 failure, and neither is a vote.
        if (info.path("width").asInt(0) <= 0 || info.path("height").asInt(0) <= 0) {
          LOG.debug("{}: info.json for {} described no image", baseUrl, pid);
          continue;
        }
        if (info.path("@context").asText("").contains("/image/3/")) {
          saidThree++;
        } else {
          saidTwo++;
        }
      } catch (Exception e) {
        interruptIfNeeded(e);
        LOG.debug("{}: info.json for {} did not answer: {}", baseUrl, pid, e.toString());
      }
    }

    if (saidTwo == 0 && saidThree == 0) {
      LOG.info("no page of {} described an image; no image service will be declared", baseUrl);
      return Profile.unavailable();
    }

    if (saidTwo > 0 && saidThree > 0) {
      LOG.warn(
          "{} answers Image API 2 on {} page(s) and 3 on {} — mixed image servers?",
          baseUrl,
          saidTwo,
          saidThree);
    }

    // An even split resolves downwards: claiming 3 on a v2 server advertises level2 capabilities
    // the
    // server does not have, while claiming 2 on a v3 server asks for less than it can give.
    int version = saidThree > saidTwo ? 3 : 2;
    LOG.info(
        "{} serves Image API {} ({} of {} sampled pages answered)",
        baseUrl,
        version,
        saidTwo + saidThree,
        pids.size());
    return new Profile(true, version);
  }

  private static void interruptIfNeeded(Exception e) {
    if (e instanceof InterruptedException) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Public tiled pages to probe, through the schema so the query works on both Kramerius versions.
   *
   * <p>The filters spelled themselves out here once, in K7's field names, which on K5 matched
   * nothing and made every probe inconclusive — the same bug {@code SolrSchema.tiledFilter} exists
   * to prevent.
   */
  private static List<String> publicTiledPages(SolrClient solr, SolrSchema schema) {
    String filter =
        schema.modelField()
            + ":"
            + schema.pageModel()
            + " AND "
            + schema.tiledFilter()
            + " AND "
            + schema.publicFilter();
    return solr
        .walkLimited(filter, schema.pidField() + " asc", List.of(schema.pidField()), SAMPLES)
        .stream()
        .map(doc -> doc.path(schema.pidField()).asText(null))
        .filter(pid -> pid != null && !pid.isBlank())
        .toList();
  }

  private static String get(HttpClient http, String url, Duration timeout) throws Exception {
    HttpRequest request =
        cz.mzk.iiif.adapter.UserAgent.request(URI.create(url))
            .timeout(timeout)
            .header("Accept", "application/json")
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
    }
    return response.body();
  }
}
