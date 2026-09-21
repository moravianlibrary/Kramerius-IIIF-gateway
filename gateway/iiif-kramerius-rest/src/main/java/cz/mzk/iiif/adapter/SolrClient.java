/*
 * Talking to one Kramerius instance's Solr through its client API.
 *
 * Two things here are load-bearing and neither is obvious:
 *
 * cursorMark, not start/rows. Deep start/rows paging degrades in Solr and can duplicate or skip rows if
 * the index changes mid-walk — a real risk while a library reindexes, and a 22-day backfill guarantees
 * overlap with one. cursorMark is stable for any document matching throughout. The cost is that `start`
 * must never be sent and every sort must end in the uniqueKey.
 *
 * The uniqueKey is DEPLOYMENT-dependent, not version-dependent: compositeId on SolrCloud, pid on
 * standalone, and they are mutually exclusive — sort=pid is a 500 on SolrCloud and sort=compositeId a
 * 400 on standalone. Kramerius' own OAI walk hardcodes compositeId, which is wrong for two thirds of the
 * registry. It is detected from responseHeader.zkConnected, which the client API passes through.
 *
 * Batch size 500 is ~p98 of documents in one round trip (p95 is 332 canvases, max 3568). It is an
 * INTERNAL batch size and never an output cap — v1 conflated the two with rows=1500 and silently
 * truncated a 3128-page monograph to 1500 canvases with no indication that 1628 were missing.
 */
