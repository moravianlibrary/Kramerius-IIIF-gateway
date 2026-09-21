/*
 * A Kramerius version, parsed leniently because the registry is not disciplined about them.
 *
 * Live values from registr.digitalniknihovna.cz include "7.2.1.1", "7.0.31-dev", "5.8.4.4",
 * "7.2.2.1-dev7" and "4.6.2" — four components, pre-release suffixes, and no promise of either. Strict
 * semver parsing would reject roughly a fifth of the registry.
 *
 * So: read the leading numeric components, stop at the first thing that is not one, and compare
 * component-wise treating a missing component as zero. A suffix never affects ordering, which is
 * deliberate — "7.2.2.1-dev7" is a 7.2.2.1 for the purpose of picking an adapter, and treating it as
 * older would route a newer instance to an older adapter for no reason.
 */
package cz.mzk.iiif.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record Version(List<Integer> components, String raw) implements Comparable<Version> {

  public Version {
    components = List.copyOf(components);
  }

  /** Parses what the registry gave us, or empty when there is no leading number at all. */
  public static Optional<Version> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    List<Integer> parsed = new ArrayList<>();
    for (String part : raw.trim().split("\\.")) {
      Optional<Integer> number = leadingNumber(part);
      if (number.isEmpty()) {
        break;
      }
      parsed.add(number.get());
      // "31-dev" parses as 31, but nothing after it can be trusted to be a component.
      if (!number.get().toString().equals(part)) {
        break;
      }
    }
    return parsed.isEmpty() ? Optional.empty() : Optional.of(new Version(parsed, raw.trim()));
  }

  /** A version written in source, where we control the spelling. */
  public static Version of(int... components) {
    List<Integer> list = new ArrayList<>(components.length);
    StringBuilder raw = new StringBuilder();
    for (int i = 0; i < components.length; i++) {
      list.add(components[i]);
      raw.append(i == 0 ? "" : ".").append(components[i]);
    }
    return new Version(list, raw.toString());
  }

  private static Optional<Integer> leadingNumber(String part) {
    int end = 0;
    while (end < part.length() && Character.isDigit(part.charAt(end))) {
      end++;
    }
    return end == 0 ? Optional.empty() : Optional.of(Integer.parseInt(part.substring(0, end)));
  }

  public int major() {
    return component(0);
  }

  /** A missing component is zero: 7.1 and 7.1.0 are the same version. */
  private int component(int index) {
    return index < components.size() ? components.get(index) : 0;
  }

  @Override
  public int compareTo(Version other) {
    int width = Math.max(components.size(), other.components.size());
    for (int i = 0; i < width; i++) {
      int difference = Integer.compare(component(i), other.component(i));
      if (difference != 0) {
        return difference;
      }
    }
    return 0;
  }

  public boolean atLeast(Version floor) {
    return compareTo(floor) >= 0;
  }

  @Override
  public String toString() {
    return raw;
  }
}
