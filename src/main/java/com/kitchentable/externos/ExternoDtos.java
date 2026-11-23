package com.kitchentable.externos;

import com.kitchentable.pagamentos.PagamentoDtos;
import com.kitchentable.pedidos.PedidoDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ExternoDtos {
  private ExternoDtos() {}

  public enum Tipo {
    DELIVERY,
    VENDA_RAPIDA
  }

  public enum Origem {
    TELEFONE,
    WHATSAPP,
    PLATAFORMA,
    OUTRO,
    BALCAO
  }

  public enum Estado {
    REGISTRADO,
    SAIU_PARA_ENTREGA,
    ENTREGUE,
    RETIRADO,
    CANCELADO
  }

  public record Pagamento(
      @NotNull UUID turnoId,
      @NotNull PagamentoDtos.Meio meio,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          @Min(1)
          @Max(999999999999999L)
          long valorCentavos,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          @Min(0)
          Long recebidoCentavos,
      boolean confirmadoExternamente,
      boolean dinheiroRecebidoNoCaixa) {}

  public record Criacao(
      @NotNull Tipo tipo,
      @NotNull Origem origem,
      @Size(max = 120) String referencia,
      @NotNull @Valid PedidoDtos.Confirmacao pedido,
      @Valid Pagamento pagamento) {}

  public record Criado(UUID comandaId, UUID pedidoId, UUID pagamentoId) {}

  public record Transicao(
      @NotNull Estado estado,
      @NotNull @PositiveOrZero Long versao,
      @NotBlank @Size(max = 500) String motivo) {}

  public record Atendimento(
      UUID comandaId,
      String codigo,
      String tipo,
      String origem,
      String referencia,
      String estado,
      long versao,
      UUID criadoPor,
      Instant criadoEm,
      String estadoComanda,
      String preparo,
      List<PedidoDtos.Pedido> pedidos) {}

  public record Evento(String tipo, String executor, Instant ocorridoEm, String motivo) {}
}
