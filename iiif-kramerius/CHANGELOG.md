# Changelog — iiif-kramerius

The library: the IIIF Presentation v3 model, the manifest builder, and the embedded example. Versioned
separately from the gateway, whose changelog is [`../gateway/CHANGELOG.md`](../gateway/CHANGELOG.md) —
this number is a compatibility promise to whoever embeds the library, that one says which build is
deployed.

Following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-09-29

Initial library release. It is versioned separately from the gateway because embedding compatibility
and service deployment have different release cadence.

### Added
- The IIIF Presentation v3 vocabulary as records, with `LanguageMap` preserving insertion order so
  serialised bytes are stable and an ETag over them means something.
- `ManifestBuilder`: a pure function from a `DocumentBundle` to a manifest. No I/O, no Kramerius client,
  no configuration beyond a base URL and the translations.
- `DocumentRecord` and `DocumentBundle`, the parse-don't-validate boundary: every enriched field is
  optional, so a source that cannot provide one is not obliged to invent it.
- `IiifStructure`, which states whether a document builds a paged manifest, media manifest, child
  collection, or virtual collection. Sources provide pages, members, media units, and tracks directly.
- `DocumentBundle.MediaUnit` and `mediaUnits`, which describe the IIIF content without tying the
  library API to one Kramerius model.
- `navDate`, manifest-level `seeAlso`, and `ContentLocator.mods(pid)`. `navDate` is formatted at second
  precision by the builder; MODS is linked but never parsed by the library.
- A short access condition in `requiredStatement`, when the source supplies one. Full licence notices
  remain in the Licence metadata row.
- `behavior` derived from indexed page placement, and `navPlace` from coordinates with closed and
  correctly wound polygons.
- Authored strings in `cs` and `en` from `i18n/<language>.json`, loadable per language.
- `iiif-kramerius-embedded`: the worked example of an application building a manifest with this library
  and nothing else. Its `KrameriusApi` supplies `pagesOf`, `membersOf`, and `mediaUnitsOf` directly.
