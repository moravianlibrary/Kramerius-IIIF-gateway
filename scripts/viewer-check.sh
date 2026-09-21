#!/usr/bin/env bash
#
# Do the manifests actually work in the viewers people use?
#
# A manifest can be valid IIIF and still be useless. The v3 schema does not say that a canvas whose
# dimensions are wrong renders as a sliver, that a `paged` behavior with no page.placement produces
# single pages where a reader expects openings, that a Range with no canvases makes a table of contents
# that goes nowhere, or that a language map viewers read in insertion order will show the wrong language
# when the order is not the one you meant. Every one of those has been a real defect in this project, and
# `make check` was green through all of them.
#
# DOM ASSERTIONS, NOT PIXEL DIFFS. A viewer upgrade changes pixels on every page and would produce a wall
# of false failures to re-baseline, which is how a visual suite stops being run. "There are 202 canvases
# in the thumbnail strip" survives a redesign; a screenshot hash does not. Screenshots are still written,
# for a human to look at, but nothing fails on them.
#
# PINNED VIEWERS. Mirador and Clover are third-party code that changes on its own schedule, and a check
# that can go red because someone else released on a Tuesday is a check people learn to ignore. Hence
# exact versions below, and hence this is NOT part of `make check`.
#
# Usage: make viewer-check          the gateway must be running
set -uo pipefail

BASE="${IIIF_BASE:-http://localhost:8080}"
OUT="${VIEWER_OUT:-tests/viewer/out}"
ENGINE="${DOCKER:-$(command -v podman || command -v docker)}"

# The viewers are pinned in tests/viewer/Containerfile and baked into this image, so the versions are a
# property of the image rather than of whatever npm resolved this morning. `make viewer-image` builds it;
# it prints its own version list at the top of every run.
VIEWER_IMAGE="${VIEWER_IMAGE:-localhost/iiif-viewers:1}"

[[ -n "$ENGINE" ]] || { echo "viewer-check needs podman or docker"; exit 2; }
"$ENGINE" image exists "$VIEWER_IMAGE" 2>/dev/null \
  || { echo "no $VIEWER_IMAGE — run: make viewer-image"; exit 2; }
command -v jq >/dev/null || { echo "viewer-check needs jq"; exit 2; }

curl -fsS --max-time 10 "$BASE/version" >/dev/null 2>&1 \
  || { echo "no gateway at $BASE — start it, or set IIIF_BASE"; exit 2; }

# The documents, and what a viewer must show for each. Chosen so each row asserts something a schema
# check cannot: a long paged monograph, an audio document with tracks, and a map with coordinates.
#
# pid|expect-canvases|why
DOCUMENTS=(
  "uuid:71d66fe0-d986-11e2-9923-005056827e52|202|a paged monograph: openings, and every canvas sized"
  "uuid:f40754db-a439-4321-9441-3c008a4200e6|20|a sound recording: 2 cover images and 18 audio tracks"
  "uuid:a5af77e0-92c8-11f0-9940-005056827e51|248|a map: navPlace, so a viewer can put it somewhere"
)

