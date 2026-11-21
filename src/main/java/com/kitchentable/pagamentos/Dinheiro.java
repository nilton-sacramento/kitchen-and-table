package com.kitchentable.pagamentos;

import static com.kitchentable.cardapio.CardapioRepository.erro;

import java.math.*;

public final class Dinheiro {
  private Dinheiro() {}

  public static final long MAX = 999999999999999L;

  public static long valor(long value, boolean zero) {
    if (value < (zero ? 0 : 1) || value > MAX) throw erro(400, "Valor em centavos fora do limite");
    return value;
  }

  public static long exato(Object value) {
    try {
      return value == null ? 0 : new BigDecimal(value.toString()).longValueExact();
    } catch (ArithmeticException e) {
      throw erro(409, "Total monetario acima do limite suportado");
    }
  }

  public static long percentual(long base, int bps) {
    if (base < 0 || bps < 0 || bps > 10000) throw erro(400, "Percentual invalido");
    return BigInteger.valueOf(base)
        .multiply(BigInteger.valueOf(bps))
        .add(BigInteger.valueOf(5000))
        .divide(BigInteger.valueOf(10000))
        .longValueExact();
  }

  public static String motivo(String value) {
    if (value == null || value.strip().isEmpty() || value.strip().length() > 500)
      throw erro(
          400,
          "Informe uma justificativa de ate 500 caracteres, sem dados bancarios ou credenciais");
    return value.strip();
  }
}
