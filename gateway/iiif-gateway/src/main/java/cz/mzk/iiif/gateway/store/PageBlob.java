/*
 * A document's page dimensions, packed into one byte array.
 *
 * The access pattern is always "every page of this document", so the document is the natural key. A row
 * per page would pay Postgres' 23-byte tuple header plus a duplicated primary-key index 75.7 million
 * times — ~7.6 GB at MZK against ~1.0 GB here — and force `pid IN (?, …)` batched at 500 on read,
 * because pgjdbc cannot bind a bytea array.
 *
 * Layout: a version byte, then per page either
 *   0x01 | uuid(16) | width(int32) | height(int32) | apiVersion(uint8)
 *   0x02 | length(uint16) | utf8 pid | width(int32) | height(int32) | apiVersion(uint8)
 *
 * Page pids are `uuid:<uuid>` in practice, so 16 binary bytes replace 41 of text and that substitution
 * is most of the saving. The tagged alternative is what keeps a non-uuid pid representable rather than
 * silently unstorable.
 *
 * NOT compressed, deliberately: measured on real pid lists, Postgres' automatic TOAST gets this to
 * 13.19 B/page (1.00 GB at MZK) and explicit zstd-19 to 10.69 (0.81 GB) — ~190 MB, which does not justify
 * a native dependency and CPU on every read. A trained dictionary adds 0.6% on top, because the
 * redundancy is the shared suffix of v1 uuids WITHIN one document, which zstd already finds inside a
 * single blob. The leading version byte is what keeps that decision cheap to revisit.
 *
 * A page with no usable answer is stored with w = h = 0. That record is what stops it being re-probed on
 * every request, and it EXPIRES rather than being permanent — see CachedImageSizes and ImageSize.
 *
 * Pages with no tiles are simply absent from the map: they have no Image API endpoint to ask.
 */
package cz.mzk.iiif.gateway.store;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import cz.mzk.iiif.document.ImageSize;

public final class PageBlob {

  static final byte VERSION = 1;
  private static final byte TAG_UUID = 1;
  private static final byte TAG_TEXT = 2;
  private static final String UUID_PREFIX = "uuid:";

  private PageBlob() {}

  public static byte[] encode(Map<String, ImageSize> sizes) {
    ByteBuffer buffer = ByteBuffer.allocate(1 + sizes.size() * 64);
    buffer.put(VERSION);

    for (Map.Entry<String, ImageSize> entry : sizes.entrySet()) {
      UUID uuid = asUuid(entry.getKey());
      if (uuid != null) {
        buffer.put(TAG_UUID);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
      } else {
        byte[] raw = entry.getKey().getBytes(StandardCharsets.UTF_8);
        buffer.put(TAG_TEXT);
        buffer.putShort((short) raw.length);
        buffer.put(raw);
      }
      ImageSize size = entry.getValue();
      buffer.putInt(size.width());
      buffer.putInt(size.height());
      buffer.put((byte) size.imageApiVersion());
    }

    byte[] packed = new byte[buffer.position()];
    buffer.flip();
    buffer.get(packed);
    return packed;
  }

  public static Map<String, ImageSize> decode(byte[] packed) {
    if (packed == null || packed.length == 0) {
      return Map.of();
    }
    ByteBuffer buffer = ByteBuffer.wrap(packed);
    byte version = buffer.get();
    if (version != VERSION) {
      // A future encoding is not an error, it is a cache miss: re-resolve rather than guess.
      return Map.of();
    }

    Map<String, ImageSize> sizes = new LinkedHashMap<>();
    while (buffer.remaining() > 0) {
      byte tag = buffer.get();
      String pid;
      if (tag == TAG_UUID) {
        pid = UUID_PREFIX + new UUID(buffer.getLong(), buffer.getLong());
      } else if (tag == TAG_TEXT) {
        byte[] raw = new byte[buffer.getShort() & 0xFFFF];
        buffer.get(raw);
        pid = new String(raw, StandardCharsets.UTF_8);
      } else {
        return Map.copyOf(sizes);
      }
      int width = buffer.getInt();
      int height = buffer.getInt();
      int api = buffer.get() & 0xFF;
      sizes.put(
          pid,
          width == 0 && height == 0 ? ImageSize.UNAVAILABLE : ImageSize.of(width, height, api));
    }
    return Map.copyOf(sizes);
  }

  /** A uuid: pid, or null when it is something else and must be stored as text. */
  private static UUID asUuid(String pid) {
    if (!pid.startsWith(UUID_PREFIX)) {
      return null;
    }
    try {
      return UUID.fromString(pid.substring(UUID_PREFIX.length()));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
