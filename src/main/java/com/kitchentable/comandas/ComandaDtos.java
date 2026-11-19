package com.kitchentable.comandas;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class ComandaDtos {
  private ComandaDtos() {}

  public enum Modalidade {
    MESA,
    BALCAO
  }

  public enum Estado {
    ABERTA,
    ENCERRADA,
    CANCELADA
  }

  public record Abertura(
      @NotNull Modalidade modalidade, UUID mesaId, @Size(max = 120) String cliente) {}

  public record Transicao(
      @NotNull @PositiveOrZero Long versao, @Size(max = 500) String justificativa) {}

  public record Comanda(
      UUID id,
      String codigo,
      String modalidade,
      UUID mesaId,
      String mesa,
      String cliente,
      String estado,
      UUID abertoPor,
      String nomeAbertura,
      Instant abertoEm,
      UUID finalizadoPor,
      Instant finalizadoEm,
      String justificativa,
      boolean teveConsumo,
      boolean possuiPendencias,
      long versao) {}

  public record Evento(
      UUID id,
      String tipo,
      UUID executor,
      String nomeExecutor,
      Instant ocorridoEm,
      String justificativa) {}
}
