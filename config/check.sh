#!/usr/bin/env bash
#
# Check a library file against libraries.schema.json, before the gateway does.
#
# The gateway validates this file at startup and REFUSES TO START if it is wrong — deliberately, since
# a broken library file is the list of things the process exists to serve. That is the right behaviour
# and a bad time to find out. This is the same check, on a laptop, in under a second.
#
# It catches the class of mistake the gateway cannot: a MISSPELLED KEY. `{"rate": 4}` instead of
# `{"rps": 4}` parses, loads, starts, serves — and paces that library at the global rate forever, with
# nothing in the log to say why. The schema sets additionalProperties:false, so the typo is an error
# here even though it is silence there.
#
#     config/check.sh                     # check config/libraries.json
#     config/check.sh /etc/iiif/mine.json
#
set -euo pipefail

here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
schema="$here/libraries.schema.json"
file="${1:-$here/libraries.json}"

die() { printf '%s\n' "$*" >&2; exit 1; }

[ -r "$file" ]   || die "no such file: $file"
[ -r "$schema" ] || die "no schema beside it: $schema"
command -v python3 >/dev/null || die "python3 is needed to read JSON"

python3 - "$schema" "$file" <<'PY'
import json, sys

schema_path, file_path = sys.argv[1], sys.argv[2]

try:
    with open(file_path, encoding="utf-8") as f:
        document = json.load(f)
except json.JSONDecodeError as e:
    sys.exit(f"{file_path} is not valid JSON: line {e.lineno}, column {e.colno}: {e.msg}")

with open(schema_path, encoding="utf-8") as f:
    schema = json.load(f)

try:
    import jsonschema
except ImportError:
    # Without the library, check the part that actually bites: unknown keys and wrong types. This is
    # narrower than the schema, not different from it — it is the same rules, hand-applied.
    print("jsonschema not installed; checking keys and types only", file=sys.stderr)
    allowed = set(schema["$defs"]["library"]["properties"])
    problems = []
    libraries = document.get("libraries")
    if not isinstance(libraries, dict):
        sys.exit(f'{file_path} has no "libraries" object')
    for code, entry in libraries.items():
        if code.startswith("_"):
            continue
        if not isinstance(entry, dict):
            problems.append(f"{code}: should be an object, got {type(entry).__name__}")
            continue
        for key in entry:
            if key not in allowed:
                near = [a for a in allowed if a.startswith(key[:2])]
                hint = f" — did you mean {near[0]!r}?" if near else ""
                problems.append(f"{code}: unknown field {key!r}{hint}")
        if "rps" in entry and not isinstance(entry["rps"], int):
            problems.append(f'{code}: "rps" must be a whole number, got {entry["rps"]!r}')
        if "name" in entry and not isinstance(entry["name"], dict):
            problems.append(f'{code}: "name" must be an object of language tag to name')
        catalogue = entry.get("catalogue")
        if catalogue is not None:
            if not isinstance(catalogue, dict):
                problems.append(f'{code}: "catalogue" must be an object')
            else:
                url = catalogue.get("url")
                if not isinstance(url, str) or "{id}" not in url:
                    problems.append(f'{code}: catalogue url must contain {{id}}')
                for i, rule in enumerate(catalogue.get("rules") or []):
                    pattern = rule.get("pattern") if isinstance(rule, dict) else None
                    if not isinstance(pattern, str) or "(" not in pattern:
                        # Group 1 is where the record number comes from; without it the rule cannot
                        # be applied, and the gateway rejects it at startup.
                        problems.append(
                            f"{code}: catalogue rule {i} has no capturing group: {pattern!r}"
                        )
        for field in ("url", "version"):
            if field in entry and not isinstance(entry[field], str):
                problems.append(f'{code}: "{field}" must be a string')
    if problems:
        sys.exit("\n".join(f"  {p}" for p in problems))
else:
    validator = jsonschema.Draft202012Validator(schema)
    errors = sorted(validator.iter_errors(document), key=lambda e: list(e.absolute_path))
    if errors:
        for error in errors:
            where = "/".join(str(p) for p in error.absolute_path) or "(root)"
            print(f"  {where}: {error.message}", file=sys.stderr)
        sys.exit(1)

count = sum(1 for code in document.get("libraries", {}) if not code.startswith("_"))
# Not an error. An empty overlay is the shipped state and the ordinary one — the registry supplies the
# libraries, and this file carries only what it cannot. Saying so beats a bare "ok" that leaves the
# reader wondering whether it checked anything.
if count == 0:
    print(f"{file_path}: valid, no local settings declared")
else:
    print(f"{file_path}: valid, {count} librar{'y' if count == 1 else 'ies'} with local settings")

# What the schema cannot know, because it depends on the environment rather than the file.
incomplete = [
    code
    for code, entry in document.get("libraries", {}).items()
    if not code.startswith("_") and not (entry.get("url") and entry.get("version"))
]
if incomplete:
    print(
        "  note: "
        + ", ".join(incomplete)
        + " state no url and version, so they need IIIF_REGISTRY_URL to fill in.",
        file=sys.stderr,
    )
PY
