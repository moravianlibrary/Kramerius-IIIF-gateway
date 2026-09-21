/*
 * Which upstream failure is which, because the HTTP status the gateway answers is derived from it.
 *
 * The point of the type is that the web layer never has to read an exception message to decide
 * between 502, 503 and 504. These tests pin the sorting; WebServer's switch is exhaustive over the
 * enum, so a new kind cannot be added without deciding its status.
 */
package cz.mzk.iiif.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UpstreamFailureTest {

  private static final String UPSTREAM =
      "https://api.kramerius.mzk.cz/search/api/client/v7.0/search";

  @Test
  @DisplayName("a timeout is a timeout, not an unreachable instance")
  void timeoutIsItsOwnKind() {
    // This is the one that matters: MZK answering in 65 s with a 60 s read timeout is a live,
    // healthy-ish instance being slow, and it becomes a 504. Collapsing it into unreachable would
    // tell an operator the host is down when it is merely loaded.
    UpstreamFailure failure =
        UpstreamFailure.transport(UPSTREAM, new HttpTimeoutException("request timed out"));

    assertThat(failure.kind()).isEqualTo(UpstreamFailure.Kind.TIMEOUT);
    assertThat(failure.upstream()).isEqualTo(UPSTREAM);
    assertThat(failure.upstreamStatus()).isEmpty();
  }

  @Test
  @DisplayName("any other transport failure is unreachable")
  void otherTransportFailuresAreUnreachable() {
    UpstreamFailure refused =
        UpstreamFailure.transport(UPSTREAM, new ConnectException("Connection refused"));
    UpstreamFailure broken = UpstreamFailure.transport(UPSTREAM, new IOException("broken pipe"));

    assertThat(refused.kind()).isEqualTo(UpstreamFailure.Kind.UNREACHABLE);
    assertThat(broken.kind()).isEqualTo(UpstreamFailure.Kind.UNREACHABLE);
  }

  @Test
  @DisplayName("429 and 503 are the instance pacing us, so they are not failures of its own")
  void rateLimitsAreOverloaded() {
    // These two must not read as "broken": the worker's backoff honours them, and answering 502
    // would invite a proxy to retry straight into the limit we were just asked to respect.
    assertThat(UpstreamFailure.status(UPSTREAM, 429, "").kind())
        .isEqualTo(UpstreamFailure.Kind.OVERLOADED);
    assertThat(UpstreamFailure.status(UPSTREAM, 503, "").kind())
        .isEqualTo(UpstreamFailure.Kind.OVERLOADED);
  }

  @Test
  @DisplayName("any other answered status is a rejection, and keeps the code it came with")
  void otherStatusesAreRejections() {
    // 422 is the search API refusing a field this adapter assumed — an unmodelled adapter boundary,
    // and worth being able to see in a log without re-reading the message.
    UpstreamFailure rejected = UpstreamFailure.status(UPSTREAM, 422, " (query rejected)");

    assertThat(rejected.kind()).isEqualTo(UpstreamFailure.Kind.REJECTED);
    assertThat(rejected.upstreamStatus()).contains(422);
    assertThat(UpstreamFailure.status(UPSTREAM, 500, "").kind())
        .isEqualTo(UpstreamFailure.Kind.REJECTED);
  }

  @Test
  @DisplayName("the message names the instance, so a log line says who was unwell")
  void messageNamesTheUpstream() {
    // Fifty-odd third-party instances: an error that does not say which one is nearly useless.
    assertThat(UpstreamFailure.status(UPSTREAM, 500, "").getMessage()).contains(UPSTREAM);
    assertThat(UpstreamFailure.transport(UPSTREAM, new IOException("nope")).getMessage())
        .contains(UPSTREAM);
  }

  @Test
  @DisplayName("the message drops the query, and upstream() keeps it")
  void messageIsLogSizedButNothingIsLost() {
    // A Solr `fl=` is thirty field names: left in the message it costs a kilobyte per warning, and
    // that is how a log stops being read. But it is also the only thing that explains a REJECTED
    // response, so it has to stay reachable.
    String withQuery = UPSTREAM + "?wt=json&q=pid:%22uuid%3Aabc%22&fl=pid%2Cmodel%2Ctitle.search";
    UpstreamFailure failure = UpstreamFailure.status(withQuery, 422, " (query rejected)");

    assertThat(failure.getMessage()).contains(UPSTREAM).doesNotContain("fl=");
    assertThat(failure.upstream()).isEqualTo(withQuery);
  }
}
