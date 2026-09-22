# Decisions

Judgement calls: questions where reasonable people could differ and the answer was chosen rather than
derived. Each entry says what was decided, what it was decided against, and the measurement that settled
it — so it is not reopened by accident, and so the reason survives the person who gave it.

**What belongs here**: anything where someone had to choose. **What does not**: how the system works
([`DESIGN.md`](DESIGN.md)), what it emits ([`BEHAVIOUR.md`](BEHAVIOUR.md)), and what is wrong or
unfinished — that last one needs doing, not deciding.

Anything still open goes in an `## Open` section at the top and moves down once it is answered. There is
nothing open today. Nothing is deleted from this file either; a decision that gets reversed gets a new
entry saying so, because the reversal is itself a decision and the next person needs both halves.

---

## Decided

### Is `Kramerius50.supports()` hardcoding `major == 5` a problem? — **no, and it never will be**

It is the only adapter not open-ended upwards. Floor-based selection means "at or above", so a 5.0 floor
left open would claim every K7 instance too, and K5 has to refuse anything at 6 or higher for a 7 to fall
through to the K7 chain.

That reads like a tripwire waiting for Kramerius 6. **There will be no Kramerius 6** — the project went
5 → 7 — so the constraint is permanent rather than provisional, and this is recorded here because that is
domain knowledge a reader of the code cannot derive from it. `Kramerius50.supports` looks like an
oversight and is not.

### Should `(NormalPage)` appear in page labels? — **no**

Roughly 90% of pages carry `page.type: NormalPage` (69 937 719 at MZK, plus 491 725 spelled
`normalPage`), so most canvas labels read `"12 (NormalPage)"`. It is Kramerius' internal vocabulary and
adds nothing a reader wants: the label already says which page it is.

**Suppressed**, in `SolrRecords.isDefaultPageType` and applied by both mappers so K5 and K7 label the
same document the same way. Every other type is kept — `FrontCover`, `TitlePage`, `Blank`,
`Advertisement` genuinely distinguish a page. A `NormalPage` with no number now gets no label at all
rather than an empty one.

### Should ALTO be advertised when the index says nothing? — **yes, keep emitting**

`has_alto_content` is unpopulated on essentially every instance (0 of MZK's 76 455 203 pages; 3 of KNAV's
6 228 108), while 73 839 443 MZK pages have `text_ocr` indexed and the ALTO stream returns a valid
document even for a front jacket with no text.

An absent flag means *emit*. Right for ~96.6% of pages and wrong for the rest, which advertise a
`seeAlso` that may 404 — against omitting it for 73.8 million pages that have one. The per-document probe
(one extra request per manifest, accurate) stays available if 404s in `seeAlso` turn out to matter to
anyone.

### Should a partial manifest be served at all? — **yes**

A cold document whose dimensions cannot be resolved within the budget is answered immediately with
nominal sizes, marked `X-IIIF-Dimensions: partial` and `no-store`, and the work handed to the worker. A
viewer would rather render something slightly wrong now than spin.

The `503` + `Retry-After` alternative — content negotiation on patience, for machine consumers that would
otherwise cache a provisional answer — is **not** implemented. The header and `no-store` are what a
correct client has to respect.

### Is `IIIF_DIMENSION_WAIT=5s` the right cut? — **no, 10s**

With 10-way concurrency and an observed ~250 ms per `info.json`, five seconds resolved roughly 200 pages
inline. The canvas distribution is p50 = 12, p90 = 233, p95 = 332, so five seconds left a p90 document
right at the boundary. Ten seconds covers p95 inline and still bounds the wait at something a reader
tolerates.

`IIIF_DIMENSION_WAIT_SECONDS=10` in `Config` and `.env.example`.

### Is a degraded K5 manifest better than no K5 manifest? — **yes**

Correct labels, structure, metadata, licences and ALTO, with whatever the image server turns out to
support. Every part that works is better than v1, which served the same documents with an image service
that does not exist.

### Should `hasImageService()` be probed per library rather than hardcoded? — **probed**

The old `false` was generalised from two instances, ndk.cz and vugtk, which both answer
`/search/iiif/{pid}/info.json` with 200 and a zero-byte body and then 404 every image request. Five
public pages sampled from each of the 11 alive K5 libraries say that is not what K5 does — it is what
those two deployments do:

