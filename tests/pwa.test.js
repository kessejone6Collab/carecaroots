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
  assert.match(worker, /loto-careca-roots-pwa-v5/);
  assert.match(worker, /addEventListener\("activate"/);
  assert.match(worker, /addEventListener\("fetch"/);
  assert.match(worker, /request\.mode === "navigate"/);
  assert.match(worker, /caches\.match\("\.\/index\.html"\)/);
});


test('mobile hero combines the app identity with eight photographic lottery banners', () => {
  assert.equal((html.match(/class="hero-slide /g) || []).length, 8);
  assert.match(html, /LOTOFECHAMENTO <em>PRO<\/em>/);
  assert.match(html, /CARECA ROOTS/);
  assert.match(html, /setInterval\(\(\)=>\{if\(!document\.hidden\)showBanner\(bannerIndex\+1\)\},3500\)/);
  assert.match(html, /aria-label="Banner anterior"/);
  assert.match(html, /aria-label="Próximo banner"/);
  assert.match(html, /Lotomania/);
  assert.match(html, /ABRIR LOTERIAS CAIXA/);
  for (const image of ['banner-mega.webp', 'banner-lotofacil.webp', 'banner-quina.webp', 'banner-lotomania.webp', 'banner-sorteio.webp', 'banner-estimativa.webp', 'banner-casa.webp', 'banner-numeros.webp']) {
    assert.ok(fs.existsSync('assets/' + image), image + ' is packaged locally');
    assert.ok(html.includes(image), image + ' is used by the carousel');
    assert.ok(worker.includes('./assets/' + image), image + ' is cached for the installed app');
  }
});

test('number pads use large neon touch targets, including Lotomania 00-99', () => {
  assert.match(html, /min-height:52px/);
  assert.match(html, /touch-action:manipulation/);
  assert.match(html, /id="generateBtn" onclick="generateAsync\(\)"/);
});
