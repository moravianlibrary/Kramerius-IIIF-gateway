#!/usr/bin/env python3
"""
Generate config/libraries.json — the static library list, from the registry that already knows.

WHY A FILE AT ALL, when registry mode serves every library with no configuration. Because the registry
is a runtime dependency on somebody else's service, and a deployment that wants a fixed, reviewable set
of libraries — a staging server, an air-gapped instance, a deployment that serves ten of the fifty-six —
had only IIIF_LIBRARIES, a single comma-separated environment variable. At fifty-six entries that value
is four kilobytes of one line with no comments and no diff worth reading.

WHY GENERATED rather than hand-written. The version field selects the adapter, so a stale one is a real
failure: a library upgraded 7.0 -> 7.2 keeps being read by the old adapter until someone edits the file.
Registry mode never has that problem, because the health checker re-reads the version on its own
schedule. A generated file does not fix the staleness — nothing in a static file can — but it makes the
fix one command, and `git diff` then shows exactly which libraries moved.

WHY IT PROBES. The registry's `alive` flag is the registry's opinion, formed from wherever it runs. Five
libraries it calls alive cannot be reached from outside at all — expired or incomplete TLS chains,
mostly — and one answers 401. Writing those into the config would produce a gateway that advertises
libraries it cannot serve, so each instance gets one cheap Solr query and has to answer it. What the
probe learns, including the refusals, is written into the file: an operator whose library is missing
should find out why from the file rather than from a timeout.

    scripts/libraries.py            # rewrite config/libraries.json
    scripts/libraries.py --dry-run  # print the summary, write nothing

Run it after a Kramerius release round, and review the diff.
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
OUT = REPO / "config" / "libraries.json"

REGISTRY_URL = "https://api.registr.digitalniknihovna.cz/api/libraries"
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


def probe(library):
    """
    One cheap Solr query, asking the question the gateway asks first.

    rows=0: we want the status code and nothing else, and a library with 76M documents should not
    have to serialise one of them to tell us it is up.
    """
    url = library["url"].rstrip("/")
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
    parser.add_argument("--registry", default=REGISTRY_URL)
    args = parser.parse_args()

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
    if OUT.exists():
        try:
            previous = json.loads(OUT.read_text(encoding="utf-8")).get("libraries", {})
        except (OSError, json.JSONDecodeError) as error:
            print(f"  could not read the existing {OUT.name}: {error}", file=sys.stderr)

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
            "GENERATED by scripts/libraries.py — `make libraries` rewrites it. Hand edits survive",
            "only until the next run, so make them in the script.",
            "",
            "url:     the Kramerius API base, no trailing slash.",
            "version: selects the adapter, so it is the field that goes stale and matters. Registry",
            "         mode re-reads it on a schedule; a file cannot, which is the cost of not",
            "         depending on the registry. Regenerate after a Kramerius release round.",
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
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(rendered, encoding="utf-8")
        print(f"wrote {OUT.relative_to(REPO)}", file=sys.stderr)

    print(f"{len(served)} served, {len(excluded)} excluded", file=sys.stderr)
    for code, reason in sorted(excluded.items()):
        print(f"  {code:<10} {reason}", file=sys.stderr)


if __name__ == "__main__":
    main()
