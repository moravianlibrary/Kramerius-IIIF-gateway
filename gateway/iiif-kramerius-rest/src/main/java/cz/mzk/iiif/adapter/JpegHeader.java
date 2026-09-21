/*
 * The pixel dimensions in a JPEG's own header.
 *
 * Needed because Kramerius' thumbnails are the only description of a page whose image will not answer,
 * and a JPEG states its size in the frame header — no decoding, no image library, just a walk over the
 * segment markers to the first Start-Of-Frame.
 *
 * Deliberately tiny and total: anything it does not understand returns null rather than throwing, since
 * the caller's fallback for "no dimensions" already exists and a malformed thumbnail is not exceptional.
 */
package cz.mzk.iiif.adapter;

public final class JpegHeader {

  /** Start-Of-Frame markers. Baseline, extended, progressive and lossless all carry the size. */
  private static final int SOF_FIRST = 0xC0;

  private static final int SOF_LAST = 0xCF;

  /** SOF4 is a Huffman table and SOF8/12 are arithmetic-coding tables, not frames. */
  private static final int NOT_A_FRAME_1 = 0xC4;

  private static final int NOT_A_FRAME_2 = 0xC8;
  private static final int NOT_A_FRAME_3 = 0xCC;

  private JpegHeader() {}

  /** {width, height}, or null when this is not a JPEG we can read. */
  public static int[] dimensions(byte[] data) {
    if (data == null || data.length < 4 || u8(data, 0) != 0xFF || u8(data, 1) != 0xD8) {
      return null;
    }

    int i = 2;
    while (i + 3 < data.length) {
      if (u8(data, i) != 0xFF) {
        return null;
      }
      int marker = u8(data, i + 1);

      // Padding: any run of 0xFF before the real marker byte.
      if (marker == 0xFF) {
        i++;
        continue;
      }
      // Standalone markers carry no length segment.
      if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
        i += 2;
        continue;
      }
      // Start of scan: the entropy-coded data begins and there is no frame header after it.
      if (marker == 0xDA || marker == 0xD9) {
        return null;
      }

      int length = (u8(data, i + 2) << 8) | u8(data, i + 3);
      if (length < 2) {
        return null;
      }

      boolean frame =
          marker >= SOF_FIRST
              && marker <= SOF_LAST
              && marker != NOT_A_FRAME_1
              && marker != NOT_A_FRAME_2
              && marker != NOT_A_FRAME_3;

      if (frame) {
        // length(2) precision(1) height(2) width(2)
        if (i + 9 >= data.length) {
          return null;
        }
        int height = (u8(data, i + 5) << 8) | u8(data, i + 6);
        int width = (u8(data, i + 7) << 8) | u8(data, i + 8);
        return width > 0 && height > 0 ? new int[] {width, height} : null;
      }
      i += 2 + length;
    }
    return null;
  }

  private static int u8(byte[] data, int index) {
    return data[index] & 0xFF;
  }
}
