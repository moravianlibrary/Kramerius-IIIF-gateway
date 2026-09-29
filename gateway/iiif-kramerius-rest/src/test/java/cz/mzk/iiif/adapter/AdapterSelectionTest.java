/*
 * Which adapter serves which instance, against the versions the registry actually returns.
 *
 * The version strings here are not invented: they are live values from
 * api.registr.digitalniknihovna.cz, including the four-component and -dev spellings that would make
 * strict semver parsing reject roughly a fifth of the registry.
 */
package cz.mzk.iiif.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import cz.mzk.iiif.adapter.v70.Kramerius70;
import cz.mzk.iiif.adapter.v714.Kramerius714;

class AdapterSelectionTest {

  private static final AdapterRegistry REGISTRY =
      AdapterRegistry.of(new Kramerius70(), new Kramerius714());

  private static String adapterFor(String version) {
    return REGISTRY
        .forVersion(Version.parse(version).orElseThrow())
        .map(KrameriusAdapter::name)
        .orElse("none");
  }

  @Nested
  @DisplayName("parsing what the registry returns")
  class Parsing {

    @ParameterizedTest(name = "{0} -> major {1}")
    @CsvSource({
      "7.2.1.1, 7",
      "7.0.31-dev, 7",
      "5.8.4.4, 5",
      "7.2.2.1-dev7, 7",
      "4.6.2, 4",
      "7.1.4, 7"
    })
    @DisplayName("four components and -dev suffixes both parse")
    void parses(String raw, int major) {
      assertThat(Version.parse(raw)).get().extracting(Version::major).isEqualTo(major);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "unknown", "v7"})
    @DisplayName("something with no leading number is not a version")
    void refuses(String raw) {
      assertThat(Version.parse(raw)).isEmpty();
    }

    @Test
    @DisplayName("a missing component is zero, so 7.1 and 7.1.0 are the same version")
    void missingComponentIsZero() {
      assertThat(Version.parse("7.1").orElseThrow())
          .isEqualByComparingTo(Version.parse("7.1.0.0").orElseThrow());
    }

    @Test
    @DisplayName("a -dev suffix does not make a version older than its release")
    void suffixDoesNotDemote() {
      // Treating 7.2.2.1-dev7 as older than 7.2.2.1 would route a NEWER instance to an OLDER
      // adapter.
      assertThat(Version.parse("7.2.2.1-dev7").orElseThrow())
          .isEqualByComparingTo(Version.parse("7.2.2.1").orElseThrow());
    }

