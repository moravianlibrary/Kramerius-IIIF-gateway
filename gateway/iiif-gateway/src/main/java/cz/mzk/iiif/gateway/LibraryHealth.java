/*
 * Which libraries this gateway can actually serve, established in the background rather than by a user's
 * failed request.
 *
 * The design constraint is the one recorded in Gateway.web(): the gateway is a client of fifty-odd
 * third-party institutions that did not ask to be woken up, so a sweep must not be its own load spike.
 * Hence two rules.
 *
 * ONE REQUEST PER LIBRARY, and only for libraries that could plausibly work. Everything else is decided
 * from data already in hand: the registry says whether an instance is alive, and the version string
 * selects the adapter with no I/O at all — that is the point of declaring capabilities per adapter
 * instead of probing for them. So a sweep of 56 libraries is ~40 requests, not ~150, and it never
 * triggers the Image API probe or the licence fetch that full resolution performs.
 *
 * AND THE PROBE IS THE CHEAPEST QUERY THAT PROVES ANYTHING: q=*:*&rows=0, which is the same request the
 * Solr topology detection already makes. It answers three questions at once — the search API is
 * reachable, it speaks the schema path this adapter expects, and the deployment is SolrCloud or
 * standalone. A ping that only proved the host answers HTTP would not be worth the request.
 *
 * The sweep is also the only thing that notices a library has been UPGRADED. A gateway that runs for
 * months would otherwise keep serving 7.1.3 through Kramerius70 forever, because a resolved library is
 * cached and nothing invalidates it. When the registry's version no longer matches what we resolved, the
 * library is forgotten and the next request rebuilds it on the right adapter.
 *
 * Set IIIF_LIBRARY_CHECK_MINUTES=0 to switch the whole thing off.
 */
package cz.mzk.iiif.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.adapter.KrameriusAdapter;
import cz.mzk.iiif.adapter.SolrClient;
import cz.mzk.iiif.adapter.Version;
import cz.mzk.iiif.gateway.registry.RegistryEntry;

