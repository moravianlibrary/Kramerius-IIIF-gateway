/*
 * Builds the IIIF Presentation v3 document for one gathered object.
 *
 * A pure function: a DocumentBundle goes in, an Iiif.Document comes out. Nothing here fetches, caches or
 * resolves anything — the host has already done that, and by the time a record arrives its shape is
 * decided and its descriptive text is already mapped to IIIF.
 *
 * That is what makes this level usable on its own. Kramerius' own application gathers from its own Solr,
 * fills a bundle, and calls build(); it never implements an SPI, never sees a registry, and never learns
 * that adapters exist.
 *
 * One instance per document, because canvas and range numbering are running counters shared across a
 * document's sections — a sound recording numbers its track canvases after its page canvases.
 *
 * The tests specify what comes out of here. When they disagree with the implementation, the
 * implementation is a bug.
 */
package cz.mzk.iiif.build;

import java.util.ArrayList;
import java.util.List;

import cz.mzk.iiif.document.DocumentBundle;
import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.presentation.Iiif;

public final class ManifestBuilder {

  private final ManifestConfig config;
  private final DocumentBundle bundle;
  private final DocumentRecord document;
  private final ManifestIds ids;
  private final Canvases canvases;
  private final DocumentBlocks blocks;

  private int rangeIndex;

  private ManifestBuilder(ManifestConfig config, DocumentBundle bundle) {
    this.config = config;
    this.bundle = bundle;
    this.document = bundle.document();
    this.ids = ManifestIds.of(config, bundle.library().code());
    this.canvases = new Canvases(config, bundle, ids);
    this.blocks = new DocumentBlocks(config, bundle, ids);
  }

  public static Iiif.Document build(ManifestConfig config, DocumentBundle bundle) {
    return new ManifestBuilder(config, bundle).dispatch();
  }

  private Iiif.Document dispatch() {
    return switch (document.shape()) {
      case PAGED_MANIFEST -> pages();
      case MEDIA_MANIFEST -> soundRecording();
      // Both collection shapes differ only in how the host gathered the members, which it already
      // has.
      case CHILD_COLLECTION, VIRTUAL_COLLECTION -> members();
    };
  }

  /**
   * A sequence of pages.
   *
   * <p>An object with a PDF and no pages lands here too and comes out as a Manifest with empty
   * `items` plus a rendering, which is what it is. There is no separate PDF shape — see
   * IiifStructure.
   */
  private Iiif.Document pages() {
    List<Object> items = new ArrayList<>(bundle.pages().size());
    for (DocumentRecord page : bundle.pages()) {
      items.add(canvases.page(page));
    }
    return assemble(items, null, DocumentBlocks.behavior(bundle.pages()));
  }

  /**
   * A recording: its own page images first, then the tracks of each sound unit.
   *
   * <p>Tracks are also grouped into one Range per unit, so a player can present the recording's own
   * division into sides or movements.
   */
  private Iiif.Document soundRecording() {
    List<Object> items = new ArrayList<>();
    for (DocumentRecord page : bundle.pages()) {
      items.add(canvases.page(page));
    }

    List<Iiif.Range> structures = new ArrayList<>();
    for (DocumentBundle.MediaUnit unit : bundle.mediaUnits()) {
      List<Iiif.Reference> members = new ArrayList<>();
      for (DocumentRecord track : unit.tracks()) {
        Iiif.Canvas canvas = canvases.track(track, unit.unit());
        items.add(canvas);
        members.add(new Iiif.Reference(canvas.id(), canvas.type(), null));
      }
      structures.add(
          new Iiif.Range(
              ids.range(document.pid(), rangeIndex++), "Range", unit.unit().label(), members));
    }
    return assemble(items, structures, null);
  }

  /**
   * A Collection of references to child documents.
   *
   * <p>Each member is typed from its OWN resolved shape, not from the parent's — so a collection
   * nested in a collection comes out a Collection while its siblings come out Manifests. v1 typed
   * every member the same and mislabelled every nested collection.
   *
   * <p>A member of the same model as the parent is skipped where the model table said so: Kramerius
   * indexes a convolute as its own child, which would otherwise make it a member of itself.
   *
   * <p>A member with no pid of its own — a page added to a collection individually — is referenced
   * through its parent, because a page has no manifest.
   */
  private Iiif.Document members() {
    List<Object> items = new ArrayList<>(bundle.members().size());
    for (DocumentRecord member : bundle.members()) {
      if (document.excludesOwnModel() && document.model().equals(member.model())) {
        continue;
      }
      items.add(reference(member));
    }
    return assemble(items, null, null);
  }

  private Iiif.Reference reference(DocumentRecord member) {
    // A page cannot be a manifest, so it points at its containing document. Other child documents
    // (a periodical volume, issue or monograph unit) have their own manifest even though they too
    // carry a parent, and must retain their own pid.
    boolean page = "page".equals(member.model());
    String target =
        page ? member.parent().map(DocumentRecord.Parent::pid).orElse(member.pid()) : member.pid();
    String type = page ? "Manifest" : member.iiifType();
    return new Iiif.Reference(ids.manifest(target), type, member.label());
  }

  /** Fills in everything that does not depend on the shape. */
  private Iiif.Document assemble(
      List<Object> items, List<Iiif.Range> structures, List<String> behavior) {

    Iiif.NavPlace navPlace = blocks.navPlace();
    Object context =
        navPlace == null
            ? Iiif.PRESENTATION_CONTEXT
            : List.of(Iiif.NAVPLACE_CONTEXT, Iiif.PRESENTATION_CONTEXT);

    return new Iiif.Document(
        context,
        ids.manifest(document.pid()),
        document.iiifType(),
        document.label(),
        blocks.requiredStatement(),
        document.metadata().isEmpty() ? null : document.metadata(),
        document.summary(),
        blocks.provider(),
        blocks.homepage(),
        blocks.thumbnail(),
        behavior,
        blocks.navDate(),
        navPlace,
        blocks.seeAlso(),
        blocks.rendering(),
        // A Manifest must carry items even when empty; a Collection need not, but always does here.
        items,
        structures == null || structures.isEmpty() ? null : structures,
        blocks.partOf());
  }
}
