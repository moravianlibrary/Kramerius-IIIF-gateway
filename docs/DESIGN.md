<!-- How the system works. The judgement calls behind it live in DECISIONS.md; what each Kramerius
     document model produces lives in BEHAVIOUR.md. -->

# Design

This is the decision record for v2 of the Kramerius IIIF Presentation API gateway. It says *why* things are
the way they are. It is not a description of the code — read the code for that — and it is not a plan.

## What this is

A **library** that turns Kramerius documents into IIIF Presentation API v3 manifests, plus a **service** built
on it.

v1 was a 1446-line Ruby script (`iiif.rb`) spawned per request by a 100-line Express app: a new process, a
fresh registry fetch and a MODS XML parse for every manifest, with no cache anywhere. Its output was also not
valid IIIF. See [BEHAVIOUR.md](BEHAVIOUR.md) for the specific defects; they are the reason v2 is allowed to
break compatibility.

## Four levels, five modules

The project is divided so that **what a manifest is** can be read without **how anything is fetched**, and
so that Kramerius' own application can use the middle of it without the parts that exist only because a
gateway serves libraries it does not own.

These are Maven modules rather than packages, so the direction is enforced by the build: level 1
physically cannot depend on level 3.

```
1  iiif-kramerius-model   IIIF v3 vocabulary, document shapes, child kinds, authored strings
   ↑                      Jackson only. Declarations, no behaviour.
2  iiif-kramerius-build   pure: a DocumentBundle in, an Iiif.Document out
   ↑                      ←── KRAMERIUS' OWN APPLICATION STOPS HERE
3  iiif-kramerius-spi     the contract to be read from, orchestration, per-library config
   ↑
4  iiif-gateway           adapter/  reaching Kramerius over REST, per-version model tables
                          gateway/  the service: web + worker roles

   iiif-kramerius-embedded   not a level — a conformance test of the level-2 boundary
                             depends on 2 and nothing else, and must keep compiling
```

**The fifth module is not a fifth level.** `iiif-kramerius-embedded` is a worked example of Kramerius
hosting the builder in-process, and it exists to keep one claim honest: that an application serving its own
repository needs level 2 and nothing else. Its POM depends on `iiif-kramerius-build` **only**, so if
embedding ever came to need the SPI or the adapters, the build would fail rather than the claim quietly
becoming false. It is documentation the compiler checks. See docs/KRAMERIUS.md.

### Level 1 — what a manifest looks like

`Iiif`, `LanguageMap`, `LanguageMaps`, `ManifestWriter`, `DocumentShape`, `ChildKind`, `Translations`, and
`i18n/*.json`.

Vocabulary and declarations. It answers which IIIF types exist, which shapes a document can take, and what
each thing is called in each language — and nothing else.

**It deliberately does not know which Kramerius model produces which shape.** Models are added and removed
between Kramerius releases, so that mapping is version-specific knowledge and belongs with the adapters.
Level 1 is version-neutral by construction.

### Level 2 — making a manifest look that way

`ManifestBuilder`, `Canvases`, `DocumentBlocks`, `ManifestIds`, `MetadataRows`, `ManifestConfig`, and the
data types they consume: `DocumentRecord`, `DocumentBundle`, `LibraryProfile`, `ContentLocator`,
`ImageSize`, `BoundingBox`.

**This is where Kramerius' own application stops.** It gathers records from its own Solr, fills a
`DocumentBundle`, and calls the builder. No SPI to implement, no registry, no adapter selection, no
per-library configuration — none of which an application serving its own repository has any use for.

The builder is a pure function. It used to *pull*, calling back into a repository as each shape discovered
what it needed, which meant level 2 could not exist without level 3. Being *pushed* a bundle is what makes
the construction rules independently usable, and is also why every test of them runs offline.

`DocumentBundle.requires(shape)` declares which children a host must gather, so a host does not have to
reverse-engineer that from the builder.

**Caching does not appear at this level at all.** The bundle carries page sizes; where they came from — a
cache, a live fetch, nowhere — is the host's mechanism, and nothing here asks.

### Level 3 — connecting a source to it

`DocumentSource`, `ImageSizeSource`, `ManagedImageSizeSource`, `Repository`, plus the orchestration that
uses them (`PageDimensions`, `DimensionPolicy`, `PrefetchPlanner`) and the configuration that goes with
reading something remote (`CatalogueLink`).

A gateway needs all of this: it serves many libraries it does not own, over HTTP, at several Kramerius
versions, with a cache in front. An application serving its own repository needs none of it.

### Level 4 — adapters and the service

One module, two packages.

`adapter` holds the HTTP client, Solr querying, `info.json` sizes, the version-floor adapters, and **the
model tables** — `models/kramerius-70.json` and later floors — because which models exist and what they
mean is exactly what changes between Kramerius versions.

`gateway` is the running service: routes, the two roles, the Postgres store, the job queue.

These were briefly two modules, split so the adapters would carry no web server and no database. That is
a real property, but the gateway is the only consumer of the adapters, so the split bought a module
boundary and no second reuser. If something else ever needs to read a Kramerius without being the
gateway — a batch tool, or an application serving a *remote* Kramerius — splitting again is a package
move.

### The library profile

