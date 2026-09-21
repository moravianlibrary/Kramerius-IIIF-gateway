#!/usr/bin/env python3
"""
The comparison corpus: a spread of real documents, and what each gateway says about them.

Why this exists. v2 is allowed to change the output — see docs/BEHAVIOUR.md — but "allowed to change"
is not "changed in whatever way happened to fall out". Every difference from the live gateway should be
one we chose, so this captures both sides and lets them be diffed document by document.

The pid list is committed (corpus/pids.json); the manifests are not. They run to megabytes — one MZK
periodical issue has 3568 canvases — and the live gateway is still up, so re-fetching costs nothing and
committing them would bloat the history for no benefit.

    scripts/corpus.py pick      re-choose the pids, refreshing corpus/pids.json
    scripts/corpus.py fetch     download current-gateway manifests to target/corpus/current/
    scripts/corpus.py report    summarise what was captured

Once iiif-kramerius-rest exists, `diff` joins these: generate the same documents from the new gateway
and compare field by field.
"""

import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
PIDS = REPO / "corpus" / "pids.json"
OUT = REPO / "target" / "corpus"

LIBRARY = "mzk"
CURRENT_GATEWAY = "https://iiif.digitalniknihovna.cz"

TIMEOUT = 120

# Every model the gateway serves, then a spread of sizes, then the awkward ones. The labels are what
# a failure report shows, so they say why the document is in the list.
SELECTORS_K7 = [
    *[(f"model:{m}", f"model:{m}", None, None) for m in (
        "monograph", "monographunit", "manuscript", "map", "graphic", "sheetmusic",
        "oldprintsheetmusic", "archive", "periodical", "periodicalvolume", "periodicalitem",
        "supplement", "article", "internalpart", "picture", "soundrecording", "convolute",
        "collection")],
    ("size: 1 page", "model:monograph", "count_page:1", None),
    ("size: ~10 pages", "model:monograph", "count_page:[8 TO 12]", None),
    ("size: ~200 pages", "model:manuscript", "count_page:[190 TO 210]", None),
    ("size: ~900 pages", "model:monograph", "count_page:[900 TO 1100]", None),
    ("size: largest", "model:periodicalitem", "count_page:[* TO *]", "count_page desc"),
    ("pdf, no pages", "model:monograph", 'ds.img_full.mime:"application/pdf"', None),
    ("cartographic", "model:map", "coords.bbox.corner_sw:[* TO *]", None),
    ("no tiles", "model:monograph", "has_tiles:false", None),
    ("licensed", "model:monograph", "licenses:[* TO *]", None),
    ("catalogued", "model:monograph", "id_sysno:mzk03*", None),
]

# K5 SPEAKS A DIFFERENT INDEX, so it needs its own selectors rather than a translation layer: fedora.model
# for model, pages_count for count_page, viewable for has_tiles, dostupnost for accessibility,
# img_full_mime for the PDF check. Getting this wrong is silent — a K7 field name matches nothing on K5
# and the selector is skipped with a note, which reads like "this library has no monographs".
SELECTORS_K5 = [
    *[(f"model:{m}", f"fedora.model:{m}", None, None) for m in (
        "periodical", "periodicalvolume", "periodicalitem", "supplement")],
    ("size: ~10 pages", "fedora.model:periodicalitem", "pages_count:[8 TO 12]", None),
    ("size: largest", "fedora.model:periodicalitem", "pages_count:[* TO *]", "pages_count desc"),
    ("public", "fedora.model:periodicalitem", "dostupnost:public", None),
]

