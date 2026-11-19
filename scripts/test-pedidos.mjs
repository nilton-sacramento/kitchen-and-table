import test from "node:test";
import assert from "node:assert/strict";
import {
  orderTotal,
  createOrderIntent,
} from "../src/main/resources/static/assets/pedidos.js";
import { readAttendanceRoute } from "../src/main/resources/static/assets/atendimento.js";
test("total de pedido usa centavos exatos e cobra cada linha uma vez", () => {
  assert.equal(
    orderTotal([
      { precoCentavos: 1250, quantidade: 3 },
      { precoCentavos: 2999, quantidade: 2 },
    ]),
    9748n,
  );
  assert.equal(
    orderTotal([{ precoCentavos: 9999999999, quantidade: 1000 }]),
    9999999999000n,
  );
});
test("intencao de envio congela o conteudo da retentativa sem manter referencia ao editor", () => {
  const body = {
    tudoJunto: true,
    linhas: [{ produtoId: "teste", quantidade: 2, precoCentavos: 2500 }],
  };
  const intent = createOrderIntent(body, "chave");
  body.linhas[0].quantidade = 8;
  assert.equal(intent.key, "chave");
  assert.equal(intent.body.linhas[0].quantidade, 2);
  assert.equal(intent.body.tudoJunto, true);
});
test("F5 reconhece as filas e mantem comanda de pedidos sem aceitar identificador invalido", () => {
  assert.equal(readAttendanceRoute("#atendimento=cozinha").page, "cozinha");
  assert.equal(readAttendanceRoute("#atendimento=bar").page, "bar");
  assert.equal(
    readAttendanceRoute("#atendimento=bar&comanda=<script>").id,
    null,
  );
});
