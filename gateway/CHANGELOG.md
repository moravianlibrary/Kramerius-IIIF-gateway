# Changelog — gateway

The service: the SPI, the Kramerius REST adapters and the application. Versioned separately from the
library, whose changelog is [`../iiif-kramerius/CHANGELOG.md`](../iiif-kramerius/CHANGELOG.md) — this
number says which build is deployed, that one is a compatibility promise to whoever embeds the library.

Entries here are service behaviour. A change that alters what a manifest *contains* usually has a
library half too, and is recorded in both.

Following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed
- **Every MZK manifest declared the wrong image service.** The Image API probe's sample query sorted
  by `pid`, but a Solr cursorMark query is a 400 unless its sort names the index's uniqueKey — and on
  SolrCloud that is `compositeId`. So the probe failed outright on every SolrCloud instance, listed no
  pages, and fell back to Image API 2 while MZK serves Image API 3: canvases carried `ImageService2`,
  `profile: http://iiif.io/api/image/2/level2.json`, `@id`/`@type` and `/full/full/`. Nothing looked
  broken, because a failed probe is not an error and MZK's v3 server accepts the v2 URL spelling too.
  `ImageApi` now asks `SolrClient.uniqueKey()`, as every other cursored query already did. Verified
  live: MZK now reports `ImageService3`, `profile: level2` and `/full/max/`.
- **`IIIF_REGISTRY_URL` pointed at the wrong registry.** `api.registr.digitalniknihovna.cz` is a
  second deployment with its own database — different row ids — and rows months out of date: it had
  `svkos` at 5.7.3 and dead, while the instance runs 7.1.3 and answers. Nine libraries had a
  different `alive` flag between the two, and `snk` existed only on the stale one. The live registry,
  and what the registry's own web UI serves, is
  `registr.digitalniknihovna.cz/api/libraries?archived=false`. The IIIF `provider` id also moves from
  an API URL on that host to `registr.digitalniknihovna.cz/library/{code}`, the page a human can
  read, which is the URL Kramerius itself gives for a library.
- **The Kramerius version is now asked of the instance** at `/info` rather than taken from the
  registry or a config file; both are a fallback. Not because the registry is wrong — against the
  live one, all 47 reachable libraries agree with their instance — but because it is a cache
  refreshed on its own schedule, and between an upgrade and the registry noticing, an instance is
  read by whatever adapter its old version chose. Across the K5/K7 boundary that is no manifest at
  all rather than a worse one. A disagreement is logged and overruled; an instance that answers
  nothing keeps the declared version. `/libraries` reports the version a resolved library was built
  on.
- **`GET /{library}/{pid}/dimensions` contradicted `X-IIIF-Dimensions` for a document with no tiled
  page.** The manifest carried no header — there is nothing to measure, so it is complete — while the
  route required `total > 0` before it would say so and answered `partial`. Not a corner case: MZK
  indexes 732 043 untiled pages, and `uuid:7891f49f-74be-4ed8-b65a-a3ba5df01ff0` is a 363-page monograph
  made entirely of them. The two were computed independently, from the bundle and from two index counts,
  and nothing compared them; `ManifestCachingTest.theRouteAgreesWithTheHeader` now does, across all three
  states. Found by an audit of the repository.
- **A failure at an upstream Kramerius is no longer reported as `500`.** It is now `504` when the
  instance did not answer in time, `502` when it could not be reached or answered with a status we
  cannot use, and `503` when it answered `429`/`503` and is asking us to wait. A `500` claimed the
  fault was ours and sent operators to our logs for a problem that was never there; it also told
  caching proxies to shield callers rather than retry. The kind travels on a typed exception, so the
  web layer never decides a status by matching on an exception message.
- A document over `IIIF_MAX_ITEMS` now answers `507 too_large`, as `docs/BEHAVIOUR.md` had always
  said it did. It was a `500`.
- Any unhandled failure now returns the same JSON error shape as every other route, rather than
  Javalin's plain-text default.
- `UpstreamPacer` gave a freshly built pacer no burst allowance, so the first request of every
  process was paced needlessly.
- A worker job failed for an unresolvable library was recorded and never logged: the document simply
  never filled and nothing said why.
- `make test-integration` reported success on a failing build — it piped maven into `grep` and
  exited through `|| true`. It now exits with maven's status.
