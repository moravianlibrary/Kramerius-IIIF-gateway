/*
 * Turns a library code into something that can be read from.
 *
 * Registry mode and static mode differ only here: both produce the same Library record, so every route
 * below this point is identical whether the gateway serves fifty institutions or one. That is what makes
 * a single-instance deployment a configuration choice rather than a different program.
 *
 * Resolution is cached because it is expensive — building it means selecting an adapter, fetching the
 * instance's licence texts and detecting the Solr topology — and because the answer changes on the order
 * of months. The health checker refreshes it on its own schedule.
 */
package cz.mzk.iiif.gateway;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.adapter.AdapterRegistry;
import cz.mzk.iiif.adapter.DocumentModels;
import cz.mzk.iiif.adapter.InfoJsonSizes;
import cz.mzk.iiif.adapter.KrameriusAdapter;
import cz.mzk.iiif.adapter.KrameriusInfo;
import cz.mzk.iiif.adapter.KrameriusRepository;
import cz.mzk.iiif.adapter.SolrClient;
import cz.mzk.iiif.adapter.Version;
import cz.mzk.iiif.adapter.v50.Kramerius50;
import cz.mzk.iiif.adapter.v70.Kramerius70;
import cz.mzk.iiif.adapter.v714.Kramerius714;
import cz.mzk.iiif.document.ContentLocator;
import cz.mzk.iiif.document.LibraryProfile;
import cz.mzk.iiif.gateway.registry.Registry;
import cz.mzk.iiif.gateway.registry.RegistryEntry;
import cz.mzk.iiif.gateway.store.CachedImageSizes;
import cz.mzk.iiif.gateway.store.SizeStore;
import cz.mzk.iiif.i18n.Translations;
import cz.mzk.iiif.spi.ImageSizeSource;

public final class Libraries {

  private static final Logger LOG = LoggerFactory.getLogger(Libraries.class);

  /** Why a library cannot be served, when it cannot. */
  public sealed interface Resolution {
    record Ready(Library library) implements Resolution {}

    /** No such code in the registry or the static list. */
    record Unknown(String code) implements Resolution {}

    /** Below every adapter floor — the one K4 instance, and K5 until Phase 9. */
    record Unsupported(String code, String version, String lowestSupported) implements Resolution {}

    /** The registry says it is down. Failing fast beats timing out. */
    record Dead(String code) implements Resolution {}

    /**
     * This deployment refuses to serve it — see IIIF_EXCLUDED_LIBRARIES.
     *
     * <p>Distinct from Dead, which is the registry's opinion about the instance, and from
     * Unsupported, which is about its version. This one is ours, and saying so is the point: an
     * operator who excluded a library and then forgot needs the answer to name the reason rather
     * than look like an outage somewhere else.
     */
    record Excluded(String code) implements Resolution {}
  }

  /** One resolved library, ready to serve. */
  public record Library(
      String code,
      String krameriusUrl,
      Version version,
      KrameriusAdapter adapter,
      KrameriusRepository repository,
      PredictivePolicy policy,
      Optional<CachedImageSizes> cache,
      boolean untested) {}

  private final Config config;
  private final Optional<Registry> registry;
  private final Optional<SizeStore> store;
  private final HttpClient http;
  private final AdapterRegistry adapters;
  private final Translations translations;
  private final Catalogues catalogues;
  private final Map<String, Library> resolved = new ConcurrentHashMap<>();

  public Libraries(
      Config config, Optional<Registry> registry, Optional<SizeStore> store, HttpClient http) {
    this.config = config;
    this.registry = registry;
    this.store = store;
    this.http = http;
    // The model tables come from IIIF_MODELS_DIR when set, so a Kramerius release that adds a model
    // does not need one of ours. K7's two adapters share a floor and so share a table.
    var models70 = ModelDirectory.load(config.modelsDir(), DocumentModels.KRAMERIUS_70);
    this.adapters =
        AdapterRegistry.of(
            new Kramerius50(ModelDirectory.load(config.modelsDir(), Kramerius50.KRAMERIUS_50)),
            new Kramerius70(models70),
            new Kramerius714(models70));
    this.translations = TranslationDirectory.load(config.translationsDir());
    this.catalogues = Catalogues.load(config.cataloguesDir());
  }

