/*
 * Loads extra languages from a directory at startup.
 *
 * The library deliberately does no file I/O — it parses what it is handed — so reading a directory is the
 * host's job, and this is the host. That keeps filesystem policy (where, whether it must exist, what a
 * bad file does) out of a library that Kramerius also embeds.
 *
 * One file per language, named for it: cs.json, sk.json, de.json. The set of languages emitted is the set
 * of files found, merged over the bundled defaults key by key — so an operator can add Slovak, or correct
 * one Czech label, without copying a whole file and then having to keep it in step with future releases.
 *
 * A missing directory is not an error: it is the normal case. An unreadable FILE is logged and skipped,
 * because failing to start over one bad translation would be a poor trade.
 */
package cz.mzk.iiif.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.i18n.Translations;

public final class TranslationDirectory {

  private static final Logger LOG = LoggerFactory.getLogger(TranslationDirectory.class);

  private TranslationDirectory() {}

  /** The bundled translations, with anything in {@code directory} merged over them. */
  public static Translations load(Optional<String> directory) {
    Translations bundled = Translations.bundled();
    if (directory.isEmpty()) {
      return bundled;
    }

    Path path = Path.of(directory.get());
    if (!Files.isDirectory(path)) {
      LOG.warn("IIIF_TRANSLATIONS_DIR {} is not a directory; using the bundled languages", path);
      return bundled;
    }

    Map<String, Map<String, String>> overrides = new LinkedHashMap<>();
    try (Stream<Path> files = Files.list(path)) {
      // getFileName() is null for a root path, which Files.list can in principle yield.
      List<Path> jsons =
          files
              .filter(file -> file.getFileName() != null)
              .filter(file -> file.getFileName().toString().endsWith(".json"))
              .sorted()
              .toList();

      for (Path file : jsons) {
        Path fileName = file.getFileName();
        if (fileName == null) {
          continue;
        }
        String name = fileName.toString();
        String language = name.substring(0, name.length() - ".json".length());
        try (InputStream in = Files.newInputStream(file)) {
          overrides.put(language, Translations.parse(in));
        } catch (IOException | RuntimeException e) {
          // One bad file must not stop the gateway starting.
          LOG.warn("skipping translation file {}: {}", file, e.toString());
        }
      }
    } catch (IOException e) {
      LOG.warn("could not list {}: {}", path, e.toString());
      return bundled;
    }

    if (overrides.isEmpty()) {
      return bundled;
    }
    LOG.info("merging translations from {} for {}", path, overrides.keySet());
    return bundled.mergedWith(overrides);
  }
}