# WHICH LIBRARIES ARE SAMPLED, and why these.
#
# `mzk` is the reference: every model, the size spread, the awkward cases. It is also the only library
# whose v1 output was ever compared field by field.
#
# `lmda` is here because K5 is a whole adapter that had never been in the corpus, and because the
# comparison is only meaningful where the OLD gateway can serve the document too — v1 does serve K5
# (iiif.rb carries five v5.0 paths and its last commit was a K5 fix), and lmda answers through it
# reliably where vugtk and nkp time out. It holds only the periodical family, which is the shape v2
# changed most for K5: labels, partOf, and whether an image service is declared at all.
#
# Adding another is a dict here. The fetch and report steps already key off each entry's own library.
LIBRARIES = [
    {
        "code": "mzk",
        "solr": "https://api.kramerius.mzk.cz/search/api/client/v7.0/search",
        "fields": "pid,model,count_page,accessibility",
        "pid": "pid",
        "model": "model",
        "pages": "count_page",
        "accessibility": "accessibility",
        "selectors": SELECTORS_K7,
    },
    {
        "code": "lmda",
        "solr": "https://lmda.silvarium.cz/search/api/v5.0/search",
        "fields": "PID,fedora.model,pages_count,dostupnost",
        "pid": "PID",
        "model": "fedora.model",
        "pages": "pages_count",
        "accessibility": "dostupnost",
        "selectors": SELECTORS_K5,
    },
]


def get(url):
    with urllib.request.urlopen(url, timeout=TIMEOUT) as response:
        return json.load(response)


def solr(endpoint, **params):
    # wt=json explicitly: K5's search API answers XML by default, and the K7 client API does not.
    params.setdefault("wt", "json")
    return get(f"{endpoint}?{urllib.parse.urlencode(params)}")["response"]["docs"]


# WHAT v1'S OWN LANDING PAGE ADVERTISED, frozen here rather than parsed.
#
# These matter more than a random sample: they are what anyone comparing v1 to v2 opens first, and they
# are the closest thing v1 had to a specification of its own behaviour. They used to be read out of
# README.md so they could not drift from what the service documented — but v2's README replaced that
# page, so the source no longer exists and parsing it silently returned nothing. Re-picking the corpus
# then dropped all fourteen without a word, which is how this was noticed.
#
# Two name the `mzkk7` library, whose host (k7-test.mzk.cz) has been unreachable for some time, so the
# documented examples for multi-volume monographs and virtual collections are already dead links. That
# is worth knowing before v2 is judged against them.
DOCUMENTED = [
    ('/full', 'mzk', 'uuid:ed255350-301b-11ed-be63-005056827e52'),
    ('Monograph', 'mzk', 'uuid:ca15a389-e5eb-4c19-b0d0-f919066ef2ba'),
    ('Multiple volume monograph', 'mzkk7', 'uuid:7e11fe20-043e-11e5-95ff-5ef3fc9bb22f'),
    ('Periodical title', 'mzk', 'uuid:ae81bb20-435d-11dd-b505-00145e5790ea'),
    ('Periodical volume', 'mzk', 'uuid:b2268013-435d-11dd-b505-00145e5790ea'),
    ('Periodical item', 'mzk', 'uuid:ee18e055-435d-11dd-b505-00145e5790ea'),
    ('Graphic', 'mzk', 'uuid:e8933b86-fecc-45cc-962c-d22e9271eee4'),
    ('Map', 'mzk', 'uuid:ba4934d1-0a1e-4a01-a89d-c948477ca833'),
    ('Sheet music', 'mzk', 'uuid:61f6fddf-27ca-4acc-a756-a2fedc6508b8'),
    ('Archival document', 'mzk', 'uuid:b85c556b-07e1-46c7-b342-9602009f1bd5'),
    ('Manuscript', 'mzk', 'uuid:5e0d749d-c560-4b8c-aae7-665cb952b655'),
    ('Sound recordings', 'mzk', 'uuid:4067ca61-96f7-48b6-9fa3-8a127a4df5e6'),
    ('PDF - does not generate items, just meta', 'mzk', 'uuid:27055693-9413-43d3-8e61-d5048b14dd16'),
    ('Virtual collection (K7 only)', 'mzkk7', 'uuid:c524ed10-7ebb-4a7f-8b5b-e0a160c52d2c'),
]


def documented_examples():
    return [
        {"label": f"documented: {label[:40]}", "pid": pid, "library": library,
         "model": None, "pages": None}
        for label, library, pid in DOCUMENTED
    ]


