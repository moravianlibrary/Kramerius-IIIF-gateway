/*
 * Every URI this gateway mints.
 *
 * They are collected here because they have to agree with each other and with the service's routes: an
 * annotation targets its canvas by id, and a client that follows a manifest id has to arrive back at the
 * manifest. Scattering the string formatting across the builder is how those drift apart.
 *
 * All of them are built from the configured base URL rather than from the request, because a manifest
 * served through a proxy would otherwise advertise the proxy's internal address.
 */
package cz.mzk.iiif.build;

final class ManifestIds {

  private final String base;

  private ManifestIds(String base) {
    this.base = base;
  }

  static ManifestIds of(ManifestConfig config, String libraryCode) {
    return new ManifestIds("%s/%s".formatted(config.baseUrl(), libraryCode));
  }

  /** The manifest for any document in this library — the id a client can follow. */
  String manifest(String pid) {
    return "%s/%s".formatted(base, pid);
  }

  /** Prefix of every id built inside one document: canvases, ranges, features. */
  String within(String pid) {
    return manifest(pid);
  }

  String canvas(String documentPid, int index) {
    return "%s/canvases/%d".formatted(within(documentPid), index);
  }

  /** An annotation page belongs to its canvas, so its id extends the canvas'. */
  static String annotationPage(String canvasId) {
    return canvasId + "/ap";
  }

  static String annotation(String canvasId) {
    return canvasId + "/ap/a";
  }

  String range(String documentPid, int index) {
    return "%s/range/%d".formatted(within(documentPid), index);
  }

  String feature(String documentPid) {
    return "%s/feature".formatted(within(documentPid));
  }
}
