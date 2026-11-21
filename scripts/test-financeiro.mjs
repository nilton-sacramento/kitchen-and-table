import test from "node:test";
import assert from "node:assert/strict";
import {
  parseMoney,
  money,
  cents,
  percent,
  parsePercent,
  financialIntent,
  executeFinancialIntent,
  MAX_CENTAVOS,
} from "../src/main/resources/static/assets/financeiro.js";
const scope = { unidade: "a", ator: "x", turno: "t", comanda: "c" };
test("reais são convertidos sem ponto flutuante", () => {
  assert.equal(parseMoney("0,10"), "10");
  assert.equal(parseMoney("1.01"), "101");
  assert.equal(parseMoney("9999999999999,99"), MAX_CENTAVOS.toString());
});
test("valores inválidos não são arredondados silenciosamente", () => {
  for (const v of [
    "1.001",
    "-1",
    "1e3",
    "1.000,00",
    "Infinity",
    "NaN",
    "10000000000000",
  ])
    assert.throws(() => parseMoney(v));
  assert.throws(() => parseMoney("0", { zero: false }));
});
test("formatação mantém precisão acima do limite seguro numérico", () => {
  assert.equal(money("999999999999999999"), "R$ 9.999.999.999.999.999,99");
  assert.equal(money("-101"), "-R$ 1,01");
  assert.throws(() => cents(Number.MAX_SAFE_INTEGER + 1));
});
test("percentual usa meio centavo para cima exatamente", () => {
  assert.equal(percent("101", 5000), "51");
  assert.equal(percent("199", 5000), "100");
  assert.equal(parsePercent("2,75"), 275);
  assert.throws(() => parsePercent("100,01"));
});
test("intenção congela cópia do conteúdo e do escopo", () => {
  const body = { nested: { x: 1 } },
    s = { ...scope };
  const intent = financialIntent(s, body, "key");
  body.nested.x = 9;
  s.ator = "y";
  assert.equal(intent.body.nested.x, 1);
  assert.throws(() => (intent.body.nested.x = 2));
});
test("escopo alterado impede consulta ou envio", async () => {
  let calls = 0;
  await assert.rejects(
    executeFinancialIntent({
      request: async () => {
        calls++;
      },
      intent: financialIntent(scope, {}),
      currentScope: { ...scope, unidade: "b" },
    }),
  );
  assert.equal(calls, 0);
});
test("resposta perdida consulta o registro antes de qualquer reenvio", async () => {
  const intent = financialIntent(scope, { valorCentavos: "100" }, "key");
  let posts = 0,
    gets = 0,
    stored = null;
  const request = async (path, method, body, headers) => {
    if (!method) {
      gets++;
      if (stored) return { resultado: stored };
      throw Object.assign(Error(), { status: 404 });
    }
    posts++;
    assert.equal(headers["Idempotency-Key"], "key");
    stored = { id: "paid" };
    throw TypeError("resposta perdida");
  };
  await assert.rejects(
    executeFinancialIntent({
      request,
      path: "pay",
      intent,
      currentScope: scope,
    }),
  );
  assert.deepEqual(
    await executeFinancialIntent({
      request,
      path: "pay",
      intent,
      currentScope: scope,
    }),
    { id: "paid" },
  );
  assert.equal(posts, 1);
  assert.equal(gets, 2);
});
test("falha de consulta não permite reenviar dinheiro às cegas", async () => {
  let posts = 0;
  await assert.rejects(
    executeFinancialIntent({
      request: async (p, m) => {
        if (m) posts++;
        throw TypeError("offline");
      },
      path: "pay",
      intent: financialIntent(scope, {}),
      currentScope: scope,
    }),
  );
  assert.equal(posts, 0);
});
test("erro HTTP de consulta preserva bloqueio em vez de tentar POST", async () => {
  let calls = 0;
  await assert.rejects(
    executeFinancialIntent({
      request: async () => {
        calls++;
        throw Object.assign(Error(), { status: 403 });
      },
      intent: financialIntent(scope, {}),
      currentScope: scope,
    }),
  );
  assert.equal(calls, 1);
});

import { readAttendanceRoute } from "../src/main/resources/static/assets/atendimento.js";
test("rotas financeiras reais são preservadas após F5", () => {
  for (const page of ["caixa", "relatorios"])
    assert.deepEqual(readAttendanceRoute("#atendimento=" + page), {
      page,
      id: null,
    });
});
