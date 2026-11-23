import test from "node:test";
import assert from "node:assert/strict";
import {
  externalIntent,
  executeExternalIntent,
  externalNames,
} from "../src/main/resources/static/assets/externo-contratos.js";
import { readAttendanceRoute } from "../src/main/resources/static/assets/atendimento.js";
import { parseMoney } from "../src/main/resources/static/assets/financeiro.js";
const scope = { unidade: "A", ator: "pessoa" };
const body = {
  tipo: "VENDA_RAPIDA",
  origem: "BALCAO",
  pedido: {
    linhas: [{ produtoId: "p", quantidade: 1, precoCentavos: "10000" }],
  },
  pagamento: {
    valorCentavos: "10000",
    recebidoCentavos: "12000",
    meio: "DINHEIRO",
  },
};
test("intenção externa preserva corpo e preço histórico contra edição local", () => {
  const original = structuredClone(body),
    i = externalIntent(scope, original, "chave");
  original.pedido.linhas[0].quantidade = 9;
  assert.equal(i.body.pedido.linhas[0].quantidade, 1);
  assert.ok(Object.isFrozen(i.body.pagamento));
});
test("resultado confirmado é consultado sem segundo POST", async () => {
  const calls = [];
  const result = await executeExternalIntent({
    intent: externalIntent(scope, body, "chave"),
    currentScope: scope,
    request: async (p, m) => {
      calls.push([p, m]);
      return { comandaId: "id" };
    },
  });
  assert.equal(result.comandaId, "id");
  assert.equal(calls.length, 1);
  assert.match(calls[0][0], /externos\/operacoes\/chave/);
});
test("somente404 confirmado permite enviar criação", async () => {
  const calls = [];
  await executeExternalIntent({
    intent: externalIntent(scope, body, "chave"),
    currentScope: scope,
    request: async (p, m, b, h) => {
      calls.push({ p, m, b, h });
      if (!m) throw { status: 404 };
      return {};
    },
  });
  assert.equal(calls[1].m, "POST");
  assert.equal(calls[1].h["Idempotency-Key"], "chave");
  assert.equal(calls[1].b.pagamento.valorCentavos, "10000");
});
test("falha de conexão na consulta não permite reenvio cego", async () => {
  let count = 0;
  await assert.rejects(
    executeExternalIntent({
      intent: externalIntent(scope, body, "chave"),
      currentScope: scope,
      request: async () => {
        count++;
        throw TypeError("sem rede");
      },
    }),
  );
  assert.equal(count, 1);
});
test("revogação403 na consulta não é tratada como inexistência", async () => {
  let count = 0;
  await assert.rejects(
    executeExternalIntent({
      intent: externalIntent(scope, body),
      currentScope: scope,
      request: async () => {
        count++;
        throw { status: 403 };
      },
    }),
  );
  assert.equal(count, 1);
});
test("troca de unidade impede qualquer requisição da intenção antiga", async () => {
  let count = 0;
  await assert.rejects(
    executeExternalIntent({
      intent: externalIntent(scope, body),
      currentScope: { ...scope, unidade: "B" },
      request: async () => {
        count++;
      },
    }),
  );
  assert.equal(count, 0);
});
test("troca de operador impede qualquer requisição da intenção antiga", async () => {
  await assert.rejects(
    executeExternalIntent({
      intent: externalIntent(scope, body),
      currentScope: { ...scope, ator: "outro" },
      request: async () => {
        throw Error("Não deve consultar");
      },
    }),
    /operador/,
  );
});
test("tela destruída durante consulta não confirma o POST", async () => {
  let live = true,
    count = 0;
  await assert.rejects(
    executeExternalIntent({
      intent: externalIntent(scope, body),
      currentScope: scope,
      isCurrent: () => live,
      request: async () => {
        count++;
        live = false;
        throw { status: 404 };
      },
    }),
  );
  assert.equal(count, 1);
});
test("F5 mantém canal delivery e identificador válido", () => {
  const id = "12345678-1234-1234-1234-123456789abc";
  assert.deepEqual(readAttendanceRoute("#atendimento=delivery&comanda=" + id), {
    page: "delivery",
    id,
  });
});
test("F5 mantém venda rápida mas rejeita identificador manipulado", () => {
  assert.deepEqual(
    readAttendanceRoute("#atendimento=venda-rapida&comanda=<script>"),
    { page: "venda-rapida", id: null },
  );
});
test("pagamento imediato preserva centavos exatos e troco", () => {
  assert.equal(
    BigInt(parseMoney("120,00")) - BigInt(parseMoney("100,00")),
    2000n,
  );
  assert.equal(parseMoney("0,01"), "1");
  assert.throws(() => parseMoney("1,001"));
});
test("logística explícita não declara pagamento", () => {
  assert.equal(externalNames.ENTREGUE, "Entregue");
  assert.equal(Object.hasOwn(externalNames, "PAGO"), false);
});

test('resposta perdida na criação é recuperada sem nova cobrança', async () => {
  const intent = externalIntent(scope, body, 'chave-persistida');
  let result = null, posts = 0;
  const request = async (path, method) => {
    if (!method) { if (result) return result; throw { status: 404 }; }
    posts++;
    result = { comandaId: 'confirmada', pedidoId: 'pedido-unico' };
    throw TypeError('Resposta perdida depois do servidor confirmar');
  };
  await assert.rejects(executeExternalIntent({ request, intent, currentScope: scope }));
  const recovered = await executeExternalIntent({ request, intent, currentScope: scope });
  assert.equal(recovered.comandaId, 'confirmada');
  assert.equal(posts, 1);
});
