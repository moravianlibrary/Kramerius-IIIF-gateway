# Changelog — gateway

The gateway service: Kramerius adapters, cache worker, and HTTP application. It is versioned separately
from [iiif-kramerius](../iiif-kramerius/CHANGELOG.md), whose version describes the embeddable library API.

## [2.0.0] - 2026-09-29

Complete Java rewrite of the legacy JavaScript/Ruby gateway.

### Added

- IIIF Presentation API 3 manifests and collections for Kramerius 5 and 7, selected through
  version-floor adapters.
- Real canvas dimensions from the Image API, stored as one PostgreSQL blob per document; a worker fills
  missing dimensions and performs resumable per-library sync.
- A bounded manifest cache, dimension status and administration endpoints, rate limiting, and
  per-library upstream concurrency and request-rate limits.
- Registry resolution with local library-file overlays, validation of every configured JSON file, and
  configurable catalogue mappings.
- Kramerius 7 licence configuration: a concise access condition in `requiredStatement`, the full notice
  in the Licence metadata row, and provider attribution when no reader-facing condition is available.
- `navDate`, MODS `seeAlso`, ALTO links, `navPlace`, `behavior`, catalogue links, and translated labels.
- Offline IIIF schema validation, PostgreSQL integration tests, public viewer fixtures, and live smoke
  checks for each adapter.

### Changed

- All output targets valid IIIF Presentation API 3. Gateway-authored Czech uses `cs`; the old gateway
  emitted `cz`, invalid `seeAlso` types, null-valued language maps, and malformed navPlace polygons.
- Metadata comes from Solr. K7 adapters do not parse MODS; MODS remains linked as `seeAlso`.
- The gateway asks each instance for its version and treats the registry or local declaration as a
  fallback, so an adapter changes immediately after an upstream upgrade.
- The library registry is an input overlaid by local settings rather than a second copied inventory.

### Fixed

- Image API probing now uses each Solr deployment's unique key, so MZK correctly emits Image API 3
  services instead of falling back to Image API 2.
- Nested collections reference their actual child documents; large documents are not truncated; page and
  track PIDs return 404 rather than an invalid standalone manifest.
- Restricted image responses no longer prevent a public manifest from being served. Approximate or partial
  dimensions are explicitly marked and are never cached as complete.
- Cache capacity counts UTF-8 bytes; concurrent cold requests share one build; idle client rate-limit
  buckets are released.
- The dimension worker refreshes modified pages, resumes an unfinished cursor window correctly, and
  starts the next completed pass three hours before its previous start.
- Upstream failures return meaningful 502, 503, or 504 responses; malformed configuration stops startup;
  database-backed dimension administration without a store returns `501 no_store`.
- Viewer checks use fully public records and fail on browser errors.

## [1.0.0]

Legacy JavaScript/Ruby gateway, retired by 2.0.0. It generated the earlier gateway manifests and had no
persistent dimension cache or background worker.
