/*
 * That re-shaping a document changes the shape and nothing else.
 *
 * withShape is a hand-written copy of a nineteen-component record, and several of those components are
 * neighbours of the same type — two language maps, four Optional<String>-ish fields. A transposition
 * there compiles cleanly and loses a document's metadata or its coordinates silently, on exactly the
 * documents that already needed a correction to be served at all.
 *
 * So every component is asserted individually, with a distinct value, rather than by comparing two
 * records: an equals() check would pass for a record whose components were swapped in pairs, and adding
 * a component later would not fail this test if it were only compared wholesale.
 */
package cz.mzk.iiif.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cz.mzk.iiif.kramerius.ChildKind;
import cz.mzk.iiif.kramerius.DocumentShape;
import cz.mzk.iiif.presentation.Iiif;

class DocumentRecordTest {

  /** Every component set, and set to something distinguishable from its neighbours. */
  private static DocumentRecord filled() {
    return DocumentRecord.of("uuid:vol", "periodicalvolume", DocumentShape.MEMBERS)
        .childKind(ChildKind.PERIODICAL_ISSUES)
        .excludesOwnModel(true)
        .label(Map.of("cs", List.of("Ročník 1922")))
        .metadata(
            List.of(
                new Iiif.KeyValue(
                    Map.of("cs", List.of("Nakladatel")), Map.of("cs", List.of("Garamond")))))
        .summary(Map.of("cs", List.of("Popis svazku")))
        .parent("uuid:periodical", "Collection", Map.of("cs", List.of("Titul")))
        .hasPdf(true)
        .pageCount(632)
        .pagePlacement("left")
        .hasTiles(true)
        .hasAltoContent(true)
        .trackLength(123.5)
        .coordinates(new BoundingBox(48.5, 16.5, 49.5, 17.5))
        .licenses(List.of("dnnto"))
        .systemNumber("mzk01-000123")
        .modified(Instant.parse("2024-03-04T05:06:07Z"))
        .build();
  }

  @Test
  @DisplayName("re-shaping keeps every other component intact")
  void withShapeCarriesEverythingElse() {
    DocumentRecord original = filled();

    DocumentRecord reshaped = original.withShape(DocumentShape.PAGES, Optional.empty());

    assertThat(reshaped.shape()).isEqualTo(DocumentShape.PAGES);
    // Cleared deliberately: a document serving pages collects nothing else, and a stale
    // PERIODICAL_ISSUES would be a claim about children it does not have.
    assertThat(reshaped.childKind()).isEmpty();

    assertThat(reshaped.pid()).isEqualTo(original.pid());
    assertThat(reshaped.model()).isEqualTo(original.model());
    assertThat(reshaped.excludesOwnModel()).isEqualTo(original.excludesOwnModel());
    assertThat(reshaped.label()).isEqualTo(original.label());
    assertThat(reshaped.metadata()).isEqualTo(original.metadata());
    assertThat(reshaped.summary()).isEqualTo(original.summary());
    assertThat(reshaped.parent()).isEqualTo(original.parent());
    assertThat(reshaped.hasPdf()).isEqualTo(original.hasPdf());
    assertThat(reshaped.pageCount()).isEqualTo(original.pageCount());
    assertThat(reshaped.pagePlacement()).isEqualTo(original.pagePlacement());
    assertThat(reshaped.hasTiles()).isEqualTo(original.hasTiles());
    assertThat(reshaped.hasAltoContent()).isEqualTo(original.hasAltoContent());
    assertThat(reshaped.trackLength()).isEqualTo(original.trackLength());
    assertThat(reshaped.coordinates()).isEqualTo(original.coordinates());
    assertThat(reshaped.licenses()).isEqualTo(original.licenses());
    assertThat(reshaped.systemNumber()).isEqualTo(original.systemNumber());
    assertThat(reshaped.modified()).isEqualTo(original.modified());
  }

  @Test
  @DisplayName("the two language maps are not transposed")
  void labelAndSummaryDoNotSwap() {
    // Same type, adjacent in the component list, and both language maps — the transposition a
    // wholesale equals() comparison would never catch, because it would still be equal to itself.
    DocumentRecord reshaped = filled().withShape(DocumentShape.PAGES, Optional.empty());

    assertThat(reshaped.label().get("cs")).containsExactly("Ročník 1922");
    assertThat(reshaped.summary().get("cs")).containsExactly("Popis svazku");
  }

  @Test
  @DisplayName("re-shaping the other way keeps the type in step")
  void reshapingToMembersChangesTheIiifType() {
    // The output type is derived from the shape, which is the whole reason the correction has to
    // travel on the record rather than only in what the assembler gathers.
    DocumentRecord pages =
        DocumentRecord.of("uuid:sheet", "sheetmusic", DocumentShape.PAGES)
            .label(Map.of("cs", List.of("Partitura")))
            .build();

    assertThat(pages.iiifType()).isEqualTo("Manifest");
    assertThat(pages.withShape(DocumentShape.MEMBERS, Optional.empty()).iiifType())
        .isEqualTo("Collection");
  }
}
