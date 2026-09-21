/**
 * The contract a host implements to feed this library.
 *
 * <p>Implemented over Kramerius' REST API by {@code iiif-kramerius-rest}, and in-process by a
 * Kramerius embedding the library. Nothing in this package performs I/O; it declares what the
 * library needs and leaves how to get it to the host.
 *
 * <p>{@link cz.mzk.iiif.spi.DocumentRecord} is the boundary type, and its split is the point of it:
 * <b>structure</b> the library reasons about is typed, while <b>presentation</b> it merely copies —
 * label, metadata, summary — arrives already mapped to IIIF. An adapter maps Solr field names; the
 * library above this line never sees one.
 *
 * <p>The rule that makes new Kramerius versions cheap: every enriched field is optional, and the
 * library must never <i>require</i> one. A missing field means "fall back", never "fail".
 */
package cz.mzk.iiif.spi;
