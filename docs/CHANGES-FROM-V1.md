# What changed from the previous gateway

For anyone who consumed the old manifests: viewer authors, API users, and whoever has to answer "why does
this look different now".

The previous gateway was a 1446-line Ruby script (`iiif.rb`) spawned as a new process per request by a
100-line Express app (`app.js`) — a fresh registry fetch and a MODS XML parse for every manifest, with no
cache anywhere. This is a rewrite, not a refactor, and **breaking changes were allowed** because most of
them are corrections.

Counts below are measured at MZK unless stated.

## If you only read one section

| | v1 | v2 |
|---|---|---|
| Canvas dimensions | `1000 × 1000` for everything | the real size, or the right shape where the image is licensed |
| Language tags | `"cz"` (not a language) | `cs`, `en`, `sk`, … |
| `seeAlso.type` | `"Alto"` (not an IIIF type) | `"Dataset"` |
| PDF documents | no `items` — **invalid IIIF** | `"items": []`, valid |
| Large documents | silently truncated at 1500 canvases | complete |
| Models served | 17 | 21 (K7) |
| Kramerius 5 | served, but with v1's defects and no image service | a per-library probe; three K5 libraries get tiles |

## Corrections

These are places v1 emitted something wrong. A client that worked around any of them can stop.

### Canvas dimensions were never real

v1 declared every canvas `1000 × 1000` unless you appended `/full`, and **`/full` was broken in
production**: it correlated `info.json` responses by comparing an `https://` URL it had built against the
`http://` `@id` the image server actually returns, so nothing ever matched and every canvas came back
`"width": null`.

v2 serves real dimensions unconditionally, correlated by request index — which makes that entire class of
bug impossible. Verified on a 202-page monograph: v1 gives one distinct size (1000×1000) for all 202
canvases; v2 gives 101 distinct sizes, 202/202 real.

The `http://` mismatch is not universal: `lmda` and `kvkli` return an `https://` `@id`, so v1's `/full`
did work there. They are also the libraries v2 was briefly behind on — see below.

`/full` still works and is a `301` to the canonical URL.

### `"cz"` is not a language tag

Czech is `cs`. v1 emitted `"cz"` on every gateway-authored label — 21 of 21 sampled manifests. JSON Schema
cannot express "this must be a BCP 47 tag", so it passed validation while still being wrong, and viewers
selecting by locale did not match it.

v2 emits `cs` and `en` for anything it authors. Record content that has no known language is `none`, which
is what that key is for.

The institution name now carries **its own** language: the registry's `name` field is Slovak for `dikda`,
`snk` and `cvti`, and German for `eod`. v1 tagged all of them Czech.

### `seeAlso.type: "Alto"`

Not an IIIF type. v2 uses `"Dataset"` with the ALTO namespace URI as `profile`, which is what `profile` is
for. v1 put the XSD URL there.

### Manifests with no `items`

The IIIF schema requires `items` on a Manifest even when empty. v1 omitted it for PDF-only documents —
**13 832 invalid manifests** at MZK. v2 emits `"items": []` and attaches the PDF as a `rendering`.

### Documents were silently truncated

v1 requested `rows=1500` from Solr and treated it as both the page size and the output cap. A 3128-page
monograph served 1500 canvases with no indication that 1628 were missing.

v2 separates the two: an internal batch size (500, ~p98 of documents in one round trip) and a sanity
ceiling far above any real document. The largest real document at MZK is 3568 canvases; it now serves all
of them.

### Absent values

v1 emitted `{"none": [null]}` for missing metadata, `{"id": null}` for a provider logo it did not have, and
pushed `{}` into `partOf` for a parent it could not link. v2 omits the entry.

### Nested collections were typed `Manifest`

v1 typed every collection member from the parent, so a collection inside a collection came out a
`Manifest`. v2 types each member from its own resolved shape.

### Other

- `audio/mp3` → `audio/mpeg` (`mp3` is not a registered media type).
- navPlace polygons are closed, correctly wound, and `[lon, lat]`. v1's were self-intersecting and unclosed.
- Requesting a page, track or sound unit returned `200` with a nonsense manifest. It is now a `404` with a
  distinct error code — the object exists, it just is not a document.
- A page added to a collection individually now references the document containing it, because a page has
  no manifest of its own.

## New

