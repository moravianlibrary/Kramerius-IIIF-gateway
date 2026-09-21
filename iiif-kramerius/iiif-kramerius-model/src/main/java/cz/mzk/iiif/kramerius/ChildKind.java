/*
 * What kind of children a shape needs.
 *
 * The library asks for a kind; the adapter decides which Solr model name, which fields and which sort
 * that means. Keeping the vocabulary here rather than passing model names through means the library
 * never mentions a Kramerius field name, and K5 — where every one of them differs — needs no special
 * case above the SPI.
 */
package cz.mzk.iiif.kramerius;

public enum ChildKind {
  /** Ordered by page.index where indexed, otherwise by rels_ext_index. */
  PAGES,

  /** Volumes of a multi-volume monograph, ordered by part number. */
  MONOGRAPH_UNITS,

  /** Volumes of a periodical, ordered by part number then earliest date. */
  PERIODICAL_VOLUMES,

  /** Issues of a volume, ordered by date then issue type. */
  PERIODICAL_ISSUES,

  /** The sound units of a recording. */
  SOUND_UNITS,

  /** The tracks of a sound unit, in index order. */
  TRACKS,

  /** Every child of a convolute, whatever its model. */
  CONVOLUTE_PARTS
}
