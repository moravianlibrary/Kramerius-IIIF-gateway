<!-- What each Kramerius document model produces. The implementation contract; rationale lives in DESIGN.md. -->

# Behaviour

What the gateway emits, model by model. This is the contract the fixture tests assert against.

Terminology: **pid** is a Kramerius identifier (`uuid:…`, sometimes `vc:…`). **Model** is Kramerius'
`model` field. **Shape** is what this gateway builds from it.

## Model dispatch

| Kramerius model | Translation key | IIIF type | Shape |
|---|---|---|---|
| `monograph` | `model.monograph` | Manifest | pages |
| `monograph` *with* volumes | `model.monographcollection` | Collection | monograph units |
| `monographunit` | `model.monographunit` | Manifest | pages, has `partOf` |
| `manuscript` | `model.manuscript` | Manifest | pages |
| `map` | `model.map` | Manifest | pages |
| `graphic` | `model.graphic` | Manifest | pages |
| `sheetmusic` | `model.sheetmusic` | Manifest | pages |
| `oldprintsheetmusic` | `model.oldprintsheetmusic` | Manifest | pages |
| `archive` | `model.archive` | Manifest | pages |
| `periodical` | `model.periodical` | Collection | periodical volumes |
| `periodicalvolume` | `model.periodicalvolume` | Collection | periodical issues, has `partOf` |
| `periodicalitem` | `model.periodicalitem` | Manifest | pages, has `partOf` |
| `supplement` | `model.supplement` | Manifest | pages, has `partOf` |
| `article` | `model.article` | Manifest | pages (**fostered**), has `partOf` |
| `internalpart` | `model.internalpart` | Manifest | pages (**fostered**), has `partOf` |
| `picture` | `model.picture` | Manifest | pages (**fostered**), has `partOf` |
| `soundrecording` | `model.soundrecording` | Manifest | sound recording |
| `convolute` | `model.convolute` | Collection | convolute parts |
| `collection` | `model.collection` | Collection | collection members |
| `soundunit` | — | — | **unsupported** |
| `page`, `track`, anything else | — | — | **unsupported** |

Two models are synthetic and derived rather than read:

- **Multi-volume monograph** — a `monograph` with at least one `monographunit` child. Detected from
  `count_monograph_unit > 0` where indexed, otherwise a `rows=0` query that **must filter on the child model**:
  `own_parent.pid:"{pid}" AND model:monographunit`. Counting all children instead would see a plain
  monograph's own pages and misclassify every one of them.
**There is no PDF shape.** A PDF is a *rendering*, not a kind of document: an object whose
`ds.img_full.mime` is `application/pdf` gets it attached whatever its model, and an object that has one and
no pages simply comes out as a Manifest with an empty `items`. v1 made it a shape, which meant a PDF could
*replace* a structural shape — so a periodical volume that happened to carry one would have hidden its
issues. None does at MZK today, but nothing guaranteed that.

PDF-mime by model at MZK, and how many of those have children:

| Model | PDF-mime | with pages / volumes / issues / units |
|---|---|---|
| `article` | 10 571 | 0 / 0 / 0 / 0 |
| `monograph` | 2 320 | 0 / 0 / 0 / 0 |
| `periodicalitem` | 940 | 0 / 0 / 0 / 0 |
| `supplement` | 1 | 0 / 0 / 0 / 0 |
| every other model | 0 | — |

**Neither the table nor the labels are in the code.** The dispatch table above is
`models/kramerius.json` — model → shape, plus the `fostersPages`, `hasAncestors`, `excludesOwnModel` and
`promoteTo` flags — and the labels are `i18n/<language>.json` under `model.<solrName>`. The gateway reads a
configured directory over both at startup, so supporting a model Kramerius has just introduced, or fixing a
label, needs no rebuild.

**Shapes are deliberately not configurable.** The builder switches on `DocumentShape` exhaustively, so a
shape exists only if there is code that knows how to build one. A table naming an unknown shape — or
promoting to a model that is not in the table, or on a child kind that does not exist — is rejected **at
startup** with the offending value quoted, rather than failing on a request later.

`oldprintsheetmusic` had no Czech label in v1 — it emitted the raw model name as the type. Fixed here.

**Four models are new in v2**, and they are not marginal. v1 rejected `supplement`, `article`, `internalpart`
and `picture` with *"Pro model X nelze vygenerovat IIIF Presentation API v3"* — at MZK alone that is roughly
152 000 documents it could have served:

| Model | With pages at MZK | PDF-only |
|---|---|---|
| `article` | 70 918 | 10 571 |
| `supplement` | 79 562 | 1 |
| `internalpart` | 1 315 | 0 |
| `picture` | 56 | 0 |

