/*
 * The document-model table and its validation.
 *
 * The validation tests matter more than the lookup ones. Making the table configurable trades a compile
 * error for a runtime one, and the whole justification for that trade is that a bad table is rejected at
 * startup with the offending value named — not at three in the morning on a manifest request.
 */
package cz.mzk.iiif.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DocumentModelsTest {

  private static InputStream json(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  @Nested
  @DisplayName("the bundled table")
  class Bundled {

    private final DocumentModels models = DocumentModels.bundled(DocumentModels.KRAMERIUS_70);

    @Test
    @DisplayName("carries every model the spec lists")
    void allModelsPresent() {
      assertThat(models.find("monograph")).isPresent();
      assertThat(models.find("supplement")).isPresent();
      assertThat(models.find("article")).isPresent();
      assertThat(models.find("internalpart")).isPresent();
      assertThat(models.find("picture")).isPresent();
      assertThat(models.find("soundunit")).isEmpty();
      assertThat(models.find("nonsense")).isEmpty();
    }

    @Test
    @DisplayName("marks exactly the three fostered-page models")
    void fosteredPages() {
      assertThat(models.require("article").fostersPages()).isTrue();
      assertThat(models.require("internalpart").fostersPages()).isTrue();
      assertThat(models.require("picture").fostersPages()).isTrue();
      assertThat(models.require("supplement").fostersPages()).isFalse();
      assertThat(models.require("periodicalitem").fostersPages()).isFalse();
    }

    @Test
    @DisplayName("a monograph promotes to a multi-volume monograph on volumes")
    void promotion() {
      DocumentModel monograph = models.require("monograph");
      assertThat(monograph.promotes()).isTrue();
      assertThat(monograph.promoteTo()).contains("monographcollection");
      assertThat(models.require("monographcollection").promotes()).isFalse();
    }

    @Test
    @DisplayName("only a convolute excludes members of its own model")
    void selfExclusion() {
      assertThat(models.require("convolute").excludesOwnModel()).isTrue();
      assertThat(models.require("collection").excludesOwnModel()).isFalse();
    }

    @Test
    @DisplayName("collection shapes are Collections, page shapes are Manifests")
    void iiifTypes() {
      assertThat(models.require("periodical").iiifType()).isEqualTo("Collection");
      assertThat(models.require("periodicalitem").iiifType()).isEqualTo("Manifest");
    }

    @Test
    @DisplayName("every MEMBERS model names which children it collects")
    void membersNameTheirChildren() {
      assertThat(models.require("periodical").children()).contains("PERIODICAL_VOLUMES");
      assertThat(models.require("periodicalvolume").children()).contains("PERIODICAL_ISSUES");
      assertThat(models.require("convolute").children()).contains("CONVOLUTE_PARTS");
      assertThat(models.require("monographcollection").children()).contains("MONOGRAPH_UNITS");
      // Shapes that do not collect children name none.
      assertThat(models.require("monograph").children()).isEmpty();
      assertThat(models.require("collection").children()).isEmpty();
    }

    @Test
    @DisplayName("component models are known, so requesting one is unsupported rather than missing")
    void components() {
      assertThat(models.isComponent("page")).isTrue();
      assertThat(models.isComponent("track")).isTrue();
      assertThat(models.isComponent("soundunit")).isTrue();
      assertThat(models.isComponent("monograph")).isFalse();
      assertThat(models.pageModel()).isEqualTo("page");
    }
  }

  @Nested
  @DisplayName("rejecting a bad table at load")
  class Validation {

    @Test
    @DisplayName("a shape no code implements is refused, and the message says which")
    void unknownShape() {
      assertThatThrownBy(
              () ->
                  DocumentModels.parse(
                      json(
                          """
                  {"models": {"thing": {"shape": "PAGESS"}}}""")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("thing")
          .hasMessageContaining("PAGESS");
    }

    @Test
    @DisplayName("a model with no shape is refused")
    void missingShape() {
      assertThatThrownBy(
              () ->
                  DocumentModels.parse(
                      json(
                          """
              {"models": {"thing": {}}}""")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("thing");
    }

    @Test
    @DisplayName("promoting to a model that is not in the table is refused")
    void danglingPromotion() {
      assertThatThrownBy(
              () ->
                  DocumentModels.parse(
                      json(
                          """
                          {"models": {"thing": {"shape": "PAGED_MANIFEST",
                                                "promoteTo": "ghost",
                                                "promoteWhen": "PAGES"}}}""")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("promoting on a child kind that does not exist is refused")
    void unknownChildRelation() {
      assertThatThrownBy(
              () ->
                  DocumentModels.parse(
                      json(
                          """
                          {"models": {"thing": {"shape": "PAGED_MANIFEST",
                                                "promoteTo": "thing",
                                                "promoteWhen": "SOMETHING"}}}""")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("SOMETHING");
    }

    @Test
    @DisplayName(
        "a CHILD_COLLECTION shape with no `children` is refused — it would collect nothing")
    void membersWithoutChildren() {
      assertThatThrownBy(
              () ->
                  DocumentModels.parse(
                      json(
                          """
                  {"models": {"thing": {"shape": "CHILD_COLLECTION"}}}""")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("thing")
          .hasMessageContaining("children");
    }

    @Test
    @DisplayName("an empty table is refused rather than silently serving nothing")
    void emptyTable() {
      assertThatThrownBy(() -> DocumentModels.parse(json("{\"models\": {}}")))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
