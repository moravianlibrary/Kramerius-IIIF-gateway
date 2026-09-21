/*
 * The cartographic extent of a map, in decimal degrees.
 *
 * K7 indexes this directly as coords.bbox.corner_sw / corner_ne, on every version from 7.0 up, so the
 * adapter hands over numbers and no MODS is involved. The Ruby gateway instead ran two regexes over a
 * degrees-minutes-seconds string in MODS and crashed on anything matching neither.
 *
 * `coords.is_point` only exists from 7.1.4, so isPoint() derives it rather than reading it: a point is a
 * box whose corners coincide. That is one of the three fallbacks Kramerius70 needs.
 */
package cz.mzk.iiif.document;

import java.util.List;

public record BoundingBox(double west, double east, double south, double north) {

  /** A single location rather than an extent. */
  public boolean isPoint() {
    return west == east && south == north;
  }

  /** GeoJSON position, longitude first as RFC 7946 requires. */
  public List<Double> centre() {
    return List.of((west + east) / 2, (south + north) / 2);
  }

  /**
   * The extent as a closed, counter-clockwise GeoJSON ring.
   *
   * <p>RFC 7946 requires the ring to be closed and non-self-intersecting. The Ruby emitted the
   * corners in (SW, SE, NW, NE) order, which is a bow tie, and never repeated the first point to
   * close it.
   */
  public List<List<Double>> ring() {
    return List.of(
        List.of(west, south),
        List.of(east, south),
        List.of(east, north),
        List.of(west, north),
        List.of(west, south));
  }
}
