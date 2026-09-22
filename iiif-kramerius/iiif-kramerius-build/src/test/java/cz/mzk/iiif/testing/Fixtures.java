/*
 * The values every manifest test needs, so no test has to invent a library.
 *
 * Published in this module's test-jar for the same reason level 1 publishes IiifSchema: level 3 and the
 * adapters assert against manifests too, and a second hand-rolled MZK profile would drift from this one
 * and make two tests disagree about what the right answer is.
 *
 * URLs are shaped like Kramerius' real ones rather than being opaque strings, so a fixture assertion
 * reads like something a live instance would actually return.
 */
package cz.mzk.iiif.testing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cz.mzk.iiif.document.BoundingBox;
import cz.mzk.iiif.document.ContentLocator;
import cz.mzk.iiif.document.DocumentBundle;
import cz.mzk.iiif.document.DocumentRecord;
import cz.mzk.iiif.document.LibraryProfile;
import cz.mzk.iiif.kramerius.DocumentShape;
import cz.mzk.iiif.presentation.LanguageMap;

public final class Fixtures {

  public static final String HOST = "https://kramerius.example.org";

  public static final String BOOK = "uuid:11111111-1111-1111-1111-111111111111";
  public static final String PAGE_1 = "uuid:aaaaaaaa-1111-1111-1111-111111111111";
  public static final String PAGE_2 = "uuid:aaaaaaaa-2222-2222-2222-222222222222";

  private Fixtures() {}

  /** A Czech library. */
  public static LibraryProfile mzk() {
    return library("mzk", "Moravská zemská knihovna", "Moravian Library", "cs");
  }

  /**
   * A Slovak library.
   *
   * <p>Its registry `name` is Slovak, not Czech — which is why nothing hardcodes a language tag.
   * See docs/DESIGN.md.
   */
  public static LibraryProfile slovak() {
    return library(
        "dikda", "Katalóg digitálnej knižnice", "Slovak Digital Library Catalogue", "sk");
  }

  public static LibraryProfile library(
      String code, String name, String nameEnglish, String language) {

    // LinkedHashMap, not Map.of — serialisation order has to be stable or the bytes are not.
    Map<String, List<String>> label = new LinkedHashMap<>();
    label.put(language, List.of(name));
    if (nameEnglish != null) {
      label.put("en", List.of(nameEnglish));
    }

    return LibraryProfile.of(code, "https://registr.digitalniknihovna.cz/library/" + code)
        .label(label)
        .homepage("https://www.mzk.cz")
        .logo("https://api.registr.digitalniknihovna.cz/libraries/" + code + "/logo")
        .build();
  }

  public static ContentLocator urls() {
    return new Urls();
  }

  // Records

  public static DocumentRecord.Builder document(String pid, String model, DocumentShape shape) {
    return DocumentRecord.of(pid, model, shape);
  }

  /** A monograph — the ordinary page-bearing case. */
  public static DocumentRecord.Builder book(String label) {
    return document(BOOK, "monograph", DocumentShape.PAGES).label(none(label));
  }

  /** A tiled page, which is what a canvas normally comes from. */
  public static DocumentRecord.Builder page(String pid, String label) {
    return document(pid, "page", DocumentShape.PAGES).label(none(label)).hasTiles(true);
  }

  public static DocumentRecord.Builder track(String pid, String label) {
    return document(pid, "track", DocumentShape.SOUND_RECORDING).label(none(label));
  }

  public static Map<String, List<String>> none(String value) {
    return LanguageMap.none(value);
  }

  public static BoundingBox bbox(double west, double east, double south, double north) {
    return new BoundingBox(west, east, south, north);
  }

  /** A bundle for one document with nothing else gathered. */
  public static DocumentBundle.Builder bundle(DocumentRecord document) {
    return DocumentBundle.of(mzk(), urls(), document);
  }

  public static DocumentBundle.Builder bundle(LibraryProfile library, DocumentRecord document) {
    return DocumentBundle.of(library, urls(), document);
  }

  private static final class Urls implements ContentLocator {

    private static String item(String pid) {
      return HOST + "/search/api/client/v7.0/items/" + pid;
    }

    @Override
    public String thumbnail(String pid) {
      return item(pid) + "/image/thumb";
    }

    @Override
    public String iiifImage(String pid) {
      return imageService(pid) + "/full/max/0/default.jpg";
    }

    @Override
    public String imageService(String pid) {
      return HOST + "/search/iiif/" + pid;
    }

    @Override
    public String plainImage(String pid) {
      return item(pid) + "/image";
    }

    @Override
    public String alto(String pid) {
      return item(pid) + "/ocr/alto";
    }

    @Override
    public String audio(String pid) {
      return item(pid) + "/audio/mp3";
    }

    @Override
    public String pdf(String pid) {
      return item(pid) + "/streams/IMG_FULL";
    }

    @Override
    public String mods(String pid) {
      return item(pid) + "/metadata/mods";
    }
  }
}
