/*
 * The MARC country codes, so `publication_places.facet` can be read as places.
 *
 * Kramerius indexes the code and the place name in the same multi-valued field — a Prague monograph is
 * ["Xr", "Praha"] — so telling them apart is the difference between printing "Praha" and printing "Xr"
 * as the place of publication on roughly a quarter of MZK's documents.
 *
 * The list is DATA, not code: dev/country-codes.py writes it from id.loc.gov plus whatever
 * deprecated codes the libraries still hold. Editing the resource by hand is pointless — the next run
 * of the script overwrites it — so a wrong entry is a fix to the script.
 *
 * A missing resource is fatal rather than an empty set: an empty set silently turns every country code
 * in the country into a place of publication, which is exactly the bug this class exists to prevent.
 */
package cz.mzk.iiif.adapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class MarcCountryCodes {

  private static final String RESOURCE = "marc-country-codes.txt";
  private static final Set<String> CODES = load();

  private MarcCountryCodes() {}

  /** Case-insensitive: LoC publishes "xr", Kramerius indexes "Xr". */
  static boolean contains(String value) {
    return CODES.contains(value.toLowerCase(Locale.ROOT));
  }

  /** How many codes are known, for the log line that proves the resource was found. */
  static int size() {
    return CODES.size();
  }

  private static Set<String> load() {
    try (InputStream in = MarcCountryCodes.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is missing from the jar");
      }
      Set<String> codes = new HashSet<>();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          // "xr  # Czech Republic" — the label is there for whoever reads the diff, not for us.
          String code = line.split("#", 2)[0].trim();
          if (!code.isEmpty()) {
            codes.add(code.toLowerCase(Locale.ROOT));
          }
        }
      }
      if (codes.isEmpty()) {
        throw new IllegalStateException(RESOURCE + " is empty");
      }
      return Set.copyOf(codes);
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + RESOURCE, e);
    }
  }
}
