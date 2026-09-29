#!/usr/bin/env python3
"""
Write a COMPLETE library list from the registry, for a deployment that must not depend on it.

NOT config/libraries.json. That file is the local OVERLAY — the handful of things the registry has no
field for — and it deliberately does not restate what the registry already knows. This script produces
the other thing: a full snapshot of url, version and name for all the libraries that answer, so a
staging server, an air-gapped instance or a deployment serving ten of the fifty-six can run with
IIIF_REGISTRY_URL unset.

The version it writes is a starting value, not a pin. The gateway asks each instance for its own at
/info and prefers that answer, so a library that upgrades is followed without regenerating anything.

WHY IT PROBES. The registry's `alive` flag is its own opinion, formed from wherever it runs, and it is
not always right in either direction — it called `svkos` dead for eight months while the instance served
perfectly well. So each instance gets one cheap Solr query and has to answer it. What the probe learns,
including the refusals, is written into the file: an operator whose library is missing should find out
why from the file rather than from a timeout.

A CAVEAT WORTH READING BEFORE YOU COMMIT THE OUTPUT: the list is what answered at the moment it ran. A
library having a bad five minutes is dropped, silently, and looks in the diff exactly like one that was
retired. Review the diff, and do not regenerate just because it has been a while.

    dev/libraries.py                    # write config/libraries-snapshot.json
    dev/libraries.py --snapshot out.json
    dev/libraries.py --dry-run          # print it, write nothing

Run it when you need a registry-free deployment, and review the diff.
"""

import argparse
import concurrent.futures
import json
import pathlib
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_SNAPSHOT = REPO / "config" / "libraries-snapshot.json"

REGISTRY_URL = "https://registr.digitalniknihovna.cz/api/libraries?archived=false"
USER_AGENT = (
    "Kramerius-IIIF-gateway/dev "
    "(+https://github.com/moravianlibrary/Kramerius-IIIF-gateway)"
)
TIMEOUT = 25

# The lowest Kramerius the adapters handle. AdapterRegistry.lowestSupported() is the authority; this
# mirrors it so the script does not write libraries the gateway would refuse at resolve time.
LOWEST_MAJOR = 5

# Institutions whose registry `name` is not Czech. Mirrors Registry.NAME_LANGUAGE — a Slovak name
# tagged `cs` is a false claim a viewer acts on, and the tag has to survive into the file.
NAME_LANGUAGE = {"dikda": "sk", "snk": "sk", "cvti": "sk", "eod": "de"}


def fetch(url, timeout=60):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def search_path(version):
    """K7 moved the Solr endpoint under /client/. Same query, different place."""
    return (
        "/search/api/client/v7.0/search"
        if version.startswith("7")
        else "/search/api/v5.0/search"
    )