Everything a manifest needs to say about a library travels as one `LibraryProfile`: code, id, name as a
language map, homepage, logo, license texts. Each host assembles it differently — the gateway from a
registry entry plus per-library configuration, Kramerius from what it already knows about itself — and
neither implements an interface to do it.

What is **not** in it: the Kramerius base URL, the version, the Solr topology, the politeness caps, the
model table. Those are about *reaching* a library, which a manifest has no opinion about, and they live at
level 4.

## Artifact versioning — two version lines

Two versions, counted separately, because the two halves differ in kind.

| | Modules | File | Today | Version means |
|---|---|---|---|---|
| library | `iiif-kramerius-model`, `iiif-kramerius-build`, `iiif-kramerius-embedded` | `VERSION_CORE` | 1.0.0 | a compatibility promise to someone else |
| application | `iiif-kramerius-spi`, `iiif-gateway` | `VERSION` | 2.0.0 | which build is deployed |

```sh
make set-version      VERSION=2.1.0   # the gateway
make set-version-core VERSION=1.1.0   # the library
make version-check                    # both, against the POMs
```

The library starts at **1.0.0, not 2.0.0**, and the mismatch is the point: the gateway is the second
version of a *service*, while the library is the first release of a *published artifact*. Sharing a
counter is exactly what this stopped doing.

The parent POM carries the application version and a `core.version` property; the library modules declare
their own version and everything depending on them references `${core.version}`. `make set-version` runs
`versions:set` across the reactor and then restores the library half, because the plugin has no notion of
half a project.

`iiif-kramerius-embedded` follows the **library**: it exists to demonstrate the library, it depends on
nothing else, and it is not published.

**Why the halves differ in kind.** Levels 1–2 are a library: their consumers are external, and a
version number is a compatibility contract. Level 4 is an application: nobody depends on it, and its
version is a deployment marker whose "breaking change" means config, routes or DB schema rather than a
Java API.

**The line is two-way, not per module.** Level 3 looks like a library but its only consumer is the
gateway — an embedded host does not need it, and `iiif-kramerius-embedded` proves that by depending on
level 2 alone. So the split, when it comes, is:

| | Modules | Version means |
|---|---|---|
| library | `iiif-kramerius-model`, `iiif-kramerius-build` | a compatibility promise to someone else |
| application | `iiif-kramerius-spi`, `iiif-gateway` | which build is deployed |

which is the same boundary the whole design already rests on: level 2 is where Kramerius stops.

**What lockstep would have cost.** Bumping the library to 2.3.0 because the gateway added a route tells a
consumer that something changed for them when nothing did. That is the failure this avoids, and it starts
the moment anything outside this repository depends on the library — Kramerius itself, most likely.

**The cost of the split** is one extra decision per release: did the library actually change? If not, only
`make set-version` runs and `VERSION_CORE` stays put.

**The tripwire:** if level 3 ever gains a consumer that is not the gateway, it belongs in the library half
and this table moves. `iiif-kramerius-embedded`'s POM is what will tell you — it depends on level 2 only,
and the build fails if that stops being enough.

## Adapter versioning — by Kramerius release, not by ours

Each adapter declares the **first Kramerius version it supports** and is named for it. Selection is not a
`switch` in a factory — every adapter answers for itself, so adding one is a single registration:

```java
interface KrameriusAdapter {
    String name();                       // "Kramerius714"
    Version floor();                     // 7.1.4
    Version testedUpTo();                // 7.2.2
    boolean supports(InstanceInfo info); // default: floor() <= info.version()
}
```

`AdapterRegistry` holds them ordered by floor descending and returns the first whose `supports()` is true:

```
5.8.4.4  -> Kramerius50      7.1.4   -> Kramerius714
7.0.39   -> Kramerius70      7.2.2   -> Kramerius714
7.1.1    -> Kramerius70      7.3.0   -> Kramerius714   (newer versions inherit the newest adapter)
4.6.2    -> refused, naming the lowest supported version
```

A new adapter is added when a release changes behaviour. It **extends the previous one and overrides only what
changed** — `Kramerius714` overrides three things and inherits the rest. Adding one never touches the adapter
below it, and an instance on a version nobody has tested keeps working.

`supports()` is overridable rather than a bare version comparison so a future adapter can key on something
else — an instance quirk, a probe result — without reworking the mechanism.

Two rules keep the chain from rotting, and they are the entire cost of this approach:

- **Shared query, URL and HTTP machinery lives in `common`, never in an adapter.** An adapter is field names,
  paths and capability answers — small enough that a subclass is a page of code, not a fork.
- **Every adapter runs the same fixture suite**, parameterised over the adapter under test, so a subclass
  cannot silently break a path it inherited.

Version parsing is lenient by necessity: the registry returns `7.2.1.1`, `7.0.31-dev`, `5.8.4.4`,
`7.2.2.1-dev7`. Compare component-wise on the numeric prefix, ignore suffixes.

**A version boundary only earns an adapter if it affects a field we actually use.** `coords.point` arrives at
7.2.1.1 and is ignored, because it duplicates `coords.bbox.center`, which every K7 version has.

## The gateway service

One fat jar, **two roles** sharing the same Postgres:

