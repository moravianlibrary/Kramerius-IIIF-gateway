#!/usr/bin/env bash
#
# Does this gateway still work against the real thing?
#
# Everything in `make check` is offline and fixture-driven, which is what makes it a gate: it cannot be
# broken by somebody else's server being slow. That is also its blind spot. Every fixture in this repo was
# written from a live response at one moment, and the whole point of a gateway is that it depends on
# fifty-odd instances nobody here controls — a renamed field, a changed status code, a licence route that
# moved, and the fixtures still pass while production is wrong.
#
# So this is deliberately NOT in the gate. It needs the network and it can fail for reasons that are not
# our bug; a CI job that goes red when NKP reboots teaches people to ignore red.
#
# ONE LIBRARY PER ADAPTER, not one per library. The adapter is the unit of behaviour — that is the whole
# reason adapters are named by version floor — so three libraries cover all three adapters, and a
# fifty-library sweep would only cover the same three paths seventeen times each while making ourselves
# a nuisance to institutions that never asked to be a test target.
#
# Usage: make smoke                 the gateway must be running (IIIF_BASE=http://localhost:8080)
#        IIIF_BASE=… ./dev/smoke.sh
set -uo pipefail

BASE="${IIIF_BASE:-http://localhost:8080}"
TIMEOUT="${SMOKE_TIMEOUT:-120}"

# code|version|why this one
LIBRARIES=(
  "mzk|7.2.x|Kramerius714, and the largest Czech instance"
  "cbvk|7.0.x|Kramerius70, below the 7.1.4 field boundary, so the fallbacks are what run"
  "vugtk|5.4.2|Kramerius50, a different API and index entirely — not a subclass of the K7 adapters"
)

# Every document model that has a v3 representation, one pid each, chosen at MZK.
# A pid that stops resolving is itself a finding — that is what this script is for.
MZK_DOCUMENTS=(
  "monograph|uuid:71d66fe0-d986-11e2-9923-005056827e52"
  "monographunit|uuid:551502c0-7a40-11ee-bf21-005056827e52"
  "manuscript|uuid:8aff1e7b-4a68-47f5-a98d-6ed9a2faf28a"
  "map|uuid:a5af77e0-92c8-11f0-9940-005056827e51"
  "graphic|uuid:e62297e6-502a-4d39-a797-1d8f18c77a3d"
  "sheetmusic|uuid:745a0189-8680-4150-a7cc-2f12e0152f47"
  "archive|uuid:b1c9ceaf-adfe-4147-8270-36965c554556"
  "periodical|uuid:58b9d800-5e81-11ed-85d9-5ef3fc9bb22f"
  "periodicalvolume|uuid:1724e800-be4c-11ed-ac82-5ef3fc9bb22f"
  "periodicalitem|uuid:b1eb7130-5d79-11e3-ae59-005056827e52"
  "supplement|uuid:fea96b90-e70b-11e4-9c07-001018b5eb5c"
  "article|uuid:022321ca-b665-46aa-8ee1-88adb67f73ea"
  "internalpart|uuid:2fc6e2a0-8982-11dd-9329-000d606f5dc6"
  "picture|uuid:062ada22-b567-451e-9310-e1e2e2343f08"
  "soundrecording|uuid:fbf5efba-ff5f-4921-aee6-2d7f4141561b"
  "convolute|uuid:1001534e-de50-4ef5-8213-4923df8a516c"
  "collection|uuid:6c17b0cf-8c49-48d3-a9e9-b64f154c3f23"
)

# Just enough of one, since the point at cbvk is the adapter and not the catalogue.
# Three models, not seventeen: the point at cbvk is the ADAPTER, and specifically the three fallbacks
# Kramerius70 has to supply for fields that arrive only at 7.1.4 — the ALTO probe, deriving is_point from
# the bbox corners, and paging without count_volume. A monograph, a periodical issue and a map exercise
# all three.
CBVK_DOCUMENTS=(
  "monograph|uuid:d8aa7625-f630-4525-a3c9-f9d11e4684dc"
  "periodicalitem|uuid:a1a4185f-d5de-4f05-9409-91266bb92347"
  "map|uuid:e1d76965-6498-434b-b2f0-e5cc18d5472c"
)

