/*
 * The on-disk encoding, which is the thing that would be expensive to get wrong.
 *
 * A blob is written once and read for years, so a round-trip bug shows up as wrong canvas dimensions
 * long after the code that caused it changed. The size assertion is here for the same reason the layout
 * exists: the whole justification for one row per document is bytes per page, and a change that quietly
 * triples it should fail rather than just cost 2 GB.
 */
package cz.mzk.iiif.gateway.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cz.mzk.iiif.document.ImageSize;

class PageBlobTest {

  @Test
  @DisplayName("a document's sizes survive a round trip")
  void roundTrip() {
    Map<String, ImageSize> sizes = new LinkedHashMap<>();
    sizes.put("uuid:fa0572d0-2b61-11e3-b62e-005056825209", ImageSize.of(2480, 3508, 3));
    sizes.put("uuid:fbaf7950-2b61-11e3-b62e-005056825209", ImageSize.of(1327, 2219, 2));

    assertThat(PageBlob.decode(PageBlob.encode(sizes))).isEqualTo(sizes);
  }

  @Test
  @DisplayName("a failed lookup round-trips as UNAVAILABLE, not as a real size")
  void unavailableSurvives() {
    // Without this sentinel a broken page is indistinguishable from one never asked about, and gets
    // re-requested on every manifest request forever.
    Map<String, ImageSize> sizes = Map.of("uuid:" + UUID.randomUUID(), ImageSize.UNAVAILABLE);

    Map<String, ImageSize> decoded = PageBlob.decode(PageBlob.encode(sizes));

    assertThat(decoded.values()).containsExactly(ImageSize.UNAVAILABLE);
    assertThat(decoded.values().iterator().next().isKnown()).isFalse();
  }

  @Test
  @DisplayName("a pid that is not a uuid is stored as text rather than being lost")
  void nonUuidPid() {
    Map<String, ImageSize> sizes = new LinkedHashMap<>();
    sizes.put("uuid:not-really-a-uuid", ImageSize.of(10, 20, 3));
    sizes.put("mzk:legacy-identifier", ImageSize.of(30, 40, 2));

    assertThat(PageBlob.decode(PageBlob.encode(sizes))).isEqualTo(sizes);
  }

  @Test
  @DisplayName("uuid pids cost 26 bytes a page, which is what the storage estimate assumes")
  void bytesPerPage() {
    Map<String, ImageSize> sizes = new LinkedHashMap<>();
    for (int i = 0; i < 1000; i++) {
      sizes.put("uuid:" + new UUID(0x71d66fe0d98611e2L, i), ImageSize.of(2480, 3508, 3));
    }

    double perPage = (PageBlob.encode(sizes).length - 1) / 1000.0;

    // 1 tag + 16 uuid + 4 width + 4 height + 1 api version. Storing the pid as its 41-character
    // text
    // form instead would be 51, and that substitution is most of the saving.
    assertThat(perPage).isEqualTo(26.0);
  }

  @Test
  @DisplayName("an unknown encoding version is a cache miss, not a crash or a wrong answer")
  void futureVersion() {
    // The leading version byte is what keeps the "no compression" decision cheap to revisit: a v2
    // encoding can be introduced with no migration, because old readers simply re-resolve.
    assertThat(PageBlob.decode(new byte[] {99, 1, 2, 3})).isEmpty();
    assertThat(PageBlob.decode(new byte[0])).isEmpty();
    assertThat(PageBlob.decode(null)).isEmpty();
  }
}
