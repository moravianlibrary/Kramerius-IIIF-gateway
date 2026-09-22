/*
 * The libraries to serve, declared in a file rather than in an environment variable.
 *
 * IIIF_LIBRARIES already does this, and for one library it is the right tool: `mzk=https://…|7.2.2` is
 * a complete deployment. It stops being the right tool at about five. The registry lists fifty-six
 * institutions, and a deployment serving most of them without depending on the registry at runtime had
 * to put all of that on ONE line — four kilobytes of comma-separated text with no room for a comment,
 * no diff anyone can review, and one misplaced `|` away from a startup failure that names the whole
 * value. This is the same data with somewhere to put a newline.
 *
 * JSON rather than YAML because Jackson is already here and a format is not worth a dependency; the
 * repository's other operator-facing configuration — catalogues.json, corpus/pids.json — is JSON too,
 * and `_comment` is how those carry their prose. Keys beginning with `_` are ignored, so the generator
 * can record why a library was left out without that becoming something to serve.
 *
 * FAILURE IS FATAL HERE, with one deliberate exception. A broken library file is the list of things this
 * process exists to serve, so starting anyway would mean answering 404 for every library the operator
 * believes is configured — worse than not starting. The exception is the `catalogue` block: a bad regex
 * there costs that one library its catalogue link and nothing else, so it is logged and skipped. That
 * distinction is why catalogue mappings used to live in a directory of their own; folding them in here
 * kept the distinction and dropped the directory.
 *
 * Documented in .env.example and checked by config/libraries.schema.json — which catches what this
 * class structurally cannot, a misspelled key:
 *
 *   {"libraries": {"mzk": {"rps": 4,
 *                          "catalogue": {"url": "https://vufind.mzk.cz/Record/{id}",
 *                                        "rules": [{"pattern": "^(\\d{9})$", "prefix": "MZK01-"}]}}}}
 */
package cz.mzk.iiif.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LibraryFile {

  private static final Logger LOG = LoggerFactory.getLogger(LibraryFile.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private LibraryFile() {}

  /**
   * Every library the file declares.
   *
   * @throws IllegalStateException if the file is missing, unreadable, not JSON, or declares nothing
   *     — all of which mean the operator asked for a library list and did not get one
   */
  public static Map<String, Config.StaticLibrary> load(Path file) {
    JsonNode root;
    try (InputStream in = Files.newInputStream(file)) {
      root = MAPPER.readTree(in);
    } catch (NoSuchFileException e) {
      throw new IllegalStateException("IIIF_LIBRARIES_FILE " + file + " does not exist", e);
    } catch (JacksonException e) {
      throw new IllegalStateException("IIIF_LIBRARIES_FILE " + file + " is not valid JSON", e);
    } catch (IOException e) {
      throw new IllegalStateException("could not read IIIF_LIBRARIES_FILE " + file, e);
    }

    JsonNode libraries = root.path("libraries");
    if (!libraries.isObject()) {
      throw new IllegalStateException(
          "IIIF_LIBRARIES_FILE " + file + " has no \"libraries\" object");
    }

    Map<String, Config.StaticLibrary> declared = new LinkedHashMap<>();
    libraries
        .fieldNames()
        .forEachRemaining(
            code -> {
              // The generator writes `_excluded` next to `libraries`, and a hand-written file may
              // park one entry the same way. Either is a note, not something to serve.
              if (!code.startsWith("_")) {
                declared.put(code, parse(file, code, libraries.get(code)));
              }
            });

    // An empty file is fine now, and is in fact the shipped state: with the registry supplying the
    // libraries, this file carries only what the registry cannot express, and a deployment with
    // nothing local to say has nothing to put in it. Config still refuses to start when there is no
    // registry AND nothing declared, which is the case this check used to be standing in for.
    return Map.copyOf(declared);
  }

  private static Config.StaticLibrary parse(Path file, String code, JsonNode node) {
    // Both optional, because an entry may be an OVERLAY on a registry that already knows them. A
    // file with neither and no registry is caught at resolve time, where it is possible to say
    // which libraries the registry covers; Config cannot see that.
    java.util.Optional<String> url = optional(node, "url");
    java.util.Optional<String> version = optional(node, "version");

    // Optional, and absent for almost every library: the global rate is the right answer until an
    // institution says otherwise. A non-integer is a startup failure rather than a silent default,
    // because a typo'd rate limit that reverts to 20 is exactly the kind of thing nobody notices
    // until the phone rings.
    JsonNode rps = node.path("rps");
    if (!rps.isMissingNode() && !rps.isNull() && !rps.isInt()) {
      throw new IllegalStateException(
          "IIIF_LIBRARIES_FILE " + file + ": " + code + " has a non-integer \"rps\": " + rps);
    }

    return new Config.StaticLibrary(
        code,
        url,
        version,
        names(file, code, node.path("name")),
        catalogue(file, code, node.path("catalogue")),
        rps.isInt() ? java.util.OptionalInt.of(rps.intValue()) : java.util.OptionalInt.empty());
  }

  /**
   * This library's catalogue mapping, if it declares one.
   *
   * <p>LOGGED AND SKIPPED rather than fatal, which is the one place this file is not strict. The
   * rest of it is: a bad url stops the gateway, because a library list that cannot be read is the
   * list of things the process exists to serve. A catalogue mapping is not that. It costs that one
   * library its catalogue link, the manifests are otherwise complete and correct, and refusing to
   * start over a regex would be a worse outcome than serving fifty-five libraries and logging about
   * the fifty-sixth. That distinction is why this lived in its own directory before, and folding it
   * in here meant keeping the distinction rather than the directory.
   */
  private static java.util.Optional<cz.mzk.iiif.spi.CatalogueLink> catalogue(
      Path file, String code, JsonNode node) {
    if (node.isMissingNode() || node.isNull()) {
      return java.util.Optional.empty();
    }
    try {
      java.util.Optional<cz.mzk.iiif.spi.CatalogueLink> parsed = Catalogues.parse(code, node);
      if (parsed.isEmpty()) {
        LOG.warn("{} in {}: \"catalogue\" has no \"url\"; no catalogue link for it", code, file);
      }
      return parsed;
    } catch (RuntimeException e) {
      LOG.warn("{} in {}: unusable catalogue mapping, ignoring it — {}", code, file, e.toString());
      return java.util.Optional.empty();
    }
  }

  private static java.util.Optional<String> optional(JsonNode node, String field) {
    String value = node.path(field).asText("").trim();
    return value.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(value);
  }

  /**
   * The institution's name, by language tag.
   *
   * <p>Absent is allowed and means the same as an absent name in IIIF_LIBRARIES: the manifest
   * attributes the document to the bare code, and startup says so. An untagged name is not allowed,
   * because a name with the wrong language on it is a false claim a viewer acts on — the same bug
   * v1 had when it tagged everything `cz`.
   */
  private static Map<String, List<String>> names(Path file, String code, JsonNode node) {
    if (node.isMissingNode() || node.isNull()) {
      return Map.of();
    }
    if (!node.isObject()) {
      throw new IllegalStateException(
          "\"name\" for library "
              + code
              + " in "
              + file
              + " needs a language tag: {\"cs\": \"…\"}");
    }
    Map<String, List<String>> names = new LinkedHashMap<>();
    node.fieldNames()
        .forEachRemaining(
            language -> {
              String name = node.path(language).asText("").trim();
              if (!name.isEmpty()) {
                names.put(language, List.of(name));
              }
            });
    return Map.copyOf(names);
  }
}