| Library | Version | `info.json` | Image API |
|---|---|---|---|
| `lmda` | 5.8.1 | 200, 649 B | **works** — 2417 × 3416, `default.jpg` 200 |
| `dsmo` | 5.8.1 | 200, 656 B | **works** — 2386 × 3447, `default.jpg` 200 |
| `kvkli` | 5.8.3 | 200, 648 B | **works** — 1409 × 2162, `default.jpg` 200 |
| `nkp` | 5.8.4.4 | 200, 0 B | 200-empty, images 404 |
| `vugtk` | 5.4.2 | 200, 0 B | 200-empty, images 404 |
| `nlk` | 5.8.4.5 | 500 / 404 | none |
| `cuni_fsv` | 5.7.2 | 500 | none |
| `mendelu` | 5.3.2 | 500 | none |
| `nfa` | 5.7.2 | — | not sampled (Cloudflare 403) |
| `cuni_lf1` | 5.7.2 | — | not sampled (Solr returned no page pids) |
| `mzkk5` | 5.7.2 | — | not sampled (Solr unreachable) |

The version does not predict it — 5.8.1 works, 5.8.4.4 is empty, 5.3.2 is a 500 — so no threshold in
`AdapterRegistry` could have got this right. It tracks whether that deployment has an image server wired
up, which is what `ImageApi.probe` now measures once per library at resolve time.

**On the optimistic failure**, which was the real question: a library that answers `info.json` but 404s
tiles for some pages would produce a manifest a viewer breaks on. Two things bound it. The probe counts a
page only if its `info.json` actually describes an image (`width` and `height` present and positive), so
K5's 200-with-empty-body is not a vote. And an adapter says whether the question applies at all —
`KrameriusAdapter.imageServiceIsCertain()` is true for K7, where a probe finding nothing means an unlucky
sample rather than a missing service, and false for K5, where it is the answer.

Verified end to end: `lmda` now serves 34 canvases at 2371 × 3418 with an image service, where it
previously served 34 squares and a flat stream. `nkp` still serves no service and `streams/IMG_FULL`.

### Should we read JPEG headers to get K5 dimensions? — **no**

For the K5 libraries with no working Image API, a ranged GET of `streams/IMG_FULL` and a parse of the
JPEG SOF marker would give real dimensions at the same request count.

A JPEG parser is a real dependency and a real surface for a version of Kramerius that is being replaced,
and it is not even JPEG everywhere — sampling found `image/vnd.djvu` at `cuni_fsv` and `application/pdf`
at `nlk` behind `IMG_FULL`. The probe above already rescues the three libraries that can be rescued
cheaply; the rest keep the approximation.

### Is Kramerius 4 worth an adapter? — **no**

One alive registry entry at 4.6.2, and an adapter would be a fourth mapper, a fourth model table and a
fourth set of URL shapes for a single institution. It is refused by name, with a message that says why.
Revisit if that institution asks.

### Should the model tables be operator-editable? — **yes**

Kramerius adds models, an unknown one degrades to `unsupported`, and a library that starts publishing a
new model would otherwise serve nothing for it until the gateway is released again.

`IIIF_MODELS_DIR` holds one `kramerius-<floor>.json` per version floor, merged over the bundled table
**model by model** (`ModelDirectory`, `DocumentModels.bundledMergedWith`) — so adding one model is a
five-line file, not a copy of a table of twenty that then has to be kept in step.

The argument against, that a mis-edited table breaks manifests in ways an operator cannot debug, is
answered by failing loudly: a table that does not parse, or whose `promoteTo` points nowhere, stops the
gateway at startup. That is the one deliberate difference from `IIIF_TRANSLATIONS_DIR`, which skips a bad
file and carries on — a wrong label is cosmetic, a wrong model shape is a wrong manifest.

### Do we want a server-side manifest cache? — **yes**

`ETag` went in first, and then this was measured rather than argued. A 202-canvas MZK monograph with its
dimensions already cached still costs **190-520 ms and 272 KB** to rebuild, and every rebuild produces
byte-identical output — the Solr walk over its pages and serialising a quarter of a megabyte of JSON are
real, and `ETag` does not avoid them, because computing the ETag *is* building the manifest.

