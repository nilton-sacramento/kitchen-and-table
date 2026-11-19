export function orderTotal(lines) {
  return lines.reduce(
    (n, l) => n + BigInt(l.precoCentavos) * BigInt(l.quantidade),
    0n,
  );
}
export function createOrderIntent(body, key = crypto.randomUUID()) {
  return Object.freeze({ key, body: structuredClone(body) });
}
