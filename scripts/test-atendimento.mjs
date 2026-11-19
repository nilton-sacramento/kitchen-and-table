import test from "node:test";
import assert from "node:assert/strict";
import {
  readAttendanceRoute,
  createOpeningIntent,
} from "../src/main/resources/static/assets/atendimento.js";
test("rota restaura comandas e identificador sem conter credenciais", () => {
  assert.deepEqual(
    readAttendanceRoute(
      "#atendimento=comandas&comanda=12345678-1234-1234-1234-123456789abc",
    ),
    { page: "comandas", id: "12345678-1234-1234-1234-123456789abc" },
  );
});
test("rota desconhecida volta a mesas", () =>
  assert.deepEqual(readAttendanceRoute("#atendimento=caixa&comanda=foo"), {
    page: "mesas",
    id: null,
  }));
test("identificadores manipulados não são consultados", () =>
  assert.equal(
    readAttendanceRoute("#comanda=------------------------------------").id,
    null,
  ));
test("retentativa mantém chave e conteúdo imutáveis", () => {
  const body = { modalidade: "BALCAO", cliente: " Cliente " };
  const intent = createOpeningIntent(body);
  body.cliente = "Outra pessoa";
  assert.equal(intent.body.cliente, "Cliente");
  assert.throws(() => {
    intent.body.cliente = "Outro";
  });
  assert.throws(() => { intent.key = "outra-chave"; });
});
test("novas aberturas recebem chaves independentes", () =>
  assert.notEqual(
    createOpeningIntent({ modalidade: "BALCAO" }).key,
    createOpeningIntent({ modalidade: "BALCAO" }).key,
  ));
test("intenção descarta campos de executor enviados pela tela", () =>
  assert.deepEqual(
    createOpeningIntent(
      { modalidade: "MESA", mesaId: "mesa", executor: "forjado" },
      "key",
    ).body,
    { modalidade: "MESA", mesaId: "mesa", cliente: null },
  ));