All four map onto the existing pages shape. `supplement` needs nothing special. The other three hold their
pages by *foster* relationship rather than ownership, which is one branch in the child query — see
[Shape: pages](#shape-pages).

`soundunit` and `track` stay unsupported: both have **zero** pages, so there is genuinely nothing to build.

## Every document carries

In this key order (Jackson serialises record components in declaration order, so the record *is* the order):

```jsonc
{
  "@context": "http://iiif.io/api/presentation/3/context.json",   // array, with navPlace, when present
  "id":       "{IIIF_BASE_URL}/{library}/{pid}",
  "type":     "Manifest" | "Collection",
  "label":    { "none": ["…"] },
  "requiredStatement": { "label": {...}, "value": {...} },
  "metadata": [ … ],
  "provider": [ { "type": "Agent", … } ],
  "homepage": [ … ],
  "thumbnail":[ { "type": "Image", … } ],
  "behavior": ["paged"],        // only when derivable, see below
  "summary":  { … },            // only when the source has one
  "navDate":  "1922-01-01T00:00:00Z",   // the document's period, where the index has one
  "navPlace": { … },            // only for documents with coordinates
  "seeAlso":  [ { "type": "Dataset", … } ],  // the MODS record
  "rendering":[ … ],            // whenever the object has a PDF, whatever its model
  "items":    [ … ],
  "structures":[ … ],           // sound recordings only
  "partOf":   [ … ]             // monographunit, periodicalvolume, periodicalitem
}
```

Every string this gateway authors — metadata labels, availability values, link labels, document types —
comes from `i18n/<language>.json`, one file per language. The set of languages emitted is the set of files
loaded, and the gateway reads a configured directory at startup on top of the bundled `cs` and `en`, so a
language can be added or corrected without rebuilding the image. A key missing from one language is not
emitted for that language rather than falling back to another.

Absent blocks are **omitted**, never emitted as `null` or `{}` — with one exception. The schema's `required`
lists are:

| Type | Required |
|---|---|
| Manifest | `id`, `type`, `label`, **`items`** |
| Collection | `id`, `type`, `label` |

So a Manifest always carries `items`, empty if it has no canvases. A Manifest also has
`additionalProperties: false`, meaning any key outside the vocabulary is a validation error — there is no
room for private extensions in the document body.

### label

| Model | Label |
|---|---|
| `periodicalvolume`, `periodicalitem` | `"{root.title} ({date.str})"` — their own title is the periodical's, so every volume would otherwise look identical |
| everything else | `title.search` |

`supplement`, `article`, `internalpart` and `picture` take `title.search`, **not** the periodical pattern:
unlike a volume or issue, they carry a real title of their own (a supplement at MZK reads *"1. Kurze,
statistische Andeutungen und Pararellen über den Bich-Stapel"*), so substituting the periodical's title would
lose information rather than add it.

`title.search` already contains the subtitle: the indexer composes `"Title: Subtitle. PartNumber. PartName"`
(`SolrInputBuilder:352`, `TitlesExtractor.buildTitle`). That is why v2 has no separate *Podnázev* row and
needs no MODS fetch — the index does not expose subtitle separately, and does not need to.

Language tag is `none`, because the value is record content in an unknown language.

### metadata

Gateway-authored labels are bilingual `cs` + `en`; values keep the language of the data. An entry with no
value is **omitted** — v1 emitted `{"none": [null]}`.

**Metadata is supplied by the adapter, not composed by the library.** The library copies it. Adapters build
it through `MetadataRows`, a shared helper that keeps the common rows consistent and translated across K5 and
K7 without forcing every source into one fixed field list.

**Every row is emitted when its data exists, and omitted when it does not.** There is no per-model list —
that would be a hand-maintained table, and the measurements below show it would be wrong for half the models.

| Order | Translation key | Source |
|---|---|---|
| 1 | `metadata.title` | `title.search` |
| 2 | `metadata.documentType` | `model.<name>`, itself translated |
| 3 | `metadata.author` | `authors` |
| 4 | `metadata.partNumber` | `part.number.str` |
| 5 | `metadata.partName` | `part.name` |
| 6 | `metadata.publicationStatement` | `publication_places.facet` + `publishers.facet` + `date.str` |
| 6′ | `metadata.published` | `date.str` — **only when row 6 was not emitted** |
| 7 | `metadata.language` | `languages.facet`, MARC code → `language.<code>` |
| 8 | `metadata.availability` | `accessibility` → `availability.<value>` |
| 9 | `metadata.license` | `licenses` + `licenses_of_ancestors` |

Rows 6 and 6′ are the one pair that interact: the publication statement is composed as
`"{place}: {publisher}, {date}"` with missing parts dropped, and if neither a publisher nor a place is
indexed it degenerates to a bare date — so the bare date is labelled *Vydáno* instead, which is what it is.

Field coverage at MZK, which is why the rule is data-driven rather than a model list. Every cell is
`model:<m> AND <field>:*` over the whole index, as a share of that model's documents:

| Model | docs | `part.number` | `part.name` | `date.str` | `publishers` | `pub. places` | `authors` |
|---|---:|---|---|---|---|---|---|
| `monograph` | 266,128 | **0%** | 4% | 98% | 98% | 98% | 93% |
| `monographunit` | 12,413 | 99% | 29% | 100% | 99% | 100% | 95% |
| `periodical` | 4,867 | **0%** | 5% | 100% | 98% | 99% | 43% |
| `periodicalvolume` | 45,429 | 99% | **0%** | 100% | 1% | **0%** | 31% |
| `periodicalitem` | 688,605 | 100% | **0%** | 100% | 70% | 1% | 11% |
| `supplement` | 79,757 | 100% | 81% | 100% | 99% | **0%** | 5% |
| `article` | 81,493 | **0%** | **0%** | 100% | **0%** | **0%** | 81% |
| `internalpart` | 1,318 | **0%** | **0%** | 98% | **0%** | **0%** | 53% |
| `picture` | 56 | **0%** | **0%** | 100% | **0%** | **0%** | 7% |

No cell is "not applicable" — every one is a measurement, and the surprises are why the rule has to be.
Publication places are near-complete on the monograph side, including `monographunit` at 100%, and on
`periodical` itself at 99% — then they vanish the moment you descend into the periodical, 0–1% for
volumes, issues and supplements alike. A volume gets a bare date (its publisher is 1% too), an issue gets
`"{publisher}, {date}"`, and the periodical gets the full `"{place}: {publisher}, {date}"`. `article` has
no part designation but does have authors — the opposite of `supplement`, which has both part fields and
almost no authors. And `part.number` splits by position rather than by kind: the four models that sit
under a title have it, the two titles and the three leaves do not. Any hand-written "applies to" column
would have got all of that wrong.

> v1 built this from MODS `originInfo` and produced a leading `": "` when there was no place. That quirk is
> **not** preserved — composing from indexed fields makes that shape impossible rather than preserving it.

### requiredStatement

```jsonc
{ "label": { "cs": ["Poskytuje"], "en": ["Provided by"] },
  "value": { "cs": ["{name}"],    "en": ["{name_en}"] } }
```

From the registry entry. Where `/client/v7.0/ui-config/licenses` describes a license the document carries, its
text is appended to the value. Viewers are obliged to display this, which is what makes it the right place for
attribution.

**`rights` is never emitted** — see DESIGN.md.

### provider

From the registry: `id` = registry URL for the library, `type` `Agent`, `label` = `{cs: name, en: name_en}`,
`homepage` = the institution's `web`, `logo` = `logo` when present. A missing logo omits the key — v1 emitted
`{"id": null}`.

> v1 pointed `homepage` at the library's Kramerius host because that was the only URL it read. The registry
> also carries `web`, the institution's own site, which is what a `provider.homepage` means.

### homepage

1. `{IIIF_DIGITAL_LIBRARY_URL}/{library}/uuid/{pid}` — `link.digitalLibrary`
2. The library's catalogue record, when one is configured and the document has a system number —
   `link.catalogue`. See [Catalogue link](#catalogue-link); nothing about any specific library is in code.

> v1 hardcoded `MZK01-` and a `library == "mzk"` check, and had a second homepage builder for periodical
> volumes and issues that omitted the catalogue link and hardcoded `/mzk/` into the Digital Library URL — a
> broken link for every other library. Both are gone.

### behavior

Derived from indexed data, never guessed from the model:

| Condition | `behavior` |
|---|---|
| `count_page <= 1` | omitted |
| any page has `page.placement` of `left` or `right` | `["paged"]` |
| otherwise | omitted |

`individuals` is never asserted. It is a positive claim that the canvases are unrelated items, which is as
wrong for a multi-sheet graphic album as `paged` is. Omitting lets the viewer apply its own default.

### navDate and seeAlso

Both were added after surveying what real v3 servers publish. Wellcome, Yale and the Internet Archive
all carry a manifest-level `seeAlso`; Yale carries `navDate`. Bodleian and Gallica are still v2 and use
a different vocabulary entirely.

`navDate` is the beginning of the document's period, and a client uses it for a timeline or a
time-sliced map — not the modification time, which is unrelated. K7 indexes `date.min` as a full UTC
dateTime (`2002-01-01T00:00:00.001Z`; the `.001` is Kramerius avoiding a zero) and K5 indexes
`datum_begin` as a bare year, `0` when unknown. Both become the first instant of the period, formatted
at second precision because that is the form the spec's examples and every published manifest use.
Omitted when the index has no date, rather than invented.

`seeAlso` points at the document's MODS record — `items/{pid}/metadata/mods` on K7,
`item/{pid}/streams/BIBLIO_MODS` on K5, both public and verified. A LINK, NOT A PARSE: the K7 adapters
still read everything from Solr and never fetch MODS, which is the decision that let them drop the XML
parser. Typed `Dataset` with the MODS 3 namespace as its profile — "MODS" is not an IIIF type, the same
mistake v1 made by typing the ALTO link "Alto".

Still not published: `rights` (needs a licence-name → URI mapping, a policy decision — see DECISIONS.md),
and `service`/`services` for Content Search and auth, which are out of scope for v2.

### navPlace

Emitted when the document has cartographic coordinates, from `coords.bbox.corner_sw` / `corner_ne`. Adds
`http://iiif.io/api/extension/navplace/context.json` to `@context` as an array.

- SW == NE → `Point`, coordinates `[lon, lat]`
- otherwise → `Polygon`, a **closed, correctly wound** ring: `[[W,S], [E,S], [E,N], [W,N], [W,S]]`

`coords.is_point` is used where indexed (7.1.4+) and derived from the corners otherwise.

> v1 parsed two DMS regexes out of MODS and emitted `(SW, SE, NW, NE)` — a self-intersecting, unclosed ring —
> and wrapped a Point in one array too many. It also crashed on any coordinate string matching neither regex.

### thumbnail

`{kramerius}/search/api/client/v7.0/items/{pid}/image/thumb`, `type: "Image"`, `format: "image/jpeg"`.

### partOf

For every model that is part of something larger: `monographunit`, `periodicalvolume`, `periodicalitem`,
`supplement`, `article`, `internalpart`, `picture`. No extra query — `own_parent.pid`, `own_parent.model` and
`own_parent.title` are all indexed on the document itself.

**Emitted when the parent's model is one this gateway can serve**, of whichever type that model produces
(`Collection` for a periodical or multi-volume monograph, `Manifest` for an issue). A parent of an unsupported
model is omitted; v1 pushed `{}` into the array.

That rule replaces v1's hardcoded allowlist of four parent models, which the new models break outright: an
`article`'s parent may be a `supplement`, and a `picture`'s parent is an `article`. "Link to it if we can
serve it" is both simpler and correct by construction — a `partOf` entry always points at a URL that resolves.

Observed parent models at MZK, as a check on the rule:

| Child | Parents |
|---|---|
| `supplement` | `periodicalvolume` 54 937 · `periodicalitem` 24 350 · `soundrecording` 3 |
| `article` | `periodicalitem` 81 261 · `supplement` 136 · `periodicalvolume` 88 |
| `internalpart` | `manuscript` 819 · `monograph` 177 · `periodicalitem` 145 · `monographunit` 133 |
| `picture` | `article` 56 |

## Shape: pages

Used by `monograph`, `monographunit`, `manuscript`, `map`, `graphic`, `sheetmusic`, `oldprintsheetmusic`,
`archive`, `periodicalitem`, `supplement`, `article`, `internalpart`, `picture`.

Children: `model:page`, sorted `page.index asc, {uniqueKey} asc`. **Which parent field to query depends on the
model:**

| Model | Query |
|---|---|
| `article`, `internalpart`, `picture` | `foster_parents.pids:"{pid}"` |
| everything else | `own_parent.pid:"{pid}"` |

An article does not own its pages — the issue does, and the article is fostered onto a page range within it.
Querying `own_parent.pid` for an article returns nothing, which is why v1 could never have served one even if
its dispatch table had listed the model.

The same page therefore appears as a canvas in both the article's manifest and the issue's, under different
canvas ids (`{base}/{article-pid}/canvases/0` and `{base}/{issue-pid}/canvases/17`). That is normal in IIIF:
canvases are per-manifest views, and `partOf` links the article back to the issue.

Each page becomes one canvas:

```jsonc
{
  "id":     "{base}/{pid}/canvases/{i}",
  "type":   "Canvas",
  "label":  { "none": ["12"] },                  // page.number; type in brackets unless it is NormalPage
  "width":  2480, "height": 3508,
  "thumbnail": [ { "id": "…/image/thumb", "type": "Image" } ],
  "seeAlso":   [ { "id": "…/ocr/alto", "type": "Dataset",
                   "profile": "http://www.loc.gov/standards/alto/ns-v3#",
                   "label": { "none": ["ALTO XML"] }, "format": "text/xml" } ],
  "items": [ { "id": "{base}/{pid}/canvases/{i}/ap", "type": "AnnotationPage",
               "items": [ { "id": "{base}/{pid}/canvases/{i}/ap/a", "type": "Annotation",
                            "motivation": "painting",
                            "body": { … }, "target": "{base}/{pid}/canvases/{i}" } ] } ]
}
```

`seeAlso` is present unless `has_alto_content` is explicitly `false`. **Absent means emit.**

That is the opposite of what this document said before Phase 5, and the reason is measurement:
`has_alto_content` exists in the schema from 7.1.4 but is populated on essentially nothing — 0 of MZK's
76 455 203 pages and 3 of KNAV's 6 228 108, both 7.2.2 instances. Treating silence as "no OCR" would drop the
link from every page in the country. The data is there: 73 839 443 MZK pages have `text_ocr` indexed, and the
ALTO stream returns a valid document even for a front jacket with no text. `text_ocr` itself is indexed but
not stored, so it cannot be read per page.

An instance that *does* populate the field is believed, and an explicit `false` omits the link — which is
what the three-state `Optional<Boolean>` on the record is for. No probe is needed at any version floor, so
`Kramerius70` and `Kramerius714` behave identically here. See docs/DESIGN.md.

> v1 emitted `"type": "Alto"`, which is not an IIIF type, with the XSD URL as `profile`. v2 uses `Dataset` and
> the ALTO namespace URI, which is what the profile field is for.

Canvas index `i` is per-manifest and continues across sections — a sound recording numbers its track canvases
after its page canvases. Counters are instance fields of a per-request builder, so concurrent requests cannot
interleave their numbering.

### Page label

`page.number`, with `page.type` in brackets when both are known; whichever exists alone otherwise; omitted
when neither. v1 crashed when a page had a type but no number.

**`NormalPage` is not a type worth printing.** It is the default — 69 937 719 pages at MZK, plus 491 725
spelled `normalPage` — so about 90% of labels would read `"12 (NormalPage)"`, which is Kramerius' internal
vocabulary saying nothing the number did not. It is dropped before the bracket, on both K5 and K7, so a
normal page is labelled `"12"` and a `NormalPage` with no number gets no label at all. Every other type is
kept and translated: `FrontCover`, `TitlePage`, `Blank` and `Advertisement` genuinely distinguish a page.

> v1 printed it. That is the one label change a client would notice.

### Body

| Page | Body |
|---|---|
| `has_tiles` | `id` = `{kramerius}/search/iiif/{pid}/full/max/0/default.jpg` (Image API 3) or `…/full/full/0/…` (Image API 2), with `width`, `height` and an image `service` |
| no tiles | `id` = `{kramerius}/…/items/{pid}/image`, no dimensions, no service |

The service is `ImageService3` (`profile: "level2"`) or `ImageService2`
(`profile: "http://iiif.io/api/image/2/level2.json"`) according to the Image API version the library's
`info.json` reports — **per library, not per page**, so the URL spelling and the service block always agree.
Both versions are live in the field: 7.0.39, 7.0.41 and 7.1.7 answer Image API 2, while both measured 7.2.2
instances answer Image API 3. Verified end to end against MZK 7.2.2.3, which serves `ImageService3` and
whose canvases carry `id`/`type` and `/full/max/`. The probe used to fail outright on every **SolrCloud**
instance — its sample query sorted by `pid` and a cursorMark query is a 400 unless the sort names the
index's uniqueKey, which on SolrCloud is `compositeId` — and a failed probe falls back to 2, so MZK's
manifests declared `ImageService2` against a v3 server. The version is read from five public pages and
every one that answers votes;
disagreement is logged and an even split resolves down to 2, which every instance measured accepts.

**On K5 the same probe also decides whether there is a service at all**, because there it is a property of
the deployment rather than of the version: `lmda`, `dsmo` and `kvkli` serve a working Image API 2 with real
dimensions, while `nkp` and `vugtk` answer 200 with an empty body and three others answer 500. A K5 library
with no usable service gets the plain `streams/IMG_FULL` on its canvases and no `service` block.

**Width and height are the real dimensions where known.** Where they are not, there are two fallbacks, and
IIIF forces the issue: it has no way to say "unknown" — the schema requires a canvas with `items` to carry
`width` and `height`, or `duration` — so some number must be invented.

**A page the gateway may not measure gets the right shape from its thumbnail.** A restricted page's
`info.json` answers 403, but its thumbnail stays public: verified on MZK's
`uuid:fbaf7950-2b61-11e3-b62e-005056825209`, whose `info.json` is 403 while its thumbnail is 200 and
12.9 KB. `InfoJsonSizes` reads the JPEG header of that thumbnail and scales the real aspect to a nominal
height of 1000, so the canvas comes out 672 × 1000 rather than 1000 × 1000 — wrong in size, right in
shape, which is what a viewer lays out from. Measured over 25 MZK pages the thumbnail's aspect matches
the full image to within 0.82% (mean 0.17%), while the scale factor is useless, running from 8.19 to
12.77. Only the ratio carries meaning, because a viewer scales the image to whatever the canvas declares.

A 403 is therefore an expected outcome, not an alarm: it is logged at DEBUG and not counted anywhere.

**The square `IIIF_FALLBACK_CANVAS_SIZE` (1000 × 1000) survives for what is left**: a page not yet
measured — transient, the worker is on its way — and a page whose thumbnail fails too. The square is
wrong in shape as well as size: pages within a document cluster around an aspect of 0.6 at MZK, so a
square distorts essentially every one of them. See docs/DECISIONS.md for what was considered instead.

### `X-IIIF-Dimensions`

Three states, because "not measured yet" and "measured indirectly" are different promises:

| Value | Meaning | `Cache-Control` | ETag | Cached here |
|---|---|---|---|---|
| *absent* | every canvas carries the image server's own width and height | `public, max-age=…` | yes | yes |
| `approximate` | at least one canvas is a thumbnail-derived shape at nominal scale, because the image answered 403 | `public, max-age=…` | yes | yes |
| `partial` | at least one canvas is the square fallback, because the worker has not reached it yet | `no-store` | no | no |

The line between them is **what will change it**, not how certain it is.

`partial` has work already queued against it: the worker is measuring those pages now, so the numbers
improve by themselves within a pass. Re-request it.

`approximate` has nothing in flight against it, which is why it is cached and revalidates like any
complete manifest. **It is not permanent, though.** An approximation becomes a measurement whenever the
403 stops — a licence expires or is changed, the deployment moves onto the library network or has its IP
whitelisted, an image is re-derived. Two timers cover that: the rendered manifest expires after
`IIIF_MANIFEST_TTL_MINUTES` (60), and the underlying approximation is re-asked after
`IIIF_FAILED_LOOKUP_RETRY_SECONDS` (86 400), so a page that becomes readable upgrades within about a day
instead of keeping a guess forever. `DELETE /{library}/{pid}/dimensions` is the escape hatch when that is
not soon enough — it drops both layers.

A measured canvas is no more permanent, for what it is worth: an image re-derived upstream makes a
`complete` manifest wrong in the same way, and the same TTL is what catches it. `approximate` is a
statement about *how the number was obtained*, not about how long it will hold.

When both apply, `partial` wins: it is the stronger caveat and the one that governs caching.

`GET /{library}/{pid}/dimensions` reports the same three states, plus how many of the cached sizes are
approximations, so the route and the header cannot disagree about one document.

A document with **no tiled page at all** is `complete` with `"total": 0`: there is nothing to measure, so
nothing is outstanding. That is not a corner case — 732 043 of MZK's pages carry no tiles, and at least one
363-page monograph there consists entirely of them. The route used to require `total > 0` before it would
say `complete` and answered `partial` for exactly those documents, against a manifest that carried no
header at all.

A cache hit reports the same value a miss does, so a document does not describe itself one way warm and
another way cold. The header is set before the ETag comparison, so a `304` carries it too — it describes
the representation the client already holds.

> This matters more than it looks: 64 068 008 of MZK's 73 082 056 pages are `accessibility: private`, so
> `approximate` is the ordinary case for that library rather than an edge one.

> v1 declared every canvas 1000×1000 unless `/full` was appended, and `/full` was broken on most libraries:
> it matched `info.json` responses by comparing an `https://` URL it built against the `http://` `@id` most
> image servers return, so nothing matched and every canvas got `"width": null`. (`lmda` and `kvkli` return
> an `https://` `@id` and did work.) v2 correlates by request index.

## Shape: sound recording

`soundrecording` → a Manifest whose canvases are its pages (usually a cover) followed by the tracks of each
sound unit, plus one `Range` per sound unit in `structures`.

```
items:      [ page canvases… , track canvases… ]
structures: [ { id: "{base}/{pid}/range/{n}", type: "Range",
                label: { none: [soundunit title] },
                items: [ { id: <track canvas id>, type: "Canvas" }, … ] }, … ]
```

Sound units: `model:soundunit` under the recording. Tracks: `model:track` under each unit, sorted
`rels_ext_index.sort asc, {uniqueKey} asc`.

A track canvas has `duration` (from `track.length`, defaulting to 1.0 where not indexed) and no width/height.
Its body is `type: "Sound"`, `format: "audio/mpeg"`.

The track's thumbnail is its **sound unit's** — a track has no image of its own.

> v1 requested `rels_ext_index` for tracks and never sorted by it, so track order was Solr's default. And it
> emitted `audio/mp3`, which is not a media type.

`soundunit` requested on its own is **unsupported** — it is a component, not a document. v1 printed
`soundunit - negeneruje iiif`, which is not JSON.

## Shape: collections

All four produce a `Collection` whose `items` are references: `{id, type, label}`.

| Shape | Children | Member type | Member label |
|---|---|---|---|
| monograph units | `model:monographunit`, sorted `part.number.sort asc` | `Manifest` | `"{part.number.str}. {part.name}"`, falling back to whichever exists, then `title.search` |
| periodical volumes | `model:periodicalvolume`, sorted `part.number.sort asc, date.min asc` | `Collection` | `date.str` |
| periodical issues | `model:periodicalitem`, sorted `date.min asc, issue.type.sort asc` | `Manifest` | `"{root.title} ({date.str})"` |
| convolute parts | all children except nested `convolute`, sorted `rels_ext_index.sort asc` | `Manifest` | `title.search` |
| collection members | `fq=in_collections.direct:"{pid}"` | `Collection` if the member is a `collection`, else `Manifest` | `root.title` |

A `page` turning up as a collection member — added to the collection individually — is referenced by its
**parent** document, since a page has no manifest of its own, labelled `"{root.title} (strana {title.search})"`.

> Two v1 bugs fixed here. A nested collection always came out as a `Manifest`, because `create_items_collection`
> tested a field `create_list_of_collection_parts` never copied. And a monograph volume was labelled
> `"{number}. {name}"` on K5 but only `"{name}"` on K7 — the same document labelled differently depending on
> which library held it. The K5 form wins: it distinguishes volumes that share a name.

## Rendering: PDF

Not a shape. Any object whose `ds.img_full.mime` is `application/pdf` gets a rendering, alongside whatever
structure it has:

```jsonc
"rendering": [ { "id": "{kramerius}/…/items/{pid}/streams/IMG_FULL",
                 "type": "Text", "label": { "cs": ["PDF"], "en": ["PDF"] },
                 "format": "application/pdf" } ]
```

An object with a PDF and no pages therefore comes out as a Manifest with `"items": []` plus this rendering.
**`items` is emitted as an empty array, not omitted.** The IIIF v3 schema requires `id`, `type`, `label` and
`items` on every Manifest — there is no `minItems`, so an empty array is legal, but leaving the key out is
not. v1 omitted it and so emitted invalid IIIF for every PDF object; at MZK that is 13 832 of them.
`IiifSchemaTest.manifestWithoutItems` pins this.

## Catalogue link

The second `homepage` entry, for libraries that have a catalogue configured. It cannot be derived, and each
of the obvious shortcuts produces 404s:

- **The sigla is the wrong identifier.** MZK's sigla is `BOA001` and `vufind.mzk.cz/Record/BOA001-…` is a
  404; the URL wants the Aleph base, `MZK01`. Sigla *is* in Solr, as `physical_locations`, but it is the
  **holding** institution — MZK's repository also holds `ABA001`, `ABA004` and `KME450` material — so it is
  not a property of the serving library at all.
- **The prefix in `id_sysno` is not discardable.** MZK indexes both `mzk01-` and `mzk03-` (424 and 121 of 600
  sampled), and `MZK03-000548262` resolves while `MZK01-000548262` is a 404. One configured base for the
  whole library would break roughly a fifth of its links.
- **The record number is the last nine digits, not the trailing digit run.** The base itself ends in digits,
  so a greedy match reads `mzk03000706629` as `mzk` + `03000706629` and 404s.

`id_sysno` has three shapes, all present at MZK in 1000 sampled records:

| Shape | Count | Example |
|---|---|---|
| prefix + hyphen | 654 | `mzk01-001506523` |
| bare | 307 | `000913055` |
| prefix, no hyphen | 39 | `mzk03000706629` |

And the format differs per library — `vkol001054610`, `000153894` — with `cuni`, `svkkl` and `upm` indexing
no `id_sysno` at all.

So the resolution takes **two mappings**, both configuration:

| Setting | Purpose |
|---|---|
| `defaultPrefix` | used when the value carries none |
| `bases` | prefix as indexed → base as the catalogue wants it |

A base mapped to the empty string means the catalogue takes a bare number, which is what vkol's does. An
unmapped prefix yields **no link** rather than a guess — a wrong catalogue link is worse than none, because
it looks like the gateway found the record.

Verified against `vufind.mzk.cz`: `mzk01-001506523` → `MZK01-001506523`, `mzk03-000548262` → `MZK03-000548262`,
`000970198` → `MZK01-000970198`, `mzk03000706629` → `MZK03-000706629` — all 200.

## Errors

JSON, with a machine-readable `code` and a bilingual message.

| Case | Status | `code` |
|---|---|---|
| Library not in the registry / not configured | `404` | `unknown_library` |
| Registry reports the library as down | `503` | `library_unavailable` |
| Library below the lowest supported Kramerius version | `501` | `unsupported_version` |
| Pid not held by the library | `404` | `not_found` |
| Model has no v3 representation | `404` | `no_manifest_for_model` |
| `?link=` unparseable | `400` | `unparseable_link` |
| Kramerius did not answer in time | `504` | `upstream_timeout` |
| Kramerius could not be reached | `502` | `upstream_unreachable` |
| Kramerius answered with a status we cannot use | `502` | `upstream_rejected` |
| Kramerius answered `429`/`503` — it is asking us to wait | `503` | `upstream_overloaded` |
| Document exceeds `IIIF_MAX_ITEMS` | `507` | `too_large` |
| `GET …/dimensions` on a deployment with no size store | `404` | `no_dimension_cache` |
| `PUT`/`DELETE …/dimensions` on a deployment with no size store | `501` | `no_store` |
| A defect in the gateway itself — and nothing else | `500` | `internal` |

> v1 answered every one of these with `200` and an `{"error": …}` body, including a message with a stray
> leading space. Machine clients could not distinguish success from failure.

**An upstream failure is never a `500`.** This gateway is a client of fifty-odd third-party instances, and
the most common reason a manifest cannot be built is that one of them is slow or down. A `500` asserts the
fault is ours: it sends an operator to our logs, our heap and our deploy history for a problem none of
those explain, and it is the wrong signal for a caching proxy, which shields callers from a `500` but will
retry past a `502`/`504`. The four `upstream_*` codes are separated because the operator response differs
— wait, check the host, look for an adapter boundary, or back off.

Measured while implementing this: MZK's search API took 8–65 s for a single document lookup and
intermittently answered `500` itself, and the gateway relayed that as its own `500`.

A document over the ceiling is deliberately **not** an upstream error. The instance answered correctly and
completely; the refusal is ours, and the fix is to raise `IIIF_MAX_ITEMS` or repair the record.

## The comparison corpus

`corpus/pids.json` names 25 real MZK documents — every model the gateway serves, sizes from 1 to 3568
pages, and the awkward cases (PDF-only, cartographic, licensed, catalogued). `make corpus-fetch` captures
what the live gateway says about each; `make corpus` summarises.

Running it against production on 2026-08-28 confirmed the defect list below from live output rather than
from reading the Ruby:

| Observed | Count |
|---|---|
| `"cz"` language tag | 21 of 21 served |
| No `behavior` on any manifest | 17 of 17 manifests |
| `seeAlso.type: "Alto"` | 8 |
| `https` presentation context | 5 |
| Manifest with no `items` | 1 (the PDF-only document) |
| Refused with HTTP 200 and an error body | 4 (`supplement`, `article`, `internalpart`, `picture`) |

Two findings sharpened the record:

- **The `rows=1500` truncation is real in production.** The corpus' largest document has 3568 pages and
  the live gateway returns exactly **1500** canvases, with nothing to say that 2068 are missing.
- **The five `https` contexts are exactly five Ruby builders.** Commit `ceeee85` moved the context to the
  spec's `http` form in 7 of 12 places; the ones it missed are `create_iiif_periodical`,
  `create_iiif_soundrecording`, `create_iiif_konvolut`, `create_iiif_collection` and `create_iiif_pdf` —
  which is precisely the set that fails validation.

**What the schema does not catch.** Running those manifests through the same JSON Schema our tests use
marks 5 invalid and 16 valid — but `"cz"` and `seeAlso.type: "Alto"` pass. JSON Schema cannot express
"this must be a BCP 47 tag", and the schema does not constrain `seeAlso.type`. Both are still wrong per
the spec text; they are simply not things validation can defend, which is why they are asserted in the
fixture tests directly.

## v1 defects fixed

Collected for review; each is explained at the relevant section above.

- `"cz"` is not a language tag — Czech is `cs`. Every label key was invalid.
- `seeAlso.type: "Alto"` is not an IIIF type.
- Empty metadata emitted as `{"none": [null]}`; provider logo as `{"id": null}`; absent parents as `{}`.
- navPlace polygons self-intersecting and unclosed; Points double-wrapped; unparseable coordinates crashed.
- `/full` never matched any `info.json`, so every canvas got `"width": null`.
- `rows=1500` silently truncated documents larger than that — real at MZK, whose largest is 3568 pages.
- Nested collections always typed `Manifest`.
- Monograph volumes labelled differently on K5 and K7.
- Tracks sorted by Solr's default rather than the index field that was fetched for the purpose.
- `audio/mp3` is not a media type.
- Periodical volume/issue homepage hardcoded `/mzk/`, and the catalogue base was hardcoded `MZK01-`,
  which is a 404 for the fifth of MZK records indexed under `mzk03`.
- Errors returned `200`; one returned a non-JSON string.
- A crafted pid could inject Solr query clauses — the raw path segment was interpolated into `q=`.
- `oldprintsheetmusic` emitted the raw model name as its "Typ dokumentu".
- Four page-bearing models were refused outright (~152k documents at MZK) — see the note under Model dispatch.
- PDF manifests omitted `items`, which the schema requires — every one of them was invalid IIIF (13 832 at MZK).

## Capability boundaries

`Kramerius70` (floor 7.0) vs `Kramerius714` (floor 7.1.4) — the whole delta, with the fallbacks the older
adapter uses. See DESIGN.md for the probe results this is derived from, and re-run `make capabilities` after
each Kramerius release to find the next boundary.

| Field | Below 7.1.4 | Fallback |
|---|---|---|
| `has_alto_content` | absent | probe the first page's ALTO stream once |
| `has_text_ocr_content` | absent | as above |
| `coords.is_point` | absent | derive: SW == NE |
| `count_volume` | absent | page until the cursor repeats |

`coords.point` arrives at 7.2.1.1 and is deliberately unused: it duplicates `coords.bbox.center`, which every
K7 version has, so it would buy a version boundary and nothing else.