mkdir -p "$OUT"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# The manifests are fetched HERE, not in the container, so the browser never needs to reach the gateway
# — one less thing to be wrong when this fails. The container gets files, served back to it over
# loopback by check.mjs.
#
# THE ID IS REWRITTEN TO THE URL THE CONTAINER WILL SERVE IT FROM, and that is not cosmetic. A viewer
# fetches a manifest by URL and stores it under the id the document declares; if the two disagree it
# then looks up what it just loaded, finds nothing, and waits forever. Clover does exactly this —
# byte-identical manifests differing only in the top-level id render or hang on "Loading" with no
# error anywhere. Serving a manifest from an address other than its own id is not a thing that happens
# in production, so this restores the arrangement the viewer is entitled to assume rather than papering
# over a defect. Only the self-reference changes; everything asserted on is untouched.
#
# Files are numbered rather than named for their pid so the URL needs no escaping: a pid contains a
# colon, and an escaped path would have to match the rewritten id character for character.
VIEWER_ORIGIN="http://127.0.0.1:8099"
cases="[]"
index=0
skipped=0
for document in "${DOCUMENTS[@]}"; do
  IFS='|' read -r pid expected why <<<"$document"
  echo "fetching $pid — $why"
  file="manifest-$index.json"
  index=$((index + 1))
  if ! curl -fsS --max-time 180 "$BASE/mzk/$pid" -o "$WORK/$file.raw"; then
    # Counted, not just mentioned. A document that could not be fetched used to leave the run green
    # — "6 passed, 0 failed" after checking one document of three, which is the shape of a check
    # nobody notices has stopped checking.
    echo "  could not fetch it; skipping"
    skipped=$((skipped + 1))
    continue
  fi
  jq --arg id "$VIEWER_ORIGIN/$file" '.id = $id' "$WORK/$file.raw" > "$WORK/$file"
  cases=$(jq --arg pid "$pid" --arg file "$file" --argjson expected "$expected" --arg why "$why" \
    '. + [{pid: $pid, file: $file, expected: $expected, why: $why}]' <<<"$cases")
done

[[ "$(jq length <<<"$cases")" != "0" ]] || { echo "no manifests to check"; exit 1; }
printf '%s' "$cases" > "$WORK/cases.json"

# ─── The pages under test ─────────────────────────────────────────────────────
# They are not written here any more. mirador.html, clover.html and clover.bundle.js are baked into the
# viewer image (tests/viewer/Containerfile), and Universal Viewer's whole dist tree with them — which is
# the only way UV can be served at all, since it resolves its own chunks relative to the script that
# loaded it and so cannot be bundled. check.mjs serves /viewers as a second document root.
#
# That also ends a divergence: tests/viewer/index.html used to load Mirador 3.3.0 from unpkg and Clover's
# WEB component from jsdelivr, while this script pinned Mirador 3.4.3 and built Clover's REACT component.
# A person and these assertions were looking at different viewers. Now both read the same files.

# ─── The assertions ───────────────────────────────────────────────────────────
cat > "$WORK/check.mjs" <<'JS'
// Runs INSIDE the viewer container. Manifests come from /work, viewers from /viewers — both baked or
// mounted, neither fetched, so a run needs no network at all.
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

// SERVED OVER HTTP, NOT OPENED AS file://. Chromium treats every file:// document as its own opaque
// origin, so a file:// page fetching a file:// manifest is a cross-origin request that is refused
// before it reaches the viewer. The viewer then reports "could not load" inside its own window and
// raises no page error — indistinguishable, from the outside, from a manifest we had got wrong. This
// cost a full run to diagnose; the loopback server is what makes a failure here mean something.
// .js is not optional here: Chromium refuses to execute a script served as octet-stream, and the
// page then fails with nothing in it rather than an error.
// .css and .ico are here for Universal Viewer, and not optionally: Chromium applies strict MIME
// checking to stylesheets, so uv.css served as octet-stream is DROPPED — UV then renders unstyled and
// still passes a label assertion, which is worse than failing.
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.json': 'application/json',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
};

// TWO DOCUMENT ROOTS. /work is the run's own directory — manifests, cases.json — and /viewers is the
// read-only set baked into the image. Serving them under one origin is what lets a viewer page and the
// manifest it loads be same-origin, which is the whole reason this server exists.
const ROOTS = ['/work', '/viewers'];
const resolve = (path) => {
  if (path.includes('..')) return null;
  for (const root of ROOTS) {
    const file = `${root}${path}`;
    if (existsSync(file)) return file;
  }
  return null;
};
const server = createServer((request, response) => {
  const path = decodeURIComponent(new URL(request.url, 'http://127.0.0.1').pathname);
  const file = resolve(path);
  if (!file) {
    response.writeHead(404).end();
    return;
  }
  response.writeHead(200, {
    'content-type': TYPES[path.slice(path.lastIndexOf('.'))] ?? 'application/octet-stream',
    // The same header the real gateway sets, for the same reason: viewers require it.
    'access-control-allow-origin': '*',
  });
  response.end(readFileSync(file));
});
await new Promise((resolve) => server.listen(8099, '127.0.0.1', resolve));
const ORIGIN = 'http://127.0.0.1:8099';

