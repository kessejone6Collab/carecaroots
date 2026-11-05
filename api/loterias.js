const LOTTERIES = {
  mega: { slug: "megasena", expected: 6 },
  lotofacil: { slug: "lotofacil", expected: 15 },
  quina: { slug: "quina", expected: 5 },
  lotomania: { slug: "lotomania", expected: 20 },
};
const ALLOWED_ORIGINS = new Set([
  "https://careca-roots.vercel.app",
  "https://kessejone6collab.github.io",
]);
function validPayload(game, data) {
  return data && data.ultimoConcurso === true && Number.isInteger(Number(data.numero)) &&
    Number(data.numero) > 0 && typeof data.dataApuracao === "string" &&
    Array.isArray(data.listaDezenas) && data.listaDezenas.length === LOTTERIES[game].expected &&
    data.listaDezenas.every(value => /^\d{2}$/.test(String(value)));
}
async function readLatest(game, config) {
  const url = `https://servicebus2.caixa.gov.br/portaldeloterias/api/${config.slug}`;
  const response = await fetch(url, {
    headers: {
      Accept: "application/json, text/plain, */*",
      Origin: "https://loterias.caixa.gov.br",
      Referer: "https://loterias.caixa.gov.br/",
    },
    signal: AbortSignal.timeout(8000),
  });
  if (!response.ok) throw new Error(`CAIXA HTTP ${response.status}`);
  const data = await response.json();
  if (!validPayload(game, data)) throw new Error("Resposta de resultado inválida");
  return {
    numero: Number(data.numero),
    dataApuracao: data.dataApuracao,
    listaDezenas: data.listaDezenas.map(String),
    ultimoConcurso: true,
  };
}
module.exports = async function handler(req, res) {
  const origin = req.headers?.origin;
  if (ALLOWED_ORIGINS.has(origin)) {
    res.setHeader("Access-Control-Allow-Origin", origin);
    res.setHeader("Vary", "Origin");
  }
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Accept, Content-Type");
  if (req.method === "OPTIONS") return res.status(204).end();
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET, OPTIONS");
    return res.status(405).json({ erro: "Método não permitido" });
  }
  const entries = await Promise.all(Object.entries(LOTTERIES).map(async ([game, config]) => {
    try { return [game, await readLatest(game, config), null]; }
    catch (error) { return [game, null, error.message || "Falha na consulta oficial"]; }
  }));
  const resultados = {};
  const erros = {};
  for (const [game, item, error] of entries) {
    if (item) resultados[game] = item;
    else erros[game] = error;
  }
  res.setHeader("Cache-Control", Object.keys(resultados).length
    ? "public, s-maxage=300, stale-while-revalidate=900"
    : "no-store");
  return res.status(Object.keys(resultados).length ? 200 : 503).json({
    fonte: "Loterias CAIXA",
    consultadoEm: new Date().toISOString(),
    resultados,
    erros,
  });
};
