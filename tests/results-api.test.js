const { test, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const handler = require('../api/loterias.js');

const originalFetch = global.fetch;
afterEach(() => { global.fetch = originalFetch; });
function responseRecorder() {
  return {
    headers: {}, statusCode: null, body: null, ended: false,
    setHeader(key, value) { this.headers[key] = value; return this; },
    status(value) { this.statusCode = value; return this; },
    json(value) { this.body = value; return this; },
    end() { this.ended = true; return this; },
  };
}
function lotteryPayload(game) {
  const config = { mega: [6, 60], lotofacil: [15, 25], quina: [5, 80], lotomania: [20, 99] }[game];
  return { ultimoConcurso: true, numero: 1000, dataApuracao: '04/11/2026', listaDezenas: Array.from({ length: config[0] }, (_, i) => String(i + 1).padStart(2, '0')) };
}

test('API returns validated latest results for all four lotteries with a short cache', async () => {
  const requested = [];
  global.fetch = async url => {
    requested.push(url);
    const slug = new URL(url).pathname.split('/').pop();
    const game = { megasena: 'mega', lotofacil: 'lotofacil', quina: 'quina', lotomania: 'lotomania' }[slug];
    return { ok: true, json: async () => lotteryPayload(game) };
  };
  const res = responseRecorder();
  await handler({ method: 'GET', headers: { origin: 'https://careca-roots.vercel.app' } }, res);
  assert.equal(res.statusCode, 200);
  assert.deepEqual(Object.keys(res.body.resultados).sort(), ['lotofacil', 'lotomania', 'mega', 'quina']);
  assert.equal(requested.length, 4);
  assert.match(res.headers['Cache-Control'], /s-maxage=300/);
  assert.equal(res.headers['Access-Control-Allow-Origin'], 'https://careca-roots.vercel.app');
});

test('API does not present invalid lottery data as official', async () => {
  global.fetch = async () => ({ ok: true, json: async () => ({ ...lotteryPayload('mega'), ultimoConcurso: false }) });
  const res = responseRecorder();
  await handler({ method: 'GET', headers: {} }, res);
  assert.equal(res.statusCode, 503);
  assert.deepEqual(res.body.resultados, {});
  assert.equal(res.headers['Cache-Control'], 'no-store');
});

test('API only accepts GET and supports CORS preflight', async () => {
  const bad = responseRecorder();
  await handler({ method: 'POST', headers: {} }, bad);
  assert.equal(bad.statusCode, 405);
  const preflight = responseRecorder();
  await handler({ method: 'OPTIONS', headers: { origin: 'https://careca-roots.vercel.app' } }, preflight);
  assert.equal(preflight.statusCode, 204);
  assert.equal(preflight.ended, true);
});
