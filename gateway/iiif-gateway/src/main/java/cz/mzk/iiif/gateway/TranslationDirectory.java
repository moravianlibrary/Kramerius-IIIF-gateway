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
 * A missing directory is not an error: it is the normal case, and means the bundled languages. A
 * directory that is present but unreadable, or a FILE in it that does not parse, stops the gateway.
 *
 * That last part used to be a warning. The argument for leniency was that a wrong label is cosmetic and
 * failing to start over one is a poor trade — which is true as far as it goes, and beside the point. An
 * operator who mounts a translations directory and gets no translations has been told nothing they will
 * notice, and every configuration file here now fails the same way for the same reason. One rule is the
 * only rule anybody can hold in their head, and `config/check.sh` plus a startup that refuses are
 * cheaper than a label that silently never appeared.
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
      // Not a warning: the operator set the variable, so they meant to mount something there.
      throw new IllegalStateException("IIIF_TRANSLATIONS_DIR " + path + " is not a directory");
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
          throw new IllegalStateException("could not read translation file " + file, e);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("could not read IIIF_TRANSLATIONS_DIR " + path, e);
    }

    if (overrides.isEmpty()) {
      return bundled;
    }
    LOG.info("merging translations from {} for {}", path, overrides.keySet());
    return bundled.mergedWith(overrides);
  }
}
