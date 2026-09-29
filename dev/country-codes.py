#!/usr/bin/env python3
"""
Generate the MARC country-code list that `SolrRecords.places()` filters with.

WHY A LIST AT ALL. `publication_places.facet` mixes MARC country codes with place names — a Prague
monograph is indexed ["Xr", "Praha"] — so something has to tell them apart. The first rule was a shape
heuristic, `[A-Z][a-z]{1,2}`, which is exact on the common values and wrong in the tail: measured over
all 6 394 distinct values at MZK it ate sixteen real places, including Ulm, Dux, Rom, Vie, Zug, Ams,
Brn and Augsburg's "Aug". A place is not a code because it is short.

WHY GENERATED rather than typed. The authority is the Library of Congress, the list moves (Curaçao and
South Sudan were added after 2010), and 334 entries is past the size where a hand-kept copy stays right.

WHY IT ALSO SCANS THE LIBRARIES. The current LoC list is not sufficient: catalogues hold codes LoC has
since deprecated, and a deprecated code is still a code, not a place. "Xxr" (Soviet Union, 12 documents)
and "Yu" (Yugoslavia, 2) both appear at MZK and both resolve at id.loc.gov as deprecated. Enumerating
every deprecated code is not possible from the published vocabulary — the deprecated collection does not
list its members — so the script works the other way round: take the code-shaped values the libraries
actually hold, ask id.loc.gov about each one, and keep the ones it recognises. Anything LoC has never
heard of is a place name and stays.

    dev/country-codes.py            # rewrite the resource
    dev/country-codes.py --dry-run  # print the summary, write nothing

Run it when a library reports a country code printed as a place, and review the diff.
"""

import argparse
import concurrent.futures
import json
import pathlib
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
OUT = (
    REPO
    / "gateway"
    / "iiif-kramerius-rest"
    / "src"
    / "main"
    / "resources"
    / "cz"
    / "mzk"
    / "iiif"
    / "adapter"
    / "marc-country-codes.txt"
)

VOCABULARY = "https://id.loc.gov/vocabulary/countries"
REGISTRY_URL = "https://registr.digitalniknihovna.cz/api/libraries?archived=false"
USER_AGENT = (
    "Kramerius-IIIF-gateway/dev "
    "(+https://github.com/moravianlibrary/Kramerius-IIIF-gateway)"
)
TIMEOUT = 30

# What could be a code rather than a place: two or three ASCII letters, nothing else. Deliberately
# WIDER than the old heuristic — this only selects candidates to ASK about, it does not decide.
CANDIDATE = re.compile(r"^[A-Za-z]{2,3}$")

# Not a place and not a country code: sine loco, the bibliographic marker for "no place given".
SINE_LOCO = {"s.l", "s.l."}

# Relaxed TLS for the same reason libraries.py needs it: several instances have incomplete chains,
# and refusing to read their facets would silently narrow the scan.
LAX = ssl.create_default_context()
LAX.check_hostname = False
LAX.verify_mode = ssl.CERT_NONE


def get(url, timeout=TIMEOUT, accept="application/json"):
    request = urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT, "Accept": accept}
    )
    with urllib.request.urlopen(request, timeout=timeout, context=LAX) as response:
        return response.read()


def current_codes():
    """The published vocabulary: every code LoC currently lists."""
    nodes = json.loads(get(f"{VOCABULARY}.json", timeout=120))
    codes = {}
    for node in nodes:
        match = re.match(rf"^https?://id\.loc\.gov/vocabulary/countries/([a-z-]+)$", node.get("@id", ""))
        if not match:
            continue
        label = None
        for key, value in node.items():
            if key.endswith("#authoritativeLabel") and value:
                label = value[0].get("@value")
        codes[match.group(1)] = label
    return codes


def facet_values(library):
    """Every distinct publication place one library holds, with no row limit."""
    path = "/search/api/client/v7.0/search"
    query = urllib.parse.urlencode(
        {
            "q": "*:*",
            "rows": 0,
            "wt": "json",
            "facet": "true",
            "facet.field": "publication_places.facet",
            "facet.limit": 20000,
            "facet.mincount": 1,
        }
    )
    body = json.loads(get(f"{library['url']}{path}?{query}", timeout=180))
    flat = body["facet_counts"]["facet_fields"]["publication_places.facet"]
    return {flat[i]: flat[i + 1] for i in range(0, len(flat), 2)}


def is_known_to_loc(code):
    """Ask the authority. A deprecated code answers 200; a place name answers 404."""
    try:
        get(f"{VOCABULARY}/{code}", timeout=TIMEOUT)
        return True
    except urllib.error.HTTPError:
        return False
    except Exception:
        # A timeout is not a "no". Leaving it out keeps a place visible, which is the safe failure.
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    print(f"reading {VOCABULARY}", file=sys.stderr)
    codes = current_codes()
    print(f"  {len(codes)} current codes", file=sys.stderr)

    # K7 only. K5 indexes no publication place at all — `publication_places.facet` is not in its
    # schema and the query 400s — so there is nothing there to scan and nothing there to filter.
    # From the registry, not from config/libraries.json — that file is the local overlay now and
    # carries no urls. The registry is the better source anyway: this wants EVERY K7 library, not
    # the subset some deployment happens to configure.
    libraries = [
        library
        for library in json.loads(get(REGISTRY_URL))
        if str(library.get("version", "")).startswith("7") and library.get("url")
    ]
    counts = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        futures = {pool.submit(facet_values, lib): lib for lib in libraries}
        for future in concurrent.futures.as_completed(futures):
            library = futures[future]
            try:
                for value, count in future.result().items():
                    counts[value] = counts.get(value, 0) + count
            except Exception as error:
                print(f"  {library['code']}: {error}", file=sys.stderr)
    print(f"  {len(counts)} distinct place values across the libraries", file=sys.stderr)

    unknown = sorted(
        value
        for value in counts
        if CANDIDATE.match(value)
        and value.lower() not in codes
        and value.lower() not in SINE_LOCO
    )
    print(f"  {len(unknown)} code-shaped values not in the current list", file=sys.stderr)

    deprecated = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        for value, known in zip(unknown, pool.map(is_known_to_loc, [v.lower() for v in unknown])):
            if known:
                deprecated[value.lower()] = None
                print(f"    {value}: deprecated code ({counts[value]} docs)", file=sys.stderr)

    everything = dict(sorted({**codes, **deprecated}.items()))
    lines = [
        "# MARC country codes. Generated by dev/country-codes.py — do not edit.",
        f"# {len(codes)} current codes from {VOCABULARY}",
        f"# plus {len(deprecated)} deprecated codes found in the libraries and confirmed at id.loc.gov.",
        "# Compared case-insensitively; Kramerius indexes them capitalised (Xr, not xr).",
    ]
    lines += [
        code if label is None else f"{code}  # {label}" for code, label in everything.items()
    ]
    text = "\n".join(lines) + "\n"

    if args.dry_run:
        print(f"would write {len(everything)} codes to {OUT}", file=sys.stderr)
        return

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {len(everything)} codes to {OUT}", file=sys.stderr)


if __name__ == "__main__":
    main()
