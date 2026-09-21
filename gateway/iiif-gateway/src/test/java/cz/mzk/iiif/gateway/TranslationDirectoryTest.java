/*
 * Adding a language without rebuilding the image, which was an explicit requirement.
 *
 * The failure cases matter as much as the happy one: a missing directory is the normal case and must not
 * be an error, and one malformed file must not stop the gateway starting.
 */
package cz.mzk.iiif.gateway;

import static org.assertj.core.api.Assertions.assertThat;

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
  @DisplayName("a directory that does not exist degrades to the bundled languages")
  void missingDirectory() {
    assertThat(
            TranslationDirectory.load(Optional.of("/nonexistent/iiif/i18n"))
                .lookup("metadata.title"))
        .containsOnlyKeys("cs", "en");
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
  @DisplayName("one unreadable file is skipped rather than stopping startup")
  void badFileIsSkipped(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("sk.json"), "{\"metadata.title\": \"Názov\"}");
    Files.writeString(dir.resolve("de.json"), "this is not json");

    Translations translations = TranslationDirectory.load(Optional.of(dir.toString()));

    assertThat(translations.lookup("metadata.title")).containsKeys("cs", "en", "sk");
  }
}