So `IIIF_MANIFEST_CACHE_SIZE` is implemented (`web/ManifestCache`). The invalidation problem it was
feared for turned out to be small, because the cache inherits rules that already existed: only complete
manifests are stored, entries never outlive `IIIF_MANIFEST_TTL_MINUTES` (so a client and the gateway can
never disagree about how stale an answer may be), and `DELETE …/dimensions` evicts.

A CDN is still the better answer at scale and this does not replace one. It is what makes a deployment
without a CDN — which is every deployment today — not pay 200 ms for an answer it already has.

### Should whole-library backfill stay opt-in and off by default? — **yes**

Enabling it for a third-party library points tens of millions of requests at an institution that never
agreed to it — at MZK, 75.7 million requests over about nine days. That lazy loading means most libraries
never get good dimensions, because most pages are never viewed, is true and is the accepted cost.

This is a question about relationships between institutions rather than about software, and the answer
can change per library without changing any code.

### Is detecting the Image API version once per library, from one page, good enough? — **no, sample several**

`ImageApi` picks five public tiled pages and **every one that answers votes**. Stopping at the first
answer made the whole library's service block depend on one document: an unrepresentative page, or one
served by a different image server mid-migration, relabelled every canvas in the instance.

Disagreement is logged as a warning rather than resolved silently, and an even split resolves **down** to
Image API 2 — claiming 3 on a v2 server advertises level2 capabilities it does not have, while claiming 2
on a v3 server merely asks for less than it could give.

The page list now goes through `SolrSchema` (`publicFilter()`, `tiledFilter()`, `pidField()`) instead of
spelling K7 field names inline, which is what lets the same probe run against K5 at all.

### Should the SPI ever be published? — **no**

`iiif-kramerius-spi` is library-shaped but its only consumer is the gateway, which is why the artifact
version split in `DESIGN.md` puts it on the application side. The tripwire stays in place:
`iiif-kramerius-embedded` depends on level 2 only, and the build fails if that stops being enough. If
somebody else ever wants to implement a `Repository`, that assumption changes and the version line moves.

### The MARC country-code filter — **exact, not a shape**

`publication_places.facet` mixes codes with names: a Prague monograph is indexed `["Xr", "Praha"]`, and
`Xr` alone is 102 984 values at MZK — the second most common in the field.

The old rule was `[A-Z][a-z]{1,2}`, exact on the 400 most common values and wrong in the tail. Measured
over all 6 394 distinct values at MZK it also ate sixteen real places: **Aug**sburg (19), **Ulm**,
**Dux**, **Rom**, **Vie**, **Zug**, **Ams**, **Brn**, and others.

Now matched against the actual list, generated by `scripts/country-codes.py` into
`marc-country-codes.txt`: 334 current codes from id.loc.gov, plus the deprecated ones the libraries still
hold — `Xxr` (Soviet Union), `Yu`, `Cs`, `Uk`, `Us` — which the published vocabulary does not enumerate
and which are still codes rather than places. The script finds those by asking id.loc.gov about every
code-shaped value the libraries actually hold, so it discovers them from the data rather than from a list
somebody typed.

The single-value fallback stays: a document whose only value is a code still has to say something.

### Unmeasurable pages keep the square 1000 × 1000 fallback — **yes, and it is now rare**

Restricted documents are expected and the gateway will not be permitted to measure their pages. IIIF
offers nothing for "dimensions unknown" — the prose makes `height` optional, but the schema requires a
canvas carrying `items` to have `width` and `height` (mutually dependent) or `duration` — so a number
must be invented.

**But a 403 on the image no longer means a square.** A restricted page's thumbnail stays public —
verified on MZK's `uuid:fbaf7950-…`, whose `info.json` is 403 while its thumbnail is 200 and 12.9 KB —
and `InfoJsonSizes` reads the JPEG header of that thumbnail for the real aspect, scaled to a nominal
height of 1000. So a restricted page gets 672 × 1000 rather than 1000 × 1000: wrong in size, right in
shape, which is what a viewer lays out from.

The square survives only where even the thumbnail fails, and for a page the worker has not reached yet.

**And the manifest now says which kind of number it is carrying.** `X-IIIF-Dimensions` took a third
value, `approximate`. Before this, a restricted document returned `1327 × 2219` for a cover that answered
and `527 × 1000` for a page that did not, in the same manifest, with nothing to tell them apart and
`Cache-Control: public` over the lot. `ImageSize.isApproximate()` already knew — only the size cache ever
read it.

