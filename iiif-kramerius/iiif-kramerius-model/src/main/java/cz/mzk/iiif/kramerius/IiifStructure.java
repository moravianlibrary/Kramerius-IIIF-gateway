/* How the builder turns a Kramerius document into IIIF. */
package cz.mzk.iiif.kramerius;

import java.util.Set;

public enum IiifStructure {
  PAGED_MANIFEST(false, Set.of(Part.PAGES)),
  MEDIA_MANIFEST(false, Set.of(Part.PAGES, Part.MEDIA_UNITS)),
  CHILD_COLLECTION(true, Set.of(Part.MEMBERS)),
  VIRTUAL_COLLECTION(true, Set.of(Part.MEMBERS));

  public enum Part {
    PAGES,
    MEMBERS,
    MEDIA_UNITS
  }

  private final boolean collection;
  private final Set<Part> requires;

  IiifStructure(boolean collection, Set<Part> requires) {
    this.collection = collection;
    this.requires = requires;
  }

  public boolean isCollection() {
    return collection;
  }

  public Set<Part> requires() {
    return requires;
  }
}
