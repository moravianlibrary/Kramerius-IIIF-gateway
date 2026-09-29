/**
 * Turning Kramerius documents into IIIF documents.
 *
 * <p>This is the machinery — neither IIIF's vocabulary nor Kramerius', but the mapping between
 * them, and the decisions that go with it: which shape to build, how canvases are identified and
 * numbered, whether {@code behavior: paged} is honest, how much a request should pay to resolve
 * page dimensions, and which pages a prefetch has to visit.
 *
 * <p>It composes structure only. Descriptive text arrives from the adapter already mapped and is
 * copied through; see {@link cz.mzk.iiif.document.DocumentRecord}.
 *
 * <p>Tests guard the output contract.
 */
package cz.mzk.iiif.build;