- **`requiredStatement`** with attribution and licence text. v1 had none — 17 of 17 sampled.
- **`behavior`**, derived from indexed page placement. `paged` when pages carry left/right placement,
  omitted otherwise. It never asserts `individuals`, which would be a positive claim that canvases are
  unrelated — as wrong for a multi-sheet graphic album as `paged` is.
- **Availability and licence** metadata rows, from index fields v1 never read.
- **`summary`** where a collection has a description.
- **Per-library Kramerius 5 image services.** v1 served K5 — `iiif.rb` carries five `v5.0` API paths and
  its last commit was a K5 fix — but declared an `ImageService2` for every K5 library whether or not one
  existed. v2 probes, so the three that have a working image server get tiles and real dimensions and the
  rest get an honest flat stream.
- **Dimension endpoints**: `GET /{library}/{pid}/dimensions` reports cache coverage; `PUT` and `DELETE` on
  an unpublished admin port warm and invalidate it.
- **`GET /libraries`** — which libraries resolve, to which adapter, whether that adapter has been tested
  against their version, which Image API they serve, and how far the dimension walk has got.

## Documents that now work

v1's dispatch table refused four models outright. At MZK:

| Model | Documents |
|---|---|
| `supplement` | 79 562 |
| `article` | 70 918 |
| `internalpart` | 1 315 |
| `picture` | 56 |

About **152 000 documents** that have pages and could have been served. Three of them hold their pages by
*foster* relationship — an article does not own its pages, the issue does — so v1 could not have served
them even if the table had listed the model.

## A regression that was, and is not any more

**Three K5 libraries briefly lost canvas dimensions they used to get.** `Kramerius5Urls.hasImageService()`
returned a hardcoded `false`, so `lmda`, `dsmo` and `kvkli` — which have working image servers reporting
real dimensions — got a square canvas for no reason but the constant. For `lmda` and `kvkli` v1 was
genuinely better: their `info.json` returns an `https://` `@id` matching what v1 built, so the broken
correlation above was not broken there.

It is now probed per library rather than assumed, and they get tiles and real dimensions again. Verified:
`lmda` serves 34 canvases at 2371 × 3418 with an image service, matching what v1's `/full` returned for
the same document. Recorded here rather than deleted because a client that noticed the gap deserves to
know it closed.

## Things that look like regressions but are not

**Most Kramerius 5 canvases have no image service.** ndk.cz and vugtk answer
`/search/iiif/{pid}/info.json` with `200` and a **zero-byte body** and then `404` every image request;
three others answer `500`. For those the plain stream serves the image fine, so v2 emits it and no
`service` block. v1 emitted an `ImageService2` regardless — a claim a viewer acts on, and it fails.

Which deployments those are is measured rather than assumed, so the three K5 libraries that do run an
image server are not punished for the five that do not.

**ALTO is now advertised on nearly every page.** `has_alto_content` exists in the K7 schema from 7.1.4 but
is populated on essentially nothing (0 of MZK's 76 455 203 pages; 3 of KNAV's 6 228 108), while 73 839 443
MZK pages have `text_ocr` indexed and the ALTO stream returns a valid document even for a front jacket. So
an absent flag means *emit*. An instance that does populate the field is believed, and an explicit `false`
omits the link.

**The provider label may differ.** v1 used the Kramerius host as the institution's homepage; v2 uses the
registry's `web` field, which is the institution's own site.

**Some canvases may carry a nominal size on first request.** A response with `X-IIIF-Dimensions: partial`
is provisional and `no-store`; the real dimensions are being measured in the background, and a later
request returns the complete manifest. v1 was permanently in this state and did not say so.

**A licensed document's canvases carry a shape, not a measurement.** Its images answer 403, so the
aspect is read from the page's public thumbnail and scaled to a nominal height — right shape, nominal
size — and the response says `X-IIIF-Dimensions: approximate`. v1 gave those documents 1000 × 1000 like
everything else, so this is strictly better; the header exists so a client can tell the two kinds of
number apart, which neither version could before. It is not frozen: a page whose 403 stops — a licence
expiring, a whitelisted deployment — upgrades to a real measurement on the next retry window.

## URLs

Unchanged: `/{library}/{pid}`, `/{library}/{pid}/full`, `/resolve?link=…`.

One behaviour change: `/resolve` now refuses a URL it cannot parse with a `400` and a message. v1 guessed,
and could return a manifest for the wrong document — a URL like
`kramerius.mzk.cz/search/handle/uuid:…` has no library code in it, and v1 read `search` as one.
