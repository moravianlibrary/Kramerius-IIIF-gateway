/*
 * The few things the builder needs that are not properties of the document or its library.
 *
 * baseUrl is load-bearing: every `id` in every manifest is built from it, so it has to match how clients
 * actually reach the service or viewers follow dead links. It is not derived from the request, because a
 * manifest served through a proxy would otherwise advertise the proxy's internal address.
 *
 * Translations travel here rather than being looked up statically so a host can load extra languages at
 * startup. A library that reached for a global would make that impossible.
 *
 * Note what is NOT here any more: the document-model table. Which Kramerius model produces which shape
 * changes between releases, so it is adapter knowledge; by the time a record reaches this level its shape
 * is already resolved.
 */
package cz.mzk.iiif.build;

import cz.mzk.iiif.i18n.Translations;

public record ManifestConfig(
    String baseUrl, String digitalLibraryUrl, int fallbackCanvasSize, Translations translations) {

  /**
   * What the legacy gateway declared for every canvas, and what we still use for an unmeasured
   * page.
   */
  public static final int DEFAULT_FALLBACK_CANVAS_SIZE = 1000;

  public ManifestConfig {
    baseUrl = trimTrailingSlash(baseUrl);
    digitalLibraryUrl = trimTrailingSlash(digitalLibraryUrl);
    if (fallbackCanvasSize <= 0) {
      throw new IllegalArgumentException("fallback canvas size must be positive");
    }
    if (translations == null) {
      throw new IllegalArgumentException("translations are required");
    }
  }

  /** Defaults, with the translations bundled in the jar. */
  public static ManifestConfig of(String baseUrl, String digitalLibraryUrl) {
    return new ManifestConfig(
        baseUrl, digitalLibraryUrl, DEFAULT_FALLBACK_CANVAS_SIZE, Translations.bundled());
  }

  private static String trimTrailingSlash(String url) {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("a base URL is required");
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