The header describes **how the number was obtained**, not how long it will hold. An approximation is not
permanent: it becomes a measurement as soon as the 403 stops — a licence expires, the deployment is
whitelisted, an image is re-derived — and `CachedImageSizes` already re-asks it after
`IIIF_FAILED_LOOKUP_RETRY_SECONDS` so that upgrade happens. A *measured* canvas is not permanent either;
an image re-derived upstream makes a complete manifest wrong the same way. The reason `partial` alone is
`no-store` is not that it is less certain but that work is already queued against it, so caching it would
outlive the answer it is waiting for.

This is not a small surface: 64 068 008 of MZK's 73 082 056 pages are `accessibility: private`.

### How are the viewers loaded — CDN per run, or one baked image? — **one image, both modes**

The check used to `npm install` Playwright, esbuild, React and Clover on every run and pull Mirador from
unpkg in a `<script src>`. Three costs: it needed the npm registry and a CDN to run at all, it paid the
install and the bundle every time for a result that never changes, and it pinned only the four direct
versions — the transitive graph was resolved fresh each run, so the check could still go red because
somebody else released on a Tuesday, which is the thing the pinning exists to prevent.

Worse, there were **two** viewer setups. `tests/viewer/index.html`, the page a person opens, loaded
Mirador **3.3.0** from unpkg and Clover's **web component** from jsdelivr, while the check pinned Mirador
**3.4.3** and bundled Clover's **React component**. A human was reviewing software the assertions had
never run against, and a screenshot in `out/` came from a third build again.

`tests/viewer/Containerfile` now bakes all three, and both modes read the same files: `make viewer-check`
drives them headless, `make viewer-open` serves them for a person. The run is offline and the version set
is a property of the image.

**This is what made Universal Viewer possible**, which is why it had been open since the harness was
written. UV cannot be bundled — its esm chunks resolve assets by relative path at runtime — but its umd
build derives its own base directory from the script tag that loaded it, so the shipped `dist/` works
served whole at any path, with no configuration. Serving it *is* the integration; every previous attempt
failed because it tried to import it.

Three details that cost a build each, recorded so they are not rediscovered: the viewers cannot share one
`npm install` (Mirador 3.4.3 peer-depends on react ^16.8.3, Clover needs React 18 — an outright ERESOLVE),
esbuild resolves packages from the *entry file's* directory so the entry must sit beside its node_modules,
and Playwright's node package is not in the Playwright image at all — only the browsers are.

### Is one upstream rate right for 56 institutions? — **no, per-library where declared**

`IIIF_UPSTREAM_RPS` paced every library identically. The pacer was already per library — one institution's
image server does not care whether a request came from a manifest or from the worker — but the rate it was
built with was global, so easing off for one library meant easing off for all of them.

A library in `IIIF_LIBRARIES_FILE` may now carry `"rps": N`, which wins. Absent means the global value;
`0` disables pacing for that library as it does globally. A non-integer stops the gateway rather than
falling back, because a rate limit that quietly reverts to 20 is discovered when the phone rings a second
time.

**The generator had to change with it.** `config/libraries.json` is written by `scripts/libraries.py` and
its header says hand edits do not survive — so shipping this without touching the script would have made
`make libraries` silently return a library to the global rate. `rps` is carried over on regeneration and
the file's header now says so: it is the one field there a human sets rather than the registry supplies.

**Registry mode keeps the global rate.** There is no per-library file to read, and inventing a parallel
env-var map for the same setting would give two mechanisms that can disagree. A deployment that needs
per-library rates is a deployment that should be declaring its libraries.

**Not supported inline.** `IIIF_LIBRARIES` is positional (`code=url|version|names`) and splits on `[=|]`,
so an `rps=N` token cannot be added without making the format ambiguous. Inline is documented for a single
library or a test server, where the global knob is the same thing.

### The Solr uniqueKey is per-schema, not a constant — **found by syncing K5 for the first time**

`SolrClient.detectUniqueKey()` returned `cloud ? "compositeId" : "pid"`. The standalone branch was a
literal, and it is K7's spelling: K5's uniqueKey is **`PID`**. So every cursored query against a K5
instance sent `sort=modified_date asc, pid asc`, naming a field that does not exist, and Solr answered
**400**.

