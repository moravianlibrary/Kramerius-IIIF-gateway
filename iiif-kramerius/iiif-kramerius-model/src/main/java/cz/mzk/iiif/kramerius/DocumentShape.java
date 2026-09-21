/*
 * How a manifest is built. OUR concept, not IIIF's and not Kramerius'.
 *
 * IIIF has exactly two kinds of top-level document, Manifest and Collection. Kramerius has twenty-odd
 * models. This names the handful of distinct strategies for getting from one to the other — four, and
 * that is the whole set:
 *
 *   canvases from pages, canvases plus track ranges, references to children, references to collection
 *   members.
 *
 * There were seven of these until four of them turned out to be the same strategy with a different
 * ChildKind argument. That argument belongs in the model table, so a new collection-ish model is now an
 * entry in a file rather than a constant here plus a switch arm.
 *
 * NOT configurable, deliberately, and the only such thing in this package: ManifestBuilder switches on
 * it exhaustively, so a shape exists only if there is code that builds one. A table naming a shape that
 * no code implements is rejected at load — see DocumentModels.
 */
package cz.mzk.iiif.kramerius;

import java.util.Set;

public enum DocumentShape {
  /** A Manifest whose canvases are the document's pages. */
  PAGES(false, false, Set.of(Part.PAGES)),

  /** A Manifest of pages plus audio tracks, grouped into ranges by sound unit. */
  SOUND_RECORDING(false, false, Set.of(Part.PAGES, Part.SOUND_UNITS)),

  /** A Collection of references to children of one kind, named by the model table. */
  MEMBERS(true, true, Set.of(Part.MEMBERS)),

  /**
   * A Collection of the members of a virtual collection.
   *
   * <p>Separate from MEMBERS because collection membership is a filter query rather than a parent
   * relation — a different SPI method — and a member may be any model at all, including a page.
   */
  COLLECTION_MEMBERS(true, false, Set.of(Part.MEMBERS));

  /**
   * The parts of a bundle a shape actually reads.
   *
   * <p>Declared here so a host can find out what to gather without reading the builder. That
   * matters for the in-process case: Kramerius' own application fills a bundle itself and never
   * calls the assembler, so this is the only thing telling it that a sound recording needs its
   * units fetched as well as its own pages.
   */
  public enum Part {
    PAGES,
    MEMBERS,
    SOUND_UNITS
  }

  private final boolean collection;
  private final boolean needsChildKind;
  private final Set<Part> requires;

  DocumentShape(boolean collection, boolean needsChildKind, Set<Part> requires) {
    this.collection = collection;
    this.needsChildKind = needsChildKind;
    this.requires = requires;
  }

  public boolean isCollection() {
    return collection;
  }

  /** Whether the model table must name which children this shape collects. */
  public boolean needsChildKind() {
    return needsChildKind;
  }

  /** What a host must gather for this shape. Anything else in the bundle is ignored. */
  public Set<Part> requires() {
    return requires;
  }
}
