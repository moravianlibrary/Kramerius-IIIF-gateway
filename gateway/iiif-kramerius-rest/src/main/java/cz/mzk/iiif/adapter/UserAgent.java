/*
 * How this gateway identifies itself to the servers it reads.
 *
 * It matters more here than in most clients. The gateway is a guest on fifty third-party Kramerius
 * instances it does not operate, and a dimension walk of a large library is tens of millions of requests
 * — so when an administrator looks at a traffic spike in their access log, the request should say what it
 * is, which version, and where to complain. An unidentified flood gets blocked, and rightly.
 *
 * The contact URL is part of the string rather than a separate header because that is the convention
 * operators actually read, and because Retry-After negotiations start with someone finding a human.
 */
package cz.mzk.iiif.adapter;

import java.net.http.HttpRequest;
import java.util.Optional;

public final class UserAgent {

  private static final String PROJECT = "https://github.com/moravianlibrary/Kramerius-IIIF-gateway";

  /** Set by the container build; "dev" when running from a working tree. */
  private static final String VERSION =
      Optional.ofNullable(System.getenv("SYSTEM_VERSION")).orElse("dev");

  public static final String VALUE = "Kramerius-IIIF-gateway/" + VERSION + " (+" + PROJECT + ")";

  private UserAgent() {}

  /** A request builder that already identifies itself. */
  public static HttpRequest.Builder request(java.net.URI uri) {
    return HttpRequest.newBuilder(uri).header("User-Agent", VALUE);
  }
}
