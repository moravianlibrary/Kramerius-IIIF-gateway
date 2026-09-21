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
