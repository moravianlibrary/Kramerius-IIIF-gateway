# Vendored IIIF schema

`iiif_3_0.json` is the official IIIF Presentation 3.0 JSON Schema, taken from
[IIIF/presentation-validator](https://github.com/IIIF/presentation-validator/blob/main/schema/iiif_3_0.json)
(`$id: http://iiif.io/api/presentation/3/schema.json`, draft-07).

It is vendored rather than fetched because the test suite must run offline and the gate must not depend on
GitHub being reachable. Refresh it deliberately, and re-run the fixture tests when you do — a schema change
is a change to what this gateway promises.

The IIIF project publishes no container image for its validator, which is why validation runs in-process
against this file rather than as a separate `make validate` step.
