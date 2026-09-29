/*
 * Serving one manifest: resolve the library, gather, build, and decide what to do with what came back.
 *
 * The single-flight is not an optimisation. Without it, ten viewers opening the same cold 3000-page
 * document each start their own info.json fan-out against the same image server — the exact stampede the
 * politeness caps exist to prevent, aimed at a third party.
 */
package cz.mzk.iiif.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.build.ManifestBuilder;
import cz.mzk.iiif.build.ManifestConfig;
import cz.mzk.iiif.document.ImageSize;
import cz.mzk.iiif.gateway.store.SizeStore;
import cz.mzk.iiif.presentation.Iiif;
import cz.mzk.iiif.spi.BundleAssembler;

public final class Manifests {

  private static final Logger LOG = LoggerFactory.getLogger(Manifests.class);

  /**
   * How much a manifest's canvas dimensions can be trusted, reported as X-IIIF-Dimensions.
   *
   * <p>Three states rather than two, because "not measured yet" and "measured indirectly" are
   * different promises. The distinction is NOT that an approximation is permanent — it is not; a
   * licence expires, an operator whitelists the gateway, an image is re-derived, and the page
   * becomes measurable. It is that a partial answer has work already in flight against it and will
   * improve by itself within a worker pass, while an approximate one will not improve until
   * something outside this system changes. That is the difference between "ask again shortly" and
   * "this is the answer for now", and a client acts on them differently.
   */
  public enum Dimensions {

    /** Every canvas carries the image server's own width and height. No header is emitted. */
    COMPLETE(null),

    /**
     * Every canvas is resolved, but at least one carries a shape derived from a thumbnail because
     * the image itself answered 403. The aspect is right to within about 1%; the magnitude is
     * nominal.
     *
     * <p>Cacheable on the same terms as COMPLETE, not because it cannot change: every cached
     * manifest is derived from someone else's repository and goes stale the same way. The
     * approximation itself is re-asked after IIIF_FAILED_LOOKUP_RETRY_SECONDS, so a page that
     * becomes readable upgrades rather than keeping a guess forever — see CachedImageSizes.
     */
    APPROXIMATE("approximate"),

    /**
     * At least one canvas carries the square fallback because the worker has not reached it yet.
     * The only state with work already queued against it, which is why it alone is no-store and
     * kept out of the manifest cache: remembering it would outlive the answer it is waiting for.
     */
    PARTIAL("partial");

    private final String header;

    Dimensions(String header) {
      this.header = header;
    }

    /** The X-IIIF-Dimensions value, empty when there is nothing to warn about. */
    public Optional<String> header() {
      return Optional.ofNullable(header);
    }

    /** Whether nothing is already on its way to replace this, and so it is worth remembering. */
    public boolean cacheable() {
      return this != PARTIAL;
    }
  }

  /** What a request should answer with. */
  public sealed interface Result {

    record Built(Iiif.Document document, Dimensions dimensions) implements Result {}

    record NotFound(String library, String pid) implements Result {}

    record Unsupported(String library, String pid, String model) implements Result {}

    record LibraryProblem(Libraries.Resolution resolution) implements Result {}
  }

  private final Libraries libraries;
  private final Optional<SizeStore> store;
  private final ManifestConfig manifestConfig;

  /** Collapses concurrent requests for the same document within this process. */
  private final Map<String, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();

  public Manifests(Config config, Libraries libraries, Optional<SizeStore> store) {
    this.libraries = libraries;
    this.store = store;
    this.manifestConfig =
        new ManifestConfig(
            config.baseUrl(),
            config.digitalLibraryUrl(),
            config.fallbackCanvasSize(),
            TranslationDirectory.load(config.translationsDir()));
  }

  public Result get(String code, String pid) {
    String key = code + "/" + pid;
    CompletableFuture<Result> created = new CompletableFuture<>();
    CompletableFuture<Result> running = inFlight.putIfAbsent(key, created);
    if (running != null) {
      return await(running);
    }
    try {
      Libraries.Resolution resolution = libraries.resolve(code);
      Result result;
      if (resolution instanceof Libraries.Resolution.Ready ready) {
        result = build(ready.library(), pid);
      } else {
        result = new Result.LibraryProblem(resolution);
      }
      created.complete(result);
      return result;
    } catch (RuntimeException e) {
      created.completeExceptionally(e);
      throw e;
    } finally {
      inFlight.remove(key, created);
    }
  }

