/*
 * The public and admin listeners.
 *
 * Two ports, not one port with a shared secret. Only 8080 is published in compose or the ingress; 8081
 * is reachable on the internal network only. A static bearer token was the alternative and is worse: no
 * rotation, no identity, no revocation, and a secret to store and leak — where binding to an
 * unpublished interface needs no secret at all and puts access control in the deployment layer, which
 * is where an ops surface belongs.
 *
 * CORS is `*` on every response because IIIF viewers are served from other origins and will not load a
 * manifest without it. That is not a relaxation: everything here is public read-only data, and the
 * routes that are not are on the other port.
 */
package cz.mzk.iiif.gateway.web;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.adapter.DocumentTooLarge;
import cz.mzk.iiif.adapter.KrameriusAdapter;
import cz.mzk.iiif.adapter.UpstreamFailure;
import cz.mzk.iiif.gateway.Config;
import cz.mzk.iiif.gateway.Libraries;
import cz.mzk.iiif.gateway.LibraryHealth;
import cz.mzk.iiif.gateway.Manifests;
import cz.mzk.iiif.gateway.store.SizeStore;
import cz.mzk.iiif.presentation.ManifestWriter;

public final class WebServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(WebServer.class);

  private final Config config;
  private final Libraries libraries;
  private final LibraryHealth health;
  private final ManifestCache manifestCache;
  private final Manifests manifests;
  private final Optional<SizeStore> store;
  private final RateLimiter limiter;
  private final ScheduledExecutorService limiterEviction =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "rate-limiter-eviction");
            thread.setDaemon(true);
            return thread;
          });

  private Javalin publicServer;
  private Javalin adminServer;

  public WebServer(
      Config config,
      Libraries libraries,
      Manifests manifests,
      Optional<SizeStore> store,
      LibraryHealth health) {
    this.config = config;
    this.libraries = libraries;
    this.health = health;
    this.manifestCache = new ManifestCache(config.manifestCacheSize(), config.manifestTtl());
    this.manifests = manifests;
    this.store = store;
    this.limiter = new RateLimiter(config.rateLimitRps(), config.rateLimitBurst());
  }

  public void start() {
    publicServer = Javalin.create(javalin -> javalin.showJavalinBanner = false);
    admin();
    publicRoutes(publicServer);
    publicServer.start(config.port());
    limiterEviction.scheduleWithFixedDelay(limiter::evictIdle, 5, 5, TimeUnit.MINUTES);
    LOG.info("public listener on {}", config.port());
  }

  private void admin() {
    adminServer = Javalin.create(javalin -> javalin.showJavalinBanner = false);
    adminRoutes(adminServer);
    adminServer.start(config.adminPort());
    LOG.info("admin listener on {} — do not publish this port", config.adminPort());
  }

  /**
   * What a failure upstream of us looks like to a caller.
   *
   * <p>Registered on both listeners, because the admin routes talk to the same instances.
   *
   * <p>Without this, Javalin's default handler answered 500 in plain text for every upstream
   * hiccup: wrong about whose fault it was, and not even the JSON error shape every other route
   * returns. Measured while checking this: MZK's search API took 8–65 s for one document lookup and
   * intermittently answered 500 itself, and the gateway reported that as its own 500.
   */
  private void errorHandlers(Javalin app) {
    app.exception(UpstreamFailure.class, WebServer::upstreamError);
    // Their data, our refusal — so not an upstream error. Named separately because the fix is to
    // raise IIIF_MAX_ITEMS or repair the record, and neither is "look at the library's server".
    app.exception(
        DocumentTooLarge.class,
        (failure, context) -> {
          LOG.error("{}: {}", context.path(), failure.getMessage());
          error(
              context,
              HttpStatus.INSUFFICIENT_STORAGE,
              "too_large",
              "this document has more children than IIIF_MAX_ITEMS (" + failure.ceiling() + ")",
              context.pathParamMap().getOrDefault("pid", ""));
        });
    // Anything else really is ours. Kept JSON so a caller never has to parse two error formats, and
    // logged whole, because the response deliberately says nothing about our internals.
    app.exception(
        Exception.class,
        (failure, context) -> {
          LOG.error("unhandled failure serving {}", context.path(), failure);
          error(context, HttpStatus.INTERNAL_SERVER_ERROR, "internal", "unexpected error", "");
        });
  }

  private static void upstreamError(UpstreamFailure failure, Context context) {
    // 502 "they broke", 504 "they were too slow", 503 "they told us to wait" — three things an
    // operator would do something different about.
    HttpStatus status =
        switch (failure.kind()) {
          case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
          case OVERLOADED -> HttpStatus.SERVICE_UNAVAILABLE;
          case UNREACHABLE, REJECTED -> HttpStatus.BAD_GATEWAY;
        };
    String code =
        switch (failure.kind()) {
          case TIMEOUT -> "upstream_timeout";
          case OVERLOADED -> "upstream_overloaded";
          case UNREACHABLE -> "upstream_unreachable";
          case REJECTED -> "upstream_rejected";
        };

    // The library, not the URI we asked. A caller cannot act on our query string, and it carries
    // pids and field lists that are nobody else's business; the log below has all of it.
    String library = context.pathParamMap().getOrDefault("library", "");
    // The query only earns its kilobyte when the instance rejected it — then the field list we sent
    // is the whole question, and what `make capabilities` would go looking for.
    String asked =
        failure.kind() == UpstreamFailure.Kind.REJECTED ? " — asked " + failure.upstream() : "";
    LOG.warn(
        "{} for {}: {}{}",
        status.getCode(),
        context.path(),
        failure.getMessage(),
        asked,
        failure.getCause());
    error(context, status, code, "the library's Kramerius did not answer usefully", library);
  }

  private void publicRoutes(Javalin app) {
    errorHandlers(app);
    app.before(
        context -> {
          context.header("Access-Control-Allow-Origin", "*");
          if (!limiter.allow(client(context))) {
            context.header("Retry-After", String.valueOf(limiter.retryAfterSeconds()));
            throw new io.javalin.http.HttpResponseException(
                HttpStatus.TOO_MANY_REQUESTS.getCode(), "rate limit exceeded", Map.of());
          }
        });

    app.get("/", context -> context.html(landingPage()));
    app.get("/health", this::health);
    app.get("/version", this::version);
    app.get("/libraries", this::listLibraries);
    app.get("/resolve", this::resolveLink);

    app.get("/{library}/{pid}", context -> manifest(context, context.pathParam("pid")));
    // /full was v1's opt-in for real dimensions. They are unconditional now, so it is a permanent
    // redirect to the canonical URL rather than a second code path.
    app.get(
        "/{library}/{pid}/full",
        context ->
            context.redirect(
                "/" + context.pathParam("library") + "/" + context.pathParam("pid"),
                HttpStatus.MOVED_PERMANENTLY));
    app.get("/{library}/{pid}/dimensions", this::dimensionStatus);
  }

  private void adminRoutes(Javalin app) {
    errorHandlers(app);
    app.put(
        "/{library}/{pid}/dimensions",
        context -> {
          if (store.isEmpty()) {
            error(
                context,
                HttpStatus.NOT_IMPLEMENTED,
                "no_store",
                "this deployment has no size store",
                context.pathParam("pid"));
            return;
          }
          // An idempotent state assertion: "these dimensions should be cached". A second call for a
          // complete document does no work, which is why the walk needs no depth or size limit.
          store.get().enqueue(context.pathParam("library"), context.pathParam("pid"));
          context.status(HttpStatus.ACCEPTED).json(Map.of("state", "queued"));
        });

    app.delete(
        "/{library}/{pid}/dimensions",
        context -> {
          if (store.isEmpty()) {
            error(
                context,
                HttpStatus.NOT_IMPLEMENTED,
                "no_store",
                "this deployment has no size store",
                context.pathParam("pid"));
            return;
          }
          // The one case no automatic rule can catch: an image re-derived without its Fedora
          // lastModifiedDate moving, so the staleness check cannot see it.
          store.get().evict(context.pathParam("library"), context.pathParam("pid"));
          // And the rendered manifest built from them, which would otherwise keep serving the
          // dimensions this call exists to discard.
          manifestCache.evict(context.pathParam("library"), context.pathParam("pid"));
          context.status(HttpStatus.NO_CONTENT);
        });

    app.put("/libraries/{library}/sync", this::configureSync);
  }

  // Handlers

  private void manifest(Context context, String pid) {
    String code = context.pathParam("library");

    // Before anything touches Kramerius. A hit answers a conditional request without a single
    // upstream call, which is the point: the manifest is deterministic, so a rebuild would produce
    // the same bytes and the same ETag at the cost of a Solr walk and 272 KB of serialisation.
    Optional<ManifestCache.Entry> hit = manifestCache.get(code, pid);
    if (hit.isPresent()) {
      dimensionHeader(context, hit.get().dimensions());
      serve(context, hit.get().json(), hit.get().etag());
      return;
    }

    Manifests.Result result = manifests.get(code, pid);

    switch (result) {
      case Manifests.Result.Built built -> {
        String json = ManifestWriter.toJson(built.document());
        dimensionHeader(context, built.dimensions());

        if (built.dimensions().cacheable()) {
          String etag = etag(json);
          // Nothing partial is remembered: it carries nominal sizes for pages the worker has not
          // reached yet, and caching those would keep serving 1000x1000 canvases long after the
          // real dimensions had been measured — v1's defect, one layer up. put() enforces that.
          manifestCache.put(code, pid, json, etag, built.dimensions());
          serve(context, json, etag);
          return;
        }
        // Provisional, so it must not be cached as final by anyone: not by us, not by a CDN, not by
        // the client. The header is what tells a caller the numbers are still moving.
        context.contentType("application/json;charset=utf-8");
        context.header("Cache-Control", "no-store");
        context.result(json);
      }
      case Manifests.Result.NotFound missing ->
          error(
              context,
              HttpStatus.NOT_FOUND,
              "not_found",
              "no such object in " + missing.library(),
              missing.pid());
      case Manifests.Result.Unsupported unsupported ->
          // Distinct from not_found on purpose: the object EXISTS, it just is not a document. v1
          // answered 200 for both.
          error(
              context,
              HttpStatus.NOT_FOUND,
              "no_manifest_for_model",
              "a " + unsupported.model() + " is part of a document, not a document",
              unsupported.pid());
      case Manifests.Result.LibraryProblem problem -> libraryError(context, problem.resolution());
    }
  }

  /**
   * A strong validator over the serialised manifest.
   *
   * <p>Truncated to 128 bits: this identifies a body, it does not defend against anyone, and a
   * shorter header is worth more than collision resistance nobody needs.
   */
  private static String etag(String json) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(32);
      for (int i = 0; i < 16; i++) {
        hex.append("%02x".formatted(digest[i]));
      }
      return "\"" + hex + "\"";
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the platform", e);
    }
  }

  private void libraryError(Context context, Libraries.Resolution resolution) {
    switch (resolution) {
      case Libraries.Resolution.Unknown unknown ->
          error(
              context,
              HttpStatus.NOT_FOUND,
              "unknown_library",
              "no library with that code",
              unknown.code());
      case Libraries.Resolution.Dead dead ->
          error(
              context,
              HttpStatus.SERVICE_UNAVAILABLE,
              "library_unavailable",
              "the registry reports this library as down",
              dead.code());
      case Libraries.Resolution.Excluded excluded ->
          // 404, not 503: nothing is wrong at the far end and nothing will change by retrying. As
          // far
          // as this deployment is concerned the library is not served, and a client should treat
          // that
          // as permanent.
          error(
              context,
              HttpStatus.NOT_FOUND,
              "library_excluded",
              "this gateway does not serve that library",
              excluded.code());
      case Libraries.Resolution.Unsupported unsupported ->
          error(
              context,
              HttpStatus.NOT_IMPLEMENTED,
              "unsupported_version",
              "Kramerius "
                  + unsupported.version()
                  + " is below the lowest supported "
                  + unsupported.lowestSupported(),
              unsupported.code());
      case Libraries.Resolution.Ready ignored ->
          error(context, HttpStatus.INTERNAL_SERVER_ERROR, "internal", "unexpected", "");
    }
  }

  private void dimensionStatus(Context context) {
    Optional<Manifests.DimensionStatus> status =
        manifests.dimensions(context.pathParam("library"), context.pathParam("pid"));

    if (status.isEmpty()) {
      error(
          context,
          HttpStatus.NOT_FOUND,
          "no_dimension_cache",
          "this deployment has no size store",
          context.pathParam("pid"));
      return;
    }
    context.json(status.get());
  }

  private void resolveLink(Context context) {
    Optional<Links.Target> target =
        Links.parse(context.queryParam("link"), code -> libraries.codes().contains(code));
    if (target.isEmpty()) {
      error(
          context,
          HttpStatus.BAD_REQUEST,
          "unparseable_link",
          "could not find a library and a pid in that URL",
          "");
      return;
    }
    context.redirect("/" + target.get().library() + "/" + target.get().pid(), HttpStatus.FOUND);
  }

  /**
   * The ops view: what the last sweep found, to which adapter, and how far the sync has got.
   *
   * <p>Read from the health checker rather than resolved on demand. Resolving fifty-odd libraries
   * inside a request is ~150 upstream calls to institutions that did not ask for them, and it made
   * this route take minutes — which is how /health came to be written the same way. A library that
   * has been resolved by real traffic contributes the extra facts only it knows: the detected Image
   * API version and the observed image-server latency.
   */
  private void listLibraries(Context context) {
    List<Map<String, Object>> rows = new ArrayList<>();
    Map<String, SizeStore.Sync> syncs = new LinkedHashMap<>();
    store.ifPresent(s -> s.allSyncs().forEach(sync -> syncs.put(sync.library(), sync)));
    Map<String, LibraryHealth.Status> checked = health.statuses();
    Map<String, Libraries.Library> resolved = libraries.resolved();

    for (String code : libraries.codes()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("code", code);

      LibraryHealth.Status status = checked.get(code);
      if (status == null) {
        // Nothing has checked it and nothing has asked for it.
        row.put("status", "unchecked");
      } else {
        row.put("status", status.state().name().toLowerCase(java.util.Locale.ROOT));
        status.version().ifPresent(version -> row.put("version", version));
        status.adapter().ifPresent(adapter -> row.put("adapter", adapter));
        status.topology().ifPresent(topology -> row.put("solr", topology));
        status.detail().ifPresent(detail -> row.put("detail", detail));
        row.put("untested", status.untested());
        row.put("checkedAt", status.checkedAt().toString());
        row.put("checkMillis", status.latencyMillis());
      }

      Libraries.Library library = resolved.get(code);
      if (library != null) {
        KrameriusAdapter adapter = library.adapter();
        // Overwrites the checker's value on purpose: this is the version the instance reported when
        // the library was built, and it is what actually chose the adapter. The declared version —
        // registry or config file — is not shown anywhere, because reporting a number that did not
        // decide anything is how an operator ends up debugging the wrong one.
        row.put("version", library.version().toString());
        row.put("adapter", adapter.name());
        row.put("testedUpTo", adapter.testedUpTo().toString());
        row.put("imageApiVersion", library.repository().urls().imageApiVersion());
        row.put("imageLatencyMillis", library.policy().latencyMillis());
      }

      SizeStore.Sync sync = syncs.get(code);
      if (sync != null) {
        // LinkedHashMap rather than Map.of: lastError is optional, and Map.of has no null-tolerant
        // form. It is reported because a walk that aborts every pass otherwise looks like a walk
        // that is merely slow — the row would say RUNNING with a watermark that never moves, and
        // the reason would be in a Postgres column nobody queries.
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("state", sync.state().name().toLowerCase(java.util.Locale.ROOT));
        state.put("watermark", sync.watermark().toString());
        state.put("seen", sync.seen());
        sync.lastError().ifPresent(error -> state.put("lastError", error));
        row.put("sync", state);
      }
      rows.add(row);
    }
    context.json(Map.of("libraries", rows));
  }

  private void configureSync(Context context) {
    if (store.isEmpty()) {
      error(
          context, HttpStatus.NOT_IMPLEMENTED, "no_store", "this deployment has no size store", "");
      return;
    }
    String code = context.pathParam("library");
    String action = Optional.ofNullable(context.queryParam("state")).orElse("running");
    boolean rewind = Boolean.parseBoolean(context.queryParam("rewind"));

    SizeStore.Sync existing =
        store
            .get()
            .sync(code)
            .orElse(
                new SizeStore.Sync(
                    code,
                    SizeStore.Sync.State.DISABLED,
                    Optional.empty(),
                    java.time.Instant.EPOCH,
                    0,
                    Optional.empty(),
                    Optional.empty()));

    SizeStore.Sync.State state =
        switch (action) {
          case "paused" -> SizeStore.Sync.State.PAUSED;
          case "disabled" -> SizeStore.Sync.State.DISABLED;
          default -> SizeStore.Sync.State.RUNNING;
        };

    // Rewinding the watermark to epoch IS "backfill this library" — there is no second mechanism,
    // because a backfill and an incremental sync are the same walk with a different starting point.
    store
        .get()
        .saveSync(
            new SizeStore.Sync(
                code,
                state,
                rewind ? Optional.empty() : existing.cursor(),
                rewind ? java.time.Instant.EPOCH : existing.watermark(),
                rewind ? 0 : existing.seen(),
                existing.lastRunAt(),
                Optional.empty()));

    context.json(
        Map.of(
            "library",
            code,
            "state",
            state.name().toLowerCase(java.util.Locale.ROOT),
            "rewound",
            rewind));
  }

  /**
   * Cheap by construction.
   *
   * <p>It reports only what is already known: the store's reachability, how many libraries are
   * configured, and how many have been resolved so far. It deliberately does NOT resolve anything —
   * doing so touches every upstream, and a health check that probes fifty third-party institutions
   * is both slow enough to time out and rude enough to get blocked.
   */
  private void health(Context context) {
    SizeStore.Health storeHealth = store.map(SizeStore::health).orElse(SizeStore.Health.OK);
    boolean storeOk = storeHealth == SizeStore.Health.OK;
    var resolved = libraries.resolved();
    long untested = resolved.values().stream().filter(Libraries.Library::untested).count();
    // The checker reports it too, but a library nobody has resolved yet is only known from there.

    context
        .status(storeOk ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
        .json(
            Map.of(
                "status",
                storeOk ? "ok" : "degraded",
                // The state by name, not just ok/unreachable: "schema_missing" is a different job
                // from "unreachable", and reporting the second for the first sends an operator to
                // the network when the answer is a migration.
                "sizeStore",
                store.isPresent() ? storeHealth.name().toLowerCase(java.util.Locale.ROOT) : "none",
                "librariesConfigured",
                libraries.codes().size(),
                "librariesResolved",
                resolved.size(),
                "pastTestedVersion",
                untested,
                "libraryChecks",
                health.summary(),
                "manifestCache",
                Map.of("entries", manifestCache.size(), "bytes", manifestCache.bytes())));
  }

  /**
   * A complete manifest, with its validator and its revalidation.
   *
   * <p>The ETag is a hash of these exact bytes, which is sound only because the manifest is
   * deterministic — stable key and language order was made a property of the model precisely so
   * this would hold. It lets a client revalidate for the cost of a 304 rather than re-downloading a
   * manifest that can reach a couple of megabytes.
   */
  /**
   * Says how much the canvas dimensions can be trusted, when there is anything to say.
   *
   * <p>Set before {@link #serve}, so it is present on a 304 as well: the header describes the
   * representation the client already holds, and dropping it there would tell a revalidating client
   * less than the original response did.
   */
  private static void dimensionHeader(Context context, Manifests.Dimensions dimensions) {
    dimensions.header().ifPresent(value -> context.header("X-IIIF-Dimensions", value));
  }

  private void serve(Context context, String json, String etag) {
    context.contentType("application/json;charset=utf-8");
    context.header("ETag", etag);
    context.header("Cache-Control", "public, max-age=" + config.manifestTtl().toSeconds());

    if (etag.equals(context.header("If-None-Match"))) {
      context.status(HttpStatus.NOT_MODIFIED).result("");
      return;
    }
    context.result(json);
  }

  private void version(Context context) {
    context.json(
        Map.of(
            "version", Optional.ofNullable(System.getenv("SYSTEM_VERSION")).orElse("dev"),
            "commit", Optional.ofNullable(System.getenv("SYSTEM_COMMIT")).orElse("unknown")));
  }

  /** Served from our own resources. v1 proxied GitHub's raw README on every page load. */
  private String landingPage() {
    try (InputStream in = WebServer.class.getResourceAsStream("/landing.html")) {
      return in == null
          ? "<h1>Kramerius IIIF gateway</h1>"
          : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "<h1>Kramerius IIIF gateway</h1>";
    }
  }

  /** Errors are JSON with a machine-readable code and a bilingual message. */
  private static void error(
      Context context, HttpStatus status, String code, String message, String subject) {
    context.status(status).json(Map.of("error", code, "message", message, "subject", subject));
  }

  private static String client(Context context) {
    // Behind a proxy the peer address is the proxy, so the forwarded address is what identifies a
    // caller. Trusted because the admin surface is on another port and this only shapes traffic.
    String forwarded = context.header("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return context.ip();
  }

  @Override
  public void close() {
    limiterEviction.shutdownNow();
    if (publicServer != null) {
      publicServer.stop();
    }
    if (adminServer != null) {
      adminServer.stop();
    }
  }
}