public final class LibraryHealth implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(LibraryHealth.class);

  /**
   * How long to leave between two libraries' probes.
   *
   * <p>A sweep is not urgent — it repeats every 30 minutes by default — so the requests are spread
   * out rather than fired at once. At 56 libraries this is under a minute of wall clock and never
   * more than one in-flight request, which is as polite as a check can be made.
   */
  private static final Duration SPACING = Duration.ofSeconds(1);

  public enum State {
    /** Reachable, and on a version some adapter supports. */
    READY,
    /** Supported, but the search API did not answer. */
    UNREACHABLE,
    /** Below every adapter floor: the one K4 instance, and anything older. */
    UNSUPPORTED,
    /** The registry itself says the instance is down. Not probed. */
    DOWN,
    /** No such code. */
    UNKNOWN
  }

  /** What one sweep found out about one library. */
  public record Status(
      String code,
      State state,
      Optional<String> version,
      Optional<String> adapter,
      boolean untested,
      Optional<String> topology,
      Optional<String> detail,
      Instant checkedAt,
      long latencyMillis) {}

  private final Config config;
  private final Libraries libraries;
  private final java.net.http.HttpClient http;
  private final Map<String, Status> statuses = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "library-health");
            thread.setDaemon(true);
            return thread;
          });

  public LibraryHealth(Config config, Libraries libraries, java.net.http.HttpClient http) {
    this.config = config;
    this.libraries = libraries;
    this.http = http;
  }

  /** Starts sweeping, unless the interval is zero. Never blocks — the first sweep is scheduled. */
  public void start() {
    if (config.libraryCheckInterval().isZero() || config.libraryCheckInterval().isNegative()) {
      LOG.info("library health checks are off (IIIF_LIBRARY_CHECK_MINUTES=0)");
      return;
    }
    long minutes = config.libraryCheckInterval().toMinutes();
    LOG.info("checking {} libraries every {} minutes", libraries.codes().size(), minutes);
    scheduler.scheduleWithFixedDelay(this::sweepQuietly, 0, minutes, TimeUnit.MINUTES);
  }

  /** What the last sweep found, by code. Empty until the first one finishes. */
  public Map<String, Status> statuses() {
    return Map.copyOf(statuses);
  }

  public Optional<Status> status(String code) {
    return Optional.ofNullable(statuses.get(code));
  }

  /** For /health: how many libraries are in each state, so an operator sees a number not a list. */
  public Map<String, Integer> summary() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (State state : State.values()) {
      counts.put(state.name().toLowerCase(java.util.Locale.ROOT), 0);
    }
    for (Status status : statuses.values()) {
      counts.merge(status.state().name().toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
    }
    return counts;
  }

  private void sweepQuietly() {
    try {
      sweep();
    } catch (RuntimeException e) {
      // A checker that dies takes its schedule with it, and the gateway would go on serving with a
      // frozen picture of which libraries work.
      LOG.warn("library health sweep failed: {}", e.toString());
    }
  }

  /** Visible for testing: one pass over every library. */
  void sweep() {
    Instant started = Instant.now();
    for (String code : libraries.codes()) {
      Status status = check(code);
      statuses.put(code, status);
      if (status.state() == State.READY) {
        pause();
      }
    }
    LOG.info(
        "library sweep: {} in {}s",
        summary().entrySet().stream()
            .filter(entry -> entry.getValue() > 0)
            .map(entry -> entry.getValue() + " " + entry.getKey())
            .toList(),
        Duration.between(started, Instant.now()).toSeconds());
  }

  private Status check(String code) {
    Optional<RegistryEntry> entry = libraries.registryEntry(code);
    Config.StaticLibrary declared = config.staticLibraries().get(code);

    if (entry.isEmpty() && declared == null) {
      return status(code, State.UNKNOWN, Optional.empty(), Optional.empty(), null);
    }
    // Only when nothing was declared locally. Naming a library in the configuration is the operator
    // overruling the registry about whether to serve it, and resolve() reads it the same way.
    if (declared == null && !entry.get().alive()) {
      return status(
          code, State.DOWN, entry.get().version(), Optional.empty(), "registry says down");
    }

    // The same overlay resolve() applies: local where stated, registry otherwise.
    Optional<String> url =
        declared != null
            ? declared.url().or(() -> entry.map(RegistryEntry::url))
            : entry.map(RegistryEntry::url);
    if (url.isEmpty()) {
      return status(code, State.UNKNOWN, Optional.empty(), Optional.empty(), "no url");
    }
    String declaredVersion =
        (declared != null ? declared.version() : Optional.<String>empty())
            .or(() -> entry.flatMap(RegistryEntry::version))
            .orElse("");

    // Asked of the instance, exactly as Libraries.build does, so /libraries reports the adapter a
    // request would actually get. The load-bearing part is forgetIfUpgraded below: it compares the
    // RESOLVED library's version against this one, so reading a declaration here while build()
    // reads the instance would make every sweep see a phantom upgrade and discard a good library.
    Optional<Version> version = libraries.effectiveVersion(code, url.get(), declaredVersion);
    Optional<KrameriusAdapter> adapter = version.flatMap(libraries.adapters()::forVersion);
    if (adapter.isEmpty()) {
      return status(
          code,
          State.UNSUPPORTED,
          Optional.of(version.map(Version::raw).orElse(declaredVersion)),
          Optional.empty(),
          "below " + libraries.adapters().lowestSupported());
    }

    forgetIfUpgraded(code, version.get().raw());
    return probe(code, url.get(), version.get().raw(), adapter.get());
  }

  /**
   * One request, and what it proves.
   *
   * <p>A new SolrClient each time rather than the resolved library's, because most libraries are
   * never resolved and building one is free — it holds a URL and a timeout, nothing more.
   */
  private Status probe(String code, String url, String rawVersion, KrameriusAdapter adapter) {
    SolrClient solr =
        new SolrClient(http, url, config.readTimeout(), adapter.schema().searchPath());
    Instant started = Instant.now();
    try {
      // solrCloud(), not uniqueKey(): uniqueKey() degrades to a guess when the instance does not
      // answer, because a manifest request still has to be served somehow. Inheriting that here
      // made
      // every library READY — including one refusing connections outright.
      boolean cloud = solr.solrCloud();
      long millis = Duration.between(started, Instant.now()).toMillis();
      boolean untested =
          Version.parse(rawVersion)
              .map(version -> cz.mzk.iiif.adapter.AdapterRegistry.isUntested(adapter, version))
              .orElse(false);
      return new Status(
          code,
          State.READY,
          Optional.of(rawVersion),
          Optional.of(adapter.name()),
          untested,
          Optional.of(cloud ? "solrcloud" : "standalone"),
          Optional.empty(),
          Instant.now(),
          millis);
    } catch (RuntimeException e) {
      LOG.warn("{} is unreachable: {}", code, e.toString());
      return new Status(
          code,
          State.UNREACHABLE,
          Optional.of(rawVersion),
          Optional.of(adapter.name()),
          false,
          Optional.empty(),
          Optional.of(e.toString()),
          Instant.now(),
          Duration.between(started, Instant.now()).toMillis());
    }
  }

  /**
   * Drops a resolved library whose version has moved.
   *
   * <p>This is the only thing that notices an upgrade. A resolved library is cached for the life of
   * the process — deliberately, since resolving costs several upstream requests — so without this a
   * gateway that has been up since before an instance went 7.1.3 → 7.1.4 keeps serving it through
   * the older adapter, silently missing the fields the newer one would use.
   */
  private void forgetIfUpgraded(String code, String rawVersion) {
    Libraries.Library resolved = libraries.resolved().get(code);
    if (resolved == null || resolved.version().toString().equals(rawVersion)) {
      return;
    }
    LOG.info(
        "{} moved from {} to {}, rebuilding on next request", code, resolved.version(), rawVersion);
    libraries.forget(code);
  }

  private Status status(
      String code, State state, Optional<String> version, Optional<String> adapter, String detail) {
    return new Status(
        code,
        state,
        version,
        adapter,
        false,
        Optional.empty(),
        Optional.ofNullable(detail),
        Instant.now(),
        0);
  }

  private static void pause() {
    try {
      Thread.sleep(SPACING);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }

  /** Every code the checker knows about, in the order the registry lists them. */
  public List<String> codes() {
    return libraries.codes();
  }
}
