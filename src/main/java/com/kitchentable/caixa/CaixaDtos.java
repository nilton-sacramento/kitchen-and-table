package com.kitchentable.caixa;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class CaixaDtos {
  private CaixaDtos() {}

  public record Politica(
      boolean configurada,
      Integer limiteOperadores,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          Long descontoLimiteCentavos,
      Integer descontoLimiteBps,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          Long sangriaLimiteCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          Long divergenciaLimiteCentavos,
      long versao) {}

  public record Configuracao(
      @Positive Integer limiteOperadores,
      @NotNull
          @Min(0)
          @Max(999999999999999L)
          @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          Long descontoLimiteCentavos,
      @NotNull @Min(0) @Max(10000) Integer descontoLimiteBps,
      @NotNull
          @Min(0)
          @Max(999999999999999L)
          @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          Long sangriaLimiteCentavos,
      @NotNull
          @Min(0)
          @Max(999999999999999L)
          @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          Long divergenciaLimiteCentavos,
      @NotNull @PositiveOrZero Long versao) {}

  public record Operador(UUID vinculoId, String nome, boolean ativo) {}

  public record Turno(
      UUID id,
      String estado,
      UUID responsavelId,
      String responsavelNome,
      UUID abertoPor,
      String nomeAbertura,
      Instant abertoEm,
      Instant fechadoEm,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long valorInicialCentavos,
      long versao,
      int ciclo,
      List<Operador> operadores) {}

  public record Abertura(
      @NotNull UUID responsavelId,
      @Min(0)
          @Max(999999999999999L)
          @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long valorInicialCentavos) {}

  public record Equipe(
      @NotNull @Size(min = 1, max = 1000) List<@NotNull UUID> operadores,
      @NotNull UUID responsavelId,
      @NotNull @PositiveOrZero Long versao,
      @NotBlank @Size(max = 500) String motivo) {}

  public record Movimento(
      @NotBlank String tipo,
      @Min(1)
          @Max(999999999999999L)
          @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long valorCentavos,
      @NotBlank @Size(max = 500) String motivo,
      @NotNull @PositiveOrZero Long versaoTurno,
      UUID autorizacaoId) {}

  public record MeioResumo(
      String meio,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long recebidoCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long estornadoCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long liquidoCentavos,
      long transacoes) {}

  public record Resumo(
      Turno turno,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long dinheiroRecebidoCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long pagamentosDinheiroCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long trocoCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long sangriasCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long reforcosCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long estornosDinheiroCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long dinheiroEsperadoCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long descontosCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long vendasLiquidadasCentavos,
      long cancelamentosOperacionais,
      List<MeioResumo> meios,
      long pendenciasExternas,
      List<com.kitchentable.pagamentos.PagamentoDtos.Lancamento> lancamentos) {}

  public record Conferir(
      @Min(0)
          @Max(999999999999999L)
          @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long informadoCentavos,
      @Size(max = 500) String motivo,
      @NotNull @PositiveOrZero Long versaoTurno) {}

  public record Conferencia(
      UUID id,
      UUID turnoId,
      long versaoTurno,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long esperadoCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long informadoCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          long diferencaCentavos,
      String motivo,
      UUID executor,
      Instant criadoEm) {}

  public record Fechar(
      @NotNull UUID conferenciaId, @NotNull @PositiveOrZero Long versaoTurno, UUID autorizacaoId) {}

  public record Reabrir(
      @NotBlank @Size(max = 500) String motivo, @NotNull @PositiveOrZero Long versao) {}

  public record Fechamento(UUID id, UUID turnoId, int ciclo, Instant criadoEm, JsonNode dados) {}

  public record SolicitarAutorizacao(
      @NotBlank String tipo,
      @NotNull UUID turnoId,
      @NotNull UUID entidadeId,
      @NotNull JsonNode operacao,
      @NotBlank @Size(max = 500) String motivo) {}

  public record Autorizar(
      @NotNull @PositiveOrZero Long versao,
      @NotBlank @Size(max = 500) String acao,
      @NotBlank @Size(max = 500) String motivo) {}

  public record Autorizacao(
      UUID id,
      UUID turnoId,
      UUID solicitante,
      String nomeSolicitante,
      String tipo,
      UUID entidadeId,
      JsonNode operacao,
      String motivo,
      String estado,
      Instant expiraEm,
      UUID autorizador,
      String nomeAutorizador,
      String motivoDecisao,
      long versao) {}
}
