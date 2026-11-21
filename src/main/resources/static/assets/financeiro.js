// Contratos puros: dinheiro exato, contexto e retentativa consultada no servidor.
export const MAX_CENTAVOS = 999999999999999n;
export function parseMoney(value, { zero = true } = {}) {
  const s = String(value).trim();
  if (!/^\d{1,13}(?:[.,]\d{1,2})?$/.test(s))
    throw Error(
      "Informe um valor com até duas casas decimais, sem separador de milhares.",
    );
  const [whole, decimal = ""] = s.replace(",", ".").split(".");
  const n = BigInt(whole) * 100n + BigInt(decimal.padEnd(2, "0"));
  if (n < (zero ? 0n : 1n) || n > MAX_CENTAVOS)
    throw Error("Valor fora do limite permitido.");
  return n.toString();
}
export function cents(value) {
  if (typeof value === "number" && !Number.isSafeInteger(value))
    throw Error("Valor monetário recebido sem precisão.");
  const text = String(value);
  if (!/^-?\d+$/.test(text)) throw Error("Valor monetário inválido.");
  return BigInt(text);
}
export function money(value) {
  const n = cents(value),
    abs = n < 0n ? -n : n;
  return (
    (n < 0n ? "-" : "") +
    "R$ " +
    (abs / 100n).toLocaleString("pt-BR") +
    "," +
    (abs % 100n).toString().padStart(2, "0")
  );
}
export function moneyInput(value) {
  const n = cents(value);
  return (n / 100n).toString() + "," + (n % 100n).toString().padStart(2, "0");
}
export function parsePercent(value) {
  const n = BigInt(parseMoney(value));
  if (n > 10000n) throw Error("Percentual deve estar entre 0 e 100%.");
  return Number(n);
}
export function percent(value, bps) {
  const b = BigInt(bps);
  if (b < 0n || b > 10000n) throw Error("Percentual inválido.");
  return ((cents(value) * b + 5000n) / 10000n).toString();
}
function freeze(value) {
  if (value && typeof value === "object") {
    Object.values(value).forEach(freeze);
    Object.freeze(value);
  }
  return value;
}
const scopeKey = (s) =>
  JSON.stringify([s.unidade, s.ator, s.turno || null, s.comanda || null]);
export function financialIntent(scope, body, key = crypto.randomUUID()) {
  return Object.freeze({
    key,
    scope: scopeKey(scope),
    body: freeze(structuredClone(body)),
  });
}
export async function executeFinancialIntent({
  request,
  path,
  intent,
  currentScope,
}) {
  if (scopeKey(currentScope) !== intent.scope)
    throw Error(
      "O usuário, estabelecimento ou atendimento mudou. Revise os dados antes de continuar.",
    );
  try {
    const result = await request("caixa/operacoes/" + intent.key);
    return result.resultado;
  } catch (e) {
    if (e.status !== 404) throw e;
  }
  return request(path, "POST", intent.body, { "Idempotency-Key": intent.key });
}
