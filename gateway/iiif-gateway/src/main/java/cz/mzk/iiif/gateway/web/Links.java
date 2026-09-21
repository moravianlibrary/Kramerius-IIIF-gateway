/*
 * Parses a Digital Library URL back into a library code and a pid.
 *
 * The ?link= resolver v1 offered, kept because people paste these. Shapes seen in the wild:
 *
 *   https://www.digitalniknihovna.cz/mzk/uuid/uuid:xxxx
 *   https://www.digitalniknihovna.cz/mzk/view/uuid:xxxx?page=uuid:yyyy
 *   https://kramerius.mzk.cz/search/handle/uuid:xxxx           (no code in the path)
 *
 * A URL we cannot parse is a 400 with a message, not a guess. v1 returned a manifest for the wrong
 * document when the path did not match what it expected.
 */
package cz.mzk.iiif.gateway.web;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Links {

  /** A pid is `uuid:` plus a uuid, or one of the older prefixed forms. */
  private static final Pattern PID = Pattern.compile("(uuid:[0-9a-fA-F-]{36})");

  private Links() {}

  public record Target(String library, String pid) {}

  /**
   * Parses a link, accepting only library codes this gateway actually serves.
   *
   * <p>The predicate is not decoration. Without it `kramerius.mzk.cz/search/handle/uuid:...` parses
   * as library "search" — a plausible answer about a library that does not exist, which is
   * precisely v1's failure mode. There is no reliable structural way to tell a library code from
   * any other first path segment, so the only sound test is whether we serve it.
   */
  public static Optional<Target> parse(String link, java.util.function.Predicate<String> known) {
    if (link == null || link.isBlank()) {
      return Optional.empty();
    }

    Matcher pid = PID.matcher(link);
    if (!pid.find()) {
      return Optional.empty();
    }

    // The FIRST pid in the URL is the document; a ?page= parameter names a page within it, which
    // has
    // no manifest of its own.
    String document = pid.group(1);

    try {
      List<String> segments =
          List.of(URI.create(link).getPath().split("/")).stream()
              .filter(segment -> !segment.isBlank())
              .toList();
      // digitalniknihovna.cz puts the code first: /mzk/uuid/uuid:...
      if (!segments.isEmpty() && known.test(segments.get(0))) {
        return Optional.of(new Target(segments.get(0), document));
      }
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }
}
