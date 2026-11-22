import { financialIntent } from "./financeiro.js";
export const externalNames = {
  REGISTRADO: "Registrado",
  SAIU_PARA_ENTREGA: "Saiu para entrega",
  ENTREGUE: "Entregue",
  RETIRADO: "Retirado",
  CANCELADO: "Cancelado",
};
export function externalIntent(scope, body, key) {
  return financialIntent(scope, body, key);
}
export async function executeExternalIntent({
  request,
  intent,
  currentScope,
  isCurrent = () => true,
}) {
  const scope = externalIntent(currentScope, {}, intent.key).scope;
  const current = () => {
    if (!isCurrent() || scope !== intent.scope)
      throw Error(
        "O operador, unidade ou tela mudou. Consulte o histórico antes de continuar.",
      );
  };
  current();
  try {
    const result = await request(
      "externos/operacoes/" + intent.key + "?tipo=" + intent.body.tipo,
    );
    current();
    return result;
  } catch (e) {
    if (e.status !== 404) throw e;
  }
  current();
  return request("externos", "POST", intent.body, {
    "Idempotency-Key": intent.key,
  });
}