- `make viewer-check` reported success after skipping documents it could not fetch ("6 passed, 0
  failed" having checked one of three). A skipped document now fails the run.
- `make smoke` required width and height on every canvas, which fails a sound recording: v3 sizes an
  audio canvas in time, not in space.
- The viewer harness served each manifest from an address other than its own `id`, which makes a
  correct manifest hang a viewer forever with no error. It also loaded pages over `file://`, where
  Chromium refuses the fetch outright.

### Added
- **Local declarations overlay the registry instead of replacing it.** Setting `IIIF_LIBRARIES` or
  `IIIF_LIBRARIES_FILE` used to clear `IIIF_REGISTRY_URL` outright and `/libraries` then listed only
  what was declared — so an operator who wanted one thing the registry cannot express had to restate
  all fifty-six libraries and own keeping them current. Set both now and the registry supplies the
  set and the facts, while each stated field is applied over it; every field is optional, and an
  empty positional segment (`mzk=||de:…`) keeps the registry's. Names merge per language tag, so
  adding `de` keeps the Czech and the English.

  What this is for is what the registry does not carry: a per-library `rps`, a name in any language
  but the institution's own and English (it has exactly those two, across all 55 rows), and a
  redirect to a test host. Declaring a library also overrules the registry's `alive` flag, since
  naming it is the operator saying to serve it.

  With no registry there is nothing to inherit from, so a declaration still has to be complete — the
  deployment with no runtime dependency on a shared service is unchanged.
- **`IIIF_LIBRARIES_FILE` — the libraries to serve, declared in a JSON file.** Static mode existed
  already, but only as `IIIF_LIBRARIES`, one comma-separated environment variable; for the registry's
  fifty-six institutions that is four kilobytes on one line, with nowhere to put a comment and no
  diff worth reading. The file carries the same three things per library — URL, version, and the
  name by language tag — and both may be set, with the inline value applied over the file so one
  library can be redirected without editing a generated file. Failure is fatal rather than skipped:
  a broken library file is the list of things the process exists to serve.

  `config/libraries.json` ships ready to use and carries **38 of the 56** registry libraries.
  `make libraries` regenerates it, and it is generated rather than written because fifty-six
  hand-typed institutions is fifty-six chances to mistype one. The version in it is a fallback only
  — see the `/info` entry above — so it does not go stale in the way that used to be static mode's
  standing cost.

  The generator probes each instance instead of trusting the registry's `alive` flag, and records
  why each of the other 18 was left out: 9 the registry itself reports down, 5 unreachable over TLS
  (`nacr` and `mzkk5` expired, `cuni_fsv`, `dsmo` and `kkvhb` serving an incomplete chain), `kkkv`
  timing out, `nfa` answering 403 and `mendelu` 500, and `mvchk` on Kramerius 4.6.2, below the
  oldest adapter. Writing those into the config would produce a gateway advertising libraries it
  cannot serve.
- **`navDate` and a manifest-level `seeAlso`**, after surveying what real v3 servers publish.
  Wellcome, Yale and the Internet Archive all carry a manifest-level `seeAlso`; Yale carries
  `navDate`. (Bodleian and Gallica are still v2 and use a different vocabulary.)

  `navDate` is the beginning of the document's period, for a timeline or a time-sliced map — from
  `date.min` on K7 and `datum_begin` on K5, at second precision because Kramerius carries
  milliseconds and every published example truncates them. Omitted when the index has no date, and
  never confused with the modification time.

  `seeAlso` points at the MODS record — `metadata/mods` on K7, `streams/BIBLIO_MODS` on K5, both
  public and verified to resolve. A link, not a parse: the K7 adapters still read everything from
  Solr and never fetch MODS, which is what let them drop the XML parser.

  Still not published: `rights`, which needs a licence-name → URI mapping and is a policy decision,
  and `service`/`services` for Content Search and auth, which are out of scope for v2.
- **Catalogue links are configuration, per library.** `catalogues.json` in the jar carries the
  verified MZK mapping; `IIIF_CATALOGUES_DIR` takes one `<code>.json` per library, added over it. A
  mapping is a record URL with `{id}` in it plus ordered regex rules, each capturing the record
  number in group 1 and mapping to the prefix the catalogue wants in front of it — first match wins,
  and nothing matching means no link, because a wrong catalogue link looks to a reader like a record
  the gateway found.

  Rules rather than a prefix table because "the last nine digits" is MZK's convention, not a fact
  about catalogues: a library whose numbers are six digits or carry a letter can now say so without a
  code change. A pattern with no capturing group is rejected at construction rather than silently
  producing no links.

  This was `code.equals("mzk") ? new CatalogueLink(…) : null` in `Libraries`, with the URL and both
  Aleph bases as Java literals — while a comment in `DocumentBlocks` already claimed both were
  configuration. Fifty-five libraries could not have a catalogue link without editing code. That
  comment is corrected too.
- `make smoke` covers `Kramerius50` (vugtk 5.4.2) alongside the two K7 adapters — 30 checks, and every
  adapter now gated rather than two of three. K5 is a different API and index, not a subclass, and
  nothing else in the suite would have noticed it breaking.
- **Route-level tests for the caching policy**, which nothing covered. `ManifestCacheTest` tested the
  data structure; the decisions about what goes into it live in `WebServer` and were verified only by
  `make smoke` against a live gateway, so they could not fail `make check`. Now in the gate: a
  complete manifest is cached and its hit costs the upstream nothing (asserted by counting requests
  at a stub Kramerius); revalidation is a `304` with an empty body and no upstream traffic;
  `Cache-Control: public, max-age` matches the configured TTL; a partial manifest is never cached and
  carries `no-store` and `X-IIIF-Dimensions: partial` with no ETag; `DELETE …/dimensions` drops the
  rendered bytes as well as the sizes. Both load-bearing assertions were mutation-checked — breaking
  the rule in the code makes the intended test, and only that test, fail.
- `GET …/dimensions` answers from one index count and one primary-key lookup instead of walking every
  child record to arrive at how many there are. Flat ~0.15 s from a 2-page document to a 3068-page
  one, measured; the largest previously needed seven cursorMark round trips to answer a status check.
- The sync walk uses its stored cursor. It takes slices until its pass budget runs out rather than one
  slice per pass, which is what lets it move through a block of pages that all share one `modified`
  instant — the watermark cannot advance past such a block, so the walk used to re-read the same rows
  on every pass indefinitely. A cursor is only reused for the query bound that minted it, because Solr
  answers a mismatched cursorMark with rows from elsewhere in the index rather than an error.
- `SolrSchema` owns the tiled-page filter, the `modified` field, the pid field and the parent field.

### Fixed
- **A document whose children were not the kind its model predicts served nothing.** The model table
  names one child kind per model; the repository does not oblige. Faceting `own_model_path` over
  MZK's 77.8 million documents: `periodical/periodicalvolume/page` on **84,559** of them — a year
  bound and digitised as one run rather than as issues — plus `monograph/monograph/page`,
  `sheetmusic/sheetmusic/page`, `map/map/page` and `periodical/monographunit/page`. Each of those
  served an empty `Manifest` or an empty `Collection`: valid IIIF describing nothing, which is the
  class of defect this rewrite exists to remove.

  The shape is now treated as a prediction. When the declared children are absent the assembler asks
  what is actually there — pages, or any child document — and corrects the shape on the record, so
  the builder types the output to match. One extra query, only for documents that would otherwise
  have served empty. Verified live: a 632-page city directory and a 104-page volume now serve as
  Manifests, a sheet-music item containing sheet music and a monograph containing a monograph as
  Collections with their members, and the K5 volume as a 165-canvas Manifest.
- `/health` reported the size store as `ok` when the database was reachable but had no tables — it
  asked about the socket, not about the schema, and the schema is applied once at startup. Seen for
  real: the Postgres container was recreated under a running gateway, the pool reconnected, and
  `/health` stayed green while every dimension read failed. `SizeStore.health()` now answers `ok`,
  `unreachable` or **`schema_missing`**, and `/health` reports that state by name — "unreachable"
  sends an operator to the network, and the answer there was a migration.
- `/health` cast the size store to `PostgresSizeStore` to ask whether it was reachable, which is a
  `ClassCastException` for any other implementation — and the SPI exists so there can be others. The
  health check is on the interface now, defaulting to healthy for a store with nothing to be
  unreachable.
- **The container image never built.** Its `Containerfile` copied POMs for `iiif-kramerius`,
  `iiif-kramerius-rest` and `iiif-kramerius-akubra` — module names from the original plan that were
  renamed before any code existed — so it failed on the first `COPY`. Both roles now build and run
  from the image, unprivileged as uid 10001.
- The sync walk queried `has_tiles` and `modified` by name, which a K5 instance indexes as `viewable`
  and `modified_date`. That did not fail: the filter matched nothing, and the walk reported that it
  had no work to do.

### Changed
- `make db-up` also creates a separate `iiif_test` database, and `make test-integration` uses it. The
  tests claim from the same job queue a running worker drains, so sharing one database made them
  fail depending on what else happened to be running.
- `docs/BEHAVIOUR.md`'s error table now matches the code: three codes had drifted from their
  implemented names, one row documented behaviour that did not exist, and three implemented codes
  were missing.

## [2.0.0] - 2026-08-28

Complete rewrite. The v1 gateway was a 1446-line Ruby script spawned per request by a 100-line Express
app, with no cache anywhere and output that was not valid IIIF.

### Added
- Kramerius 5 support (`Kramerius50`), covering 11 live registry libraries including the National Library.
- Version-floor adapters: the highest floor at or below an instance's version wins, so a new Kramerius
  release needs no work unless it changes something we read.
- Real canvas dimensions, cached in Postgres one blob per document (~9.4 B/page after TOAST, measured).
- A `worker` role: job queue plus a per-library dimension walk that resumes from its watermark.
- `requiredStatement` with attribution and licence text; availability and licence metadata rows.
- `behavior` derived from indexed page placement, never guessed from the model.
- navPlace from indexed coordinates, with closed and correctly wound polygons.
- Dimension status, prefetch and sync-control endpoints on a separate, unpublished admin port.
- Per-IP rate limiting, and per-library politeness caps toward each upstream Kramerius.
- IIIF v3 schema validation in `make check`.
- Extra languages loadable from a directory at startup, merged per key.

### Fixed
- `"cz"` is not a language tag; gateway-authored labels are now `cs` + `en`.
- `seeAlso.type: "Alto"` is not an IIIF type — now `Dataset` with the ALTO namespace as `profile`.
- Manifests with no `items`, which the schema requires — 13 832 invalid manifests at MZK.
- `{"none": [null]}` for absent values; absent entries are now omitted.
- `rows=1500` silently truncating documents — a 3128-page monograph served 1500 canvases with no
  indication that 1628 were missing.
- `/full` never returning real sizes: it correlated `info.json` responses by comparing an `https://` URL
  it built against the `http://` `@id` the image server returned, so nothing ever matched. Correlation is
  now by request index.
- Nested collections typed `Manifest`.
- `audio/mp3` → `audio/mpeg`.
- Unlinkable parents pushed into `partOf` as `{}`.
- Requesting a page or a track answered 200; it is now a distinct 404 from "no such object".
- Four document models refused outright (`supplement`, `article`, `internalpart`, `picture`) — about
  152 000 documents at MZK.
- Publication place read from `publication_places.facet` without stripping MARC country codes would print
  "Xr" where a city belongs, on roughly a quarter of documents.

### Changed
- Metadata comes from the Solr index; the K7 adapters parse no MODS at all.
- Solr paged with cursorMark, with the tiebreaker detected per library (`compositeId` on SolrCloud, `pid`
  on standalone) rather than hardcoded.
- The registry is fetched once and cached, not on every request.
- The landing page is served from the app's own resources rather than proxied from GitHub.


v2 is a rewrite. The Ruby/Node gateway is replaced by a Java library plus a service built on it; see
[docs/DESIGN.md](../docs/DESIGN.md) for how it works and [docs/DECISIONS.md](../docs/DECISIONS.md) for what was
chosen and why.

### Added

- `docs/DESIGN.md`, `docs/BEHAVIOUR.md`, `docs/DECISIONS.md` — how it works, the per-model output contract,
  and the judgement calls behind both. The measurements behind the design (index field boundaries, document size distribution,
  storage and compression numbers, Solr topology) are recorded so they are not re-derived.
- `scripts/capabilities.sh` — probes each library's Solr schema. This is how the next adapter version
  boundary gets discovered after a Kramerius release.
- `Makefile` fronting the repo with the standard verbs; `make check` is the gate.
- The library, split into four levels so that what a manifest *is* can be read without how anything is
  fetched: `iiif-kramerius-model` (IIIF v3 vocabulary, document shapes, authored strings),
  `iiif-kramerius-build` (pure construction — where Kramerius' own application plugs in),
  `iiif-kramerius-spi` (the contract to be read from, orchestration, per-library config), and
  `iiif-gateway` (the adapters and the service). The direction is enforced by Maven.
- Per-library `LibraryProfile`, assembled by each host: the gateway from a registry entry plus
  configuration, an embedding application from what it already knows about itself.
- IIIF v3 schema validation inside `make test`. The IIIF project publishes no validator image, so the
  official JSON Schema is vendored and checked in-process — offline, and it gates CI. Every manifest a
  test builds is validated.

### Changed

- Manifests target **valid IIIF Presentation v3** rather than compatibility with v1's output. v1 emitted
  `"cz"` as a language tag (Czech is `cs`), `seeAlso.type: "Alto"` (not an IIIF type), null-valued labels,
  and self-intersecting unclosed navPlace polygons. Every departure is listed in `docs/BEHAVIOUR.md`.
- Canvas dimensions are real by default. v1 declared every canvas 1000×1000 unless `/full` was appended,
  and `/full` was broken in production — it matched `info.json` responses by comparing an `https://` URL it
  built against the `http://` `@id` the image server returned, so every canvas got `"width": null`.
- Gateway-authored labels are bilingual `cs` + `en`. The registry already carried `name_en`, which v1
  fetched and discarded.
- Errors return real status codes with a machine-readable `code`. v1 answered every failure with `200`.

### Fixed

- Documents larger than 1500 children were silently truncated (`rows=1500` on every query). MZK's largest
  document has 3568 pages, so this affected real material.
- Four page-bearing models — `supplement`, `article`, `internalpart`, `picture` — were refused outright.
  At MZK alone that is roughly 152 000 documents.
- Solr query injection: a crafted pid was interpolated into `q=` unescaped.

### Removed

- The Ruby and Node implementation is scheduled for removal once the replacement is verified against live
  instances; see Phase 8 in the roadmap. It is still present and still what runs in production.
