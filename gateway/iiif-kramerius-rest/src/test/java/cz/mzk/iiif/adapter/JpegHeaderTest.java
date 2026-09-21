/*
 * Reading dimensions out of real Kramerius thumbnails.
 *
 * The fixtures are downloaded from MZK rather than synthesised, because the failure this guards against
 * is a JPEG variant we did not anticipate — a progressive scan, an EXIF block before the frame header,
 * padding between markers. A JPEG we wrote ourselves would only prove we can read our own.
 *
 * `restricted.jpg` is the page that matters: its info.json answers 403, and this thumbnail is the only
 * description of its shape the gateway can get.
 */
package cz.mzk.iiif.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JpegHeaderTest {

  private static byte[] thumb(String name) {
    try (InputStream in = JpegHeaderTest.class.getResourceAsStream("/thumbs/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing fixture " + name);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  @DisplayName("a real thumbnail of a page the gateway may not measure")
  void restrictedPage() {
    assertThat(JpegHeader.dimensions(thumb("restricted.jpg"))).containsExactly(151, 256);
  }

  @Test
  @DisplayName("a real thumbnail of a public page")
  void publicPage() {
    int[] size = JpegHeader.dimensions(thumb("public.jpg"));

    assertThat(size).isNotNull();
    assertThat(size[0]).isPositive();
    assertThat(size[1]).isPositive();
  }

  @Test
  @DisplayName("the aspect matches the full image, which is the only part we keep")
  void aspectIsTrustworthy() {
    // The page behind restricted.jpg measures 1274 x 2166 when it can be measured at all: aspect
    // 0.5882 against the thumbnail's 0.5898, a 0.3% difference from rounding at 256 px tall.
    int[] size = JpegHeader.dimensions(thumb("restricted.jpg"));

    double thumbnail = (double) size[0] / size[1];
    assertThat(thumbnail).isCloseTo(1274.0 / 2166.0, org.assertj.core.data.Offset.offset(0.01));
  }

  @Test
  @DisplayName("anything unreadable is null, never an exception")
  void unreadable() {
    // The caller already has a fallback for "no dimensions"; a malformed thumbnail is not
    // exceptional.
    assertThat(JpegHeader.dimensions(null)).isNull();
    assertThat(JpegHeader.dimensions(new byte[0])).isNull();
    assertThat(JpegHeader.dimensions("not a jpeg at all".getBytes())).isNull();
    assertThat(JpegHeader.dimensions(new byte[] {(byte) 0xFF, (byte) 0xD8})).isNull();
    // Truncated mid-segment.
    assertThat(
            JpegHeader.dimensions(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC0}))
        .isNull();
  }
}
