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
    const icon = manifest.icons.find(item => item.sizes === expectedSize + 'x' + expectedSize && item.type === 'image/png');
    assert.ok(icon, 'manifest declares PNG ' + expectedSize + 'px icon');
    const path = icon.src.replace(/^\.\//, '');
    const png = fs.readFileSync(path);
    assert.equal(png.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
    assert.equal(png.readUInt32BE(16), expectedSize);
    assert.equal(png.readUInt32BE(20), expectedSize);
  }
});

test('service worker caches the app shell and handles offline navigation', () => {
  assert.match(worker, /addEventListener\("install"/);
  assert.match(worker, /loto-careca-roots-pwa-v6/);
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

  }
});

test('number pads use large neon touch targets, including Lotomania 00-99', () => {
  assert.match(html, /min-height:52px/);
  assert.match(html, /touch-action:manipulation/);
  assert.match(html, /id="generateBtn" onclick="generateAsync\(\)"/);
});

test('Android install guidance is visible immediately and native prompt is triggered by button', () => {
  assert.match(html, /id="installCard"/);
  assert.match(html, /id="installButton"/);
  assert.match(html, /beforeinstallprompt/);
  assert.match(html, /prompt\.userChoice/);
  assert.match(html, /Adicionar à tela inicial/);
});
test('lightweight app shell does not require downloading banners before service-worker installation', () => {
  assert.match(worker, /loto-careca-roots-pwa-v6/);
  assert.match(worker, /icon-192\.png/);
  assert.match(worker, /icon-512\.png/);
  assert.doesNotMatch(worker.split('const APP_SHELL = ')[1].split(';')[0], /banner-.*\.webp/);
  assert.match(worker, /cache\.put\(request, copy\)/);
});