Manifests never noticed, which is why it survived. Child walks on K5 sort client-side and never send the
uniqueKey at all, so every K5 manifest this gateway has ever served was built through the one path that
does not touch it. The sync walk is the only caller that cursors K5 — and until now nobody had run it.

The client takes the schema's `pidField` now. K7 is unchanged, `SolrClient(http, url, timeout)` still
defaults to `"pid"` for callers with no schema to hand, and `Libraries` passes
`adapter.schema().pidField()`.

> Two things about this are worth keeping. It was found by running the thing, not by reading it — the
> code had been reviewed several times this week. And the error was loud the moment it happened, because
> the walk had just been taught to fail explicitly rather than report an empty pass; the previous
> behaviour would have logged "0 documents" and moved the watermark on.

### What to do about `mendelu`? — **do not serve it**

It is the one library that returns no `nextCursorMark`, so its dimension sync throws rather than walks,
and its Solr answers 500 to an image probe, so it has no usable Image API either. `scripts/libraries.py`
had already dropped it from the generated static list for the second reason; registry mode reads no such
file and would have gone on serving it.

`IIIF_EXCLUDED_LIBRARIES` refuses codes whatever the registry says, ahead of both the static list and the
registry, and `Libraries.Resolution.Excluded` answers **404** with `library_excluded` — not 503, because
nothing is wrong at the far end and retrying changes nothing.

**No code names an institution.** The setting is empty by default and `mendelu` appears only in
`.env.example` as the worked example. Which libraries a deployment carries is the deployment's business,
and a hardcoded exclusion would be a policy decision baked into a jar.

### Does `/dimensions` report `approximate` too? — **yes, it had to**

`X-IIIF-Dimensions` grew a third value and this route did not, so a fully restricted document reported
`complete` from `GET /{library}/{pid}/dimensions` while the header on the identical request said
`approximate` — two surfaces describing one document differently, which is the kind of disagreement that
costs an afternoon to unpick.

`CachedImageSizes.approximateCount` counts them from the blob already in hand, so the route stays one
index count and one primary-key lookup. The count is reported as a number as well as folded into the
state, because "3 of 202" and "202 of 202" are different situations and an operator deciding whether to
chase one needs to know which.

### A Solr with no `nextCursorMark` — fall back, or fail? — **fail**

Solr ends a cursored walk by echoing back the `cursorMark` it was sent. A response with **no**
`nextCursorMark` at all means something different: that server never implemented cursorMark and answered
a plain `rows=` query, so what came back is the first batch and nothing else.

Both were read as "the cursor did not move, stop here". On a sync over 76 million pages that fetches one
batch, reports itself complete, and advances its watermark past everything it never looked at — silent,
and invisible in every log line. Of the 11 alive K5 libraries, `mendelu` 5.3.2 is the one that does this;
the other seven that answer cursor correctly, so it is a property of one deployment and cannot be decided
from the version.

A `start`/`rows` fallback was the alternative. Rejected because it changes the consistency guarantee
silently: cursorMark is stable while the index moves underneath and `start`/`rows` is not, so one library
would be walked with weaker semantics than the one beside it and nothing in the output would say so.

`SolrClient.advance()` now throws `CursorMarkUnsupported`, naming the instance. `DocumentWalk` logs it at
ERROR and records it, and it surfaces as `sync.lastError` on `GET /libraries`. The consequence is that
`mendelu` cannot be synced at all until someone chooses what it should do instead — which is the point: a
library that cannot be walked correctly should say so rather than report success.

> The stub Kramerius in the gateway tests had the same defect — it omitted `nextCursorMark` and relied on
> the misreading to end its walk. Six tests failed the moment this became an exception, which is the check
> working.

### Should the gateway return 403 when Kramerius returns 403?

**No.** Considered and rejected on four measurements.

The gateway's 403 is about *its* credentials, not the caller's. Canvas bodies point straight at the
library's own host, so a viewer fetches them with its own IP and session — a reader on-site, or with a
DNNT account, gets 200 on the exact URLs the gateway got 403 on. Refusing would deny the manifest to the
people entitled to read the book.

It is not per-document either. In MZK's `uuid:71d66fe0-…` — 202 pages, all `private`, licence `onsite` —
a sample of every fifth page found 2 that answer `info.json` and 39 that do not: the covers are open and
the body is not. There is no single status to propagate, and a manifest cannot be half-403.