```
java -jar iiif-gateway.jar web       serves manifests; no long-running work
java -jar iiif-gateway.jar worker    dimension jobs + sync; serves nothing
```

The split exists because a full library walk runs for weeks: it must not compete with request serving, must be
pausable and scalable independently, and must not be interrupted by every web deploy.

### Routes

Public listener (`IIIF_PORT`):

| Route | Behaviour |
|---|---|
| `GET /{library}/{pid}` | the manifest. ETag + `Cache-Control`, `304` on revalidation. `X-IIIF-Dimensions` is `partial` (provisional, `no-store`), `approximate` (thumbnail-derived shapes, cached), or absent (all measured) |
| `GET /{library}/{pid}/full` | `301` to the canonical URL — real sizes are unconditional now |
| `GET /{library}/{pid}/dimensions` | `{total, cached, missing, state, fetchedAt}`, read from the store |
| `GET /resolve?link=…` | parse library + pid from a Digital Library URL; `400` if unparseable |
| `GET /libraries` | per library: version, selected adapter, whether past its `testedUpTo`, Solr topology, reachability, sync progress |
| `GET /` | static landing page from the app's own resources |
| `GET /health` | store reachable, plus the library health summary |
| `GET /version` | version + git short SHA from `SYSTEM_VERSION` / `SYSTEM_COMMIT` |

Admin listener (`IIIF_ADMIN_PORT`, not published):

| Route | Behaviour |
|---|---|
| `PUT /{library}/{pid}/dimensions` | assert the dimensions should be cached. `200` if already complete (no work), `202` if started. Fully recursive, no limits |
| `DELETE /{library}/{pid}/dimensions` | invalidate, so the next request re-resolves. `DELETE` then `PUT` is a forced renewal |
| `PUT /libraries/{library}/sync` | enable, pause, or rewind. Rewinding the watermark to epoch is what "backfill this library" means. A `refresh` flag re-fetches even cached pages, overwriting in place |

