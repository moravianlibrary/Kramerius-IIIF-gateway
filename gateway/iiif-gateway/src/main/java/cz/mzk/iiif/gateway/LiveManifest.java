/*
 * Builds one manifest against a live Kramerius, and prints it.
 *
 * A dev/ops entry point, not part of the served application: one `main` that answers "does this work
 * against a real instance" without the web role being up. Everything it wires — adapter selection,
 * Solr, sizes, assembly, building — is the same code path a request takes.
 *
 * It lives on the gateway side of the split, not with the adapters, because it wires the whole
 * application together: it reads the catalogue configuration, which is gateway-side. Left in the
 * adapter package it was the one import that pointed the wrong way, and a module boundary now makes
 * that a compile error rather than a thing to notice.
 *
 * Sizes are resolved inline with no cache, because there is nowhere to defer to here. That makes it slow
 * for a large document and is exactly why the gateway has a worker.
 */
package cz.mzk.iiif.gateway;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cz.mzk.iiif.adapter.AdapterRegistry;
import cz.mzk.iiif.adapter.InfoJsonSizes;
import cz.mzk.iiif.adapter.KrameriusAdapter;
import cz.mzk.iiif.adapter.KrameriusRepository;
import cz.mzk.iiif.adapter.LicenseConfig;
import cz.mzk.iiif.adapter.SolrClient;
import cz.mzk.iiif.adapter.Version;
import cz.mzk.iiif.adapter.v70.Kramerius70;
import cz.mzk.iiif.adapter.v714.Kramerius714;
import cz.mzk.iiif.build.ManifestBuilder;
import cz.mzk.iiif.build.ManifestConfig;
import cz.mzk.iiif.document.LibraryProfile;
import cz.mzk.iiif.i18n.Translations;
import cz.mzk.iiif.presentation.ManifestWriter;
import cz.mzk.iiif.spi.BundleAssembler;
import cz.mzk.iiif.spi.CatalogueLink;
import cz.mzk.iiif.spi.DimensionPolicy;

public final class LiveManifest {

  private LiveManifest() {}

  /** args: <library-code> <kramerius-base-url> <version> <pid> [--no-sizes] */
  public static void main(String[] args) {
    if (args.length < 4) {
      System.err.println(
          "usage: LiveManifest <code> <baseUrl> <version> <pid> [--no-sizes] [--image-api-3]");
      System.exit(2);
    }
    String code = args[0];
    String baseUrl = args[1];
    Version version = Version.parse(args[2]).orElseThrow();
    String pid = args[3];
    List<String> flags = List.of(args).subList(Math.min(4, args.length), args.length);
    boolean withSizes = !flags.contains("--no-sizes");
    int imageApi = flags.contains("--image-api-3") ? 3 : 2;

    AdapterRegistry registry =
        AdapterRegistry.of(
            new cz.mzk.iiif.adapter.v50.Kramerius50(), new Kramerius70(), new Kramerius714());
    KrameriusAdapter adapter =
        registry
            .forVersion(version)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        version + " is below the lowest supported " + registry.lowestSupported()));

    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    SolrClient solr =
        new SolrClient(http, baseUrl, Duration.ofSeconds(30), adapter.schema().searchPath());

    // This is a diagnostic tool, so the flags win over a probe: --image-api-3 exists precisely to
    // see
    // what a manifest looks like against a version the library may not be serving. An image service
    // is assumed present for the same reason — --no-sizes is how you ask for the flat-stream shape.
    cz.mzk.iiif.document.ContentLocator urls = adapter.urls(baseUrl, true, imageApi);

    Map<String, List<String>> label = new LinkedHashMap<>();
    label.put("cs", List.of(code));

    var repository =
        new KrameriusRepository(
            adapter,
            solr,
            withSizes ? new InfoJsonSizes(http, urls, Duration.ofSeconds(20), 8) : pids -> Map.of(),
            urls,
            LibraryProfile.of(
                    code, "https://api.registr.digitalniknihovna.cz/api/libraries/" + code)
                .label(label)
                .build(),
            catalogueFor(code),
            adapter.records(
                Translations.bundled(),
                LicenseConfig.fetch(http, baseUrl, Duration.ofSeconds(20))));

    BundleAssembler.Result result =
        BundleAssembler.assemble(repository, DimensionPolicy.ALWAYS, pid);

    if (!(result instanceof BundleAssembler.Result.Found found)) {
      System.err.println(result);
      System.exit(1);
      return;
    }

    ManifestConfig config =
        ManifestConfig.of("https://iiif.digitalniknihovna.cz", "https://www.digitalniknihovna.cz");
    System.out.println(ManifestWriter.toJson(ManifestBuilder.build(config, found.bundle())));
  }

  /** The same configured mappings the gateway serves from, so this tool shows what it would. */
  private static CatalogueLink catalogueFor(String code) {
    return Catalogues.load(java.util.Optional.empty()).forLibrary(code).orElse(null);
  }
}
