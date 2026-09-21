/**
 * Every string this gateway authors, and the helper that builds metadata rows from them.
 *
 * <p>One file per language under {@code i18n/}, keyed the same way; the set of languages emitted is
 * the set of files loaded, and a configured directory is merged over the bundled defaults at
 * startup, so a language can be added or a label corrected without rebuilding the image.
 *
 * <p>Nothing that comes <i>from</i> Kramerius is translated — titles, authors and dates pass
 * through under the {@code none} tag. Only labels this gateway wrote are.
 */
package cz.mzk.iiif.i18n;
