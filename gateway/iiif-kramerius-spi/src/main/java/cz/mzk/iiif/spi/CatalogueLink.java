/*
 * Turns an indexed system number into a catalogue record URL.
 *
 * This cannot be derived, and every shortcut here is one that was tried and produced 404s.
 *
 * THE SIGLA IS NOT THE ANSWER. MZK's sigla is BOA001 and vufind.mzk.cz/Record/BOA001-… is a 404; the
 * URL wants the Aleph base, MZK01. Sigla is in Solr as physical_locations, but it is the *holding*
 * institution — MZK's repository also holds ABA001, ABA004, KME450 material — so it is not a property
 * of the serving library at all.
 *
 * THE PREFIX IS NOT DISCARDABLE. MZK indexes both mzk01- and mzk03- prefixes (424 and 121 of 600
 * sampled), and MZK03-000548262 resolves while MZK01-000548262 is a 404. Stripping the prefix and
 * applying one configured base would break roughly a fifth of MZK's links.
 *
 * THE VALUE HAS THREE SHAPES, all present at MZK in 1000 samples:
 *   mzk01-001506523    prefix + hyphen   654
 *   000913055          bare               307
 *   mzk03000706629     prefix, no hyphen   39
 * The record number is the LAST NINE DIGITS, not the trailing digit run — the base itself ends in
 * digits, so a greedy match reads mzk03000706629 as prefix "mzk" + "03000706629" and 404s.
 *
 * SO THE RULES ARE THE CONFIGURATION, one regex per shape a library indexes. Each pattern captures the
 * record number in group 1 and maps to the prefix the catalogue wants in front of it; the first pattern
 * that matches wins, and nothing matching means no link. Expressing it as patterns rather than as a
 * prefix table is what lets the next library be added without changing code: "the last nine digits" is
 * MZK's convention, not a fact about catalogues, and a library whose numbers are six digits or carry a
 * letter can simply say so.
 */
package cz.mzk.iiif.spi;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record CatalogueLink(String urlTemplate, List<Rule> rules) {

  /** Replaced with the resolved record id. */
  public static final String ID_PLACEHOLDER = "{id}";

  /**
   * One shape of indexed system number, and what the catalogue calls it.
   *
   * @param pattern must capture the record number in group 1; matched against the whole value
   * @param prefix put in front of the captured number verbatim — so it carries its own separator,
   *     and an empty prefix means the catalogue takes a bare number, which is what vkol's does
   */
  public record Rule(Pattern pattern, String prefix) {

    public Rule {
      if (pattern == null) {
        throw new IllegalArgumentException("a catalogue rule needs a pattern");
      }
      if (pattern.matcher("").groupCount() < 1) {
        throw new IllegalArgumentException(
            "catalogue pattern must capture the record number in group 1: " + pattern);
      }
      prefix = prefix == null ? "" : prefix;
    }

    public static Rule of(String pattern, String prefix) {
      return new Rule(Pattern.compile(pattern), prefix);
    }
  }

  public CatalogueLink {
    if (urlTemplate == null || !urlTemplate.contains(ID_PLACEHOLDER)) {
      throw new IllegalArgumentException("catalogue URL template must contain " + ID_PLACEHOLDER);
    }
    rules = List.copyOf(rules);
  }

  /**
   * The catalogue URL for one indexed system number, or empty when no rule claims it.
   *
   * <p>Empty rather than a guess: a wrong catalogue link is worse than none, because it looks like
   * the gateway found the record and the reader believes it.
   */
  public Optional<String> urlFor(String systemNumber) {
    if (systemNumber == null || systemNumber.isBlank()) {
      return Optional.empty();
    }
    String value = systemNumber.trim();

    for (Rule rule : rules) {
      Matcher matcher = rule.pattern().matcher(value);
      if (matcher.matches()) {
        return Optional.of(urlTemplate.replace(ID_PLACEHOLDER, rule.prefix() + matcher.group(1)));
      }
    }
    // An unclaimed shape means this library indexes something we have not been told about. A link
    // built from a guess would 404; saying nothing is correct.
    return Optional.empty();
  }
}
