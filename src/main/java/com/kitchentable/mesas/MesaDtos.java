package com.kitchentable.mesas;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class MesaDtos {
  private MesaDtos() {}

  public record Criacao(@NotBlank @Size(max = 40) String identificacao) {}

  public record Edicao(
      @NotBlank @Size(max = 40) String identificacao, @NotNull @PositiveOrZero Long versao) {}

  public record Situacao(@NotNull Boolean ativo, @NotNull @PositiveOrZero Long versao) {}

  public record Mesa(
      UUID id,
      String identificacao,
      boolean ativo,
      String situacao,
      long versao,
      UUID comandaAtiva,
      String codigoComanda) {}

  public record Uso(
      UUID id, String codigo, String estado, Instant abertoEm, Instant finalizadoEm) {}
}
