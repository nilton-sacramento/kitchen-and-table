package com.kitchentable.caixa;

import static com.kitchentable.externos.ExternoDtos.*;
import static com.kitchentable.pagamentos.PagamentoDtos.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ExternoConcorrenciaTest extends ExternoPostgresBase {
  List<Integer> race(Runnable a, Runnable b) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try {
      var fs = new ArrayList<Future<Integer>>();
      for (var fn : List.of(a, b))
        fs.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(10, TimeUnit.SECONDS));
                  return code(fn);
                }));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      var out = new ArrayList<Integer>();
      for (var f : fs) out.add(f.get(30, TimeUnit.SECONDS));
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void mesmaIntencaoSimultaneaTemUmPedidoUmPagamentoUmaBaixa() throws Exception {
    var t = open();
    var key = UUID.randomUUID();
    var body = quick(t, Meio.PIX, 10000, null);
    assertEquals(
        List.of(200, 200),
        race(
            () -> externo.criar(a, c1, cash1, key, body),
            () -> externo.criar(a, c1, cash1, key, body)));
    assertEquals(1, count("atendimentos_externos"));
    assertEquals(1, count("pedidos"));
    assertEquals(19, stock());
    assertEquals(
        10000,
        caixas.resumo(a, t.id()).meios().stream()
            .filter(m -> m.meio().equals("PIX"))
            .findFirst()
            .orElseThrow()
            .liquidoCentavos());
  }

  @Test
  void mesmaChavePorDoisOperadoresNaoDuplicaVenda() throws Exception {
    var t = team(open());
    var key = UUID.randomUUID();
    var body = quick(t, Meio.PIX, 10000, null);
    var result =
        race(
            () -> externo.criar(a, c1, cash1, key, body),
            () -> externo.criar(a, c2, cash2, key, body));
    assertEquals(List.of(200, 409), result.stream().sorted().toList());
    assertEquals(19, stock());
    assertEquals(1, count("pedidos"));
  }

  @Test
  void ultimaUnidadeVendidaUmaVezEmDuasIntencoes() throws Exception {
    var current =
        catalogo.produtos(a).stream()
            .filter(p -> p.id().equals(food.id()))
            .findFirst()
            .orElseThrow();
    cardapio.ajustar(
        a,
        food.id(),
        admin,
        new com.kitchentable.cardapio.CardapioDtos.Ajuste(
            1, "Ultima unidade ficticia", current.versao()));
    var t = team(open());
    var body = quick(t, Meio.PIX, 10000, null);
    var result =
        race(
            () -> externo.criar(a, c1, cash1, UUID.randomUUID(), body),
            () -> externo.criar(a, c2, cash2, UUID.randomUUID(), body));
    assertEquals(List.of(200, 409), result.stream().sorted().toList());
    assertEquals(0, stock());
    assertEquals(1, count("atendimentos_externos"));
  }

  @Test
  void retiradaConcorrenteTemUmaTransicaoEUmEvento() throws Exception {
    var d = create(delivery());
    ready(d);
    var body = new Transicao(Estado.RETIRADO, 0L, "Retirada concorrente ficticia");
    var result =
        race(
            () -> externo.transicionar(a, d.comandaId(), waiter, at, UUID.randomUUID(), body),
            () -> externo.transicionar(a, d.comandaId(), c1, cash1, UUID.randomUUID(), body));
    assertEquals(List.of(200, 409), result.stream().sorted().toList());
    assertEquals(2, externo.historico(a, d.comandaId(), waiter, at).size());
  }

  @Test
  void mesmaChaveLogisticaNaoDuplicaEntrega() throws Exception {
    var d = create(delivery());
    ready(d);
    var key = UUID.randomUUID();
    var body = new Transicao(Estado.ENTREGUE, 0L, "Conclusao concorrente ficticia");
    assertEquals(
        List.of(200, 200),
        race(
            () -> externo.transicionar(a, d.comandaId(), waiter, at, key, body),
            () -> externo.transicionar(a, d.comandaId(), waiter, at, key, body)));
    assertEquals(2, externo.historico(a, d.comandaId(), waiter, at).size());
  }

  @Test
  void cancelamentoConcorrenteComDespachoNaoDeixaPedidoEmTransitoCancelavel() throws Exception {
    var d = create(delivery());
    ready(d);
    var p = pedidoRepo.pedido(a, d.pedidoId());
    var result =
        race(
            () -> cancel(p),
            () ->
                externo.transicionar(
                    a,
                    d.comandaId(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Transicao(Estado.SAIU_PARA_ENTREGA, 0L, "Despacho concorrente")));
    assertEquals(List.of(200, 409), result.stream().sorted().toList());
    var state = externo.buscar(a, d.comandaId(), waiter, at).estado();
    assertEquals(state.equals("SAIU_PARA_ENTREGA") ? 0L : 1L, count("solicitacoes_pedido"));
  }
}