def pick():
    chosen, seen = [], set()

    for library in LIBRARIES:
        code = library["code"]
        print(f"{code}:", file=sys.stderr)
        for label, query, filter_query, sort in library["selectors"]:
            params = {"q": query, "fl": library["fields"], "rows": 1}
            if filter_query:
                params["fq"] = filter_query
            if sort:
                params["sort"] = sort
            try:
                docs = solr(library["solr"], **params)
            except urllib.error.URLError as error:
                print(f"  ! {label}: {error}", file=sys.stderr)
                continue
            if not docs:
                print(f"  - {label}: nothing matched at {code}", file=sys.stderr)
                continue
            doc = docs[0]
            pid = doc.get(library["pid"])
            if not pid or pid in seen:
                continue
            seen.add(pid)
            chosen.append({
                "label": label,
                "pid": pid,
                # Carried per entry, because the corpus is no longer one library. fetch() and
                # report() already read it; only pick() had assumed.
                "library": code,
                "model": doc.get(library["model"]),
                "pages": doc.get(library["pages"]),
                "accessibility": doc.get(library["accessibility"]),
            })

    # The landing page's own examples, kept distinct from the sampled ones.
    for example in documented_examples():
        if example["pid"] not in seen:
            seen.add(example["pid"])
            chosen.append(example)

    PIDS.parent.mkdir(parents=True, exist_ok=True)
    PIDS.write_text(
        json.dumps({"library": LIBRARY, "documents": chosen}, indent=2, ensure_ascii=False) + "\n")
    counts = {}
    for entry in chosen:
        counts[entry.get("library") or LIBRARY] = counts.get(entry.get("library") or LIBRARY, 0) + 1
    print(f"{len(chosen)} documents {counts} -> {PIDS.relative_to(REPO)}")


def load():
    if not PIDS.exists():
        sys.exit(f"no pid list yet — run: {sys.argv[0]} pick")
    return json.loads(PIDS.read_text())


def fetch():
    corpus = load()
    target = OUT / "current"
    target.mkdir(parents=True, exist_ok=True)

    for entry in corpus["documents"]:
        pid = entry["pid"]
        path = target / f"{(entry.get('library') or corpus['library'])}_{pid.replace(':', '_')}.json"
        if path.exists():
            continue
        url = f"{CURRENT_GATEWAY}/{entry.get('library') or corpus['library']}/{pid}"
        # The live gateway spawns a Ruby process per request and intermittently 502s under sustained
        # load, so one failure means nothing. A document that fails three times with a pause between
        # is genuinely broken; anything less is the service being tired.
        body, failure = None, None
        for attempt in range(3):
            try:
                body = get(url)
                break
            except (urllib.error.HTTPError, urllib.error.URLError) as error:
                failure = getattr(error, "code", None) or getattr(error, "reason", error)
                time.sleep(2 * (attempt + 1))
        if body is None:
            print(f"  ! {entry['label']:22} failed 3x: {failure}")
            continue
        path.write_text(json.dumps(body, indent=2, ensure_ascii=False) + "\n")
        size = path.stat().st_size
        # The live gateway answers 200 with an {"error": …} body; that is one of the defects v2 fixes.
        note = "  ERROR BODY" if isinstance(body, dict) and "error" in body else ""
        print(f"  {entry['label']:22} {size / 1024:8.1f} KiB{note}")

    print(f"\n-> {target.relative_to(REPO)}")


def report():
    corpus = load()
    current = OUT / "current"
    print(f"{'label':24} {'model':18} {'pages':>6}  current gateway")
    for entry in corpus["documents"]:
        path = current / (
            f"{entry.get('library') or corpus['library']}_{entry['pid'].replace(':', '_')}.json")
        if not path.exists():
            state = "not fetched"
        else:
            body = json.loads(path.read_text())
            if "error" in body:
                state = f"error: {str(body['error'])[:40]}"
            else:
                items = body.get("items", [])
                state = f"{body.get('type', '?')}, {len(items)} items"
        print(f"{entry['label']:24} {entry['model'] or '?':18} {str(entry['pages'] or '-'):>6}  {state}")


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else "report"
    {"pick": pick, "fetch": fetch, "report": report}.get(command, report)()