    @Test
    @DisplayName("ordering is component-wise, not lexicographic")
    void ordering() {
      // "7.10" sorts before "7.9" as a string, which is exactly the bug this avoids.
      assertThat(Version.parse("7.10.0").orElseThrow())
          .isGreaterThan(Version.parse("7.9.0").orElseThrow());
    }
  }

  @Nested
  @DisplayName("selection by floor")
  class Selection {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
      "7.0.23, Kramerius70",
      "7.0.39, Kramerius70",
      "7.0.41, Kramerius70",
      "7.1.0, Kramerius70",
      "7.1.1, Kramerius70",
      "7.1.3.1, Kramerius70",
      "7.1.4, Kramerius714",
      "7.1.7, Kramerius714",
      "7.2.2, Kramerius714",
      "7.2.2.1-dev7, Kramerius714"
    })
    @DisplayName("the highest floor at or below the version wins")
    void selects(String version, String expected) {
      assertThat(adapterFor(version)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a version nobody has tested still resolves, to the newest adapter")
    void forwardCompatible() {
      // The whole point of naming adapters by floor: 7.3 needs no release from us.
      assertThat(adapterFor("7.3.0")).isEqualTo("Kramerius714");
      assertThat(adapterFor("8.0.0")).isEqualTo("Kramerius714");
    }

    @Test
    @DisplayName("exceeding testedUpTo is allowed but reported, not silent")
    void untestedIsVisible() {
      Version version = Version.parse("7.3.0").orElseThrow();
      KrameriusAdapter adapter = REGISTRY.forVersion(version).orElseThrow();

      assertThat(AdapterRegistry.isUntested(adapter, version)).isTrue();
      assertThat(AdapterRegistry.isUntested(adapter, Version.parse("7.2.2").orElseThrow()))
          .isFalse();
    }

    @Test
    @DisplayName("K5 and K4 are refused by name rather than served something broken")
    void belowEveryFloor() {
      // Kramerius 5 has an adapter; the remaining 4.6.2 instance is out of scope.
      assertThat(adapterFor("5.8.4.4")).isEqualTo("none");
      assertThat(adapterFor("4.6.2")).isEqualTo("none");
      assertThat(REGISTRY.lowestSupported()).isEqualByComparingTo(Version.of(7, 0));
    }

    @Test
    @DisplayName("registration order does not matter")
    void orderIndependent() {
      Optional<KrameriusAdapter> reversed =
          AdapterRegistry.of(new Kramerius714(), new Kramerius70())
              .forVersion(Version.parse("7.1.0").orElseThrow());

      assertThat(reversed).get().extracting(KrameriusAdapter::name).isEqualTo("Kramerius70");
    }
  }

  @Nested
  @DisplayName("what each floor declares about the index")
  class Capabilities {

    @Test
    @DisplayName("7.1.4 adds exactly the three fields the probe found, and 7.0 has none of them")
    void theBoundary() {
      SolrSchema before = new Kramerius70().schema();
      SolrSchema after = new Kramerius714().schema();

      assertThat(before.hasAltoField()).isFalse();
      assertThat(before.hasIsPointField()).isFalse();
      assertThat(before.hasVolumeCountField()).isFalse();

      assertThat(after.hasAltoField()).isTrue();
      assertThat(after.hasIsPointField()).isTrue();
      assertThat(after.hasVolumeCountField()).isTrue();
    }

    @Test
    @DisplayName("a 7.0 query never asks for a field 7.0 does not have")
    void noAbsentFieldsRequested() {
      SolrSchema before = new Kramerius70().schema();

      assertThat(before.documentFields())
          .doesNotContain("coords.is_point", "count_volume", "coords.point");
      assertThat(before.pageFields()).doesNotContain("has_alto_content", "has_text_ocr_content");
    }

    @Test
    @DisplayName("both floors ask for the bbox corners, which is why navPlace needs no fallback")
    void bboxIsUniversal() {
      for (KrameriusAdapter adapter : REGISTRY.all()) {
        assertThat(adapter.schema().documentFields())
            .as(adapter.name())
            .contains("coords.bbox.corner_sw", "coords.bbox.corner_ne");
      }
    }

    @Test
    @DisplayName("coords.point is deliberately never requested, at any floor")
    void coordsPointIsIgnored() {
      // It arrives at 7.2.1.1 but is a copyField of coords.bbox.center, which every K7 has. A
      // boundary that buys nothing does not earn an adapter.
      for (KrameriusAdapter adapter : REGISTRY.all()) {
        assertThat(adapter.schema().documentFields())
            .as(adapter.name())
            .doesNotContain("coords.point");
      }
    }
  }

  @Nested
  @DisplayName("query construction")
  class Queries {

    @Test
    @DisplayName("every child sort ends in the uniqueKey, or cursorMark is undefined")
    void sortsEndInUniqueKey() {
      SolrSchema schema = new Kramerius714().schema();

      for (String kind :
          List.of(
              "PAGES",
              "TRACKS",
              "SOUND_UNITS",
              "MONOGRAPH_UNITS",
              "PERIODICAL_VOLUMES",
              "PERIODICAL_ISSUES",
              "CONVOLUTE_PARTS")) {
        assertThat(schema.childSort(kind, "compositeId")).endsWith("compositeId asc");
        assertThat(schema.childSort(kind, "pid")).endsWith("pid asc");
      }
    }

    @Test
    @DisplayName("a pid is quoted and escaped, so it cannot inject query clauses")
    void pidsAreEscaped() {
      // v1 interpolated the raw path segment straight into q=.
      String filter =
          new Kramerius70().schema().childFilter("uuid:x\" OR model:page OR \"", "PAGES", false);

      assertThat(filter).isEqualTo("own_parent.pid:\"uuid:x\\\" OR model:page OR \\\"\"");
    }

    @Test
    @DisplayName("fostered children are looked up through foster_parents, not own_parent")
    void fosteredChildren() {
      // An article does not own its pages — the issue does. Querying own_parent.pid returns
      // nothing,
      // which is why v1 could not have served an article even had its dispatch table listed one.
      SolrSchema schema = new Kramerius70().schema();

      assertThat(schema.childFilter("uuid:a", "PAGES", true)).startsWith("foster_parents.pids:");
      assertThat(schema.childFilter("uuid:a", "PAGES", false)).startsWith("own_parent.pid:");
    }
  }
}
