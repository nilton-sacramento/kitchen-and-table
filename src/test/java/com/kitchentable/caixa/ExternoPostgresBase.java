package com.kitchentable.caixa;

import static com.kitchentable.externos.ExternoDtos.*;
import static com.kitchentable.pagamentos.PagamentoDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.kitchentable.externos.*;
import com.kitchentable.pedidos.PedidoDtos;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

abstract class ExternoPostgresBase extends FinanceiroPostgresBase {
  @Autowired ExternoService externo;

  @BeforeEach
  void acessosNovos() {
    for (UUID id : List.of(at, cash1, cash2))
      for (String code :
          List.of(
              "DELIVERY_CONSULTAR",
              "DELIVERY_REGISTRAR",
              "DELIVERY_STATUS_ALTERAR",
              "VENDA_RAPIDA_CONSULTAR",
              "VENDA_RAPIDA_REGISTRAR",
              "ATENDIMENTO_ABRIR",
              "PRODUTO_CONSULTAR",
              "PEDIDO_REGISTRAR",
              "PEDIDO_ENVIAR",
              "PEDIDO_CONSULTAR",
              "PEDIDO_ENTREGAR",
              "COMANDA_CANCELAR")) grant(id, code, true);
  }

  Criacao delivery() {
    return new Criacao(
        Tipo.DELIVERY, Origem.WHATSAPP, "Referencia ficticia", form(food.id()), null);
  }

  Criacao quick(CaixaDtos.Turno t, Meio meio, long valor, Long recebido) {
    return new Criacao(
        Tipo.VENDA_RAPIDA,
        Origem.BALCAO,
        null,
        form(food.id()),
        new Pagamento(t.id(), meio, valor, recebido, meio != Meio.DINHEIRO, meio == Meio.DINHEIRO));
  }

  Criado create(Criacao d) {
    return externo.criar(a, waiter, at, UUID.randomUUID(), d);
  }

  Criado quick(CaixaDtos.Turno t) {
    return externo.criar(a, c1, cash1, UUID.randomUUID(), quick(t, Meio.DINHEIRO, 10000, 12000L));
  }

  Atendimento status(Criado d, Estado state) {
    var before = externo.buscar(a, d.comandaId(), waiter, at);
    return externo.transicionar(
        a,
        d.comandaId(),
        waiter,
        at,
        UUID.randomUUID(),
        new Transicao(state, before.versao(), "Confirmacao ficticia pelo operador"));
  }

  int stock() {
    return catalogo.produtos(a).stream()
        .filter(p -> p.id().equals(food.id()))
        .findFirst()
        .orElseThrow()
        .quantidade();
  }

  long count(String table) {
    return j.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE estabelecimento_id=?", Long.class, a);
  }

  void ready(Criado d) {
    for (var i : pedidoRepo.pedido(a, d.pedidoId()).itens()) {
      var actor = i.setor().equals("BAR") ? drinks : kitchen;
      var link = i.setor().equals("BAR") ? bar : coz;
      producao.preparar(
          a,
          i.setor(),
          i.id(),
          actor,
          link,
          new PedidoDtos.Atualizacao(PedidoDtos.EstadoItem.EM_PREPARO, i.versao()));
      producao.preparar(
          a,
          i.setor(),
          i.id(),
          actor,
          link,
          new PedidoDtos.Atualizacao(PedidoDtos.EstadoItem.PRONTO, i.versao() + 1));
    }
  }
}
