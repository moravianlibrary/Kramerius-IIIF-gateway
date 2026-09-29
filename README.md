# Kramerius IIIF gateway

The gateway creates [IIIF Presentation API 3.0](https://iiif.io/api/presentation/3.0/) manifests for
Kramerius digital libraries. It supports Kramerius 5 and 7, resolves libraries through the Kramerius
registry or a local configuration, and serves manifests that load in Mirador, Clover and Universal Viewer.

Example:

```text
https://iiif.digitalniknihovna.cz/mzk/uuid:71d66fe0-d986-11e2-9923-005056827e52
```

## What it provides

- IIIF v3 manifests and collections for supported Kramerius document models.
- Canvas dimensions from the upstream Image API, with PostgreSQL-backed caching and background filling.
- Kramerius 7 licence configuration in access conditions and metadata.
- Catalogue links where a library supplies a mapping.
- CORS headers for browser-based IIIF viewers.

## Run it

The jar has two roles:

```sh
java -jar iiif-gateway.jar web      # serve manifests
java -jar iiif-gateway.jar worker   # fill dimension cache; serves no HTTP traffic
```

For a single library:

```sh
IIIF_LIBRARIES="mzk=https://api.kramerius.mzk.cz|7.2.2" \
  java -jar iiif-gateway.jar web
```

For registry-based operation:

```sh
IIIF_REGISTRY_URL=https://registr.digitalniknihovna.cz/api/libraries?archived=false \
IIIF_DB_URL=jdbc:postgresql://localhost:5432/iiif \
  java -jar iiif-gateway.jar web
```

Use `IIIF_LIBRARIES_FILE=config/libraries.json` for per-library overrides such as an image-server rate,
additional translated name, or catalogue mapping. It is an overlay: the registry still supplies the
library URL, version and names, while only the fields in the file override them. Leave it empty when no
local setting is needed. Validate it with `config/check.sh config/libraries.json`. Every setting and its
default is documented in [`.env.example`](.env.example).

PostgreSQL is optional for serving manifests, but required for persistent dimensions, the work queue and
sync state. Run both `web` and `worker` against the same database in a production installation. The admin
port is intentionally separate and must not be exposed publicly.

## Endpoints

| Route | Response |
|---|---|
| `GET /{library}/{pid}` | Manifest or collection |
| `GET /{library}/{pid}/dimensions` | Cached-dimension status |
| `GET /resolve?link=…` | Redirect from a Kramerius link to its manifest |
| `GET /libraries` | Library resolution and sync status |
| `GET /health`, `GET /version` | Operational status |

The separate admin port provides `PUT` and `DELETE` dimension operations and library sync control.

`X-IIIF-Dimensions: partial` means the gateway returned before every image size was resolved. Such a
response is `no-store` and is never placed in the manifest cache. `approximate` means an upstream licence
prevents an image-size lookup; the gateway uses the thumbnail's aspect ratio until the upstream permits a
better result.

## Build and verify

```sh
make check              # formatting, configuration, compilation and unit tests
make db-up              # disposable PostgreSQL for integration tests
make test-integration   # PostgreSQL store and queue tests
make db-down            # remove the disposable database
make viewer-check       # pinned Mirador, Clover and Universal Viewer check; gateway must be running
make build              # fat jar and container image
```

`make viewer-open` serves the review page. Its `test` and `production` gateway choices can be set with
`IIIF_VIEWER_TEST_URL` and `IIIF_VIEWER_PRODUCTION_URL`. Set `IIIF_VIEWER_DOCUMENTS` to a JSON array of
`{"library":"…","pid":"…","label":"…"}` objects to replace the predefined documents; any library
and PID can also be entered directly in the page.

`make db-up` creates an empty database. Start `web` or `worker` with `IIIF_DB_URL` once before using
`make db-size`, because the gateway creates the cache tables during its database-backed startup.

## Limits and decisions

Kramerius licence identifiers have no reliable universal `rights` URI mapping. For Kramerius 7.2+, a short
access condition is emitted in `requiredStatement`; the full anonymous-reader notice remains in the Licence
metadata row. Kramerius 4 is unsupported. Kramerius 5 image capability is detected per library because
deployments vary.

[docs/DECISIONS.md](docs/DECISIONS.md) records the durable reasons behind these choices. The code, tests,
configuration schema and this README define current behaviour.