  public AdapterRegistry adapters() {
    return adapters;
  }

  /**
   * The registry's own row for a library, without resolving it.
   *
   * <p>For the health checker, which decides most of what it reports — alive, version, adapter —
   * from this alone, and only reaches the instance when those say it could work.
   */
  public Optional<RegistryEntry> registryEntry(String code) {
    return registry.flatMap(r -> r.find(code));
  }

  /**
   * Every code this gateway could serve, for the ops view.
   *
   * <p>The union, not one or the other. A declaration used to hide the registry entirely, so an
   * operator who set a rate for one library silently stopped serving the other fifty-five.
   */
  public List<String> codes() {
    java.util.SequencedSet<String> codes = new java.util.LinkedHashSet<>();
    registry.ifPresent(r -> codes.addAll(r.all().keySet()));
    codes.addAll(config.staticLibraries().keySet());
    return List.copyOf(codes);
  }

  public Resolution resolve(String code) {
    Library cached = resolved.get(code);
    if (cached != null) {
      return new Resolution.Ready(cached);
    }

    // Before the registry and before the static list, because it overrides both: a code that is
    // excluded is excluded however it was going to be found.
    if (config.excludedLibraries().contains(code.toLowerCase(java.util.Locale.ROOT))) {
      return new Resolution.Excluded(code);
    }

    Config.StaticLibrary declared = config.staticLibraries().get(code);
    Optional<RegistryEntry> entry = registry.flatMap(r -> r.find(code));

    if (declared == null) {
      if (entry.isEmpty()) {
        return new Resolution.Unknown(code);
      }
      if (!entry.get().alive()) {
        return new Resolution.Dead(code);
      }
      return build(code, entry.get().url(), entry.get().version().orElse(""), entry);
    }

    // Declared, so the registry's `alive` is not consulted: naming a library locally is the
    // operator saying to serve it. That matters — the registry called `svkos` dead for eight
    // months while the instance answered perfectly well, and an operator who can see that should
    // not have to wait for the registry to agree.
    Optional<String> url = declared.url().or(() -> entry.map(RegistryEntry::url));
    if (url.isEmpty()) {
      LOG.warn("{} is declared with no url and the registry has no entry for it", code);
      return new Resolution.Unknown(code);
    }
    String version = declared.version().or(() -> entry.flatMap(RegistryEntry::version)).orElse("");
    return build(code, url.get(), version, entry);
  }

