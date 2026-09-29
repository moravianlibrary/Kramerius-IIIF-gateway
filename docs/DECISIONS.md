# Decisions

This records choices that affect the gateway's behaviour or maintenance. The README explains operation;
tests define the emitted contract. When a choice changes, update its entry.

## Adapters and representation

### Kramerius 5 adapter boundary

**Keep the Kramerius 5 adapter limited to major version 5.** The default floor-based predicate would
also report support for K7. The registry currently selects a higher K7 adapter first, but the exact
major check makes K5's compatibility contract explicit. Kramerius went directly from 5 to 7, so there
will be no version 6 to accommodate.

### Kramerius 4 support

**Do not add an adapter.** One remaining registry instance does not justify another mapper, model table,
and URL family. Reconsider if its operator asks.

### Source of the Kramerius version

- **Ask the instance's info endpoint first.** It selects the correct adapter immediately after an
  upgrade; the declared registry or static-file version remains a fallback for unreachable instances.
- **Do not treat the registry as authoritative.**

### Model tables

- **Allow overlays through IIIF_MODELS_DIR.** New Kramerius models can be served without a gateway
  release.
- **Do not silently accept malformed overlays.** A bad model table or invalid promotion stops startup;
  a partial table produces missing manifests that are difficult to diagnose.

### Page labels

**Suppress the default NormalPage type.** It is internal vocabulary and adds nothing to the page number.
Preserve meaningful types such as FrontCover, TitlePage, and Advertisement.

### ALTO links when the flag is absent

- **Advertise ALTO when has_alto_content is absent.** The field is often unpopulated while OCR and ALTO
  are available, so omission would hide text from most documents.
- **Do not probe every document first.** The additional request per manifest is not worth avoiding an
  occasional unavailable seeAlso link.

### Kramerius 5 image service

- **Probe each library's Image API.** K5 versions do not predict whether a deployment has a working image
  server; a valid info.json with dimensions is the evidence.
- **Do not hardcode K5 as having or lacking an Image API.** Working and broken deployments exist at the
  same version levels.
- **Do not parse IMG_FULL headers.** K5 full-image streams are not consistently JPEG, and a parser would
  add maintenance surface for a retiring platform.

### Restricted upstream images

- **Serve the manifest when Kramerius returns 403 for an image.** Metadata is public and a viewer may
  fetch canvas URLs with credentials or from an authorised network.
- **Do not return the gateway's 403.** It would deny manifests to readers who can access the original
  image service.
- **Do not invent IIIF Authorization Flow endpoints.** Kramerius does not expose the required services.

### Licence and access text

- **Put the concise access condition in requiredStatement; keep the full notice in the Licence metadata
  row.** Readers see the required action without receiving a legal-text banner.
- **Build that statement only from licenses and licenses_of_ancestors.** contains_licenses describes
  restrictions inside a compound object and must not restrict its parent.
- **Keep provider attribution as the fallback.** Kramerius 5 and K7 instances without licence
  configuration cannot supply a reader-facing condition.

### MARC country codes

**Use the generated exact list, not a shape heuristic.** Short values can be real place names; the list
combines current authority codes with deprecated codes still found in Kramerius data.

### Out-of-scope representation features

- **Do not emit a rights URI.** A Kramerius licence name is not enough to make a legal mapping to a
  standard rights vocabulary.
- **Do not implement IIIF Content Search v2 or OCR supplementing annotations in this release.** They do
  not block manifest use and can be added later.
- **Do not add a coords.point adapter boundary.** It duplicates data already available through
  coords.bbox.center.

## Dimensions, caching, and sync

### Incomplete dimensions

- **Serve a provisional manifest and queue the remaining work.** It lets a viewer open immediately;
  X-IIIF-Dimensions: partial and no-store make its status explicit.
- **Do not answer 503 with Retry-After.** A provisional manifest is more useful to readers, and clients
  already have the header and cache directive needed to handle it.

### Inline dimension wait

**Use a 10-second default.** It covers ordinary and most large documents while bounding the delay a
reader experiences; five seconds cut off too many normal multi-page documents.

### Unknown page size