const cases = JSON.parse(readFileSync('/work/cases.json', 'utf8'));
const results = [];
const record = (viewer, pid, name, ok, detail) => {
  results.push({ viewer, pid, name, ok, detail });
  console.log(`  ${ok ? '\x1b[32m✓\x1b[0m' : '\x1b[31m✗\x1b[0m'} ${viewer} ${name}${detail ? ` — ${detail}` : ''}`);
};

// WHERE EACH VIEWER LIVES AND HOW IT TAKES A MANIFEST. Mirador and Clover read ?manifest=; Universal
// Viewer reads its own hash through UV.IIIFURLAdapter, and is served from its shipped dist rather than
// from a page of ours — uv.html IS the embedding UV documents, and reimplementing it is what failed
// before.
const VIEWERS_BY_NAME = {
  mirador: { url: (o, m) => `${o}/mirador.html?manifest=${encodeURIComponent(m)}` },
  clover: { url: (o, m) => `${o}/clover.html?manifest=${encodeURIComponent(m)}` },
  uv: { url: (o, m) => `${o}/uv/uv.html#?manifest=${encodeURIComponent(m)}` },
};
const VIEWERS = Object.keys(VIEWERS_BY_NAME);

const browser = await chromium.launch();

for (const testCase of cases) {
  const manifest = JSON.parse(readFileSync(`/work/${testCase.file}`, 'utf8'));
  const canvases = (manifest.items ?? []).filter((item) => item.type === 'Canvas');
  console.log(`\n\x1b[1m${testCase.pid}\x1b[0m — ${testCase.why}`);

  // What the manifest itself claims, so a viewer assertion can be compared against something.
  const expectedLabel = Object.values(manifest.label ?? {})[0]?.[0] ?? '';

  for (const viewer of VIEWERS) {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));

    // Upstream refusals, tracked separately from our own origin. 64 of MZK's 73 million pages are
    // `accessibility: private`, so a viewer fetching info.json for one gets a 403 carrying Kramerius'
    // HTML error page — and UV JSON-parses it and throws. That is a real thing to see, but it is not a
    // manifest defect: the gateway declares the service because a reader WITH credentials can use it,
    // and Kramerius implements no auth service for us to advertise instead. See docs/DECISIONS.md.
    const refused = [];
    page.on('response', (response) => {
      if (response.status() >= 400 && !response.url().startsWith(ORIGIN)) {
        refused.push(`${response.status()} ${response.url()}`);
      }
    });

    try {
      // The manifest URL is its own id — see the fetch loop.
      const manifestUrl = `${ORIGIN}/${testCase.file}`;
      await page.goto(VIEWERS_BY_NAME[viewer].url(ORIGIN, manifestUrl),
        { waitUntil: 'load', timeout: 60000 });

      // The label is the one thing every viewer renders, so it is the readiness signal as well as
      // an assertion: if the label never appears, the viewer failed to load the manifest at all.
      const labelShown = await page
        .getByText(expectedLabel.slice(0, 40), { exact: false })
        .first()
        .waitFor({ timeout: 45000 })
        .then(() => true)
        .catch(() => false);
      record(viewer, testCase.pid, 'renders the label', labelShown,
        labelShown ? '' : `never showed "${expectedLabel.slice(0, 40)}"`);

      if (viewer === 'mirador' && canvases.length > 0) {
        // Canvas count, from the viewer's own state rather than by counting DOM nodes: a thumbnail
        // strip is virtualised, so the DOM holds a window and not the whole document.
        const counted = await page.evaluate(() => {
          const state = window.miradorInstance?.store?.getState();
          const manifests = Object.values(state?.manifests ?? {});
          const json = manifests[0]?.json;
          return (json?.items ?? []).length;
        });
        record(viewer, testCase.pid, `loads all ${testCase.expected} canvases`,
          counted === testCase.expected, `viewer sees ${counted}`);

        // The defect this exists for: a canvas with no width/height renders as a sliver, and v1
        // shipped "width": null on every canvas of every manifest for years.
        //
        // An audio canvas is sized in time instead, and a sound recording mixes both kinds — cover
        // images with dimensions, tracks with a duration — so requiring width/height everywhere
        // fails a manifest that is correct.
        const unsized = canvases.filter(
          (c) => (!(c.width > 0) || !(c.height > 0)) && !(c.duration > 0),
        ).length;
        record(viewer, testCase.pid, 'every canvas has real dimensions', unsized === 0,
          unsized ? `${unsized} canvases unsized` : '');
      }

      if (viewer === 'clover') {
        // Clover is here for audio: it renders a track list with durations where Mirador shows a
        // blank canvas and a player it cannot drive.
        //
        // NOT the ranges, though that is what this originally asserted. Clover 2.14.0 renders no
        // structures panel at all — checked against the cookbook's own table-of-contents recipe,
        // which yields exactly as many structure nodes as our manifests do: none. That assertion was
        // therefore testing a viewer feature that does not exist, while reading as though our ranges
        // were broken. Ranges stay covered by `make validate` and the fixture tests.
        const audio = canvases.filter((canvas) => canvas.duration > 0).length;
        if (audio > 0) {
          const shown = await page.evaluate(
            () =>
              [...document.querySelectorAll('*')].filter(
                (element) =>
                  element.children.length === 0 &&
                  /^\d{1,2}:\d{2}$/.test(element.textContent.trim()),
              ).length,
          );
          record(viewer, testCase.pid, `lists all ${audio} audio tracks with durations`,
            shown === audio, shown === audio ? '' : `viewer shows ${shown}`);
        }
      }

      // Screenshots are for a human, and increasingly for a model asked to review a change. Nothing
      // fails on them — see the script header — but an empty image pane tells either reviewer nothing,
      // and the assertions above finish long before the first tile has painted. Wait for the network
      // to go quiet, then a little longer for the paint, and only then look.
      await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});
      await page.waitForTimeout(1500);
      await page.screenshot({ path: `/out/${viewer}-${testCase.pid}.png`, fullPage: false });

      // A viewer that logged an exception may still have rendered; worth knowing either way. An
      // exception that followed an upstream refusal is reported rather than failed — see `refused`.
      const ours = errors.length > 0 && refused.length === 0;
      record(viewer, testCase.pid, 'no uncaught JavaScript errors', !ours,
        errors.length === 0
          ? ''
          : `${errors.slice(0, 2).join(' | ')}${refused.length ? ` (after ${refused[0]})` : ''}`);
    } catch (error) {
      record(viewer, testCase.pid, 'loads', false, error.message.split('\n')[0]);
    } finally {
      await page.close();
    }
  }
}

await browser.close();
server.close();
writeFileSync('/out/viewer-check.json', JSON.stringify(results, null, 2));

const failed = results.filter((result) => !result.ok);
console.log(`\n\x1b[1m${results.length - failed.length} passed, ${failed.length} failed\x1b[0m`);
console.log('screenshots and viewer-check.json in tests/viewer/out/');
process.exit(failed.length > 0 ? 1 : 0);
JS

echo
"$ENGINE" run --rm \
  -v "$WORK:/work:z" \
  -v "$PWD/$OUT:/out:z" \
  -w /work \
  "$VIEWER_IMAGE" \
  bash -c "cat /viewers/VERSIONS; echo; node /work/check.mjs"
status=$?

if [[ "$skipped" -gt 0 ]]; then
  echo
  echo "$skipped of ${#DOCUMENTS[@]} documents could not be fetched — that is not a pass"
  exit 1
fi
exit "$status"