CORS `*` on every response — IIIF viewers require it. Errors are JSON with a machine-readable `code` and a
bilingual message: unknown pid → `404`; a model with no v3 representation → `404` with a distinct code. v1
answered both `200`. **A failure at an upstream Kramerius is a `502`, `503` or `504`, never a `500`** — the
full table is in [BEHAVIOUR.md](BEHAVIOUR.md#errors). The kind travels as a field on a typed exception
rather than in its message, so the web layer never decides a status code by matching on text.

Library resolution is config: `IIIF_REGISTRY_URL` set → registry mode, which also selects the adapter from the
registry's `version`; `IIIF_LIBRARIES=code=url|version` or `IIIF_LIBRARIES_FILE=…json` → static mode. Same
routes either way, and the two static forms produce the same record, so nothing below `Config` knows which was
used. The file exists because one environment variable stops scaling at about five libraries — fifty of them
is four kilobytes on one line with nowhere to put a comment. Static mode's cost is the pinned `version`: the
health checker re-reads it from the registry every `IIIF_LIBRARY_CHECK_MINUTES` and drops a library that has
been upgraded so the next request rebuilds it on the right adapter; a file has nothing doing that, so an
upgraded library keeps being read by the old adapter until `make libraries` is run again.

### Why an admin port and not a bearer token

A static shared token has no rotation, no identity and no revocation, and is a secret to store and leak.
Binding write routes to an unpublished interface needs no secret at all and puts access control in the
deployment layer, where an ops surface belongs. If per-caller identity is ever genuinely needed, delegate to
an external IdP rather than inventing tokens.

### Abuse control

Three separate concerns, deliberately not conflated:

- **Per-IP rate limiting** on every public route (`IIIF_RATE_LIMIT_RPS` / `_BURST`), `429` with `Retry-After`.
- **Admin listener** for write routes, as above.
- **Politeness toward upstreams** — a per-library concurrency cap and request rate, shared by both roles. This
  matters more than the inbound limits: the gateway is a client of 50-odd third-party servers, and the worker
  can issue tens of millions of requests to one of them.

## Dimensions

A canvas must state width and height, and the only source over HTTP is the image server's `info.json` — one
request per page. This is where all the cost is, and it drives more of the design than anything else.

### Cold path: predict, don't time out

`count_page` arrives in the very first Solr response, before any image server is touched, so a request knows
the size of the job up front:

```
uncached = count_page − entries already cached
estimate = ceil(uncached / concurrency) × observed_latency     # EWMA, per library
estimate ≤ IIIF_DIMENSION_WAIT  →  resolve inline, return a complete manifest
estimate >  IIIF_DIMENSION_WAIT  →  enqueue for the worker, return partial immediately
```

**p50 is 12 canvases**, so half of all documents resolve completely in well under a second and the common case
is always accurate. A plain time budget would instead make every request for a 3000-page cold document burn
the full budget and *still* return partial — slow and wrong at once. Predicting means nobody waits for an
answer that was never going to be complete.

The budget survives as a hard cap for when an upstream is slower than the EWMA predicted: the request stops,
returns partial, and hands the remainder to the worker. Whatever was fetched is still stored.

**Because the request resolves inline, it never waits on another process.** That is what keeps web↔worker
messaging one-directional — there is no completion signal to listen for.

### No failed lookup is permanent

An image lookup either yields a size or it does not, and a failure is **always** recorded with an expiry
(`IIIF_FAILED_LOOKUP_RETRY_SECONDS`, default 86 400 — a day) rather than forever.

The record exists so a restricted document is not re-probed on every request. The expiry exists because
nothing a failed lookup tells us is a settled fact about the image:

- **403** is about the *caller*. Kramerius grants access by licence, and `onsite` means **by IP**. Measured
  at MZK: one document exposed 6 of its 202 pages to an anonymous client and answered 403 for the other
  196, having answered for all 202 from inside the library network three days earlier.
- **404**, against a page Solr says has tiles, is an *inconsistency*, not a fact. Kramerius derives tile
  pyramids asynchronously, so a 404 can precede a 200; an image server mid-migration or misconfigured
  answers 404 for everything.
- **Timeouts and 5xx** are obviously transient.

An earlier version of this made 404 permanent and expired only 403. That was wrong twice: it would poison
every page touched during an image-server outage, and the case it protected against cannot arise — a page
that is genuinely deleted loses its Solr record too, so it is never asked about again. Probed live, 40 of
40 public tiled pages at MZK answer 200, so the permanent category was empty in practice as well.

Consequence worth stating: whether a restricted document ever gets real canvas dimensions depends on where
the worker runs — but it is now *recoverable*. Move the worker onto the library network and the cache
repairs itself within the retry window, instead of the first answer being frozen forever behind a manifest
that still validates.


### Invalidation

A cached entry is a hit only if `fetched_at >= modified`, **checked on every read**, not only by the sync
loop. It costs nothing, because `modified` is already in the `fl=` of the query that fetches each page's label
and number. It matters because sync is opt-in per library: for most libraries the request path is the only
thing that will ever notice a replaced image.

That leaves exactly one case for `DELETE …/dimensions`: an image re-derived without its Fedora
`lastModifiedDate` changing, which no automatic rule can detect.

### Storage: one blob per document

The access pattern is always "every page of this document", so the document is the natural key:

```sql
iiif_document_sizes(library text, pid text,
                    pages bytea,          -- version byte, then [uuid16 | w:int32 | h:int32] * n
                    api_version smallint, fetched_at timestamptz,
                    primary key (library, pid))
```

A row per page would pay Postgres' 23-byte tuple header plus a duplicated primary-key index 75.7M times —
~7.6 GB at MZK against ~1.8 GB here — and force `pid IN (?, ?, …)` batched at 500 on read, because pgjdbc
cannot bind a `bytea` array. One row per manifest replaces eight queries and that whole workaround.

It also removes the need for a separate completion marker: the row *is* the record that a document was done,
its entry count against `count_page` says whether it was done completely, and `fetched_at` says when. That is
why the recursive prefetch needs no depth or size limit — a re-run skips finished subtrees in one lookup each.

Page pids are `uuid:<uuid>` in practice, so 16 binary bytes replace 41 of text; that substitution is most of
the saving. A non-uuid pid is stored as its UTF-8 form and cannot collide, since any 16-byte entry is a uuid
by construction and a spelled-out pid is longer.

A page whose image server refused to answer is written with `w = h = 0`, meaning "asked, no answer" — without
that sentinel it is indistinguishable from unfetched and retried forever. Pages with no tiles are simply
absent.

Trade-offs accepted: one changed page rewrites its document's blob (rare, ~85 KB at the largest), and a page
fostered into several parents is stored once per parent.

**Not compressed, deliberately** — see the measurements below.

### The sync loop

"Whole-library backfill" and "incremental sync" are the same walk:

> from `watermark`, page through `modified:[watermark TO *] AND model:page AND has_tiles:true` by cursorMark
> in **`modified asc`** order, fetching `info.json` for pages that are missing — or cached but whose
> `modified` exceeds `fetched_at` — advancing the watermark to the greatest `modified` in each batch **as that
> batch completes**.

The only difference is where the watermark starts. **Seeded at epoch it is a backfill; seeded at now it tracks
only new material.** One loop, one state row, one code path.

The sort order is load-bearing. Because the walk is ordered by the same field the watermark tracks, and the
watermark moves after every batch, **the watermark alone is a valid resume point**: a run that dies on day 12
restarts where it stopped even if the cursorMark is unusable. The cursor is the fast path; the watermark is
the one that always works.

Ties are common — a whole document is ingested at one instant — so the watermark cannot advance past a tie
boundary safely. The overlap window (`IIIF_SYNC_OVERLAP`, default 10 min) covers it, and re-processing
already-cached pages costs a lookup and no upstream requests.

**Why `modified` and not `indexed`:** what we cache is a function of the *image*, not of the index. `indexed`
records when Solr last wrote the document, so it can only over-trigger — a full reindex bumps it on all 76M
pages and forces a complete re-scan that fetches nothing. The field name is adapter-declared: K7 `modified`,
K5 `modified_date`.

The residual gap — an object ingested long ago but indexed only now, carrying an old `modified` — affects well
under 1% of documents and is covered by lazy loading on first request.

### Messaging: one direction, no broker

```sql
-- web, in the same transaction as the state change
INSERT INTO iiif_dimension_job … ON CONFLICT (library, pid) DO NOTHING;
NOTIFY iiif_job;

-- worker
LISTEN iiif_job;
SELECT … FROM iiif_dimension_job WHERE state = 'queued' FOR UPDATE SKIP LOCKED LIMIT :batch;
```

Three properties make Postgres the right tool rather than a compromise. The enqueue **commits atomically with
the state it describes**, which no external broker can offer against a Postgres write. `NOTIFY` is only a
wakeup, so a worker that is down loses nothing — the row is still queued when it returns. And
`FOR UPDATE SKIP LOCKED` is the standard queue claim, so several workers need no coordination.

Redis or RabbitMQ would mean a second service to run, monitor and back up, and would split job state away from
the data it is about — for one job type flowing one way between two roles that already share a database.

## Solr access

Queries use per-`ChildKind` `fl=` and `sort=`, with quoted and escaped field matches so a crafted pid cannot
inject query clauses. (v1 interpolated the raw path segment straight into `q=`.)

### cursorMark, not `start`/`rows`

Deep `start`/`rows` paging degrades and can duplicate or skip rows if the index changes between requests — a
real risk while a library is reindexing. Constraints it imposes, all of them design decisions:

- **Every sort must end in the uniqueKey as a tiebreaker.** cursorMark is undefined without a total ordering.
- **`start` must not be sent.** Walk `cursorMark=*`, follow `nextCursorMark` until it repeats.
- **Sort fields must be single-valued.** K5's multi-valued `rels_ext_index` cannot be a cursor sort field, so
  `Kramerius50` falls back to `start`/`rows` with client-side ordering.
- `count_*` gives the expected total, so a walk returning a different number is index drift and gets logged
  rather than silently serving a short manifest — precisely v1's failure.

**The tiebreaker field depends on the Solr deployment and must be detected.** Kramerius runs on SolrCloud or
standalone Solr, and the uniqueKey differs; Kramerius' own OAI walk hardcodes `sort=compositeId asc`
(`rest/.../oai/OAISet.java:201`), correct for SolrCloud and wrong elsewhere. On SolrCloud `sort=pid` fails
outright; on standalone `compositeId` does. This is **deployment topology, not a release feature**, so unlike
the index-field boundaries no version predicts it and it cannot live in an adapter. It is detected per library
from `responseHeader.zkConnected`, which the client API passes through, in the same startup sweep the library
health checker performs. If the chosen sort errors anyway, the other is tried once and logged.

So: `sort=page.index asc, <uniqueKey> asc`.

### Batch size and result size

`IIIF_SOLR_PAGE_SIZE` (default 500) is an internal batch size, never an output cap. `IIIF_MAX_ITEMS`
(default 50 000) is a sanity ceiling an order of magnitude above the largest real document; exceeding it
returns a clear error rather than exhausting memory. v1's `rows=1500` conflated the two and silently truncated
real MZK documents — a 3128-page monograph served 1500 canvases with no indication.

Responses are streamed and gzipped, so peak memory is bounded by the batch size rather than the document size.

## Rights

`requiredStatement` carries attribution plus license text, bilingual, from the registry entry and
`/client/v7.0/ui-config/licenses` (public; `/admin/v7.0/licenses` is `permitEdit`-gated).

**`rights` is deliberately absent.** IIIF requires a URI from creativecommons.org or rightsstatements.org, and
Kramerius license names are `dnnto`, `dnntt`, `knav_vip`. Mapping them would be a policy decision with legal
weight, not an engineering one.

## How it is verified

Four of these run against somebody else's live server, which is why only the offline ones are in the gate:
a third party being down must not be able to fail `make check`.

| Target | What it does | In `make check` |
|---|---|---|
| `test` | Fixture-driven, offline. Exact manifest JSON per model, **plus IIIF v3 schema validation** | yes |
| `test-integration` | Postgres store and `iiif_library_sync` against `make db-up`. Asserts the three failure-only properties: prefetch resumes without redoing finished subtrees, a killed sync resumes at its watermark, concurrent workers never double-process. 8 tests, and they SKIP rather than fail without `IIIF_TEST_DB_URL` | no |
| `smoke` | Live instances, one document per model, **one library per adapter** (pre-7.1.4, post-7.1.4, K5) so every fallback is exercised against real data | no |
| `capabilities` | The field probe over every alive registry entry. How the *next* version boundary gets discovered | no |
| `libraries` | Regenerates `config/libraries.json` from the registry, probing each instance rather than trusting its `alive` flag | no |
| `country-codes` | Regenerates the MARC country-code list from id.loc.gov, plus the deprecated codes the libraries still hold | no |
| `viewer-check` | Playwright + pinned Mirador 3.4.3 and Clover 2.14.0 in a container. DOM assertions; screenshots to `tests/viewer/out/` for review | no — third-party viewers must not be able to fail the gate |
| `viewer-open` | Serves fixtures and opens the local viewer page via `xdg-open` | no |
| `db-size` | Cached pages and bytes per page, so the ~1 GB-per-large-library figure stays honest | no |

## Measurements

Established once, against live instances. Recorded so they are not re-derived — and so they can be checked
when they go stale.

### The registry

`https://api.registr.digitalniknihovna.cz/api/libraries` carries `code`, `sigla`, `name`, `name_en`, `alive`,
`version` as a full string, `url`, `logo`, `web`, `licenses`, and per-model counts. v1 fetched this on every
request and used five fields. `name_en` is what makes bilingual labels free; `web` is the institution's own
site, a better `homepage` than the Kramerius host v1 used; `alive` lets a dead library fail fast.

56 libraries: **K7 40 (35 alive), K5 15 (11 alive), K4 1.** K7 spans 7.0.23 → 7.2.2.1-dev7.

### The 7.1.4 index boundary

Probed with `fq=<field>:[* TO *]&rows=0` against a **narrow** query (`q=model:periodical`), distinguishing an
HTTP error (field rejected) from a transport timeout (inconclusive). *That distinction matters: a first
attempt used `q=*:*` over 76M documents, timed out, and scored the timeouts as "field missing" — producing a
wrong table.* `scripts/capabilities.sh` encodes both, or it will lie the same way.

| Instance | Version | `has_alto_content` | `has_text_ocr_content` | `coords.is_point` | `count_volume` |
|---|---|---|---|---|---|
| cbvk | 7.0.39 | ✗ | ✗ | ✗ | ✗ |
| svkhk | 7.0.41 | ✗ | ✗ | ✗ | ✗ |
| uzei | 7.1.0 | ✗ | ✗ | ✗ | ✗ |
| cvti | 7.1.1 | ✗ | ✗ | ✗ | ✗ |
| idu | 7.1.3.1 | ✗ | ✗ | ✗ | ✗ |
| **d** | **7.1.4** | ✓ | ✓ | ✓ | ✓ |
| ntk | 7.1.7 | ✓ | ✓ | ✓ | ✓ |
| mzk | 7.2.2 | ✓ | ✓ | ✓ | ✓ |

Present on every K7 instance from 7.0 up: `has_tiles`, `page.placement`, `licenses`, `accessibility`,
`count_page`, `id_sysno`, `page.index`, `part.number.sort`, `rels_ext_index.sort`, `track.length`, `modified`,
`indexed`, `created`, and all of `coords.bbox`, `coords.bbox.center`, `coords.bbox.corner_sw`, `corner_ne`.

`Kramerius70`'s fallbacks — the whole delta:

| Missing below 7.1.4 | Fallback |
|---|---|
| `has_alto_content`, `has_text_ocr_content` | none needed — see below, the field is empty everywhere |
| `coords.is_point` | derive it: a point is a bbox whose SW and NE corners coincide |
| `count_volume` | no expected total to check the walk against; page until the cursor repeats |

navPlace needs no fallback at all, because the bbox corners are present on every K7 version.

#### The table above measures schema acceptance, not population — and for two fields they differ

**This corrects the Phase 5 assumption that the index removes the ALTO probe.** A field that is defined in
the schema but holds no values answers `fq=<field>:[* TO *]` with `numFound: 0` and HTTP 200 — which the
probe scores as "present", because it only distinguishes an error from a non-error. It measures *is this
field defined*, not *does this field have data*. For `coords.is_point` and `count_volume` the two agree. For
the two OCR fields they do not:

| Instance | Version | pages | `has_alto_content` populated | `has_text_ocr_content` populated |
|---|---|---|---|---|
| mzk | 7.2.2 | 76 455 203 | **0** | **0** |
| knav | 7.2.2 | 6 228 108 | **3** | **3** |

So `has_alto_content` is unusable in practice, on instances that have it. The data is there regardless:
73 839 443 of MZK's 76 455 203 pages have `text_ocr` indexed, and the ALTO stream returns a valid document
even for a front jacket with no text (1 203 bytes; a nonexistent pid 404s). `text_ocr` itself cannot be read
per page — it is indexed but not stored, so it never comes back in `fl`.

**Consequence:** an absent `has_alto_content` means *emit the ALTO link*, not *omit it*. Treating silence as
"no OCR" would drop `seeAlso` from every page in the country. An instance that does populate the field is
still believed — an explicit `false` omits the link — which is what the three-state `Optional<Boolean>` is
for. There is no probe and no per-document fallback, so `Kramerius70` and `Kramerius714` behave identically
here and the 7.1.4 boundary is real only for `coords.is_point` and `count_volume`.

`scripts/capabilities.sh` reports both numbers now. A field that is defined and empty is worse than one that
is absent, because it looks available.

### Image API version: 2 and 3 are both in the field, and it is detected

Measured from `info.json` on a tiled page per instance:

| Instance | Version | `@context` | type | profile |
|---|---|---|---|---|
| cbvk | 7.0.39 | image/2 | ImageService2 | (array) |
| svkhk | 7.0.41 | image/2 | ImageService2 | (array) |
| ntk | 7.1.7 | image/2 | ImageService2 | (array) |
| knav | 7.2.2 | **image/3** | **ImageService3** | **level2** |
| mzk | 7.2.2 | **image/3** | **ImageService3** | **level2** |

Consistent with a boundary at 7.2, but **two instances cannot prove one**, and an operator can replace the
image server independently of the Kramerius release. So this is treated like the SolrCloud cursor tiebreaker:
**detected from `info.json`, not declared by an adapter.** It lives on `ContentLocator` because it is a
property of the library's image server, and it is read once per manifest rather than per page — otherwise one
manifest could declare `ImageService2` on some canvases and `ImageService3` on others.

It decides two things together, which must agree: the service block (`ImageService2` + a level URI, versus
`ImageService3` + `level2`) and the image URL (`/full/full/` versus `/full/max/`).

The default when unmeasured is **2**, because it is the safe one: every instance measured serves both
`/full/full/` and `/full/max/`, so a conservative declaration works everywhere, while claiming
`ImageService3` on a v2 server advertises level2 capabilities the server does not have.

v1 declares `ImageService2` with `level1` unconditionally. It is wrong about the type on 7.2 instances and
wrong about the level on all of them — MZK reports `level2`.

### `publication_places.facet` mixes MARC country codes with place names

| | |
|---|---|
| Total values at MZK | 414 323 |
| Values that are MARC country codes | 109 658 |
| `Xr` (Czechia) alone | 102 984 — the second most common value in the field |

A Prague monograph is indexed `["Xr", "Praha"]`, so taking the first element prints a country code where a
city belongs, on roughly a quarter of documents. v1 read MODS and did not have this problem; reading the
index only avoids it by filtering.

**Matched against the actual code list, not against a shape.** The first rule here was
`[A-Z][a-z]{1,2}` — exact on the 400 most common values, and wrong in the tail: over all 6 394 distinct
values at MZK it also dropped sixteen real places, including `Aug`sburg (19 documents), `Ulm`, `Dux`,
`Rom`, `Vie`, `Zug`, `Ams` and `Brn`. A place is not a code because it is short.

`scripts/country-codes.py` generates `marc-country-codes.txt`: 334 current codes from id.loc.gov, plus
the deprecated ones the libraries still hold (`Xxr`, `Yu`, `Cs`, `Uk`, `Us`), which the published
vocabulary does not enumerate. The script finds those by asking id.loc.gov about every code-shaped value
the libraries actually hold, so the list comes from the data rather than from memory. Comparison is
case-insensitive — LoC publishes `xr`, Kramerius indexes `Xr`.

`S.l` (*sine loco*) is dropped by name. If nothing survives the filter the original values are kept, so a
document whose only value is a code still says something.

### Document sizes at MZK

| Single document | Max children |
|---|---|
| Periodical issue → canvases | **3568** |
| Monograph → canvases | 3128 |
| Virtual collection → members | 3154 |
| Volume → issues | 1274 |
| Periodical → volumes | 151 |
| Monograph → units | 55 |
| Sound recording → units / unit → tracks | 48 / 20 |

Canvases per manifest across 987 954 documents: **p50 = 12**, p90 = 233, p95 = 332, p99 = 581, p99.9 = 1040.
Issues per volume: p50 = 1, p95 = 52, p99 = 307.

A 300k-page periodical is 151 volumes × up to 1274 issues × ~30 pages — **the tree distributes the pages**, so
no single document exceeds ~3600 items (~2 MB raw, ~180 KB gzipped). IIIF v3's lack of any pagination
mechanism for `items` therefore costs nothing, and no chunking into synthetic sub-collections is needed.

Batch 500 covers roughly p98 in a single round trip. Larger trades latency per request for a benefit only the
last 2% of documents would see.

### The dimension cache is a bulk-data problem

| | |
|---|---|
| Pages at MZK | **76 447 122** |
| With tiles | **75 715 079** |
| Storage, blob layout + TOAST | ~1.0 GB for this one library |
| Full walk at 40 req/s | ~22 days |
| Full walk at 100 req/s | ~9 days |

There is no bulk dimension source over HTTP. So:

- **A full walk is opt-in per library and off by default.** Pointing 76M requests at your own Kramerius is
  reasonable; pointing them at fifty third-party institutions that never agreed to it is not. Un-enabled
  libraries are populated lazily by real traffic, which is where the value is — most pages are never viewed.
- **It cannot run in the request-serving JVM.** Hence the `worker` role.
- **In-process access does not avoid this.** Most libraries serve tiles from a separate image server rather
  than from Akubra, so an embedded host pays the same `info.json` per page and needs the same cache and the
  same walk. Embedding saves the Solr and metadata round trips, not the dimension lookups.

### Compression: measured, and rejected

Held-out real pid lists (dimensions synthesised):

| Encoding | B/page | MZK total |
|---|---|---|
| Raw | 24.00 | 1.82 GB |

**Measured on the implemented encoding, live.** A 202-page monograph stores 5 253 raw bytes — exactly
26 B/page — which Postgres TOASTs to 1 906, or **9.4 B/page**. That is better than the 13.19 estimated
below, so the ~1.0 GB figure for MZK is if anything pessimistic. `make db-size` reports this on real data.

*(The implemented encoding is 26 B/page — 1 tag + 16 uuid + 4 width + 4 height + 1 Image API version —
because it also records which Image API answered, and tags each entry so a non-uuid pid stays
representable. The compression measurements below were taken on the 24-byte layout; the ratios carry over,
so the TOAST figure is ~1.08 GB rather than 1.00 GB at MZK. `make db-size` is what keeps this honest on
real data.)*

| Postgres TOAST, automatic above 2 KB | 13.19 | **1.00 GB** |
| zstd-19 | 10.69 | 0.81 GB |
| zstd-19 + trained dictionary | 10.62 | 0.80 GB |

A trained dictionary adds **0.6%**. The redundancy is the shared 10-byte suffix of v1 uuids *within* one
document — 81% of sampled page pids are v1 — which zstd already finds inside a single blob, so a
cross-document dictionary has nothing left to learn.

Explicit zstd buys ~190 MB over what TOAST does for free, which does not justify a native `zstd-jni`
dependency and CPU on every read. The leading version byte keeps this cheap to revisit: the blob is opaque
behind the store interface, so a compressed encoding v2 needs no migration if `make db-size` on real data ever
disagrees.

### Solr topology

| Library | `zkConnected` | `compositeId` | `sort=compositeId` | `sort=pid` |
|---|---|---|---|---|
| mzk | `True` | present | **200** | **500** |
| cbvk | absent | absent | 400 | **200** |
| ntk | absent | absent | 500 | **200** |
| vkol | absent | absent | 500 | **200** |

### K5, measured

Read off three live instances — `www.ndk.cz` 5.8.4.4 (the National Library), `kramerius.medvik.cz` 5.8.4.5
and `kramerius.vugtk.cz` 5.4.2. K5 differs from K7 **structurally**, not cosmetically, which is why
`Kramerius50` sits beside the K7 chain rather than extending it:

| | K7 | K5 |
|---|---|---|
| Solr path | `/search/api/client/v7.0/search` | `/search/api/v5.0/search` |
| uniqueKey | `pid` | **`PID`** |
| model | `model` | `fedora.model` |
| title | `title.search` | `dc.title` |
| author | `authors` | `dc.creator` |
| parent | `own_parent.pid` | `parent_pid` (**multi-valued**) |
| ordering | `page.index` | `rels_ext_index` (**multi-valued**) |
| page number + type | `page.number`, `page.type` | `details`, `"number##type"` packed |
| tiles | `has_tiles` | `viewable` |
| accessibility | `accessibility` | `dostupnost` |
| licences | `licenses` | `dnnt-labels` |
| page count | `count_page` | `pages_count` |

**The multi-valued fields are the expensive difference.** cursorMark requires single-valued sort fields, so
K5 cannot use it: the walk falls back to `start`/`rows` and sorts client-side, because asking Solr to sort
on a multi-valued field is an *error* rather than a degradation.

**`##` is K5's packing convention throughout.** `details` is `"number##type"`, `browse_title` and
`search_title` are `"SORTFORM##Display form"`, `browse_autor` likewise. The half before the separator is
accent-stripped and uppercased, so anything read for display takes the half after it.

**`parent_pid` contains the document's own pid** for a top-level object, so the self-reference has to be
skipped or every monograph becomes part of itself.

#### K5's IIIF Image API works on some deployments and not others

On ndk.cz and vugtk, `/search/iiif/{pid}/info.json` answers **200 with a zero-byte body**, and every real
image request under it — `full/full`, `full/max`, a region — answers **404**, while
`/search/api/v5.0/item/{pid}/streams/IMG_FULL` serves the image normally (1.2 MB). Three others —
`lmda` 5.8.1, `dsmo` 5.8.1, `kvkli` 5.8.3 — answer with a complete Image API 2 `info.json` carrying real
`width`/`height`, and serve `full/full/0/default.jpg`. Three more answer 500. The Kramerius version does
not predict which: 5.8.1 works, 5.8.4.4 is empty, 5.3.2 is a 500. It tracks whether that particular
deployment has an image server wired up. Full sample in docs/DECISIONS.md.

So it is **probed per library**, not declared. `ImageApi.probe` samples five public pages once at resolve
time and counts a page only if its `info.json` describes an image, which is what keeps K5's
200-with-an-empty-body from reading as a working server. A library that answers gets an `ImageService2`
and real dimensions; one that does not gets the plain stream and no `service` block, because declaring a
service there is a claim a viewer acts on and it would try to fetch tiles and fail.

`ContentLocator.hasImageService()` carries the answer, so the builder still needs no knowledge of K5 — the
probe changed that one method and nothing below it. `KrameriusAdapter.imageServiceIsCertain()` is what
says the question applies at all: false for K5, true for K7, where a probe finding nothing means an
unlucky sample rather than a missing service.

#### K5's model set is genuinely narrower

Union across the three instances: `page`, `monograph`, `monographunit`, `periodical`, `periodicalvolume`,
`periodicalitem`, `supplement`, `article`, `internalpart`, `chapter`, `map`, `sheetmusic`, `graphic`.

**Absent from every instance measured:** `soundrecording`, `soundunit`, `track` (K5 has no audio model at
all), `collection`, `convolute`, `archive`, `manuscript`, `picture`, `oldprintsheetmusic`. They are not in
`models/kramerius-50.json`, because listing them would be inventing capability.

K5 also has no foster relationship, so `fostersPages` is false everywhere — one of the few places K5 is
simpler than K7.

#### Timestamps

K5 shares no timestamp field name with K7:

| | K7 | K5 |
|---|---|---|
| Index write time | `indexed` | `timestamp` |
| Object change time | `modified` | `modified_date` |
| Creation | `created` | `created_date` |

Note the registry's `url` for some K5 entries redirects (301) — follow redirects, and do not read a redirect
as a missing field.