**Use an approximate thumbnail aspect when available, otherwise 1000 × 1000.** IIIF canvases need
dimensions. A thumbnail gives a restricted page's real shape; the square remains the safe final fallback.

### Dimension state

**Report approximate consistently on manifests and the dimensions endpoint.** Operators need to
distinguish a measured size, a thumbnail-derived size, and queued work.

### Backfill policy

- **Keep whole-library dimension backfill opt-in and disabled by default.** It can send millions of
  requests to an institution that did not agree to a crawl.
- **Accept lazy dimensions for seldom-viewed pages.** Respecting partner infrastructure outweighs
  complete precomputation.

### Image API version detection

- **Sample several public tiled pages and use the conservative result.** One page can be anomalous; a tie
  resolves to Image API 2 because claiming v3 to a v2 server breaks clients.
- **Do not detect from one page.** A single migration or exceptional page could mislabel every canvas in
  a library.

### Manifest cache

- **Keep the bounded server-side manifest cache.** Rebuilding a complete large manifest is expensive even
  when its dimensions are cached, while identical output can safely be reused within the manifest TTL.
- **Do not cache partial manifests.** They have queued work and must be rebuilt when it finishes.
- **Do not treat it as a CDN replacement.** A CDN remains the scaling layer for deployments that need one.

### Size-blob compression

**Rely on PostgreSQL TOAST.** Additional zstd compression did not materially reduce stored data and would
add work on every read and write.

### Solr cursor and unique key

- **Take the unique-key field from each adapter schema.** K5 uses PID; K7 uses a different field.
- **Fail a sync that omits nextCursorMark.** It means cursor pagination was not supported, and accepting
  the first batch as complete would silently skip the rest.
- **Do not fall back to start/rows.** It weakens the consistency guarantee without making the result
  visibly different.

### Unsupported libraries

**Allow deployments to exclude a library with IIIF_EXCLUDED_LIBRARIES.** A library that cannot provide
correct cursoring or image probing should report a deliberate 404, not misleading sync success. The
default remains empty; deployments decide whom to serve.

## Operations and packaging

### Upstream rate limits

- **Allow an rps value per library in IIIF_LIBRARIES_FILE.** Institutions have different capacity; absent
  values inherit the global rate.
- **Do not add a parallel inline environment-variable map.** The inline list is positional and a second
  configuration mechanism could conflict with the file.

### Invalid configuration

- **Fail startup for every malformed configured file or directory.** A configuration that is silently
  ignored is worse than an immediate, actionable deployment failure.
- **Do not fail for an unset optional directory.** Unset means use bundled defaults; malformed means the
  operator intended an override that cannot be applied.

### Registry-free library snapshots

**Keep dev/libraries.py and the generated snapshot option.** Some deployments cannot depend on the
registry at runtime; the snapshot is reviewed data, while config/libraries.json remains a local overlay.

### MARC code regeneration

**Keep dev/country-codes.py.** The country-code resource is maintained data derived from the authority
and live catalogue values, not hand-edited application logic.

### Adapter capability probing

**Keep dev/capabilities.sh.** It is the evidence-gathering tool for a future Kramerius adapter boundary;
compatibility is determined by actual Solr schemas, not release labels alone.

### Public extension SPI

**Do not publish the SPI.** The gateway is its only consumer, and the build enforces that the embedded
library remains independent of it.

### Artifact versions

**Version the reusable library and gateway application separately.** Their release cadence and consumers
are different; the build checks that the gateway pins the matching library version.

## Viewer verification

### Viewer dependencies

- **Use one pinned container image for Mirador, Clover, and Universal Viewer.** Headless checks and the
  human review page use the same assets and work without a CDN or fresh npm resolution.
- **Do not load viewer dependencies from CDNs at run time.** Different dependency graphs made manual
  review and automated checks exercise different viewers.

### Anonymous viewer fixtures

**Use only fully public documents.** A restricted fixture can pass only because a viewer happened not to
preload the protected canvas; every uncaught browser error fails the check.

### Live smoke checks

**Keep dev/smoke.sh outside make check.** It covers live K5/K7 adapters and document models, but a
third-party outage must not make the offline CI gate unreliable.
