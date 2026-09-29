// The human half of the viewer check: the same viewers the assertions drive, served for a person.
//
// It exists so that `make viewer-open` and `make viewer-check` cannot drift. They used to: index.html
// took Mirador 3.3.0 from unpkg and Clover's WEB component from jsdelivr, while viewer-check pinned
// Mirador 3.4.3 and bundled Clover's REACT component. A person reviewing a manifest was looking at
// software the assertions had never run against, and a screenshot in out/ came from a third build again.
//
// TWO DOCUMENT ROOTS, exactly as check.mjs has: /work is this directory (index.html, and out/ for the
// screenshots), /viewers is the read-only set baked into the image. That is the whole server.
//
// Unlike check.mjs this one talks to a LIVE gateway rather than to manifests fetched ahead of time —
// browsing is the point, so the document list is whatever the gateway can serve.
import { createServer } from 'node:http';
import { existsSync, readFileSync, statSync } from 'node:fs';

const port = Number(process.argv[2] ?? 18090);

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.json': 'application/json',
  '.js': 'text/javascript; charset=utf-8',
  '.jsx': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.ico': 'image/x-icon',
  '.png': 'image/png',
  '.txt': 'text/plain; charset=utf-8',
};

const ROOTS = ['/work', '/viewers'];

const defaults = [
  { library: 'mzk', pid: 'uuid:7456c2c0-6aa4-11e2-8aec-005056827e52', label: 'monograph, 58 pages — real dimensions' },
  { library: 'mzk', pid: 'uuid:551502c0-7a40-11ee-bf21-005056827e52', label: 'monograph unit' },
  { library: 'mzk', pid: 'uuid:fbf5efba-ff5f-4921-aee6-2d7f4141561b', label: 'public sound recording — 4 canvases, 2 tracks' },
  { library: 'mzk', pid: 'uuid:0acfebfb-3a57-4d62-8b8f-9e58da01c96c', label: 'public map, 1 page' },
  { library: 'mzk', pid: 'uuid:6c17b0cf-8c49-48d3-a9e9-b64f154c3f23', label: 'collection — 35 members' },
  { library: 'mzk', pid: 'uuid:022321ca-b665-46aa-8ee1-88adb67f73ea', label: 'article' },
  { library: 'mzk', pid: 'uuid:58b9d800-5e81-11ed-85d9-5ef3fc9bb22f', label: 'periodical' },
  { library: 'mzk', pid: 'uuid:1724e800-be4c-11ed-ac82-5ef3fc9bb22f', label: 'periodical volume' },
  { library: 'mzk', pid: 'uuid:b1eb7130-5d79-11e3-ae59-005056827e52', label: 'periodical item' },
  { library: 'mzk', pid: 'uuid:fea96b90-e70b-11e4-9c07-001018b5eb5c', label: 'supplement' },
];

const documents = (() => {
  if (!process.env.IIIF_VIEWER_DOCUMENTS) return defaults;
  try {
    const value = JSON.parse(process.env.IIIF_VIEWER_DOCUMENTS);
    if (!Array.isArray(value) || !value.every(({ library, pid, label }) => library && pid && label)) {
      throw new Error('each document needs library, pid and label');
    }
    return value;
  } catch (error) {
    console.error(`invalid IIIF_VIEWER_DOCUMENTS: ${error.message}; using defaults`);
    return defaults;
  }
})();

const reviewConfig = {
  documents,
  testUrl: process.env.IIIF_VIEWER_TEST_URL ?? 'http://localhost:18080',
  productionUrl: process.env.IIIF_VIEWER_PRODUCTION_URL ?? 'https://iiif.digitalniknihovna.cz',
};

const resolve = (path) => {
  if (path.includes('..')) return null;
  const candidates = path.endsWith('/') ? [`${path}index.html`] : [path];
  for (const candidate of candidates) {
    for (const root of ROOTS) {
      const file = `${root}${candidate}`;
      if (existsSync(file) && statSync(file).isFile()) return file;
    }
  }
  return null;
};

createServer((request, response) => {
  const path = decodeURIComponent(new URL(request.url, 'http://127.0.0.1').pathname);
  if (path === '/review-config.json') {
    response.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    response.end(JSON.stringify(reviewConfig));
    return;
  }
  const file = resolve(path);
  if (!file) {
    response.writeHead(404, { 'content-type': 'text/plain' }).end(`no ${path}`);
    return;
  }
  response.writeHead(200, {
    // Typed from the RESOLVED file, not the request path: "/" resolves to index.html but has no
    // extension of its own, and a browser handed text/html as octet-stream downloads it instead of
    // rendering it.
    'content-type': TYPES[file.slice(file.lastIndexOf('.'))] ?? 'application/octet-stream',
    // No caching: the point of this page is to look at a manifest again after changing something.
    'cache-control': 'no-store',
  });
  response.end(readFileSync(file));
}).listen(port, '0.0.0.0', () => {
  console.log(readFileSync('/viewers/VERSIONS', 'utf8').trim());
  console.log(`\nviewer review page on http://localhost:${port}/`);
});

// As PID 1 in a container, node gets no default signal handlers, so without these `podman stop` and a
// Kubernetes rollout wait out their grace period and then kill it.
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => process.exit(0));
