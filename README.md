# Kramerius IIIF gateway

IIIF [Presentation API 3.0](https://iiif.io/api/presentation/3.0/) manifests for the digital libraries in
the [Kramerius registry](https://registr.digitalniknihovna.cz) — 56 institutions running Kramerius 5 and 7.

```
https://iiif.digitalniknihovna.cz/mzk/uuid:71d66fe0-d986-11e2-9923-005056827e52
```

Manifests are served with `Access-Control-Allow-Origin: *`, so they load directly in Mirador, the
Universal Viewer and Clover.

## What it does

- **Real canvas dimensions**, cached. Not the 1000×1000 placeholder the previous version declared.
- **Kramerius 5 and 7**, selected per library from the registry's version.
- **Every document model with pages**, including `article`, `internalpart`, `supplement` and `picture` —
  roughly 152 000 documents at MZK alone that the previous version refused.
- **Valid IIIF v3.** Every manifest the test suite produces is checked against the official schema.

## Endpoints

| Route | Returns |
|---|---|
| `GET /{library}/{pid}` | the manifest or collection |
| `GET /{library}/{pid}/dimensions` | how much of this document's page sizes are cached |
| `GET /resolve?link=…` | redirects from a Digital Library URL to its manifest |
| `GET /libraries` | which libraries resolve, to which adapter, and how the sync is doing |
| `GET /health`, `GET /version` | operational |

On a separate, **unpublished** admin port:

| Route | Does |
|---|---|
| `PUT /{library}/{pid}/dimensions` | warm this document's page sizes |
| `DELETE /{library}/{pid}/dimensions` | drop them, so the next request re-measures |
| `PUT /libraries/{library}/sync` | enable, pause or rewind a library's dimension walk |

`X-IIIF-Dimensions` says how the canvas sizes were obtained. `partial` is provisional — some canvases
still declare a nominal size while the real ones are measured — and is `no-store`; ask again shortly.
`approximate` means some canvases carry a shape read from the page's thumbnail because the image itself
is licensed and answers 403: the aspect is right and the magnitude is nominal. Nothing is on its way to
improve that, so it is cached like any other manifest — but it is not permanent either, and a page that
becomes readable is picked up within `IIIF_FAILED_LOOKUP_RETRY_SECONDS`. No header means every canvas was
measured.

## Running it

One jar, two roles, the same configuration:

```sh
java -jar iiif-gateway.jar web      # serves manifests
java -jar iiif-gateway.jar worker   # fills the dimension cache; serves nothing
```

The split exists because a full dimension walk of a large library runs for **weeks** — 75.7 million
`info.json` requests at MZK, about nine days at 100 req/s — and must not compete with request serving.

Minimum configuration is one line:

```sh
IIIF_LIBRARIES="mzk=https://api.kramerius.mzk.cz|7.2.2" java -jar iiif-gateway.jar web
```

Point it at the registry instead to serve everything:

```sh
IIIF_REGISTRY_URL=https://registr.digitalniknihovna.cz/api/libraries?archived=false
IIIF_DB_URL=jdbc:postgresql://localhost:5432/iiif   # optional; without it, no dimension cache
```

Per-library settings the registry has no field for go in a file, **alongside** the registry rather than
instead of it:

```sh
IIIF_LIBRARIES_FILE=config/libraries.json java -jar iiif-gateway.jar web
```

[`config/libraries.json`](config/libraries.json) is an **overlay**, and ships empty because there is
nothing local to say yet. The registry already carries every library's url, version and name, and is
right about them; what it has no field for is a rate one institution asked for, a name in a third
language (it carries exactly two), or a redirect to a test host. Every field is optional and anything
omitted comes from the registry:

```json
{"libraries": {"mzk":  {"rps": 4},
               "vkol": {"name": {"de": "Wissenschaftliche Bibliothek Olmütz"}}}}
```

Inline does the same, with an empty segment meaning "keep the registry's":
`IIIF_LIBRARIES="mzk=||de:Mährische Landesbibliothek"`.

[`config/check.sh`](config/check.sh) validates the file against
[`config/libraries.schema.json`](config/libraries.schema.json) before the gateway has to — it is in
`make check`, and it catches the one mistake the gateway cannot: a misspelled key. `{"rate": 4}` parses,
loads and starts, then paces that library at the global rate forever with nothing in the log to say why.

For a deployment that must not reach the registry at all, `scripts/libraries.py --snapshot <path>`
writes a complete list — url, version and name for every library that answers — which is then the whole
configuration.

Every key is documented in [`.env.example`](.env.example).

## Building

```sh
make help      # every target, self-documented
make check     # the gate: format, typecheck, lint, tests
make build     # fat jar and container image
make version-check   # the library and the application are versioned separately
```

The image carries both roles, as the jar does. The admin port is deliberately **not** exposed — access
control is the deployment's job, which is why there is no bearer token:

```sh
podman run -e IIIF_LIBRARIES="mzk=https://api.kramerius.mzk.cz|7.2.2" -p 8080:8080 \
  localhost/iiif-gateway:2.0.0 web
podman run -e IIIF_LIBRARIES=… -e IIIF_DB_URL=… localhost/iiif-gateway:2.0.0 worker
```

There is no compose file yet, and no `make up`: web, worker and Postgres are still wired together by
hand.

## Layout

Four levels, each usable without the one below it:

```
iiif-kramerius-model      IIIF v3 records, language maps, translations. No I/O.
  └── iiif-kramerius-build    the manifest builder — a pure function of a DocumentBundle
        ├── iiif-kramerius-embedded   worked example: Kramerius hosting this in-process
        └── iiif-kramerius-spi        DocumentSource, ImageSizeSource, Repository, orchestration
              └── iiif-gateway        version-floor adapters + the service
```

**Kramerius' own application stops at level 2.** It gathers records from its own Solr, fills a
`DocumentBundle` and calls the builder — no SPI to implement, no registry, no adapter selection. See
[`docs/KRAMERIUS.md`](docs/KRAMERIUS.md); `iiif-kramerius-embedded` is a compiling example, and its POM
depends on level 2 only so the claim cannot quietly stop being true.

Adapters are named for the **first Kramerius version they support** — `Kramerius50`, `Kramerius70`,
`Kramerius714` — and the highest floor at or below an instance's version wins. A release that changes
nothing we depend on needs no work at all.

## Documentation

| | |
|---|---|
| [`docs/DESIGN.md`](docs/DESIGN.md) | every decision and why, plus the live measurements behind them |
| [`docs/BEHAVIOUR.md`](docs/BEHAVIOUR.md) | the contract: what comes out, model by model |
| [`docs/KRAMERIUS.md`](docs/KRAMERIUS.md) | embedding it in Kramerius itself |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | judgement calls: what was chosen, against what, and the measurement that settled it |
| [`docs/CHANGES-FROM-V1.md`](docs/CHANGES-FROM-V1.md) | what changed for anyone who used the old gateway |

`docs/BEHAVIOUR.md` is the specification. Where the code and that document disagree, the document is right
and the code is a bug.

## Known limits

The calls that were made, and what each was made against, are in
[`docs/DECISIONS.md`](docs/DECISIONS.md). The three limits that affect what you get back:

- **Most Kramerius 5 libraries have no working IIIF Image API.** Their `info.json` answers 200 with an
  empty body, or 500, and image requests 404 — so their manifests carry the plain image stream, no image
  service, and approximated canvas dimensions. It is probed per library rather than assumed: `lmda`,
  `dsmo` and `kvkli` do run a working Image API 2 and get tiles and real dimensions like a K7 library.
  Everything else works on every K5 instance.
- **No `rights` URI.** Kramerius licence names (`dnnto`, `dnntt`, `knav_vip`) have no mapping to
  creativecommons.org or rightsstatements.org, and inventing one would be a legal claim. Licence text goes
  in `requiredStatement` instead.
- **Kramerius 4** (one registry entry) is refused with a clear message rather than served a broken
  manifest.
