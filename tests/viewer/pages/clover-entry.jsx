// Clover's React component, bundled into the image by esbuild at build time.
//
// The REACT component, not the web component. index.html used to load the web component from a CDN
// while the check bundled this one — two different code paths through the same library, so a person
// and the assertions were looking at different Clover. This is now the only one.
import React from 'react';
import { createRoot } from 'react-dom/client';
import Viewer from '@samvera/clover-iiif/viewer';

const params = new URLSearchParams(location.search);

// Clover needs telling too. Without it the language selector on the review page appears not to work,
// which is a harness bug reading as a manifest one.
createRoot(document.getElementById('viewer')).render(
  React.createElement(Viewer, {
    iiifContent: params.get('manifest'),
    options: { language: params.get('lang') ?? 'en' },
  }),
);
