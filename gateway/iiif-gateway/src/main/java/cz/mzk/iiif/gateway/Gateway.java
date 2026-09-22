/*
 * The entry point. One jar, two roles.
 *
 *   java -jar iiif-gateway.jar web      serves manifests; no long-running work
 *   java -jar iiif-gateway.jar worker   backfill and incremental sync; serves nothing
 *
 * The split exists because a backfill runs for WEEKS — at MZK, 75.7 million info.json requests, which is
 * ~9 days at 100 req/s. It must not compete with request serving for heap and CPU, must be pausable and
 * scalable independently, and must not be interrupted by every web deploy. Same image and same config,
 * so there is one artifact to build and one to document.
 */
package cz.mzk.iiif.gateway;

import java.net.http.HttpClient;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.mzk.iiif.gateway.registry.Registry;
import cz.mzk.iiif.gateway.store.PostgresSizeStore;
import cz.mzk.iiif.gateway.store.SizeStore;
import cz.mzk.iiif.gateway.web.WebServer;
import cz.mzk.iiif.gateway.worker.Worker;

public final class Gateway {

  private static final Logger LOG = LoggerFactory.getLogger(Gateway.class);

  private Gateway() {}

  public static void main(String[] args) {
    String role = args.length > 0 ? args[0] : "web";

    Config config;
    try {
      config = Config.fromEnvironment();
    } catch (RuntimeException e) {
      // Loudly, at startup, rather than quietly at the first request that needed the bad value.
      LOG.error("configuration is invalid: {}", message(e));
      System.exit(2);
      return;
    }

    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    Optional<Registry> registry =
        config
            .registryUrl()
            .map(url -> new Registry(http, url, config.registryTtl(), config.readTimeout()));

    Optional<SizeStore> store = config.database().map(PostgresSizeStore::new);
    if (store.isEmpty()) {
      LOG.warn("no IIIF_DB_URL: dimensions will be re-read from the image server on every request");
    }

    // Every remaining configuration FILE is read here: the translations, the model tables and the
    // catalogue mappings bundled in the jar. They are as fatal as the environment above and must
    // report the same way — an operator debugging a mounted directory should get one line naming
    // the file, not a stack trace from whichever loader happened to run first.
    Libraries libraries;
    try {
      libraries = new Libraries(config, registry, store, http);
    } catch (RuntimeException e) {
      LOG.error("configuration is invalid: {}", message(e));
      System.exit(2);
      return;
    }

    switch (role) {
      case "web" -> web(config, libraries, store, http);
      case "worker" -> worker(config, libraries, store);
      default -> {
        LOG.error("unknown role '{}': expected web or worker", role);
        System.exit(2);
      }
    }
  }

  /**
   * The message, plus the cause's where there is one.
   *
   * <p>"could not read translation file /etc/iiif/i18n/de.json" alone does not say what is wrong
   * with it, and the reason is always in the cause.
   */
  private static String message(Throwable e) {
    String own = e.getMessage() == null ? e.toString() : e.getMessage();
    Throwable cause = e.getCause();
    return cause == null
        ? own
        : own + " — " + (cause.getMessage() == null ? cause : cause.getMessage());
  }

  private static void web(
      Config config, Libraries libraries, Optional<SizeStore> store, HttpClient http) {
    Manifests manifests = new Manifests(config, libraries, store);
    LibraryHealth health = new LibraryHealth(config, libraries, http);
    WebServer server = new WebServer(config, libraries, manifests, store, health);
    server.start();

    // Nothing is RESOLVED at startup, on purpose: resolving one library means adapter selection,
    // Solr topology detection, an Image API probe and a licence fetch against somebody else's
    // server, and doing that for all fifty-odd in the registry is ~150 upstream requests before the
    // gateway has served anything. A library is resolved when first asked for, and cached after.
    //
    // The health checker is the deliberately cheaper thing: one query per library, spaced out, in
    // the background, so an operator learns that three libraries are unreachable from /health
    // rather than from a user's failed request.
    health.start();

    shutdown(
        () -> {
          health.close();
          server.close();
        },
        store);
  }

  private static void worker(Config config, Libraries libraries, Optional<SizeStore> store) {
    if (store.isEmpty()) {
      LOG.error("the worker role needs IIIF_DB_URL: there is nothing to consume or record");
      System.exit(2);
      return;
    }
    Worker worker = new Worker(config, libraries, store.get());
    worker.start();
    shutdown(worker::close, store);
  }

  private static void shutdown(Runnable stop, Optional<SizeStore> store) {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  LOG.info("shutting down");
                  stop.run();
                  store.ifPresent(
                      s -> {
                        if (s instanceof AutoCloseable closeable) {
                          try {
                            closeable.close();
                          } catch (Exception ignored) {
                            // Already shutting down; a failed close changes nothing.
                          }
                        }
                      });
                }));
  }
}
