/*
 * Everything needed to build one manifest, gathered.
 *
 * This is the seam Kramerius' own application uses. It fetches its own records from its own Solr, fills
 * one of these, and calls ManifestBuilder — no SPI to implement, no registry, no adapter selection, no
 * per-library configuration. All of that exists because a gateway serves libraries it does not own; an
 * application serving its own repository has none of that problem.
 *
 * The builder used to *pull* — calling back into a repository as each shape discovered what it needed —
 * which meant level 2 could not exist without level 3. Pushing instead makes the construction rules a
 * pure function, which is both the point of the layering and why every test of them runs offline.
 *
 * Which children a shape needs is declared by DocumentShape at level 1, so a host does not have to
 * reverse-engineer it from the builder.
 */
package cz.mzk.iiif.document;

import java.util.List;
import java.util.Map;

import cz.mzk.iiif.kramerius.DocumentShape;

public record DocumentBundle(
    LibraryProfile library,

    /** URL construction for this library's streams. Pure string building, no I/O. */
    ContentLocator urls,
    DocumentRecord document,

    /** The document's pages, in reading order. Empty for shapes that have none. */
    List<DocumentRecord> pages,

    /** Child documents this becomes a Collection of. Empty for page shapes. */
    List<DocumentRecord> members,

    /** Sound units with their tracks, in order. Empty for everything but a recording. */
    List<SoundUnit> soundUnits,

    /**
     * Page dimensions, by page pid.
     *
     * <p>A pid may be absent, and the canvas then falls back to a nominal size. Where these came
     * from — a cache, a live fetch, nowhere — is the host's business and nothing here asks.
     */
    Map<String, ImageSize> sizes,

    /** The catalogue record link, when the host resolved one. */
    java.util.Optional<String> catalogueUrl) {

  /**
   * What a host must gather for a document of this shape.
   *
   * <p>Re-exposed from DocumentShape so a host filling a bundle has one place to look rather than
   * having to know that the answer lives at level 1.
   */
  public static java.util.Set<DocumentShape.Part> requires(DocumentShape shape) {
    return shape.requires();
  }

  /** Whether everything this document's shape needs is actually present. */
  public boolean isComplete() {
    java.util.Set<DocumentShape.Part> required = requires(document.shape());
    return (!required.contains(DocumentShape.Part.PAGES) || !pages.isEmpty() || document.hasPdf())
        && (!required.contains(DocumentShape.Part.MEMBERS) || !members.isEmpty())
        && (!required.contains(DocumentShape.Part.SOUND_UNITS) || !soundUnits.isEmpty());
  }

  /** One sound unit and the tracks under it. */
  public record SoundUnit(DocumentRecord unit, List<DocumentRecord> tracks) {
    public SoundUnit {
      tracks = List.copyOf(tracks);
    }
  }

  public DocumentBundle {
    pages = List.copyOf(pages);
    members = List.copyOf(members);
    soundUnits = List.copyOf(soundUnits);
    sizes = Map.copyOf(sizes);
  }

  public static Builder of(LibraryProfile library, ContentLocator urls, DocumentRecord document) {
    return new Builder(library, urls, document);
  }

  public static final class Builder {
    private final LibraryProfile library;
    private final ContentLocator urls;
    private final DocumentRecord document;
    private List<DocumentRecord> pages = List.of();
    private List<DocumentRecord> members = List.of();
    private List<SoundUnit> soundUnits = List.of();
    private Map<String, ImageSize> sizes = Map.of();
    private String catalogueUrl;

    private Builder(LibraryProfile library, ContentLocator urls, DocumentRecord document) {
      this.library = library;
      this.urls = urls;
      this.document = document;
    }

    public Builder pages(List<DocumentRecord> value) {
      this.pages = value == null ? List.of() : value;
      return this;
    }

    public Builder members(List<DocumentRecord> value) {
      this.members = value == null ? List.of() : value;
      return this;
    }

    public Builder soundUnits(List<SoundUnit> value) {
      this.soundUnits = value == null ? List.of() : value;
      return this;
    }

    public Builder sizes(Map<String, ImageSize> value) {
      this.sizes = value == null ? Map.of() : value;
      return this;
    }

    public Builder catalogueUrl(String value) {
      this.catalogueUrl = value;
      return this;
    }

    public DocumentBundle build() {
      return new DocumentBundle(
          library,
          urls,
          document,
          pages,
          members,
          soundUnits,
          sizes,
          java.util.Optional.ofNullable(catalogueUrl));
    }
  }
}
