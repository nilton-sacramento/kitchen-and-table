package com.kitchentable.pagamentos;

import com.kitchentable.pedidos.PedidoDtos;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class PagamentoDtos {
  private PagamentoDtos() {}

  public enum Meio {
    DINHEIRO,
    PIX,
    DEBITO,
    CREDITO
  }

  public record Lancamento(
      UUID id,
      UUID turnoId,
      UUID comandaId,
      String tipo,
      String natureza,
      String meio,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long valorCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          Long recebidoCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long trocoCentavos,
      UUID originalId,
      UUID executor,
      String nomeExecutor,
      String motivo,
      UUID autorizacaoId,
      Instant ocorridoEm,
      boolean confirmacaoExternaPendente) {}

  public record Conta(
      UUID comandaId,
      String codigo,
      String modalidade,
      String mesa,
      String estado,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long consumoCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long descontosCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long pagoCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long saldoCentavos,
      String revisao,
      List<PedidoDtos.Pedido> pedidos,
      List<Lancamento> lancamentos,
      long solicitacoesPendentes,
      boolean possuiItensNaoEntregues) {}

  public record Receber(
      @NotNull Meio meio,
      @Min(1)
          @Max(999999999999999L)
          @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long valorCentavos,
      @Min(0)
          @Max(999999999999999L)
          @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          Long recebidoCentavos,
      boolean confirmadoExternamente,
      @NotBlank String revisaoConta) {}

  public record Desconto(
      @NotBlank String tipo,
      @Min(0)
          @Max(999999999999999L)
          @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          Long valorCentavos,
      @Min(1) @Max(10000) Integer percentualBps,
      @NotBlank @Size(max = 500) String motivo,
      @NotBlank String revisaoConta,
      UUID autorizacaoId) {}

  public record Compensar(
      @NotBlank String natureza,
      @NotBlank @Size(max = 500) String motivo,
      @NotBlank String revisaoConta,
      UUID autorizacaoId) {}

  public record Encerrar(@NotBlank String revisaoConta) {}

  public record ConfirmarExterno(
      @NotBlank @Size(max = 500) String motivo, boolean confirmadoExternamente) {}

  public record Quitacao(
      UUID id,
      UUID comandaId,
      UUID turnoId,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long consumoCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long descontoCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long pagoCentavos,
      UUID executor,
      Instant criadoEm) {}
}