  private Resolution build(
      String code, String url, String rawVersion, Optional<RegistryEntry> entry) {

    Optional<Version> version = effectiveVersion(code, url, rawVersion);
    if (version.isEmpty()) {
      return new Resolution.Unsupported(code, rawVersion, adapters.lowestSupported().toString());
    }

    Optional<KrameriusAdapter> adapter = adapters.forVersion(version.get());
    if (adapter.isEmpty()) {
      return new Resolution.Unsupported(code, rawVersion, adapters.lowestSupported().toString());
    }

    SolrClient solr =
        new SolrClient(
            http,
            url,
            config.readTimeout(),
            adapter.get().schema().searchPath(),
            adapter.get().schema().pidField(),
            config.solrPageSize(),
            config.maxItems());

    // What the image server offers is detected, not declared — an operator can swap it
    // independently
    // of the Kramerius release, and on K5 whether there is one at all is a deployment choice. The
    // probe needs Solr to find public pages to ask about, which is why it comes after the client.
    // See docs/DESIGN.md.
    ImageApi.Profile imageService =
        ImageApi.probe(http, url, solr, adapter.get().schema(), config.readTimeout());
    if (adapter.get().imageServiceIsCertain()) {
      imageService = imageService.assumingAvailable();
    }

    ContentLocator urls = adapter.get().urls(url, imageService.available(), imageService.version());

    // What this instance calls its own licences. The API route exists only in newer 7.2.x;
    // everything
    // else publishes them through its web client, so that is the fallback. An instance with neither
    // keeps the bundled wording, and nothing breaks.
    Map<String, cz.mzk.iiif.adapter.LicenseConfig.License> licenses =
        cz.mzk.iiif.adapter.LicenseConfig.fetch(http, url, config.readTimeout());
    if (licenses.isEmpty()) {
      licenses =
          cz.mzk.iiif.adapter.WebClientLicenses.fetch(
              http,
              clientBase(entry),
              code,
              solr.distinctValues("licenses", 40),
              cz.mzk.iiif.i18n.Translations.BUNDLED,
              config.readTimeout());
    }

    // One pacer per library, held for the life of the resolved library, because the thing being
    // protected is one institution's image server — and it does not care whether the request came
    // from a manifest or from the worker walking its way through 76 million pages.
    //
    // The RATE is per library too, where the library config declares one. A single global number is
    // the wrong shape for 56 institutions of wildly different size, and it is the knob an operator
    // reaches for after a phone call from one of them. Registry mode has no per-library file, so it
    // gets the global value — see docs/DECISIONS.md.
    int rps =
        Optional.ofNullable(config.staticLibraries().get(code))
            .flatMap(declared -> declared.rps().stream().boxed().findFirst())
            .orElse(config.upstreamRps());
    if (rps != config.upstreamRps()) {
      LOG.info("{}: upstream pacing at {} rps, not the global {}", code, rps, config.upstreamRps());
    }

    ImageSizeSource live =
        new InfoJsonSizes(
            http,
            urls,
            config.readTimeout(),
            config.imageInfoConcurrency(),
            new cz.mzk.iiif.adapter.UpstreamPacer(rps));
    Optional<CachedImageSizes> cache =
        store.map(s -> new CachedImageSizes(s, live, code, config.failedRetry()));

    Library library =
        new Library(
            code,
            url,
            version.get(),
            adapter.get(),
            new KrameriusRepository(
                adapter.get(),
                solr,
                cache.map(c -> (ImageSizeSource) c).orElse(live),
                urls,
                profile(code, entry, licenses),
                catalogues.forLibrary(code).orElse(null),
                adapter.get().records(translations, licenses)),
            new PredictivePolicy(config.dimensionWait(), config.imageInfoConcurrency()),
            cache,
            AdapterRegistry.isUntested(adapter.get(), version.get()));

    if (library.untested()) {
      LOG.warn(
          "{} runs {}, newer than {} has been tested against ({})",
          code,
          version.get(),
          adapter.get().name(),
          adapter.get().testedUpTo());
    }
    resolved.put(code, library);
    return new Resolution.Ready(library);
  }

  /**
   * What version this instance actually runs, asked of the instance.
   *
   * <p>The declared version — from the registry or from configuration — is a fallback, not the
   * source of truth. It has to be, because the version selects the adapter and second-hand versions
   * measurably go stale: of the 48 registry libraries that answered /info, three disagreed with the
   * registry, and for `svkos` the registry was a whole major version out. See {@link
   * KrameriusInfo}.
   *
   * <p>A disagreement is logged at WARN rather than swallowed. It is not an error — the instance is
   * right and we adapt — but it means somebody's configuration or the registry needs regenerating,
   * and that is worth one line in a log rather than being silently correct forever.
   *
   * <p>When the instance does not answer, the declared version stands. An instance that answers
   * nothing at all is unservable for reasons this method is not equipped to report.
   */
  public Optional<Version> effectiveVersion(String code, String url, String rawVersion) {
    Optional<Version> declared = Version.parse(rawVersion);
    Optional<Version> reported = KrameriusInfo.version(http, url, config.readTimeout());

    if (reported.isEmpty()) {
      if (declared.isPresent()) {
        LOG.debug("{} did not report a version; using the declared {}", code, declared.get());
      }
      return declared;
    }
    if (declared.isPresent() && !declared.get().raw().equals(reported.get().raw())) {
      LOG.warn(
          "{} reports {} but was declared {} — trusting the instance."
              + " Regenerate the library file, or let the registry catch up.",
          code,
          reported.get().raw(),
          declared.get().raw());
    }
    return reported;
  }

