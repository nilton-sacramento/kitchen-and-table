package com.kitchentable.pedidos;

import com.kitchentable.cardapio.CardapioDtos;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class PedidoDtos {
  private PedidoDtos() {}

  public enum EstadoItem {
    AGUARDANDO,
    EM_PREPARO,
    PRONTO,
    ENTREGUE,
    CANCELADO
  }

  public enum TipoSolicitacao {
    ALTERACAO,
    CANCELAMENTO
  }

  public record Oferta(CardapioDtos.Produto produto, String assinaturaOferta) {}

  public record LinhaEntrada(
      @NotNull UUID produtoId,
      @Min(1) @Max(1000) int quantidade,
      @Min(0) @Max(9999999999L) long precoCentavos,
      @NotBlank String assinaturaOferta,
      @Size(max = 500) String observacao) {}

  public record Confirmacao(
      boolean tudoJunto,
      @NotNull @Size(min = 1, max = 30)
          List<@NotNull @jakarta.validation.Valid LinhaEntrada> linhas) {}

  public record ComponenteSnapshot(
      UUID produtoId, String nome, CardapioDtos.Setor setor, int quantidade) {}

  public record Linha(
      UUID id,
      UUID produtoId,
      String nome,
      String tipo,
      int quantidade,
      long precoUnitarioCentavos,
      long totalCentavos,
      String observacao,
      List<ComponenteSnapshot> composicao) {}

  public record Item(
      UUID id,
      UUID linhaId,
      UUID produtoId,
      String nome,
      String setor,
      int quantidade,
      String observacao,
      String estado,
      long versao) {}

  public record Pedido(
      UUID id,
      UUID comandaId,
      String codigo,
      String comanda,
      String mesa,
      UUID abertoPor,
      String nomeAbertura,
      Instant abertoEm,
      boolean tudoJunto,
      String estado,
      long totalCentavos,
      long versao,
      UUID substituiId,
      List<Linha> linhas,
      List<Item> itens) {}

  public record Atualizacao(@NotNull EstadoItem estado, @NotNull @PositiveOrZero Long versao) {}

  public record Referencia(@NotNull UUID id, @PositiveOrZero long versao) {}

  public record Entrega(
      @NotNull @Size(min = 1, max = 3000)
          List<@NotNull @jakarta.validation.Valid Referencia> itens) {}

  public record SolicitacaoEntrada(
      @NotNull TipoSolicitacao tipo,
      @NotBlank @Size(max = 500) String motivo,
      @jakarta.validation.Valid Confirmacao proposta,
      @NotNull @PositiveOrZero Long versaoPedido) {}

  public record DecisaoEntrada(
      boolean aceitar,
      @Size(max = 500) String motivo,
      @NotNull @PositiveOrZero Long versao,
      @NotNull @PositiveOrZero Long versaoPedido) {}

  public record ItemProposto(
      UUID produtoId, String nome, String setor, int quantidade, String observacao) {}

  public record DecisaoFila(
      UUID id,
      UUID pedidoId,
      String pedido,
      String comanda,
      String mesa,
      String tipo,
      String motivo,
      String estadoDecisao,
      long versaoDecisao,
      long versaoPedido,
      List<ItemProposto> proposta) {}

  public record Decisao(String setor, String estado, UUID executor, String motivo, long versao) {}

  public record Solicitacao(
      UUID id,
      UUID pedidoId,
      String tipo,
      String motivo,
      String estado,
      String resultadoMotivo,
      UUID solicitadoPor,
      Instant criadoEm,
      UUID novoPedidoId,
      long versaoPedido,
      List<Decisao> decisoes) {}

  public record ResultadoDecisao(UUID id, String estado, String motivo, UUID novoPedidoId) {}

  public record Pendencia(
      UUID id,
      String tipo,
      String motivo,
      String estadoDecisao,
      long versaoDecisao,
      long versaoPedido) {}

  public record FilaItem(
      UUID id,
      UUID pedidoId,
      String pedido,
      String comanda,
      String mesa,
      String nome,
      int quantidade,
      String observacao,
      String setor,
      String estado,
      long versao,
      boolean tudoJunto,
      List<Pendencia> solicitacoes) {}

  public record Evento(String tipo, String nomeExecutor, Instant ocorridoEm) {}
}