  private static Result await(CompletableFuture<Result> running) {
    try {
      return running.join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException failure) {
        throw failure;
      }
      throw e;
    }
  }

  private Result build(Libraries.Library library, String pid) {
    Instant started = Instant.now();
    BundleAssembler.Result assembled =
        BundleAssembler.assemble(library.repository(), library.policy(), pid);

    return switch (assembled) {
      case BundleAssembler.Result.NotFound missing ->
          new Result.NotFound(missing.library(), missing.pid());
      case BundleAssembler.Result.Unsupported unsupported ->
          new Result.Unsupported(unsupported.library(), unsupported.pid(), unsupported.model());
      case BundleAssembler.Result.Found found -> {
        if (!found.dimensionsComplete()) {
          // Answer now, and let the worker finish. Nothing waits on it — that is what keeps the
          // web/worker messaging one-directional.
          store.ifPresent(s -> s.enqueue(library.code(), pid));
          LOG.debug(
              "{}/{}: {} pages left to the worker",
              library.code(),
              pid,
              found.unresolvedPages().size());
        } else {
          library
              .policy()
              .observed(found.bundle().pages().size(), Duration.between(started, Instant.now()));
        }

        yield new Result.Built(ManifestBuilder.build(manifestConfig, found.bundle()), state(found));
      }
    };
  }

  /**
   * Which of the three promises this manifest can make.
   *
   * <p>PARTIAL wins over APPROXIMATE when both apply: an unresolved page is the stronger caveat and
   * the one that governs caching, and a client that re-requests will get the approximate answer
   * anyway once the worker has finished.
   */
  private static Dimensions state(BundleAssembler.Result.Found found) {
    if (!found.dimensionsComplete()) {
      return Dimensions.PARTIAL;
    }
    boolean approximated =
        found.bundle().sizes().values().stream().anyMatch(ImageSize::isApproximate);
    return approximated ? Dimensions.APPROXIMATE : Dimensions.COMPLETE;
  }

  /** The dimension status of one document, for GET /{library}/{pid}/dimensions. */
  public Optional<DimensionStatus> dimensions(String code, String pid) {
    if (!(libraries.resolve(code) instanceof Libraries.Resolution.Ready ready)) {
      return Optional.empty();
    }
    return ready
        .library()
        .cache()
        .map(
            cache -> {
              // One index count and one primary-key lookup, rather than fetching every page record
              // in the document to arrive at how many there are. The route exists to be cheap.
              int total = ready.library().repository().documents().tiledPageCount(pid);
              int cached = cache.cachedCount(pid);
              int approximate = cache.approximateCount(pid);
              // A blob can hold a page that is no longer a child, so cached can exceed the total —
              // reported as complete rather than as more than 100%.
              //
              // Nothing to measure is COMPLETE, not partial. `total == 0` is an ordinary document
              // whose pages carry no tiles — 732 043 of MZK's pages are such, and one monograph in
              // 363 pages of them has no tiled page at all. PageDimensions.resolve returns complete
              // for exactly that case, so the manifest carries no header; requiring `total > 0`
              // here answered `partial` for the same document and broke the one promise this route
              // makes.
              boolean resolved = cached >= total;
              // THE SAME THREE STATES X-IIIF-Dimensions REPORTS, and for the same reason: a page
              // measured through its thumbnail is resolved but is not a measurement, and a route
              // that
              // called that `complete` would contradict the header on the identical request.
              String state = !resolved ? "partial" : approximate > 0 ? "approximate" : "complete";
              return new DimensionStatus(total, Math.min(cached, total), approximate, state);
            });
  }

  /**
   * @param approximate how many cached sizes are thumbnail-derived shapes rather than measurements.
   *     Reported as a number rather than only folded into the state, because "3 of 202" and "202 of
   *     202" are different situations and an operator deciding whether to chase one needs to know
   *     which.
   */
  public record DimensionStatus(int total, int cached, int approximate, String state) {}
}