package cz.mzk.iiif.adapter;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SolrClient {

  private static final Logger LOG = LoggerFactory.getLogger(SolrClient.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * ~p98 of documents in one round trip. Internal only — NEVER an output cap.
   *
   * <p>Conflating the two is what v1 did: its rows=1500 was both, so a 3128-page monograph served
   * 1500 canvases and said nothing about the other 1628. Overridable as IIIF_SOLR_PAGE_SIZE, for
   * tuning against a Solr that prefers a different round-trip size — it cannot change what is
   * served, only how many requests it takes.
   */
  public static final int DEFAULT_BATCH = 500;

  /**
   * A ceiling far above any real document (largest at MZK is 3568 items), so a misindexed or
   * pathological object is visible in the log rather than exhausting the heap.
   *
   * <p>Overridable as IIIF_MAX_ITEMS. Raising it is what an operator does when a legitimate
   * document genuinely exceeds it; lowering it is a way to cap memory on a small deployment.
   */
  public static final int DEFAULT_MAX_ITEMS = 50_000;

  /** K7's client API. K5 answers on a different path entirely — see Solr5Schema. */
  public static final String K7_PATH = "/search/api/client/v7.0/search";

  private final HttpClient http;
  private final String baseUrl;
  private final Duration timeout;
  private final String searchPath;

  /**
   * How THIS Kramerius spells its uniqueKey on a standalone Solr. K5 spells it `PID`.
   *
   * <p>It was a literal "pid" here, which is K7's spelling, and every cursored query against a K5
   * instance was therefore a 400: `sort=modified_date asc, pid asc` names a field that does not
   * exist. Manifests were unaffected — child walks sort client-side — so it only surfaced the first
   * time a K5 library was actually synced, which is to say the first time anyone tried.
   */
  private final String pidField;

  private final int batch;
  private final int maxItems;

  /** Detected once per library, because topology is not predicted by version. */
  private volatile String uniqueKey;

  public SolrClient(HttpClient http, String baseUrl, Duration timeout) {
    this(http, baseUrl, timeout, K7_PATH);
  }

  public SolrClient(HttpClient http, String baseUrl, Duration timeout, String searchPath) {
    this(http, baseUrl, timeout, searchPath, "pid", DEFAULT_BATCH, DEFAULT_MAX_ITEMS);
  }

  /** The K7 spelling of the uniqueKey, for callers that have no schema to hand. */
  public SolrClient(
      HttpClient http,
      String baseUrl,
      Duration timeout,
      String searchPath,
      int batch,
      int maxItems) {
    this(http, baseUrl, timeout, searchPath, "pid", batch, maxItems);
  }

  public SolrClient(
      HttpClient http,
      String baseUrl,
      Duration timeout,
      String searchPath,
      String pidField,
      int batch,
      int maxItems) {
    this.pidField = pidField;
    this.http = http;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.timeout = timeout;
    this.searchPath = searchPath;
    this.batch = batch;
    this.maxItems = maxItems;
  }

  /** The sanity ceiling this client enforces, for callers that page through it themselves. */
  public int maxItems() {
    return maxItems;
  }

  /**
   * The Solr uniqueKey for this deployment, detected and cached.
   *
   * <p>Falls back to this schema's pid field, which is the standalone answer and covers two thirds
   * of the registry — a wrong guess degrades to one failed query and a retry rather than to
   * silence.
   */
  public String uniqueKey() {
    if (uniqueKey == null) {
      uniqueKey = detectUniqueKey();
    }
    return uniqueKey;
  }

  /**
   * The cheapest query that proves anything: does the search API answer, and is it SolrCloud?
   *
   * <p>Unlike {@link #uniqueKey()} this THROWS when the instance does not answer, which is the
   * whole point of having it separately — uniqueKey() has to degrade to a guess so a manifest
   * request can still be served, and a health check that inherited that guess could never report a
   * library as unreachable. It reported everything as ready instead, including a host refusing
   * connections.
   */
  public boolean solrCloud() {
    return get(query("q=*:*", "rows=0"))
        .path("responseHeader")
        .path("zkConnected")
        .asBoolean(false);
  }

  private String detectUniqueKey() {
    try {
      boolean cloud = solrCloud();
      String detected = cloud ? "compositeId" : pidField;
      LOG.info(
          "{} is {}, cursor tiebreaker is {}",
          baseUrl,
          cloud ? "SolrCloud" : "standalone",
          detected);
      return detected;
    } catch (RuntimeException e) {
      LOG.warn(
          "could not detect Solr topology for {}, assuming standalone: {}", baseUrl, e.toString());
      return pidField;
    }
  }

  /**
   * How many documents match, without fetching any of them.
   *
   * <p>One request whatever the answer is, because rows=0 makes Solr return numFound and no
   * documents. This is what a status route should cost: fetching 3568 page records through eight
   * cursorMark round trips to arrive at the number 3568 is work nobody asked for.
   */
  public int count(String filter) {
    return get(query("q=" + encode(filter), "rows=0")).path("response").path("numFound").asInt(0);
  }

  /** One document by pid, or empty. The uniqueKey field differs by version — K5 spells it `PID`. */
  public Optional<JsonNode> findByPid(String pidField, String pid, List<String> fields) {
    JsonNode response =
        get(query("q=" + pidField + ":" + encode(SolrSchema.quote(pid)), "rows=1", fl(fields)));
    JsonNode docs = response.path("response").path("docs");
    return docs.isArray() && !docs.isEmpty() ? Optional.of(docs.get(0)) : Optional.empty();
  }

  /**
   * Every document matching a filter, in sort order, walked by cursorMark.
   *
   * <p>The walk ends when the cursor repeats, which is Solr's own end-of-results signal. `expected`
   * is the count the index claimed up front: a walk that returns a different number is index drift
   * and is logged rather than silently serving a short manifest, which is what v1 did.
   */
  public List<JsonNode> walk(String filter, String sort, List<String> fields, int expected) {
    List<JsonNode> collected = new ArrayList<>();
    String cursor = "*";

    while (true) {
      JsonNode response =
          get(
              query(
                  "q=" + encode(filter),
                  "sort=" + encode(sort),
                  "rows=" + batch,
                  "cursorMark=" + encode(cursor),
                  fl(fields)));

      response.path("response").path("docs").forEach(collected::add);

      Optional<String> next = advance(response, cursor, filter);
      if (next.isEmpty()) {
        break;
      }
      cursor = next.get();

      if (collected.size() > maxItems) {
        LOG.error("{} exceeded the {} item ceiling for {}", baseUrl, maxItems, filter);
        throw new DocumentTooLarge(filter, maxItems);
      }
    }

    if (expected >= 0 && collected.size() != expected) {
      // Not fatal: the index moved under us, or the count field is stale. Worth knowing about.
      LOG.warn("{}: index said {} children, walk returned {}", filter, expected, collected.size());
    }
    return List.copyOf(collected);
  }

  /**
   * Like {@link #walk} but stops after `limit` documents.
   *
   * <p>The sync walk is unbounded in principle — 76 million pages at MZK — so it takes a slice per
   * pass and resumes from the watermark next time, rather than holding the whole library in memory.
   */
  public List<JsonNode> walkLimited(String filter, String sort, List<String> fields, int limit) {
    return window(filter, sort, fields, limit, Optional.empty()).docs();
  }

  /**
   * One slice of a walk, and where to carry on from.
   *
   * @param exhausted the cursor repeated, so the query has no more rows — as opposed to stopping
   *     because the limit was reached
   */
  public record Window(List<JsonNode> docs, Optional<String> nextCursor, boolean exhausted) {

    /** Copied on the way in: the rows come from a mutable list this record then hands around. */
    public Window {
      docs = List.copyOf(docs);
    }
  }

  /**
   * Like {@link #walkLimited} but says where it stopped, and can be told where to start.
   *
   * <p>This is what makes a multi-day walk resumable at its cursor rather than only at its
   * watermark. It also fixes a case the watermark cannot handle at all: when more rows share one
   * `modified` instant than a slice can hold, the greatest `modified` in the slice equals the one
   * it started from, so a watermark-only walk re-reads the same rows forever and never gets past
   * them. A whole document is ingested at one instant and MZK's largest is 3568 pages, so this is
   * not hypothetical.
   *
   * <p>A cursorMark is only meaningful for the query that minted it — same q, same sort, same rows.
   * Handing one back with a different filter is undefined rather than an error, which is why the
   * caller pairs it with the bound it belongs to. See WalkCursor.
   */
  public Window window(
      String filter, String sort, List<String> fields, int limit, Optional<String> from) {
    List<JsonNode> collected = new ArrayList<>();
    String cursor = from.orElse("*");
    boolean exhausted = false;

    while (collected.size() < limit) {
      JsonNode response =
          get(
              query(
                  "q=" + encode(filter),
                  "sort=" + encode(sort),
                  "rows=" + Math.min(batch, limit - collected.size()),
                  "cursorMark=" + encode(cursor),
                  fl(fields)));

      response.path("response").path("docs").forEach(collected::add);

      Optional<String> next = advance(response, cursor, filter);
      if (next.isEmpty()) {
        exhausted = true;
        break;
      }
      cursor = next.get();
    }
    return new Window(List.copyOf(collected), Optional.of(cursor), exhausted);
  }

  /**
   * Where the cursor goes next, or empty when the walk is finished.
   *
   * <p>THE TWO CASES ARE NOT THE SAME, and collapsing them is a silent-truncation bug. A
   * `nextCursorMark` equal to the one sent is Solr's documented end-of-results signal. A
   * `nextCursorMark` that is ABSENT means this Solr never implemented cursorMark and answered the
   * request as a plain `rows=` query — so what came back is the first batch and nothing else. Read
   * as "the cursor did not move", that ends the walk after one page and reports success.
   *
   * @throws CursorMarkUnsupported when the field is absent, naming the instance
   */
  private Optional<String> advance(JsonNode response, String cursor, String filter) {
    String next = response.path("nextCursorMark").asText(null);
    if (next == null) {
      throw new CursorMarkUnsupported(baseUrl, filter);
    }
    return next.equals(cursor) ? Optional.empty() : Optional.of(next);
  }

  /**
   * The distinct values of a field, most common first.
   *
   * <p>Used to learn which licences an instance actually uses, so licence notices are fetched for
   * those and not for every licence the software could theoretically have.
   */
  public List<String> distinctValues(String field, int limit) {
    try {
      JsonNode response =
          get(
              query(
                  "q=*:*",
                  "rows=0",
                  "facet=true",
                  "facet.field=" + encode(field),
                  "facet.limit=" + limit));

      JsonNode values = response.path("facet_counts").path("facet_fields").path(field);
      List<String> found = new ArrayList<>();
      for (int i = 0; i + 1 < values.size(); i += 2) {
        if (values.get(i + 1).asLong() > 0) {
          found.add(values.get(i).asText());
        }
      }
      return List.copyOf(found);
    } catch (RuntimeException e) {
      LOG.debug("could not facet {} on {}: {}", field, baseUrl, e.toString());
      return List.of();
    }
  }

  private String fl(List<String> fields) {
    return "fl=" + encode(String.join(",", fields));
  }

  private URI query(String... params) {
    return URI.create(baseUrl + searchPath + "?wt=json&" + String.join("&", params));
  }

  /**
   * The same walk with start/rows instead of cursorMark.
   *
   * <p>Needed for K5, where the only usable sort field (rels_ext_index) is MULTI-VALUED, and
   * cursorMark requires single-valued sort fields. Deep start/rows paging is exactly what
   * cursorMark exists to avoid — it degrades, and it can duplicate or skip rows if the index
   * changes mid-walk — so this is a fallback for an index that leaves no choice, not an
   * alternative.
   */
  public List<JsonNode> pageThrough(String filter, String sort, List<String> fields, int limit) {
    List<JsonNode> collected = new ArrayList<>();
    int start = 0;

    while (collected.size() < limit) {
      int rows = Math.min(batch, limit - collected.size());
      JsonNode response =
          get(
              query(
                  "q=" + encode(filter),
                  sort == null ? "rows=" + rows : "sort=" + encode(sort) + "&rows=" + rows,
                  "start=" + start,
                  fl(fields)));

      JsonNode docs = response.path("response").path("docs");
      if (!docs.isArray() || docs.isEmpty()) {
        break;
      }
      docs.forEach(collected::add);
      start += rows;

      if (docs.size() < rows) {
        break;
      }
      if (collected.size() > maxItems) {
        throw new DocumentTooLarge(filter, maxItems);
      }
    }
    return List.copyOf(collected);
  }

  private JsonNode get(URI uri) {
    HttpRequest request =
        UserAgent.request(uri).timeout(timeout).header("Accept", "application/json").build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        // Named, because these three mean different things to whoever reads the log. 429/503 is the
        // instance telling us to slow down, which the worker's backoff must honour rather than
        // retry through. 422 is the search API rejecting the query itself — a field this adapter
        // assumes and this instance does not have — which is an adapter boundary we have not
        // modelled, and is exactly what `make capabilities` exists to find. Neither is "Solr is
        // broken", which is what the bare status code read like.
        String meaning =
            switch (response.statusCode()) {
              case 429, 503 -> " (rate limited or overloaded — back off)";
              case 422 -> " (query rejected: this instance does not accept a field we sent)";
              default -> "";
            };
        throw UpstreamFailure.status(uri.toString(), response.statusCode(), meaning);
      }
      return MAPPER.readTree(response.body());
    } catch (IOException e) {
      // Typed rather than an UncheckedIOException, so the web layer can answer 504 for a timeout
      // and
      // 502 for an unreachable instance instead of blaming itself with a 500.
      throw UpstreamFailure.transport(uri.toString(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted querying " + uri, e);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
