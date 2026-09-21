/*
 * What the checker decides, and how little it asks to decide it.
 *
 * A sweep that RESOLVED every library would be ~150 upstream calls to institutions that did not ask to be
 * woken up, every thirty minutes, forever — resolution probes the Image API over five pages, fetches the
 * licence config and detects the Solr topology. That is why /health and /libraries resolve nothing, and
 * the checker must not quietly undo it.
 *
 * What it does ask is bounded and deliberate: /info for the version, then one Solr query. The /info call
 * is not optional and not an optimisation target — the version selects the adapter, and the registry is
 * measurably wrong about it for three libraries including one across the K5/K7 boundary. It is also the
 * only thing that detects an upgrade, since re-reading a stale registry on a schedule just re-reads a
 * stale registry. A K7 library costs one /info request because that path is tried first; K5 costs two.
 *
 * The declared-library path is used here because it needs no registry. The Solr probe is covered by
 * `make smoke` against real instances, where it means something.
 */
package cz.mzk.iiif.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LibraryHealthTest {

  private static LibraryHealth checker(String libraries, String checkMinutes) {
    Config config =
        Config.from(
            Map.of(
                "IIIF_LIBRARIES",
                libraries,
                "IIIF_LIBRARY_CHECK_MINUTES",
                checkMinutes,
                "IIIF_HTTP_CONNECT_TIMEOUT_SECONDS",
                "1",
                "IIIF_HTTP_READ_TIMEOUT_SECONDS",
                "2"));
    HttpClient http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
    return new LibraryHealth(
        config, new Libraries(config, Optional.empty(), Optional.empty(), http), http);
  }

  @Test
  @DisplayName("a version below every adapter floor is unsupported, and is never Solr-probed")
  void unsupportedVersionIsDecidedWithoutProbing() {
    // The one K4 instance in the registry. It IS asked for its /info — the declared version is the
    // thing we have learned not to trust, and refusing a working library on the strength of a stale
    // registry entry is the failure this whole path exists to avoid. What it is not asked for is
    // anything expensive: no Solr query, no Image API probe, no licence config.
    LibraryHealth health = checker("old=http://127.0.0.1:1|4.6.2|cs:Stará knihovna", "30");

    health.sweep();

    LibraryHealth.Status status = health.status("old").orElseThrow();
    assertThat(status.state()).isEqualTo(LibraryHealth.State.UNSUPPORTED);
    assertThat(status.detail()).contains("below 5.0");
    // Nothing was asked, so nothing was waited for.
    assertThat(status.latencyMillis()).isZero();
  }

  @Test
  @DisplayName("an unparseable version is unsupported rather than optimistically probed")
  void unparseableVersionIsUnsupported() {
    LibraryHealth health = checker("odd=http://127.0.0.1:1|not-a-version|cs:Divná", "30");

    health.sweep();

    assertThat(health.status("odd").orElseThrow().state())
        .isEqualTo(LibraryHealth.State.UNSUPPORTED);
  }

  @Test
  @DisplayName("a supported library that does not answer is unreachable, not unsupported")
  void unreachableIsItsOwnState() {
    // The distinction an operator needs: "we cannot read this version" is a permanent fact about
    // the
    // instance, "it did not answer" is a thing that may fix itself. Collapsing them would make a
    // rebooting library look like one we have to write an adapter for.
    LibraryHealth health = checker("down=http://127.0.0.1:1|7.2.2|cs:Nedostupná", "30");

    health.sweep();

    LibraryHealth.Status status = health.status("down").orElseThrow();
    assertThat(status.state()).isEqualTo(LibraryHealth.State.UNREACHABLE);
    assertThat(status.adapter()).contains("Kramerius714");
    assertThat(status.detail()).isPresent();
  }

  @Test
  @DisplayName("the summary counts every state, including the ones at zero")
  void summaryNamesEveryState() {
    // /health shows this. A state missing from the map reads as "no such problem exists" rather
    // than
    // "none right now", which is a different claim.
    LibraryHealth health = checker("old=http://127.0.0.1:1|4.6.2|cs:Stará", "30");

    health.sweep();

    assertThat(health.summary())
        .containsKeys("ready", "unreachable", "unsupported", "down", "unknown")
        .containsEntry("unsupported", 1)
        .containsEntry("ready", 0);
  }

  @Test
  @DisplayName("nothing is known before the first sweep")
  void statusesAreEmptyUntilSwept() {
    // /libraries reports "unchecked" for these, rather than resolving them to find out — which is
    // the
    // behaviour that made the route usable in the first place.
    LibraryHealth health = checker("mzk=http://127.0.0.1:1|7.2.2|cs:MZK", "30");

    assertThat(health.statuses()).isEmpty();
    assertThat(health.status("mzk")).isEmpty();
  }

  @Test
  @DisplayName("a zero interval switches the checker off and sweeps nothing")
  void zeroIntervalDisablesIt() {
    LibraryHealth health = checker("mzk=http://127.0.0.1:1|7.2.2|cs:MZK", "0");

    health.start();

    assertThat(health.statuses()).isEmpty();
    health.close();
  }
}
