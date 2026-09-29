/*
 * What one Kramerius behaviour boundary knows.
 *
 * Adapters are named for the FIRST version they support, not for a range, so a release that changes
 * nothing we depend on needs no work at all: the highest floor at or below the instance's version wins,
 * and a 7.3 instance is served by Kramerius714 out of the box.
 *
 * The alternative — a switch in a factory — puts the knowledge of which adapter handles what somewhere
 * other than the adapter. Here each one answers for itself, so adding a boundary is a registration plus
 * a subclass that overrides only what actually changed.
 *
 * A boundary earns an adapter only when it affects a field we USE. coords.point arrives at 7.2.1.1 and is
 * deliberately ignored: it is a copyField of coords.bbox.center, which every K7 instance already has, so
 * an adapter for it would buy a longer chain and nothing else.
 */
package cz.mzk.iiif.adapter;

public interface KrameriusAdapter {

  /** For logs and the ops view. Matches the class name. */
  String name();

  /** The first Kramerius version this adapter supports. */
  Version floor();

  /**
   * The newest version this has actually been exercised against.
   *
   * <p>Exceeding it is allowed and is the point of floor-based selection, but it is logged once per
   * library and shown in GET /libraries, so optimistic forward-compatibility is visible rather than
   * silent.
   */
  Version testedUpTo();

  /**
   * Whether this adapter can serve that instance.
   *
   * <p>Overridable rather than a bare version comparison so a future boundary can key on something
   * else — an instance quirk, a probe result — without reworking selection.
   */
  default boolean supports(Version version) {
    return version.atLeast(floor());
  }

  /** The model table for this floor. Which models exist changes between releases. */
  DocumentModels models();

  /** Solr field names and query construction for this floor. */
  SolrSchema schema();

  /**
   * How this Kramerius' Solr documents become records.
   *
   * <p>On the adapter rather than chosen by the caller, so adding a boundary stays a single
   * registration — K5 needs a structurally different mapper, and nothing outside should have to
   * know that.
   */
  RecordMapper records(
      cz.mzk.iiif.i18n.Translations translations,
      java.util.Map<String, LicenseConfig.License> licenses);

  /**
   * How this Kramerius' stream URLs are built.
   *
   * @param imageServiceAvailable whether this library actually serves a usable Image API. Probed,
   *     not declared — see {@link #imageServiceIsCertain()}.
   * @param imageApiVersion which version it speaks, when it does
   */
  cz.mzk.iiif.document.ContentLocator urls(
      String baseUrl, boolean imageServiceAvailable, int imageApiVersion);

  /**
   * Whether every instance of this Kramerius has an Image API, so only its version is in question.
   *
   * <p>True for K7, where a probe that finds nothing means the sample was unlucky — every page
   * restricted, the server briefly down — and declaring no image service on that evidence would
   * strip tiles from a library that has them.
   *
   * <p>False for K5, where it is genuinely a property of the deployment: three of the eleven alive
   * K5 libraries serve a working Image API 2 and five demonstrably do not. There the probe finding
   * nothing is the answer, and the safe direction is the flat stream.
   */
  default boolean imageServiceIsCertain() {
    return true;
  }
}