def info_version(url):
    """
    What the instance says it runs, which beats what the registry says it runs.

    Not because the registry is wrong — asked of all 55, the 47 that answer /info agree with it
    exactly. Because the registry is a cache refreshed on its own schedule, and this file is a cache
    of that cache, so asking the instance collapses two staleness windows into none. The two paths
    discriminate cleanly: a K7 404s the v5 path and a K5 404s the v7 path, so whichever answers is
    also evidence of the family.

    Returns None when the instance does not answer; the caller then keeps the registry's value,
    which is all it ever had.
    """
    for path in ("/search/api/client/v7.0/info", "/search/api/v5.0/info"):
        request = urllib.request.Request(url + path, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
                version = (json.load(response).get("version") or "").strip()
                if version:
                    return version
        except (OSError, urllib.error.HTTPError, json.JSONDecodeError):
            continue
    return None


def probe(library):
    """
    Ask the instance what it is, then one cheap Solr query at the place that answer implies.

    rows=0: we want the status code and nothing else, and a library with 76M documents should not
    have to serialise one of them to tell us it is up.

    Mutates `library["version"]` when the instance disagrees with the registry, so everything
    downstream — the Solr path here, the adapter the gateway will pick, the value written to the
    file — uses the one the instance vouched for.
    """
    url = library["url"].rstrip("/")
    reported = info_version(url)
    if reported and reported != library["version"]:
        print(
            f"  {library['code']}: registry says {library['version']}, instance says {reported}",
            file=sys.stderr,
        )
        library["version"] = reported

    query = urllib.parse.urlencode({"q": "*:*", "rows": 0, "wt": "json"})
    target = f"{url}{search_path(library['version'])}?{query}"
    request = urllib.request.Request(target, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            if response.status != 200:
                return f"Solr answered {response.status}"
            return None
    except urllib.error.HTTPError as e:
        return f"Solr answered {e.code}"
    except urllib.error.URLError as e:
        reason = e.reason
        if isinstance(reason, ssl.SSLCertVerificationError):
            return f"TLS: {reason.verify_message or reason.reason}"
        return f"unreachable: {reason}"
    except OSError as e:
        return f"unreachable: {e}"


def unsupported(library):
    """Why this library cannot go in the file, before anything is fetched from it."""
    if not library["alive"]:
        return "the registry reports it down"
    version = library.get("version") or ""
    major = version.split(".")[0]
    if not major.isdigit():
        return f"unreadable version {version!r}"
    if int(major) < LOWEST_MAJOR:
        return f"Kramerius {version} is below the oldest supported adapter ({LOWEST_MAJOR}.x)"
    return None


def names(library):
    """
    The institution's name, by language tag.

    Its own language first — which is Slovak for the SNK libraries and German for eod — then English
    where the registry has one. A code is not a name, so a library with neither gets no `name` key and
    the gateway logs about it at startup rather than inventing one here.
    """
    result = {}
    own = (library.get("name") or "").strip()
    if own:
        result[NAME_LANGUAGE.get(library["code"], "cs")] = own
    english = (library.get("name_en") or "").strip()
    if english:
        result["en"] = english
    return result


def entry(library):
    built = {"url": library["url"].rstrip("/"), "version": library["version"]}
    declared = names(library)
    if declared:
        built["name"] = declared
    return built


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="print, write nothing")
    parser.add_argument(
        "--snapshot",
        default=DEFAULT_SNAPSHOT,
        type=pathlib.Path,
        help="where to write the complete list (default: config/libraries-snapshot.json)",
    )
    parser.add_argument("--registry", default=REGISTRY_URL)
    args = parser.parse_args()
    out = args.snapshot

    registry = sorted(fetch(args.registry), key=lambda library: library["code"])
    print(f"{len(registry)} libraries in the registry", file=sys.stderr)

    candidates, excluded = [], {}
    for library in registry:
        reason = unsupported(library)
        if reason:
            excluded[library["code"]] = reason
        else:
            candidates.append(library)

    # Concurrent because it is fifty independent institutions, and one slow instance should not set
    # the runtime of the whole survey. Modest, because none of them asked to be surveyed.
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        for library, failure in zip(candidates, pool.map(probe, candidates)):
            if failure:
                excluded[library["code"]] = failure

    # PER-LIBRARY RATES ARE CARRIED OVER, and that is the whole reason this block exists. `rps` is
    # the one field here a human sets rather than the registry supplies — it is what an operator
    # writes after an institution asks to be hit more gently — and a generator that dropped it would
    # silently return that library to the global rate on the next `make libraries`. The header says
    # hand edits do not survive; this is the documented exception, so it has to be real.
    previous = {}
    if out.exists():
        try:
            previous = json.loads(out.read_text(encoding="utf-8")).get("libraries", {})
        except (OSError, json.JSONDecodeError) as error:
            print(f"  could not read the existing {out.name}: {error}", file=sys.stderr)

    served = {}
    for library in candidates:
        code = library["code"]
        if code in excluded:
            continue
        row = entry(library)
        kept = previous.get(code, {}).get("rps")
        if kept is not None:
            row["rps"] = kept
            print(f"  {code}: keeping rps={kept}", file=sys.stderr)
        served[code] = row

    document = {
        "_comment": [
            "Libraries this gateway serves, for a deployment that does not want to depend on the",
            "shared registry at runtime. Point IIIF_LIBRARIES_FILE at this file.",
            "",
            "GENERATED by dev/libraries.py — `make libraries` rewrites it. Hand edits survive",
            "only until the next run, so make them in the script.",
            "",
            "url:     the Kramerius API base, no trailing slash.",
            "version: what the instance reported at /info when this was generated, NOT what the",
            "         registry said — the registry is wrong for three libraries, one of them by a",
            "         whole major version. It is a record, not an instruction: the gateway asks",
            "         /info itself at resolution and prefers that answer, so a library that",
            "         upgrades is followed without regenerating anything. This value is what it",
            "         falls back to for an instance that answers no /info at all.",
            "name:    the institution's name by language tag — its OWN language plus English. The",
            "         library code is not a name, and a name with no language tag is refused.",
            "rps:     OPTIONAL requests per second against this institution's image server,",
            "         overriding IIIF_UPSTREAM_RPS. The one field here a human sets rather than the",
            "         registry supplies, so regeneration CARRIES IT OVER rather than dropping it.",
            "         Omit it and the library gets the global rate; 0 disables pacing for it.",
            "",
            "_excluded records every registry library NOT in the list and why, so a library missing",
            "from a deployment is explained here rather than discovered as a timeout. It is ignored",
            "when the file is read.",
        ],
        "libraries": served,
        "_excluded": dict(sorted(excluded.items())),
    }

    rendered = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
    if args.dry_run:
        print(rendered)
    else:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(rendered, encoding="utf-8")
        print(f"wrote {out.relative_to(REPO)}", file=sys.stderr)

    print(f"{len(served)} served, {len(excluded)} excluded", file=sys.stderr)
    for code, reason in sorted(excluded.items()):
        print(f"  {code:<10} {reason}", file=sys.stderr)


if __name__ == "__main__":
    main()
