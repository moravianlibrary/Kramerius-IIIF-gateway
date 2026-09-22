/*
 * Loads model-table overrides from a directory at startup.
 *
 * Same split as TranslationDirectory, and for the same reason: the library parses what it is handed and
 * does no file I/O, so deciding where a file lives and what a bad one does is the host's job.
 *
 * One file per version floor, named like the bundled resource: kramerius-50.json, kramerius-70.json.
 * What is found is merged over the bundled table model by model, so adding a model Kramerius introduced
 * after this release is a five-line file rather than a copy of the whole table.
 *
 * WHY THIS IS WIRED AT ALL. Kramerius adds models, and an unknown model degrades to `unsupported` —
 * safe, but it means a library that starts publishing a new model serves nothing for it until the
 * gateway is released again. The counter-argument, that a mis-edited table breaks manifests in ways an
 * operator cannot debug, is answered by failing loudly: a table that does not parse or whose promoteTo
 * points nowhere stops the gateway at startup rather than producing quiet nonsense. An operator who
 * edits this wants to know immediately, and an operator who has not set IIIF_MODELS_DIR cannot be hurt
 * by it.
 *
 * That is the one deliberate difference from TranslationDirectory, which skips a bad file and carries
 * on: a wrong label is cosmetic, a wrong model shape is a wrong manifest.
 */
package cz.mzk.iiif.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.adapter.DocumentModels;

public final class ModelDirectory {

  private static final Logger LOG = LoggerFactory.getLogger(ModelDirectory.class);

  private ModelDirectory() {}

  /** The bundled table for {@code versionFloor}, with {@code directory}'s file merged over it. */
  public static DocumentModels load(Optional<String> directory, String versionFloor) {
    if (directory.isEmpty()) {
      return DocumentModels.bundled(versionFloor);
    }

    Path path = Path.of(directory.get());
    if (!Files.isDirectory(path)) {
      // The operator set the variable, so they meant to mount something there. Quietly falling
      // back to the bundled tables is how a deployment runs for months on configuration it thinks
      // it replaced.
      throw new IllegalStateException("IIIF_MODELS_DIR " + path + " is not a directory");
    }

    Path file = path.resolve("kramerius-" + versionFloor + ".json");
    if (!Files.isRegularFile(file)) {
      // Overriding one floor and not the other is normal — a K5-only deployment has no reason to
      // carry a K7 table.
      return DocumentModels.bundled(versionFloor);
    }

    try (InputStream in = Files.newInputStream(file)) {
      DocumentModels models = DocumentModels.bundledMergedWith(versionFloor, in);
      LOG.info("merged model overrides from {}", file);
      return models;
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + file, e);
    }
  }
}
