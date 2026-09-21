/**
 * The IIIF Presentation API 3.0 vocabulary.
 *
 * <p><b>Nothing in this package is our invention.</b> Every type, field name and constant here is
 * defined by the spec, and the shape of the JSON is fixed by it — see <a
 * href="https://iiif.io/api/presentation/3.0/">the specification</a>. If a name here is not in the
 * spec, that is a bug.
 *
 * <p>At the top level IIIF has exactly two kinds of document, {@code Manifest} and {@code
 * Collection}, which is why {@code Iiif.Document} serves both.
 *
 * <p>What this package is <i>not</i>: any notion of a Kramerius model, of how to fetch anything, or
 * of how a document should be assembled. Those live in {@code cz.mzk.iiif.kramerius} and {@code
 * cz.mzk.iiif.build} respectively.
 */
package cz.mzk.iiif.presentation;
