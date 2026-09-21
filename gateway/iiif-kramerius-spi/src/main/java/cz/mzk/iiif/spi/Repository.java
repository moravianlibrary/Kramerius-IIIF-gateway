/*
 * One Kramerius library, as something that can be read from.
 *
 * Two fetching contracts, plus the two pieces of data a manifest needs about the library itself. The
 * fetching halves are interfaces because how you reach a repository genuinely varies; the data halves
 * are values because they do not.
 *
 * A host that has no cache implements only ImageSizeSource and leaves managed() empty; everything that
 * depends on a cache is then skipped rather than faked.
 */
package cz.mzk.iiif.spi;

import java.util.Optional;

import cz.mzk.iiif.document.ContentLocator;
import cz.mzk.iiif.document.LibraryProfile;

public interface Repository {

  DocumentSource documents();

  ImageSizeSource sizes();

  /** URL construction for this library's streams. Pure string building. */
  ContentLocator urls();

  /** What a manifest says about the institution. Assembled by the host, not fetched here. */
  LibraryProfile library();

  /**
   * How this library's system numbers become catalogue URLs, when it has a catalogue.
   *
   * <p>Level 3 rather than on the profile because it is about reaching an external system, which a
   * manifest has no opinion about — the profile carries only what a manifest says.
   */
  default Optional<CatalogueLink> catalogue() {
    return Optional.empty();
  }

  /** The size source's cache, when it has an addressable one. */
  default Optional<ManagedImageSizeSource> managed() {
    return sizes() instanceof ManagedImageSizeSource managed
        ? Optional.of(managed)
        : Optional.empty();
  }
}