  /**
   * Everything a manifest says about the institution.
   *
   * <p>The registry's names go in first and the operator's are applied OVER them, language tag by
   * language tag. That is the merge that makes the registry usable as a base: it carries exactly
   * two names per library — the institution's own and an English one, verified across all 55 rows —
   * so a deployment wanting German, Polish or a corrected Czech had no way to say so without
   * abandoning the registry for that library and restating its url and version too. Now `de:…` adds
   * a third language and `cs:…` replaces the Czech, and nothing else changes.
   *
   * <p>Failing both, all we have is the library CODE — and a code is not a name. It is emitted as
   * `none` rather than tagged with a language, because claiming "mzk" is Czech is the same
   * false-language bug as v1's `"cz"`, and a viewer selecting by locale would act on it.
   *
   * <p>Kramerius cannot help here: /client/v7.0/info reports only an acronym, and points back at
   * the registry for the rest. So "Provided by: mzk" is what an operator gets for declaring a
   * library inline without a name, which is why .env.example tells them to give one.
   */
  private LibraryProfile profile(
      String code,
      Optional<RegistryEntry> entry,
      Map<String, cz.mzk.iiif.adapter.LicenseConfig.License> licenses) {
    Map<String, List<String>> label = new LinkedHashMap<>();
    Map<String, List<String>> declared =
        Optional.ofNullable(config.staticLibraries().get(code))
            .map(Config.StaticLibrary::names)
            .orElseGet(Map::of);

    if (entry.isPresent()) {
      // Tagged with the language the name is actually in — Slovak for the SNK libraries.
      RegistryEntry e = entry.get();
      label.put(e.nameLanguage(), List.of(e.name()));
      e.nameEnglish().ifPresent(english -> label.put("en", List.of(english)));
    }
    // Over the registry's, per tag: an operator adding `de` keeps the Czech and the English.
    label.putAll(declared);

    if (label.isEmpty()) {
      LOG.warn(
          "{} has no name: declare one as IIIF_LIBRARIES={}=<url>|<version>|cs:Name;en:Name,"
              + " or manifests will attribute it to the bare code",
          code,
          code);
      label.put("none", List.of(code));
    }

    return LibraryProfile.of(code, "https://registr.digitalniknihovna.cz/library/" + code)
        .label(label)
        .homepage(entry.flatMap(RegistryEntry::web).orElse(null))
        .logo(entry.flatMap(RegistryEntry::logo).orElse(null))
        .build();
  }

  /**
   * Libraries already resolved, without resolving any more.
   *
   * <p>Separate from {@link #resolve} because resolving touches the upstream — adapter selection,
   * Solr topology, Image API detection — and anything that reports on all of them at once must not
   * set that off for fifty third-party institutions.
   */
  public Map<String, Library> resolved() {
    return Map.copyOf(resolved);
  }

  /**
   * Where the library's web client lives, which is where its licence notices are published.
   *
   * <p>The registry calls it new_client_url and points at a per-library path (…/mzk); the assets
   * sit at the origin, so the path is trimmed.
   */
  private static String clientBase(Optional<RegistryEntry> entry) {
    return entry
        .flatMap(RegistryEntry::newClientUrl)
        .map(
            url -> {
              int scheme = url.indexOf("://");
              int slash = url.indexOf('/', scheme < 0 ? 0 : scheme + 3);
              return slash < 0 ? url : url.substring(0, slash);
            })
        .orElse("https://www.digitalniknihovna.cz");
  }

  /** Drops a resolved library so the next request rebuilds it. */
  public void forget(String code) {
    resolved.remove(code);
  }
}
