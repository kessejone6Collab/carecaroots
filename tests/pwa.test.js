const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');

const html = fs.readFileSync('index.html', 'utf8');
const manifest = JSON.parse(fs.readFileSync('manifest.webmanifest', 'utf8'));
const worker = fs.readFileSync('service-worker.js', 'utf8');

test('page links the PWA manifest and registers a scoped service worker', () => {
  assert.match(html, /<link rel="manifest" href="\.\/manifest\.webmanifest">/);
  assert.match(html, /navigator\.serviceWorker\.register\("\.\/service-worker\.js",\{scope:"\.\/"\}\)/);
  assert.match(html, /<meta name="theme-color" content="#07101f">/);
});

test('manifest contains Android install fields and app display mode', () => {
  assert.equal(manifest.name, 'LotoFechamento Pro — Careca Roots');
  assert.equal(manifest.short_name, 'LotoFechamento');
  assert.equal(manifest.start_url, './?source=pwa');
  assert.equal(manifest.scope, './');
  assert.equal(manifest.display, 'standalone');
  assert.equal(manifest.background_color, '#07101f');
  assert.equal(manifest.theme_color, '#07101f');
  assert.equal(manifest.icons.length >= 2, true);
});

test('manifest icons exist and declare the expected square dimensions', () => {
  for (const expectedSize of [192, 512]) {
    const icon = manifest.icons.find(item => item.sizes === expectedSize + 'x' + expectedSize);
    assert.ok(icon, 'manifest declares ' + expectedSize + 'px icon');
    assert.match(icon.src, /^\.\//);
    const path = icon.src.replace(/^\.\//, '');
    const svg = fs.readFileSync(path, 'utf8');
    assert.match(svg, new RegExp('<svg[^>]*width="' + expectedSize + '"[^>]*height="' + expectedSize + '"'));
  }
});

test('service worker caches the app shell and handles offline navigation', () => {
  assert.match(worker, /addEventListener\("install"/);
  assert.match(worker, /addEventListener\("activate"/);
  assert.match(worker, /addEventListener\("fetch"/);
  assert.match(worker, /request\.mode === "navigate"/);
  assert.match(worker, /caches\.match\("\.\/index\.html"\)/);
});
