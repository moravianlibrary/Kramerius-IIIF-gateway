/*
 * Proves the schema harness works before anything depends on it.
 *
 * A validator that accepts everything passes every test and tells you nothing, which is the failure
 * mode worth guarding against here — so this checks both directions: valid documents pass, and
 * documents with known defects are rejected.
 *
 * The invalid cases are not hypothetical. They are the shapes the Ruby gateway actually emitted; see
 * the defect list in docs/BEHAVIOUR.md.
 */
package cz.mzk.iiif.testing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IiifSchemaTest {

  private static final String CONTEXT = "http://iiif.io/api/presentation/3/context.json";

  @Test
  @DisplayName("a minimal Manifest is valid — note that `items` is required, even if empty")
  void minimalManifest() {
    IiifSchema.assertValid(
        """
        {
          "@context": "%s",
          "id": "https://iiif.example.org/mzk/uuid:abc",
          "type": "Manifest",
          "label": { "none": ["Test"] },
          "items": []
        }
        """
            .formatted(CONTEXT));
  }

  @Test
  @DisplayName(
      "a Manifest without `items` is rejected — which is what v1 emitted for PDF documents")
  void manifestWithoutItems() {
    IiifSchema.assertInvalid(
        """
        {
          "@context": "%s",
          "id": "https://iiif.example.org/mzk/uuid:abc",
          "type": "Manifest",
          "label": { "none": ["Test"] }
        }
        """
            .formatted(CONTEXT));
  }

  @Test
  @DisplayName("the PDF shape: no canvases, but `items` present and a rendering")
  void pdfShape() {
    IiifSchema.assertValid(
        """
        {
          "@context": "%s",
          "id": "https://iiif.example.org/mzk/uuid:abc",
          "type": "Manifest",
          "label": { "none": ["Test"] },
          "rendering": [
            {
              "id": "https://kramerius.example.org/items/uuid:abc/streams/IMG_FULL",
              "type": "Text",
              "label": { "cs": ["PDF"], "en": ["PDF"] },
              "format": "application/pdf"
            }
          ],
          "items": []
        }
        """
            .formatted(CONTEXT));
  }

  @Test
  @DisplayName("a minimal Collection is valid — the same schema entry point covers both")
  void minimalCollection() {
    IiifSchema.assertValid(
        """
        {
          "@context": "%s",
          "id": "https://iiif.example.org/mzk/uuid:abc",
          "type": "Collection",
          "label": { "none": ["Test"] },
          "items": [
            {
              "id": "https://iiif.example.org/mzk/uuid:def",
              "type": "Manifest",
              "label": { "none": ["Volume 1"] }
            }
          ]
        }
        """
            .formatted(CONTEXT));
  }

  @Test
  @DisplayName("a Manifest with no id is rejected")
  void missingId() {
    IiifSchema.assertInvalid(
        """
        {
          "@context": "%s",
          "type": "Manifest",
          "label": { "none": ["Test"] }
        }
        """
            .formatted(CONTEXT));
  }

  @Test
  @DisplayName("a label that is a bare string, not a language map, is rejected")
  void labelNotALanguageMap() {
    IiifSchema.assertInvalid(
        """
        {
          "@context": "%s",
          "id": "https://iiif.example.org/mzk/uuid:abc",
          "type": "Manifest",
          "label": "Test"
        }
        """
            .formatted(CONTEXT));
  }

  @Test
  @DisplayName("an unknown type is rejected")
  void unknownType() {
    IiifSchema.assertInvalid(
        """
        {
          "@context": "%s",
          "id": "https://iiif.example.org/mzk/uuid:abc",
          "type": "Sbirka",
          "label": { "none": ["Test"] }
        }
        """
            .formatted(CONTEXT));
  }
}
