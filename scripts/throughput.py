#!/usr/bin/env python3
"""
What the gateway can serve when it is not waiting for anybody: throughput from a warm manifest cache.

WHY THIS AND NOT A LOAD-TESTING FRAMEWORK. Load-testing the cold path would mean pointing thousands of
requests at somebody else's Kramerius, which is the one thing this project's whole politeness apparatus
exists to prevent — so it would need a stub upstream, a synthetic corpus, and a day of work. The WARM
path needs none of that: a manifest-cache hit never touches Kramerius at all. The upstream is not
involved, so there is nobody to be rude to, and what is measured is our own serialisation, our own
locking and our own GC rather than somebody's network.

WHAT IT ACTUALLY EXERCISES, beyond a number:

  - the single-flight lock in Manifests.inFlight, which is a correctness mechanism under concurrency and
    was previously only reasoned about. Every worker asking for the SAME document at once is the
    stampede it exists to prevent.
  - manifest-cache behaviour against its byte ceiling, when the working set is larger than the cache.
  - the conditional path: a 304 costs an ETag comparison and no body, and real clients revalidate.

WHAT IT IS NOT. Not an endurance test — that is a long sync run, and a different question. Not a test of
the cold path, deliberately. Not part of `make check`: it needs a running gateway.

    scripts/throughput.py --base http://localhost:8080 --library mzk
    scripts/throughput.py --concurrency 32 --requests 2000 --same-document

RUN THE GATEWAY WITH IIIF_RATE_LIMIT_RPS=0. Otherwise the inbound limiter answers most of the run with
429 and what gets measured is the throttle, not the cache. The script says so if it sees one.

Standard library only, like every other script here.
"""

import argparse
import concurrent.futures
import json
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = (
    "Kramerius-IIIF-gateway/dev "
    "(+https://github.com/moravianlibrary/Kramerius-IIIF-gateway)"
)


def request(url, etag=None, timeout=120):
    """One GET. Returns (status, bytes, seconds, etag)."""
    headers = {"User-Agent": USER_AGENT}
    if etag:
        headers["If-None-Match"] = etag
    req = urllib.request.Request(url, headers=headers)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            body = response.read()
            return response.status, len(body), time.perf_counter() - started, response.headers.get("ETag")
    except urllib.error.HTTPError as error:
        # 304 arrives here, and it is the interesting case rather than a failure.
        error.read()
        return error.code, 0, time.perf_counter() - started, error.headers.get("ETag")


def health(base):
    try:
        with urllib.request.urlopen(
            urllib.request.Request(f"{base}/health", headers={"User-Agent": USER_AGENT}), timeout=30
        ) as response:
            return json.load(response)
    except Exception as error:  # noqa: BLE001 - reported, not handled
        return {"error": str(error)}


def pids_from_corpus(path, library):
    """The comparison corpus doubles as a document list: real pids, real sizes, already chosen."""
    data = json.loads(path.read_text())
    entries = data["documents"] if "documents" in data else next(
        value for value in data.values() if isinstance(value, list)
    )
    return [
        entry["pid"]
        for entry in entries
        if entry.get("pid") and entry.get("library", library) == library
    ]


def percentiles(samples):
    ordered = sorted(samples)
    def at(fraction):
        return ordered[min(len(ordered) - 1, int(len(ordered) * fraction))]
    return at(0.50), at(0.95), at(0.99), ordered[-1]


def main():
    import pathlib

    repo = pathlib.Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--library", default="mzk")
    parser.add_argument("--concurrency", type=int, default=16)
    parser.add_argument("--requests", type=int, default=500)
    parser.add_argument("--pids", nargs="*", help="override the corpus document list")
    parser.add_argument(
        "--same-document",
        action="store_true",
        help="every request for ONE document — the single-flight case",
    )
    parser.add_argument(
        "--revalidate",
        action="store_true",
        help="send If-None-Match and expect 304s, which is what a real client does",
    )
    args = parser.parse_args()

    pids = args.pids or pids_from_corpus(repo / "corpus" / "pids.json", args.library)
    if not pids:
        sys.exit(f"no pids for {args.library}; pass --pids")
    if args.same_document:
        pids = pids[:1]

    urls = [f"{args.base}/{args.library}/{urllib.parse.quote(pid, safe=':')}" for pid in pids]

    # WARM FIRST, and report what did not warm. A manifest still waiting on the worker is `no-store`
    # and never enters the cache, so measuring it would be measuring the cold path by accident.
    print(f"warming {len(urls)} document(s) from {args.base} …", file=sys.stderr)
    etags = {}
    warmed = 0
    for url in urls:
        status, size, seconds, etag = request(url)
        if status != 200:
            print(f"  {status} {url}", file=sys.stderr)
            continue
        warmed += 1
        if etag:
            etags[url] = etag
        print(f"  {seconds:6.2f}s {size / 1024:8.1f} KB  {'etag' if etag else 'NO ETAG (not cacheable)'}",
              file=sys.stderr)

    if not warmed:
        sys.exit("nothing warmed; is the gateway serving this library?")

    before = health(args.base).get("manifestCache", {})
    print(f"\nmanifest cache after warming: {before}", file=sys.stderr)
    print(
        f"measuring: {args.requests} requests, {args.concurrency} concurrent, "
        f"{'one document' if args.same_document else f'{len(urls)} documents'}"
        f"{', revalidating' if args.revalidate else ''}\n",
        file=sys.stderr,
    )

    def one(index):
        url = urls[index % len(urls)]
        return request(url, etags.get(url) if args.revalidate else None)

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        results = list(pool.map(one, range(args.requests)))
    elapsed = time.perf_counter() - started

    latencies = [seconds for _, _, seconds, _ in results]
    total_bytes = sum(size for _, size, _, _ in results)
    codes = {}
    for status, _, _, _ in results:
        codes[status] = codes.get(status, 0) + 1

    after = health(args.base).get("manifestCache", {})
    p50, p95, p99, worst = percentiles(latencies)
    print(f"  {args.requests} requests in {elapsed:.2f}s")
    print(f"  {args.requests / elapsed:,.0f} req/s   {total_bytes / elapsed / 1024 / 1024:.1f} MB/s")
    print(f"  latency  p50 {p50 * 1000:.1f} ms   p95 {p95 * 1000:.1f} ms   p99 {p99 * 1000:.1f} ms   max {worst * 1000:.1f} ms")
    print(f"  statuses {codes}")
    print(f"  manifest cache: {before} -> {after}")

    # THE INBOUND LIMITER WILL DOMINATE THIS unless it is raised, and the first run of this script
    # spent 279 of 400 requests being told 429. That is the gateway working — IIIF_RATE_LIMIT_RPS
    # defaults to 20 with a burst of 100 — but it measures the limiter rather than the cache, and the
    # number printed above would be a throttle speed reported as a throughput.
    if codes.get(429):
        print(
            f"\n  {codes[429]} of {args.requests} requests were rate-limited: the number above is the"
            "\n  INBOUND LIMITER, not the cache. Re-run the gateway with IIIF_RATE_LIMIT_RPS=0."
        )

    # A cache that grew during the measurement means something was not served warm, which makes the
    # number a mixture. Only claim that when the health call actually answered — it is rate-limited
    # like everything else, and a throttled health check used to report itself as a cache change.
    elif after and before.get("entries") != after.get("entries"):
        print("\n  NOTE: the cache changed size while measuring — some requests were not warm.")
    elif not after:
        print("\n  NOTE: /health did not answer, so warmth could not be confirmed.")


if __name__ == "__main__":
    main()
