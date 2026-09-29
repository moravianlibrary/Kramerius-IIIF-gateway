/**
 * Kramerius' vocabulary, as this gateway models it.
 *
 * <p><b>These names come from Kramerius, not from IIIF.</b> {@code monograph}, {@code
 * periodicalvolume}, {@code soundunit} are Kramerius document models.
 *
 * <p>{@link cz.mzk.iiif.kramerius.IiifStructure} is <b>ours</b>, not either side's: it names a
 * build strategy, and it is the one thing here that is an enum rather than data, because {@code
 * ManifestBuilder} switches on it exhaustively — a shape exists only if there is code that builds
 * one. Everything else about a model is loaded from a versioned {@code models/kramerius-*.json}
 * table and can be changed without a rebuild.
 *
 * <p>Type labels are not here either; they are {@code i18n/<language>.json} under {@code
 * model.<solrName>}.
 */
package cz.mzk.iiif.kramerius;
