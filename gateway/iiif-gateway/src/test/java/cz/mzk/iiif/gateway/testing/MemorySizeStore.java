/*
 * A SizeStore in a HashMap, so route-level tests can exercise the paths that only exist when a size
 * cache is present — the partial-manifest branch above all, which is unreachable without one.
 *
 * Not a mock. It is the real behaviour of the two operations the web role uses (find, put) plus the
 * job table reduced to a set, which is all `enqueue` promises: at most one job per document. The
 * durability properties are Postgres' and are tested against Postgres in PostgresSizeStoreIT.
 */
package cz.mzk.iiif.gateway.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import cz.mzk.iiif.document.ImageSize;
import cz.mzk.iiif.gateway.store.SizeStore;

public final class MemorySizeStore implements SizeStore {

  private final Map<String, Document> documents = new ConcurrentHashMap<>();
  private final Set<String> queued = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
  private final Map<String, Sync> syncs = new ConcurrentHashMap<>();

  private static String key(String library, String pid) {
    return library + "/" + pid;
  }

  @Override
  public Optional<Document> find(String library, String pid) {
    return Optional.ofNullable(documents.get(key(library, pid)));
  }

  @Override
  public void put(String library, String pid, Map<String, ImageSize> sizes) {
    documents.put(key(library, pid), new Document(sizes, Instant.now()));
  }

  @Override
  public void evict(String library, String pid) {
    documents.remove(key(library, pid));
  }

  @Override
  public void enqueue(String library, String pid) {
    queued.add(key(library, pid));
  }

  @Override
  public List<Job> claim(int limit) {
    List<Job> claimed = new ArrayList<>();
    synchronized (queued) {
      for (String key : new ArrayList<>(queued)) {
        if (claimed.size() >= limit) {
          break;
        }
        queued.remove(key);
        int slash = key.indexOf('/');
        claimed.add(new Job(key.substring(0, slash), key.substring(slash + 1), 1));
      }
    }
    return claimed;
  }

  @Override
  public void complete(String library, String pid) {
    queued.remove(key(library, pid));
  }

  @Override
  public void fail(String library, String pid, String error) {
    queued.remove(key(library, pid));
  }

  @Override
  public Optional<Sync> sync(String library) {
    return Optional.ofNullable(syncs.get(library));
  }

  @Override
  public List<Sync> allSyncs() {
    return List.copyOf(syncs.values());
  }

  @Override
  public void saveSync(Sync sync) {
    syncs.put(sync.library(), sync);
  }

  @Override
  public boolean awaitJob(Duration timeout) {
    return !queued.isEmpty();
  }

  /** What the web role enqueued, so a test can assert the worker was handed the remainder. */
  public Set<String> queued() {
    return Set.copyOf(queued);
  }
}
