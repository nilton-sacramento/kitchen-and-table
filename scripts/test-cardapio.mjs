import test from "node:test";
import assert from "node:assert/strict";
import {
  parseCents,
  formatCents,
} from "../src/main/resources/static/assets/cardapio.js";
test("preço decimal convertido em centavos sem arredondamento", () => {
  assert.equal(parseCents("12,34"), 1234);
  assert.equal(parseCents("0.01"), 1);
  assert.equal(parseCents("19.9"), 1990);
});
test("limites e preços inválidos rejeitados", () => {
  for (const s of ["-1", "1.234", "1e3", "NaN", "100000000", ""])
    assert.throws(() => parseCents(s));
});
test("limite monetário exato e apresentação", () => {
  assert.equal(parseCents("99999999,99"), 9999999999);
  assert.equal(formatCents(1250), "R$ 12,50");
  assert.equal(formatCents(0), "R$ 0,00");
});