# Kramerius50 is not a subclass of the K7 adapters — different API paths, different field names, a
# multi-valued sort field that forces start/rows paging, `##`-packed details, and MODS for metadata the
# index does not carry. Nothing else in this script would notice if any of that broke.
#
# vugtk rather than a larger K5 instance: it is the one the K5 field names were verified against
# (5.4.2), it answers in a few seconds where the National Library takes fifteen, and four documents is
# a polite footprint on a research institute's server. It holds no maps, sound or graphics at all, so
# these four are what there is — and they are the paging paths that matter: pages under a monograph,
# pages under an issue, units under a monograph, and volumes under a periodical.
#
# Every pid was resolved from the live index and then requested through the gateway before being
# written down here.
VUGTK_DOCUMENTS=(
  "monograph|uuid:315f03e0-3398-4af0-a15e-33da7fe6f4b3"
  "monographunit|uuid:41c470b2-f8c3-42fc-b326-31f881092167"
  "periodical|uuid:88ed0302-6ecd-11e2-9fa8-08002795e402"
  "periodicalitem|uuid:df59a1e0-293b-4c02-8b37-e3a0bfd94e8e"
)

pass=0
fail=0
skipped=0
failures=()

note() { printf '\n\033[1m%s\033[0m\n' "$1"; }
ok() { printf '  \033[32m✓\033[0m %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf '  \033[31m✗\033[0m %s — %s\n' "$1" "$2"; fail=$((fail + 1)); failures+=("$1: $2"); }
skip() { printf '  \033[33m-\033[0m %s — %s\n' "$1" "$2"; skipped=$((skipped + 1)); }

require() {
  for tool in curl jq; do
    command -v "$tool" >/dev/null || { echo "smoke needs $tool"; exit 2; }
  done
}

# The gateway, not Kramerius. A smoke run against a gateway that is not up is a wasted trip to fifty
# institutions' servers, so this is checked first and hard.
gateway_up() {
  local version
  version=$(curl -fsS --max-time 10 "$BASE/version" 2>/dev/null | jq -r '.version // empty')
  [[ -n "$version" ]] || { echo "no gateway at $BASE — start it, or set IIIF_BASE"; exit 2; }
  echo "gateway $version at $BASE"
}

# What a manifest has to satisfy to count. Not a schema check — `make test` already validates every
# fixture against the vendored IIIF v3 schema offline. What can only be checked here is that the
# LIVE data still produces a manifest with real content in it.
check_manifest() {
  local label="$1" library="$2" pid="$3"
  local body status partial

  body=$(curl -sS --max-time "$TIMEOUT" -D /tmp/smoke-headers.$$ \
    "$BASE/$library/$pid" 2>/dev/null)
  status=$(awk 'NR==1 {print $2}' /tmp/smoke-headers.$$ 2>/dev/null)
  partial=$(grep -i '^x-iiif-dimensions:' /tmp/smoke-headers.$$ 2>/dev/null | tr -d '\r' | awk '{print $2}')
  rm -f /tmp/smoke-headers.$$

  if [[ "$status" != "200" ]]; then
    bad "$label" "HTTP ${status:-no response}"
    return
  fi
  if ! jq -e . >/dev/null 2>&1 <<<"$body"; then
    bad "$label" "not JSON"
    return
  fi

  local problem
  problem=$(jq -r '
    def bad(cond; msg): if cond then msg else empty end;
    [
      bad(.["@context"] | type != "string" and type != "array"; "no @context"),
      bad(.id == null or (.id | startswith("http") | not); "no absolute id"),
      bad((.type // "") | test("^(Manifest|Collection)$") | not; "type is \(.type // "missing")"),
      bad(.label == null or (.label | length) == 0; "no label"),
      bad((.label // {} | keys | any(. == "cz")); "label tagged cz — not a language tag"),
      bad((.items // []) | length == 0; "no items"),
      # Either, not both: v3 sizes a canvas in space or in time. A sound recording carries both
      # kinds at once — cover images with width/height, tracks with a duration — so demanding
      # dimensions everywhere fails a manifest that is correct.
      bad(
        (.type == "Manifest") and
        ((.items // [])
          | map(select(
              ((.width // 0) <= 0 or (.height // 0) <= 0) and (.duration // 0) <= 0))
          | length > 0);
        "a canvas has neither dimensions nor a duration"
      ),
      bad(
        (.type == "Manifest") and
        ((.items // []) | map(select((.items // []) | length == 0)) | length > 0);
        "a canvas has no annotation page"
      )
    ] | join("; ")' <<<"$body")

  if [[ -n "$problem" ]]; then
    bad "$label" "$problem"
    return
  fi

  local items canvases
  items=$(jq '(.items // []) | length' <<<"$body")
  if [[ "$partial" == "partial" ]]; then
    # Not a failure. A cold document answers immediately and hands the rest to the worker; that is
    # the design. Worth SAYING, because a smoke run where everything is partial means the size
    # cache is empty and the numbers below are nominal.
    skip "$label" "$items items, dimensions still partial (worker has it)"
    return
  fi
  canvases=$(jq '[(.items // [])[] | select(.type == "Canvas")] | length' <<<"$body")
  ok "$label ($items items, $canvases canvases)"
}

# The routes that are not manifests, and that no fixture test can cover because they are wiring.
check_routes() {
  local library="$1" pid="$2"

  local etag second
  etag=$(curl -sS --max-time "$TIMEOUT" -o /dev/null -D - "$BASE/$library/$pid" 2>/dev/null \
    | grep -i '^etag:' | tr -d '\r' | awk '{print $2}')
  if [[ -z "$etag" ]]; then
    skip "etag" "no ETag (a partial manifest has none, by design)"
  else
    second=$(curl -sS --max-time "$TIMEOUT" -o /dev/null -w '%{http_code}' \
      -H "If-None-Match: $etag" "$BASE/$library/$pid" 2>/dev/null)
    [[ "$second" == "304" ]] && ok "etag revalidates (304)" || bad "etag" "revalidated $second, not 304"
  fi

  local full
  full=$(curl -sS --max-time 30 -o /dev/null -w '%{http_code}' "$BASE/$library/$pid/full" 2>/dev/null)
  [[ "$full" == "301" ]] && ok "/full redirects (301)" || bad "/full" "answered $full, not 301"

  local resolved
  resolved=$(curl -sS --max-time 30 -o /dev/null -w '%{redirect_url}' \
    "$BASE/resolve?link=https://www.digitalniknihovna.cz/$library/uuid/$pid" 2>/dev/null)
  [[ "$resolved" == *"/$library/$pid" ]] \
    && ok "?link= resolves" || bad "?link=" "redirected to '${resolved:-nothing}'"

  local dimensions
  dimensions=$(curl -sS --max-time "$TIMEOUT" "$BASE/$library/$pid/dimensions" 2>/dev/null \
    | jq -r '"\(.cached)/\(.total)"' 2>/dev/null)
  [[ -n "$dimensions" && "$dimensions" != "null/null" ]] \
    && ok "/dimensions reports $dimensions" || bad "/dimensions" "no status"

  # A pid that does not exist must be 404 with a code, not v1's 200-with-an-empty-manifest.
  local missing
  missing=$(curl -sS --max-time 30 "$BASE/$library/uuid:00000000-0000-0000-0000-000000000000" 2>/dev/null \
    | jq -r '.error // empty')
  [[ "$missing" == "not_found" ]] && ok "unknown pid is not_found" || bad "unknown pid" "error was '${missing:-none}'"

  # An unknown library must not be mistaken for a broken gateway. This is the cheap end of the same
  # rule the upstream_* codes exist for: nothing that is somebody else's fault answers 500.
  local unknown unknown_status unknown_code
  unknown=$(curl -sS -w $'\n%{http_code}' --max-time 30 \
    "$BASE/no-such-library/uuid:00000000-0000-0000-0000-000000000000" 2>/dev/null)
  unknown_status=${unknown##*$'\n'}
  unknown_code=$(jq -r '.error // empty' <<<"${unknown%$'\n'*}" 2>/dev/null)
  [[ "$unknown_status" == "404" && "$unknown_code" == "unknown_library" ]] \
    && ok "unknown library is 404 unknown_library" \
    || bad "unknown library" "got $unknown_status '${unknown_code:-none}'"
}

require
gateway_up

note "routes (mzk)"
check_routes "mzk" "uuid:71d66fe0-d986-11e2-9923-005056827e52"

for library in "${LIBRARIES[@]}"; do
  IFS='|' read -r code version why <<<"$library"
  note "$code — $version ($why)"

  documents=()
  case "$code" in
    mzk) documents=("${MZK_DOCUMENTS[@]}") ;;
    cbvk) documents=("${CBVK_DOCUMENTS[@]}") ;;
    vugtk) documents=("${VUGTK_DOCUMENTS[@]}") ;;
  esac

  for document in "${documents[@]}"; do
    IFS='|' read -r model pid <<<"$document"
    check_manifest "$model" "$code" "$pid"
  done
done

note "$pass passed, $fail failed, $skipped inconclusive"
if ((fail > 0)); then
  printf '\n'
  for problem in "${failures[@]}"; do printf '  %s\n' "$problem"; done
  exit 1
fi
