#!/usr/bin/env bash
#
# Probe every alive library in the registry for Solr field availability.
#
# This is how the *next* adapter boundary gets found: run it after a Kramerius release, and if a field
# set has changed, add an adapter whose floor is that version. The current boundaries are recorded in
# the adapter and schema implementations; this script is what produced them.
#
# Two things here are not incidental, and removing either makes the output silently wrong:
#
#   1. The probe query is NARROW (q=model:periodical, a few thousand docs). An earlier version used
#      q=*:*, which on MZK means 76M documents; the spatial and boolean filters then took longer than
#      the timeout, curl returned 000, and the script scored those as "field missing" — producing a
#      table that was wrong for exactly the fields we cared about.
#   2. An HTTP error (field rejected by Solr => absent from the schema) is distinguished from a
#      transport failure (timeout, DNS, TLS => we learned nothing). They are not the same answer.
#
# Redirects are followed: some registry URLs 301 to their real host, and a redirect is not evidence
# of anything about the schema.
#
# Usage:
#   dev/capabilities.sh                  # every alive K7 library
#   dev/capabilities.sh mzk ntk cbvk     # only these library codes
#   FIELDS="has_tiles licenses" dev/capabilities.sh

set -uo pipefail

REGISTRY_URL="${IIIF_REGISTRY_URL:-https://registr.digitalniknihovna.cz/api/libraries?archived=false}"
TIMEOUT="${TIMEOUT:-60}"

# Fields worth knowing about: everything an adapter's behaviour keys on.
FIELDS="${FIELDS:-has_tiles has_alto_content has_text_ocr_content page.placement page.index \
licenses accessibility count_page count_volume coords.bbox.corner_sw coords.bbox.center \
coords.is_point coords.point part.number.sort rels_ext_index.sort id_sysno track.length \
modified indexed foster_parents.pids own_parent.model}"

command -v python3 >/dev/null || { echo "python3 required" >&2; exit 1; }

# ─── Which libraries ──────────────────────────────────────────────────────────

registry=$(curl -sSL --max-time "$TIMEOUT" -H 'Accept: application/json' "$REGISTRY_URL") || {
  echo "could not fetch the registry at $REGISTRY_URL" >&2
  exit 1
}

libraries=$(printf '%s' "$registry" | python3 -c '
import json, sys
wanted = set(sys.argv[1:])
for lib in json.load(sys.stdin):
    code, version, url = lib.get("code"), lib.get("version") or "", lib.get("url")
    if not code or not url:
        continue
    if wanted:
        if code not in wanted:
            continue
    elif not lib.get("alive") or not version.startswith("7"):
        continue
    print(f"{code}\t{version}\t{url}")
' "$@" | sort -t$'\t' -k2,2V)

[ -n "$libraries" ] || { echo "no libraries matched" >&2; exit 1; }

# ─── Probe ────────────────────────────────────────────────────────────────────

# shellcheck disable=SC2086
set -- $FIELDS
printf '%-10s %-12s' "library" "version"
for field in "$@"; do printf ' %s' "$(printf '%s' "$field" | cut -c1-3)"; done
printf '\n'

probe() { # url field -> Y (present) | . (rejected) | ? (inconclusive)
  local status
  status=$(curl -sSL -o /dev/null -w '%{http_code}' --max-time "$TIMEOUT" \
    --get "$1/search/api/client/v7.0/search" \
    --data-urlencode 'q=model:periodical' \
    --data-urlencode "fq=$2:[* TO *]" \
    --data 'rows=0' 2>/dev/null)
  case "$status" in
    200)      printf 'Y' ;;
    000|'')   printf '?' ;;
    *)        printf '.' ;;
  esac
}

while IFS=$'\t' read -r code version url; do
  printf '%-10s %-12s' "$code" "$version"
  for field in "$@"; do printf ' %3s' "$(probe "$url" "$field")"; done
  printf '\n'
done <<< "$libraries"

cat <<'LEGEND'

  Y = field present    . = rejected by Solr (absent from the schema)    ? = inconclusive (timeout)

  Columns are the first three characters of each field, in this order:
LEGEND
printf '    %s\n' "$@"
