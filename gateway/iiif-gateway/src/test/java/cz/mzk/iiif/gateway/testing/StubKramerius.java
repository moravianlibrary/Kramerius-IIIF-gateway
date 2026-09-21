/*
 * A Kramerius 7 client API with just enough in it to build a manifest, and a request counter.
 *
 * The counter is the point. "A cache hit answers without a single upstream call" is the whole claim the
 * manifest cache exists to make, and it cannot be checked by looking at a response — only by asking the
 * far end how many times it was asked. Everything else here is the minimum that gets a document and its
 * pages through the adapter.
 */
package cz.mzk.iiif.gateway.testing;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class StubKramerius implements AutoCloseable {

  private final HttpServer server;
  private final AtomicInteger requests = new AtomicInteger();
  private final String documentPid;
  private final int pages;
  private final boolean tiled;
  private final boolean restricted;

  /**
   * What this instance reports at /info, which is what now selects the adapter.
   *
   * <p>Null makes the endpoint 404, modelling the instances that do not answer one — the gateway
   * has to fall back to the declared version rather than refuse to serve them.
   */
  private volatile String reportedVersion = "7.2.2";

  /**
   * @param tiled whether the pages claim an Image API endpoint. False is how a test gets a complete
   *     manifest with no image server at all: a page with no tiles has no size to ask for.
   */
  public StubKramerius(String documentPid, int pages, boolean tiled) throws IOException {
    this(documentPid, pages, tiled, false);
  }

  /**
   * @param restricted models a licensed document: info.json answers 403 while the thumbnail stays
   *     public, which is what MZK actually does — verified on uuid:fbaf7950-…, 403 on the image and
   *     200 on the thumb. This is the only way to reach the approximate-dimensions path.
   */
  public StubKramerius(String documentPid, int pages, boolean tiled, boolean restricted)
      throws IOException {
    this.documentPid = documentPid;
    this.pages = pages;
    this.tiled = tiled;
    this.restricted = restricted;
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.setExecutor(null);
    server.start();
  }

  /**
   * What /info should report, or null for an instance that answers no /info at all.
   *
   * <p>Set before the library is resolved — resolution is once per library and then cached.
   */
  public void reportVersion(String version) {
    this.reportedVersion = version;
  }

  /** The aspect a restricted page's thumbnail reports, and so the shape its canvas comes out. */
  public static final int THUMB_WIDTH = 172;

  public static final int THUMB_HEIGHT = 256;

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** How many times anything has been asked of this instance. */
  public int requests() {
    return requests.get();
  }

  public void resetRequests() {
    requests.set(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    URI uri = exchange.getRequestURI();
    String query = uri.getQuery() == null ? "" : uri.getQuery();

    // Before the /search check, because the K7 info path contains "/search" and would otherwise be
    // read as a Solr query.
    if (uri.getPath().endsWith("/info")) {
      if (reportedVersion == null) {
        respond(exchange, 404, "{}");
      } else {
        respond(exchange, 200, "{\"version\":\"" + reportedVersion + "\",\"indexerVersion\":25}");
      }
      return;
    }
    if (restricted && uri.getPath().endsWith("/image/thumb")) {
      // Public even when the image is not. Only the frame header matters — JpegHeader reads the
      // size without decoding, so a valid SOF0 is a valid thumbnail for this purpose.
      respondBytes(exchange, 200, "image/jpeg", jpeg(THUMB_WIDTH, THUMB_HEIGHT));
      return;
    }
    if (restricted && uri.getPath().contains("/search/iiif/")) {
      // What a licensed page answers: not "no image", but "not for you".
      respond(exchange, 403, "<html>403</html>");
      return;
    }
    if (!uri.getPath().contains("/search")) {
      // Licence config, the Image API probe, anything else the adapter tries opportunistically.
      // A 404 is a normal answer for those and the adapter is expected to carry on.
      respond(exchange, 404, "{}");
      return;
    }
    if (query.contains("rows=0")) {
      // Topology detection and the tiled-page count. zkConnected absent means standalone, so the
      // cursor tiebreaker is `pid`.
      respond(
          exchange,
          200,
          "{\"responseHeader\":{\"status\":0},\"response\":{\"numFound\":"
              + (tiled ? pages : 0)
              + ",\"docs\":[]}}");
      return;
    }
    if (query.contains("q=pid")) {
      respond(exchange, 200, document());
      return;
    }
    respond(exchange, 200, pageBatch(cursorMark(query)));
  }

  /**
   * The cursorMark this request sent, so the response can echo it back.
   *
   * <p>Echoing it is Solr's end-of-results signal, and it is what makes this stub a Solr rather
   * than an approximation of one: a response with NO nextCursorMark means the server does not
   * implement cursorMark at all, which SolrClient now refuses rather than reading as "walk
   * complete". The stub used to omit the field and rely on exactly that misreading.
   */
  private static String cursorMark(String query) {
    for (String part : query.split("&")) {
      if (part.startsWith("cursorMark=")) {
        return URLDecoder.decode(part.substring("cursorMark=".length()), StandardCharsets.UTF_8);
      }
    }
    return "*";
  }

  private String document() {
    return """
        {"responseHeader":{"status":0},"response":{"numFound":1,"docs":[{
          "pid":"%s","model":"monograph","title.search":"A stub monograph",
          "root.title":"A stub monograph","accessibility":"public",
          "count_page":%d,"modified":"2024-01-01T00:00:00.000Z"
        }]}}"""
        .formatted(documentPid, pages);
  }

  private String pageBatch(String cursorMark) {
    List<String> docs = new java.util.ArrayList<>();
    for (int i = 0; i < pages; i++) {
      docs.add(
          """
          {"pid":"uuid:stub-page-%d","model":"page","page.index":%d,
           "page.number":"%d","has_tiles":%s,"modified":"2024-01-01T00:00:00.000Z"}"""
              .formatted(i, i, i + 1, tiled));
    }
    // nextCursorMark echoed back: Solr's end-of-results signal, so the walk takes the docs in this
    // response and stops — one round trip for the whole document, which is what the tests want.
    return "{\"responseHeader\":{\"status\":0},\"response\":{\"numFound\":"
        + pages
        + ",\"docs\":["
        + String.join(",", docs)
        + "]},\"nextCursorMark\":\""
        + cursorMark
        + "\"}";
  }

  /** SOI + a baseline SOF0 stating the size. Not decodable, and does not need to be. */
  private static byte[] jpeg(int width, int height) {
    return new byte[] {
      (byte) 0xFF,
      (byte) 0xD8, // SOI
      (byte) 0xFF,
      (byte) 0xC0, // SOF0
      0x00,
      0x11, // length
      0x08, // precision
      (byte) (height >> 8),
      (byte) height,
      (byte) (width >> 8),
      (byte) width,
      0x03, // components, then three component specs
      0x01,
      0x22,
      0x00,
      0x02,
      0x11,
      0x01,
      0x03,
      0x11,
      0x01
    };
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    respondBytes(exchange, status, "application/json", body.getBytes(StandardCharsets.UTF_8));
  }

  private static void respondBytes(HttpExchange exchange, int status, String type, byte[] bytes)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", type);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