And the metadata is genuinely public: Solr returns title, author, publication statement, licence and page
numbers for that document with no credentials, and the manifest already says the restriction in words —
its licence row reads "The document is not publicly accessible". A 403 would publish less than Kramerius
itself does, and break the one thing a manifest is for when you cannot see the images.

The properly-specified answer is the Authorization Flow API below.

Two alternatives stay rejected:

| | why not |
|---|---|
| extrapolate the aspect from the document's measured pages | the thumbnail gives the page's own aspect, which is strictly better |
| `placeholderCanvas` carrying the thumbnail | spec-sanctioned, but there is now a real aspect on the canvas itself |

The properly-specified answer for restricted content is the **IIIF Authorization Flow API 2.0**, where
the image service advertises probe and token services and the viewer prompts the user. **Kramerius does
not implement it** — its 403 is a bare Tomcat HTML page with no `WWW-Authenticate` and no auth service —
so there is nothing for a gateway to pass through, and inventing auth services would point at endpoints
that do not exist. That is an upstream ask, not a gateway change.

Because restricted pages are expected, a 403 is a normal outcome: logged at DEBUG, not surfaced as a
warning and not counted. It is not a signal that anything is wrong.

---

## Also decided, briefly

Scope calls that did not need an argument, listed so they are not reopened by accident. Fuller rationale
in `DESIGN.md` where there is any.

- **`rights` URI** — Kramerius licence names have no mapping to creativecommons.org or
  rightsstatements.org. Inventing one is a legal claim, not an engineering decision.
- **IIIF Content Search v2 and OCR `supplementing` annotations** — out of scope for v2, and cheap later.
- **`coords.point`** (7.2.1.1+) — a `copyField` of `coords.bbox.center`, which every K7 instance has. It
  would buy an adapter boundary and nothing else.
- **Compression of the size blob** — measured: a trained zstd dictionary adds 0.6% over what Postgres
  TOAST already does for free.
- **Artifact version split** — done: the library and the application are counted separately
  (`VERSION_CORE` and `VERSION`). See `DESIGN.md`.

## The Kramerius version is asked of the instance, not read from the registry

**Decided** 2026-09-21. The version selects the adapter, and it arrived second-hand — from the shared
registry, or from a version somebody typed into `IIIF_LIBRARIES`. Both are now a fallback; `/info` on
the instance wins.

**Against**: keeping the registry as the source and regenerating `config/libraries.json` after each
Kramerius release round, which is what the file's own header used to instruct. Also against: trusting
the registry but *verifying* asynchronously, which is the same thing with a delay.

**Corrected 2026-09-22, and the correction is the interesting part.** This entry originally said the
measurement settled it: three of 48 libraries disagreed with the registry, `svkos` recorded as 5.7.3
while running 7.1.3. That was measured against `api.registr.digitalniknihovna.cz`, which turns out to be
a **second deployment** of the registry — its own database, different row ids, months-stale rows. The
live one is `registr.digitalniknihovna.cz/api/libraries?archived=false`. Asked of it, **47 of 47
reachable libraries agree with their instance. Zero disagreements.**

So the real finding was a configuration bug — we pointed at the wrong host — and it is fixed separately.
The decision stands anyway, on a weaker and more honest argument: the registry is a *cache* of what the
instance would tell us, refreshed on its own schedule, and we are one request from the source. Between
an upgrade and the registry noticing, an instance is served by whatever adapter its stale version chose;
across the K5/K7 boundary that is not a degraded manifest but no manifest at all. One request per
library at resolve time closes the window and means nobody has to regenerate a file after a release
round.

**What it costs**: one extra request per library per health sweep (two for K5, since the K7 path is
tried first). The eight libraries that answer no `/info` are all unreachable at the root as well, so
this wakes nothing that was otherwise reachable. The declared version still stands for them.

**What it removes**: static mode's documented weakness. The file used to pin a version that went stale
and an upgraded library kept being read by the old adapter until `make libraries` ran again. Now the
file pins a url and a name, which change on the order of years.

**Still open, deliberately**: the registry's `alive: false` is still honoured, and `svkos` is still not
served even though it answers. Believing the instance about its version while believing the registry
about whether it exists is not obviously coherent — but changing it would add libraries to what a
deployment serves, which is a bigger decision than this one.
