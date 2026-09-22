/*
 * Adding a language without rebuilding the image, which was an explicit requirement.
 *
 * The failure cases matter as much as the happy one: a missing directory is the normal case and must not
 * be an error, and one malformed file must not stop the gateway starting.
 */
package cz.mzk.iiif.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cz.mzk.iiif.i18n.Translations;

class TranslationDirectoryTest {

  @Test
  @DisplayName("no directory configured is the normal case, not an error")
  void noDirectory() {
    assertThat(TranslationDirectory.load(Optional.empty()).lookup("metadata.title"))
        .containsOnlyKeys("cs", "en");
  }

  @Test
  @DisplayName("a configured directory that is not there stops the gateway")
  void missingDirectoryIsFatal() {
    // NOT the normal case — that is the variable being unset, which the test above covers. Setting
    // it to a path that is not there is an operator who mounted something and mounted it wrong, and
    // the symptom of carrying on is a deployment running for months on translations it believes it
    // replaced.
    assertThatThrownBy(() -> TranslationDirectory.load(Optional.of("/nonexistent/iiif/i18n")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("IIIF_TRANSLATIONS_DIR");
  }

  @Test
  @DisplayName("a new language file adds that language everywhere")
  void addsALanguage(@TempDir Path dir) throws IOException {
    // The registry already lists Slovak institutions, so this is not hypothetical.
    Files.writeString(dir.resolve("sk.json"), "{\"metadata.title\": \"Názov\"}");

    Translations translations = TranslationDirectory.load(Optional.of(dir.toString()));

    assertThat(translations.lookup("metadata.title"))
        .containsEntry("sk", java.util.List.of("Názov"))
        .containsKeys("cs", "en");
  }

  @Test
  @DisplayName("an override replaces one key without disturbing the rest of that language")
  void overridesOneKey(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("cs.json"), "{\"metadata.title\": \"Titul\"}");

    Translations translations = TranslationDirectory.load(Optional.of(dir.toString()));

    assertThat(translations.lookup("metadata.title"))
        .containsEntry("cs", java.util.List.of("Titul"));
    // Everything else in Czech is still there — merging is per key, not per file.
    assertThat(translations.lookup("metadata.author")).containsKey("cs");
  }

  @Test
  @DisplayName("one unreadable file stops the gateway, like every other config mistake")
  void badFileIsFatal(@TempDir Path dir) throws IOException {
    // This was skipped-with-a-warning, on the argument that a wrong label is cosmetic and failing
    // to start over one is a poor trade. True, and beside the point: an operator who mounts a
    // translations directory and gets no Slovak has been told nothing they will notice. Every
    // configuration file now fails the same way, which is the only rule anyone can hold in their
    // head — and config/check.sh catches the library file before it ever gets this far.
    Files.writeString(dir.resolve("sk.json"), "{\"metadata.title\": \"Názov\"}");
    Files.writeString(dir.resolve("de.json"), "this is not json");

    assertThatThrownBy(() -> TranslationDirectory.load(Optional.of(dir.toString())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("de.json");
  }
}
