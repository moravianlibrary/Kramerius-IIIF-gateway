/*
 * One Kramerius document model and what this gateway does with it.
 *
 * A record loaded from models/kramerius.json rather than an enum, because the table is data: Kramerius
 * introduces models, and supporting a new one should be an entry in a file rather than a release. The
 * SHAPE is still an enum — see IiifStructure — because the builder switches on it exhaustively, so a
 * shape only exists if there is code that knows how to build it.
 *
 * Type labels are not here either; they are i18n/<language>.json under `model.<solrName>`.
 *
 * Four of these models were refused outright by the Ruby gateway — supplement, article, internalpart and
 * picture — which at MZK alone is roughly 152 000 documents that have pages and could have been served.
 * Three hold their pages by foster relationship, which is what fostersPages says.
 */
package cz.mzk.iiif.adapter;

import java.util.Optional;

import cz.mzk.iiif.kramerius.IiifStructure;

public record DocumentModel(
    String solrName,
    IiifStructure shape,

    /** Which children a CHILD_COLLECTION shape collects. Empty for other shapes. */
    Optional<String> children,

    /**
     * Pages belong to an ancestor rather than to this model.
     *
     * <p>An article does not own its pages — the issue does, and the article is fostered onto a
     * page range within it. Querying own_parent.pid for one returns nothing, which is why the Ruby
     * could not have served an article even if its dispatch table had listed the model.
     */
    boolean fostersPages,

    /**
     * Worth building a partOf block for. Whether the parent is linkable is decided from the parent.
     */
    boolean hasAncestors,

    /** Skip members of this same model — Kramerius indexes a convolute as its own child. */
    boolean excludesOwnModel,

    /** The model this becomes when children of {@link #promoteWhen} exist. */
    Optional<String> promoteTo,

    /** Which children trigger the promotion. */
    Optional<String> promoteWhen) {

  /** Translation key for this model's human-readable type. */
  public String labelKey() {
    return "model." + solrName;
  }

  /** Collections are Collections; everything else is a Manifest. */
  public String iiifType() {
    return shape.isCollection() ? "Collection" : "Manifest";
  }

  /**
   * Whether a document of this model should be re-resolved as another when it has such children.
   */
  public boolean promotes() {
    return promoteTo.isPresent() && promoteWhen.isPresent();
  }
}
