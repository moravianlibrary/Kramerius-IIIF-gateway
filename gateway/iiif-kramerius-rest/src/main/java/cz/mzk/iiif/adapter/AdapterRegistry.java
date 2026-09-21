/*
 * Picks the adapter for an instance: the highest floor at or below its version.
 *
 * Held ordered by floor descending, so the first that supports the version is the most specific one. An
 * instance below every floor is refused by name rather than served a broken manifest — the one K4
 * library in the registry (4.6.2) lands here, and saying so is more useful than half-working.
 */
package cz.mzk.iiif.adapter;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class AdapterRegistry {

  private final List<KrameriusAdapter> adapters;

  private AdapterRegistry(List<KrameriusAdapter> adapters) {
    this.adapters = adapters;
  }

  public static AdapterRegistry of(KrameriusAdapter... adapters) {
    return of(List.of(adapters));
  }

  public static AdapterRegistry of(List<KrameriusAdapter> adapters) {
    if (adapters.isEmpty()) {
      throw new IllegalArgumentException("an adapter registry needs at least one adapter");
    }
    return new AdapterRegistry(
        adapters.stream()
            .sorted(Comparator.comparing(KrameriusAdapter::floor).reversed())
            .toList());
  }

  /** Every adapter, newest floor first. */
  public List<KrameriusAdapter> all() {
    // Copied rather than excluded from the check: the list is two elements and read rarely, so the
    // copy costs nothing and keeps EI_EXPOSE_REP live for this package, where real logic lives.
    return List.copyOf(adapters);
  }

  public Optional<KrameriusAdapter> forVersion(Version version) {
    return adapters.stream().filter(adapter -> adapter.supports(version)).findFirst();
  }

  /** The lowest version any adapter serves, for the refusal message. */
  public Version lowestSupported() {
    return adapters.get(adapters.size() - 1).floor();
  }

  /** Whether the instance is newer than the chosen adapter has been tested against. */
  public static boolean isUntested(KrameriusAdapter adapter, Version version) {
    return version.compareTo(adapter.testedUpTo()) > 0;
  }
}
