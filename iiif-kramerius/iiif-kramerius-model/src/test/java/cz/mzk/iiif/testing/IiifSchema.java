/*
 * Validates a generated document against the official IIIF Presentation 3.0 JSON Schema.
 *
 * Every manifest fixture test runs through here. The IIIF project publishes no validator container
 * image, so the schema is vendored at src/test/resources/iiif/ and checked in-process — which also
 * means the gate works offline and does not depend on anyone else's uptime.
 *
 * The schema's root is a oneOf over the IIIF resource types, so a Manifest and a Collection are both
 * validated by the same entry point without picking a sub-schema.
 *
 * See docs/BEHAVIOUR.md for what the documents are supposed to contain; this only checks that what we
 * emit is legal IIIF, not that it says the right things.
 */
package cz.mzk.iiif.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

public final class IiifSchema {

  private static final String RESOURCE = "/iiif/iiif_3_0.json";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Parsing the schema is not cheap and it never changes within a run. */
  private static final JsonSchema SCHEMA = load();

  private IiifSchema() {}

  private static JsonSchema load() {
    try (InputStream in = IiifSchema.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(
            "vendored IIIF schema missing from the classpath: " + RESOURCE);
      }
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(in);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + RESOURCE, e);
    }
  }

  /** Schema violations in {@code json}, empty when it is valid IIIF. */
  public static Set<ValidationMessage> violations(String json) {
    try {
      return SCHEMA.validate(MAPPER.readTree(json));
    } catch (IOException e) {
      throw new UncheckedIOException("not parseable as JSON", e);
    }
  }

  /**
   * Fails with every violation listed, rather than just the first.
   *
   * <p>A manifest that breaks the schema usually breaks it in several related ways at once, and
   * fixing them one round-trip at a time is miserable.
   */
  public static void assertValid(String json) {
    Set<ValidationMessage> violations = violations(json);
    if (!violations.isEmpty()) {
      fail(
          "not valid IIIF Presentation v3:%n%s%nDocument:%n%s"
              .formatted(
                  violations.stream()
                      .map(v -> "  - " + v.getMessage())
                      .collect(Collectors.joining(System.lineSeparator())),
                  json));
    }
  }

  /** Asserts the document is rejected. Used to prove the validator is actually looking. */
  public static void assertInvalid(String json) {
    assertThat(violations(json)).as("expected schema violations in %s", json).isNotEmpty();
  }
}
