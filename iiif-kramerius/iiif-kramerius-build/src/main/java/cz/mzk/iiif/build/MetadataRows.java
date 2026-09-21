/*
 * Builds the standard metadata rows, translated, for adapters to use.
 *
 * Metadata is adapter-supplied now — the library copies it rather than composing it — but the common
 * rows are the same for every Kramerius version, and having K5 and K7 each invent their own labels
 * would produce manifests that differ for no reason. So this is a shared helper the adapters call, not
 * a rule the library imposes: an adapter is free to add rows this class knows nothing about.
 *
 * EVERY ROW IS EMITTED WHEN ITS DATA EXISTS, and omitted when it does not. There is deliberately no
 * per-model list of which rows apply, because the measured coverage shows any such list would be wrong
 * for half the models:
 *
 *   article has authors (81%) but no part fields at all (0%);
 *   supplement is the inverse — part number 100%, part name 81%, authors 5%;
 *   publication places are 98% on monograph and 0-1% on everything else.
 *
 * See docs/BEHAVIOUR.md for the coverage table.
 */
package cz.mzk.iiif.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import cz.mzk.iiif.i18n.Translations;
import cz.mzk.iiif.presentation.Iiif;
import cz.mzk.iiif.presentation.LanguageMap;

public final class MetadataRows {

  private final Translations translations;
  private final List<Iiif.KeyValue> rows = new ArrayList<>();

  private MetadataRows(Translations translations) {
    this.translations = translations;
  }

  public static MetadataRows using(Translations translations) {
    return new MetadataRows(translations);
  }

  /** A row whose value is record content in an unknown language. */
  public MetadataRows text(String labelKey, String value) {
    return add(labelKey, LanguageMap.none(value));
  }

  public MetadataRows texts(String labelKey, List<String> values) {
    return add(labelKey, LanguageMap.none(values));
  }

  /** A row whose value is itself translated — a document type, an availability. */
  public MetadataRows translated(String labelKey, String valueKey) {
    return add(labelKey, translations.lookup(valueKey));
  }

  /** A row whose values are codes translated through a key prefix — MARC languages. */
  public MetadataRows codes(String labelKey, String valueKeyPrefix, List<String> values) {
    return add(labelKey, translations.lookupAll(valueKeyPrefix, values));
  }

  /**
   * The publication statement, or a bare date when there is nothing to build one from.
   *
   * <p>"{place}: {publisher}, {date}" with missing parts dropped. When neither a publisher nor a
   * place is indexed the result is just a date, so it is labelled "Vydáno" instead — which is what
   * it is. The Ruby composed this from MODS and emitted a leading ": " when the place was missing;
   * composing from indexed fields makes that shape impossible rather than preserving it.
   */
  public MetadataRows publication(List<String> places, List<String> publishers, String date) {
    Optional<String> place = first(places);
    Optional<String> publisher = first(publishers);

    if (place.isEmpty() && publisher.isEmpty()) {
      return text("metadata.published", date);
    }

    StringBuilder text = new StringBuilder();
    place.ifPresent(text::append);
    publisher.ifPresent(value -> text.append(text.isEmpty() ? "" : ": ").append(value));
    if (date != null && !date.isBlank()) {
      text.append(text.isEmpty() ? "" : ", ").append(date);
    }
    return text("metadata.publicationStatement", text.toString());
  }

  /**
   * The licences that apply, translated where we know the term and passed through where we do not.
   *
   * <p>There is no separate availability row. Kramerius still indexes `accessibility`, but it
   * stopped being the thing that decides access somewhere in 7.x — licences are what the
   * application actually reads — so showing both said the same thing twice, in two vocabularies,
   * one of them stale.
   *
   * <p>Unknown names are shown as-is on purpose: `dnnto` and `dnntt` are established abbreviations
   * that a Czech reader recognises, and inventing an expansion for a legal term would be worse than
   * the abbreviation. An instance that publishes its own licence texts supplies them through
   * LibraryProfile.licenseTexts, which is what requiredStatement uses.
   */
  public MetadataRows licenses(List<String> licenses) {
    return licenses(licenses, null);
  }

  /**
   * @param resolved what the instance itself calls these licences, and what it says about them;
   *     null when it publishes no licence config, in which case the ids fall back to the bundled
   *     wording
   */
  public MetadataRows licenses(List<String> licenses, Map<String, List<String>> resolved) {
    if (resolved != null && !resolved.isEmpty()) {
      return add("metadata.license", resolved);
    }
    return codes("metadata.license", "license.", licenses);
  }

  public List<Iiif.KeyValue> build() {
    return List.copyOf(rows);
  }

  private MetadataRows add(String labelKey, Map<String, List<String>> value) {
    if (value != null) {
      Map<String, List<String>> label = translations.lookup(labelKey);
      if (label != null) {
        rows.add(new Iiif.KeyValue(label, value));
      }
    }
    return this;
  }

  private static Optional<String> first(List<String> values) {
    return values == null || values.isEmpty()
        ? Optional.empty()
        : Optional.ofNullable(values.get(0));
  }
}
