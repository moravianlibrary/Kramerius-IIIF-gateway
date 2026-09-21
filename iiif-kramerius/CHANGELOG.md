# Changelog — iiif-kramerius

The library: the IIIF Presentation v3 model, the manifest builder, and the embedded example. Versioned
separately from the gateway, whose changelog is [`../gateway/CHANGELOG.md`](../gateway/CHANGELOG.md) —
this number is a compatibility promise to whoever embeds the library, that one says which build is
deployed.

Following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- `navDate` and a manifest-level `seeAlso` on `Iiif.Document`, with `Iiif.SeeAlso.mods(…)` beside the
  existing `alto(…)` factory. Added after surveying live v3 servers: Wellcome, Yale and the Internet
  Archive all publish a manifest-level `seeAlso`, and Yale publishes `navDate`.

  `navDate` is formatted at second precision by the builder rather than by whoever fills the record, so
  no adapter can get it wrong on its own — Kramerius indexes milliseconds and every published manifest
  truncates them.
- `ContentLocator.mods(pid)`, for that `seeAlso`. A link only: nothing in the library fetches or parses
  MODS, which is what lets an embedder skip an XML parser entirely.
- `DocumentRecord.navDate`, the beginning of the document's period — distinct from `modified`, which is
  when it changed.
- `DocumentRecord.withShape(shape, childKind)`. The model table names one child kind per model and the
  repository does not oblige: a periodical volume can hold pages instead of issues (84,559 such pages
  at MZK), a sheet-music item another sheet-music item. Whoever assembles a bundle discovers that only
  by asking, and the builder types its output from the record's shape — so the correction has to travel
  on the record.

## [1.0.0] - 2026-08-28

First release as a separately versioned artifact. The code is not new — it is the library half of the
gateway rewrite, given its own version because it changes for different reasons than a deployment does.

### Added
- The IIIF Presentation v3 vocabulary as records, with `LanguageMap` preserving insertion order so
  serialised bytes are stable and an ETag over them means something.
- `ManifestBuilder`: a pure function from a `DocumentBundle` to a manifest. No I/O, no Kramerius client,
  no configuration beyond a base URL and the translations.
- `DocumentRecord` and `DocumentBundle`, the parse-don't-validate boundary: every enriched field is
  `Optional`, so a source that cannot provide one is not obliged to invent it.
- Document shapes for every Kramerius model that has a v3 representation, dispatched exhaustively — a
  shape exists only where there is code that builds one.
- `behavior` derived from indexed page placement, `navPlace` from coordinates with closed and correctly
  wound polygons, `requiredStatement` from attribution.
- Authored strings in `cs` and `en` from `i18n/<language>.json`, loadable per language.
- `iiif-kramerius-embedded`: the worked example of an application building a manifest with this library
  and nothing else — no SPI, no Repository, no adapter. Its test is what keeps that true.
