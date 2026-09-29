/*
 * IIIF_MODELS_DIR: what an operator can change without a release, and what stops the gateway instead.
 *
 * The point of the setting is that Kramerius adds models between releases and an unknown model degrades
 * to `unsupported` — so a library that starts publishing one serves nothing for it until a gateway
 * release catches up. Merging model-by-model is what makes describing a new model a five-line file
 * rather than a copy of a table of twenty that then has to be kept in step.
 *
 * The other half is the one worth testing hardest: this is STRICTER than IIIF_TRANSLATIONS_DIR, which
 * skips a bad file and carries on. A wrong label is cosmetic; a wrong model shape is a wrong manifest,
 * served confidently, to everyone. So a table that does not parse has to stop startup rather than be
 * logged past — and an operator who has not set the variable cannot be hurt by any of it.
 */
package cz.mzk.iiif.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cz.mzk.iiif.adapter.DocumentModels;
import cz.mzk.iiif.kramerius.IiifStructure;

class ModelDirectoryTest {

  private static final String FLOOR = DocumentModels.KRAMERIUS_70;

  private static Path table(Path directory, String json) throws IOException {
    Path file = directory.resolve("kramerius-" + FLOOR + ".json");
    Files.writeString(file, json);
    return file;
  }

  @Nested
  @DisplayName("when there is nothing to merge")
  class Bundled {

    @Test
    @DisplayName("unset means the bundled table, untouched")
    void unset() {
      DocumentModels models = ModelDirectory.load(Optional.empty(), FLOOR);

      assertThat(models.find("monograph")).isPresent();
      assertThat(models.pageModel()).isEqualTo("page");
    }

    @Test
    @DisplayName("a path that is not a directory stops the gateway")
    void notADirectoryIsFatal(@TempDir Path directory) throws IOException {
      // This used to warn and fall back, on the argument that a typo changes nothing about what is
      // served. It does change something: the operator believes their model tables are in use and
      // they are not, and nothing they look at will say so.
      Path file = directory.resolve("not-a-directory");
      Files.writeString(file, "");

      assertThatThrownBy(() -> ModelDirectory.load(Optional.of(file.toString()), FLOOR))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("IIIF_MODELS_DIR");
    }

    @Test
    @DisplayName("a directory with no table for this floor is normal, not an error")
    void noTableForThisFloor(@TempDir Path directory) {
      // A K5-only deployment has no reason to carry a K7 table, and vice versa.
      assertThat(ModelDirectory.load(Optional.of(directory.toString()), FLOOR).find("monograph"))
          .isPresent();
    }
  }

  @Nested
  @DisplayName("merging")
  class Merging {

    @Test
    @DisplayName("a new model is added without restating the other nineteen")
    void addsAModel(@TempDir Path directory) throws IOException {
      // The case the setting exists for: Kramerius introduces a model, and describing it should not
      // mean copying the bundled table and owing us a re-copy every release.
      table(
          directory,
          """
          {"models": {"newthing": {"shape": "PAGED_MANIFEST"}}}
          """);

      DocumentModels models = ModelDirectory.load(Optional.of(directory.toString()), FLOOR);

      assertThat(models.find("newthing")).isPresent();
      assertThat(models.find("newthing").orElseThrow().shape())
          .isEqualTo(IiifStructure.PAGED_MANIFEST);
      // And the bundled ones survived, which is the whole difference between merging and replacing.
      assertThat(models.find("monograph")).isPresent();
      assertThat(models.find("periodicalvolume")).isPresent();
    }

    @Test
    @DisplayName("a model present in both is taken whole from the operator, not half-merged")
    void overridesWholeModels(@TempDir Path directory) throws IOException {
      // Bundled `monograph` is PAGED_MANIFEST with promoteTo=monographcollection. Overriding it
      // with a bare
      // PAGED_MANIFEST must drop the promotion rather than keep it: a model is a coherent unit, and
      // merging
      // field-by-field would produce a shape nobody wrote.
      table(
          directory,
          """
          {"models": {"monograph": {"shape": "PAGED_MANIFEST"}}}
          """);

      DocumentModels models = ModelDirectory.load(Optional.of(directory.toString()), FLOOR);

      assertThat(models.find("monograph").orElseThrow().promotes()).isFalse();
    }

    @Test
    @DisplayName("components and pageModel replace, because they are statements about the table")
    void replacesTheSingletons(@TempDir Path directory) throws IOException {
      table(
          directory,
          """
          {"models": {}, "components": ["page"], "pageModel": "page"}
          """);

      DocumentModels models = ModelDirectory.load(Optional.of(directory.toString()), FLOOR);

      assertThat(models.components()).containsExactly("page");
      assertThat(models.isComponent("track")).isFalse();
      assertThat(models.pageModel()).isEqualTo("page");
    }

    @Test
    @DisplayName("an override may promote to a model only the bundled table defines")
    void validatesAfterMerging(@TempDir Path directory) throws IOException {
      // Validation has to happen on the merged result, or a legal five-line file would be rejected
      // for referring to something it correctly did not restate.
      table(
          directory,
          """
          {"models": {"newthing": {"shape": "PAGED_MANIFEST", "promoteTo": "monographcollection",
                                   "promoteWhen": "MONOGRAPH_UNITS"}}}
          """);

      DocumentModels models = ModelDirectory.load(Optional.of(directory.toString()), FLOOR);

      assertThat(models.find("newthing").orElseThrow().promoteTo()).contains("monographcollection");
    }
  }

  @Nested
  @DisplayName("failing loudly, which is the difference from the translations")
  class Strictness {

    @Test
    @DisplayName("a table that is not JSON stops the gateway")
    void malformedJson(@TempDir Path directory) throws IOException {
      table(directory, "{ not json");

      assertThatThrownBy(() -> ModelDirectory.load(Optional.of(directory.toString()), FLOOR))
          .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("a shape no code implements stops the gateway, naming it")
    void unknownShape(@TempDir Path directory) throws IOException {
      // The builder switches on IiifStructure exhaustively, so a shape exists only if something
      // knows
      // how to build one. Accepting this would fail per-request, later, somewhere else.
      table(
          directory,
          """
          {"models": {"newthing": {"shape": "INTERPRETIVE_DANCE"}}}
          """);

      assertThatThrownBy(() -> ModelDirectory.load(Optional.of(directory.toString()), FLOOR))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("INTERPRETIVE_DANCE");
    }

    @Test
    @DisplayName("promoting to a model that is in neither table stops the gateway, naming it")
    void danglingPromotion(@TempDir Path directory) throws IOException {
      table(
          directory,
          """
          {"models": {"newthing": {"shape": "PAGED_MANIFEST", "promoteTo": "nowhere",
                                   "promoteWhen": "MONOGRAPH_UNITS"}}}
          """);

      assertThatThrownBy(() -> ModelDirectory.load(Optional.of(directory.toString()), FLOOR))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("nowhere");
    }
  }
}
