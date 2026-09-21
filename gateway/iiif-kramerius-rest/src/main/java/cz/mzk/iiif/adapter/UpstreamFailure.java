/*
 * Somebody else's server did not hold up its end, and which way it failed.
 *
 * This exists so the web layer can answer 502 or 504 instead of 500. The distinction is not
 * pedantry: this gateway is a client of fifty-odd third-party instances, and by far the most common
 * reason a manifest cannot be built is that one of them is slow or down. A 500 asserts the fault is
 * ours — it sends an operator to our logs, our heap and our deploy history for a problem none of
 * those can explain, and it is the wrong answer to give a caching proxy, which treats 500 as a bug
 * to shield callers from and 502/504 as an upstream to retry past.
 *
 * TYPED, NOT PARSED. The kind travels as a field because the alternative is the web layer matching
 * on exception messages to decide a status code, which breaks the first time somebody rewords a log
 * line. The adapter knows why the request failed; that knowledge should survive the throw.
 *
 * `upstream` is the URI we asked, so an error and a log line can name the instance that is actually
 * unwell rather than the library code the caller happened to use.
 */
package cz.mzk.iiif.adapter;

import java.util.Optional;

public final class UpstreamFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public enum Kind {
    /** Asked, nothing came back in time. Retrying later may work. */
    TIMEOUT,
    /** Could not be reached at all: connection refused, DNS, TLS. */
    UNREACHABLE,
    /** Answered, with a status saying it would not or could not serve the query. */
    REJECTED,
    /** Answered 429 or 503: explicitly asking us to come back later. */
    OVERLOADED
  }

  private final Kind kind;
  private final String upstream;
  private final int status;

  private UpstreamFailure(Kind kind, String upstream, int status, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
    this.upstream = upstream;
    this.status = status;
  }

  /**
   * A transport failure, sorted into timeout or unreachable.
   *
   * <p>The two are told apart by the exception type rather than by asking the caller, because the
   * caller does not know either — it only has an IOException from the HTTP client.
   */
  public static UpstreamFailure transport(String upstream, java.io.IOException cause) {
    boolean timedOut = cause instanceof java.net.http.HttpTimeoutException;
    Kind kind = timedOut ? Kind.TIMEOUT : Kind.UNREACHABLE;
    String what = timedOut ? "did not answer in time" : "could not be reached";
    return new UpstreamFailure(kind, upstream, 0, where(upstream) + " " + what, cause);
  }

  /** A response we cannot use. 429 and 503 are the instance pacing us, not failing. */
  public static UpstreamFailure status(String upstream, int status, String detail) {
    Kind kind = (status == 429 || status == 503) ? Kind.OVERLOADED : Kind.REJECTED;
    return new UpstreamFailure(
        kind, upstream, status, where(upstream) + " returned " + status + detail, null);
  }

  /**
   * The instance and path, without the query.
   *
   * <p>The message goes in a log line, and the query string is a `fl=` of thirty field names — a
   * kilobyte per warning, which is how a log stops being read. The whole URI is still on {@link
   * #upstream()} for the one case that needs it: a REJECTED response is usually a field this
   * adapter assumed and the instance does not have, and then the field list is the entire question.
   */
  private static String where(String uri) {
    int query = uri.indexOf('?');
    return query < 0 ? uri : uri.substring(0, query);
  }

  public Kind kind() {
    return kind;
  }

  public String upstream() {
    return upstream;
  }

  /** The status the upstream sent, or empty when it never answered. */
  public Optional<Integer> upstreamStatus() {
    return status > 0 ? Optional.of(status) : Optional.empty();
  }
}
