# Embedding the manifest builder in Kramerius

Kramerius can serve its own IIIF manifests without the gateway. This document is how, and — more usefully
— what it costs and what it does not buy.

The working example is `iiif-kramerius-embedded`. It compiles against no Kramerius code at all, so it
builds anywhere, and it depends on **`iiif-kramerius-build` only**. That dependency is the point: if
embedding ever needed the SPI, the adapters or the gateway, the build would fail and the claim in
`docs/DESIGN.md` would be false.

## What you need, and what you do not

| | Gateway | Embedded |
|---|---|---|
| IIIF model and the builder | ✅ | ✅ |
| `DocumentBundle` | ✅ | ✅ |
| `Repository` / `DocumentSource` SPI | ✅ | ❌ |
| Version-floor adapters (`Kramerius70`, `Kramerius714`) | ✅ | ❌ |
| Registry, library resolution | ✅ | ❌ |
| Solr topology detection | ✅ | ❌ |
| Postgres size store, job queue, worker | ✅ | *maybe — see below* |

Everything in the "❌" rows exists because a gateway serves libraries **it does not own**: at unknown
versions, over HTTP, with no access to internals. An application serving its own repository knows its
version, its model set and its Solr schema, and can call them directly.

## The whole integration

Three steps, and the middle one is the only one with any content.

```java
// 1. Configure once, at startup.
ManifestConfig config = new ManifestConfig(
    baseUrl,                       // how clients reach YOU — every id is built from it
    digitalLibraryUrl,
    ManifestConfig.DEFAULT_FALLBACK_CANVAS_SIZE,
    Translations.bundled());       // .mergedWith(...) to add languages — see below

LibraryProfile library = LibraryProfile.of("mzk", "https://example.org/mzk")
    .label(Map.of("cs", List.of("Moravská zemská knihovna")))
    .licenseTexts(licensesFromLicensesManager())
    .build();

// 2. Gather what the document's shape needs.
DocumentBundle bundle = DocumentBundle.of(library, urls, record)
    .pages(pages)
    .sizes(sizes)
    .build();

// 3. Build. A pure function: no I/O, no cache, no surprises.
Iiif.Document manifest = ManifestBuilder.build(config, bundle);
```

`DocumentShape.requires()` tells you which parts to fill, so you do not have to reverse-engineer that
from the builder:

```java
for (DocumentShape.Part part : record.shape().requires()) {
  switch (part) {
    case PAGES       -> bundle.pages(pagesOf(pid)).sizes(sizesOf(pages));
    case MEMBERS     -> bundle.members(childrenOf(pid, record.childKind().orElseThrow()));
    case SOUND_UNITS -> bundle.soundUnits(soundUnitsOf(pid));
  }
}
```

## Mapping onto Kramerius' own classes

`KrameriusApi` in the example is a stub. In a real integration each method is one of these:

| Example method | Real source |
|---|---|
| `byPid`, `childrenOf` | the internal Solr client — no HTTP, no cursorMark paging needed for a single document |
| `imageSize` | the image server, or Akubra where tiles are local — **see the warning below** |
| `baseUrl` | `/client/v7.0/info`, or your own configuration |
| licence texts | `LicensesManager` |
| the model → shape mapping | your own model set, as a `switch` |

The last row is worth dwelling on. The gateway needs a configurable, version-specific model table because
Kramerius' model set changes between releases and it must serve all of them at once. **You do not.** You
know which release you are. A `switch` over your own models is correct, obvious, and cannot drift out of
sync with a JSON file.

## The thing embedding does not fix

**Embedding removes the Solr round trips. It does not remove `info.json`.**

This is the one claim worth stating plainly, because the opposite is intuitive and wrong. IIIF requires a
real `width` and `height` on every canvas, Solr indexes neither, and there is no bulk dimension source
over HTTP — so dimensions cost one request per page from wherever the images live.

If your tiles are served by a **separate image server**, which is the case for most Czech libraries,
embedding changes nothing about that cost. You still pay one `info.json` per page, you still want a
cache, and for a large repository you still want something like the gateway's worker to fill it. At MZK
that is 75.7 million requests for full coverage — roughly nine days at 100 req/s.

If your tiles are **local to the application** — read from Akubra, or from a filesystem you can `stat` —
then reading dimensions is nearly free and the whole caching apparatus disappears. That is the case where
embedding is dramatically cheaper, and it is worth checking which one you are in before assuming.

Either way, `ManifestBuilder` does not care. The bundle carries sizes; where they came from is your
business, and level 2 never asks. Pages you have no size for fall back to
`ManifestConfig.DEFAULT_FALLBACK_CANVAS_SIZE` and the manifest stays valid — see
`EmbeddedManifestsTest.unmeasuredPage`.

## Two things to get right

**`baseUrl` is load-bearing.** Every `id` in every manifest is built from it. Derive it from the incoming
request and a manifest served through a proxy will advertise the proxy's internal address, and viewers
will follow dead links. Configure it.

**Adding a language is adding a file.** `Translations` does no file I/O — it parses what you hand it, so
that a library Kramerius embeds carries no filesystem policy. To add Slovak, read `sk.json` yourself and
merge it:

```java
Translations translations = Translations.bundled()
    .mergedWith(Map.of("sk", Translations.parse(Files.newInputStream(path))));
```

Merging is per key, so correcting a single label does not mean copying a whole file and keeping it in
step with future releases. The gateway does exactly this from `IIIF_TRANSLATIONS_DIR`; see
`TranslationDirectory` for a worked version, including what it does with an unreadable file.

**Language tags are data, not decoration.** `LanguageMap.none(...)` is for record content whose language
you do not know, which is most of it. Only tag `cs` or `en` when you actually know. The registry's own
`name` field is the institution's name in its own language — Slovak for several entries — and tagging
that `cs` is a false claim a viewer acts on.

## Validating what you produce

`iiif-kramerius-model` publishes a test-jar containing `IiifSchema`, the official IIIF Presentation v3
JSON Schema and a harness for it:

```xml
<dependency>
  <groupId>cz.mzk.iiif</groupId>
  <artifactId>iiif-kramerius-model</artifactId>
  <type>test-jar</type>
  <scope>test</scope>
</dependency>
```

```java
IiifSchema.assertValid(ManifestWriter.toJson(manifest));
```

Worth wiring in from the first commit. The v1 gateway shipped `"cz"` language tags, `seeAlso.type: "Alto"`
and Manifests with no `items` for years — 13 832 invalid manifests at MZK alone — and nobody noticed,
because nothing checked.

Note that the schema does not catch everything: it cannot express "this must be a BCP 47 tag" and does not
constrain `seeAlso.type`, so `"cz"` passes validation while still being wrong. The schema is a floor, not
a ceiling; `docs/BEHAVIOUR.md` is the actual contract.
