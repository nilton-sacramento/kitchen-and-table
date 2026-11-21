package com.kitchentable.caixa;

import static com.kitchentable.caixa.CaixaDtos.*;
import static com.kitchentable.pagamentos.PagamentoDtos.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class FinanceiroConcorrenciaTest extends FinanceiroPostgresBase {
  List<Integer> race(Runnable first, Runnable second) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try {
      var futures = new ArrayList<Future<Integer>>();
      for (var action : List.of(first, second))
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(10, TimeUnit.SECONDS));
                  return code(action);
                }));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      var result = new ArrayList<Integer>();
      for (var f : futures) result.add(f.get(30, TimeUnit.SECONDS));
      return result;
    } finally {
      pool.shutdownNow();
    }
  }

  void one(List<Integer> r) {
    assertEquals(1, r.stream().filter(x -> x == 200).count(), r.toString());
    assertTrue(r.stream().allMatch(x -> x == 200 || x == 409), r.toString());
  }

  @Test
  void doisOperadoresNaoRecebemMesmoSaldo() throws Exception {
    var t = team(open());
    order();
    var d = payment(Meio.PIX, 10000);
    one(
        race(
            () -> pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, UUID.randomUUID(), d),
            () -> pagamentos.receber(a, t.id(), comanda.id(), c2, cash2, UUID.randomUUID(), d)));
    assertEquals(10000, account().pagoCentavos());
  }

  @Test
  void mesmaChaveConcorrenteProduzUmLancamento() throws Exception {
    var t = open();
    order();
    var d = payment(Meio.PIX, 10000);
    var key = UUID.randomUUID();
    assertEquals(
        List.of(200, 200),
        race(
            () -> pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, key, d),
            () -> pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, key, d)));
    assertEquals(
        1, account().lancamentos().stream().filter(l -> l.tipo().equals("PAGAMENTO")).count());
  }

  @Test
  void respostaPerdidaConsultaRegistroSemRepetirRecebimento() {
    var t = open();
    order();
    var key = UUID.randomUUID();
    var d = payment(Meio.PIX, 10000);
    var original = pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, key, d);
    var status = caixa.consultarOperacao(a, key, c1, cash1);
    assertEquals(
        original.id().toString(),
        ((com.fasterxml.jackson.databind.JsonNode) status.get("resultado")).path("id").asText());
    assertEquals(
        original.id(), pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, key, d).id());
  }

  @Test
  void novoPedidoConcorrenteInvalidaRevisaoOuMantemNovoSaldo() throws Exception {
    var t = open();
    order();
    var d = payment(Meio.PIX, 10000);
    var r =
        race(
            () -> order(drink.id()),
            () -> pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, UUID.randomUUID(), d));
    assertTrue(r.contains(200));
    assertTrue(r.stream().allMatch(x -> x == 200 || x == 409));
    assertEquals(15000, account().consumoCentavos());
    assertTrue(account().saldoCentavos() >= 5000);
  }

  @Test
  void cancelamentoERecebimentoNaoCorrompemConta() throws Exception {
    var t = open();
    var p = order();
    var d = payment(Meio.PIX, 10000);
    one(
        race(
            () -> cancel(p),
            () -> pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, UUID.randomUUID(), d)));
    assertTrue(account().saldoCentavos() >= 0);
  }

  @Test
  void quitacoesSimultaneasPreservamCertificadoUnico() throws Exception {
    var t = team(open());
    var p = order();
    deliver(p);
    pay(t, Meio.PIX, 10000);
    var d = new Encerrar(account().revisao());
    one(
        race(
            () -> pagamentos.encerrar(a, t.id(), comanda.id(), c1, cash1, UUID.randomUUID(), d),
            () -> pagamentos.encerrar(a, t.id(), comanda.id(), c2, cash2, UUID.randomUUID(), d)));
    assertEquals(
        1,
        j.queryForObject(
            "SELECT count(*) FROM quitacoes_comanda WHERE comanda_id=?",
            Integer.class,
            comanda.id()));
  }

  @Test
  void fechamentoNaoPassaPorRecebimentoComComandaAberta() throws Exception {
    var t = team(open());
    order();
    var d = payment(Meio.PIX, 10000);
    var conf = conference(t, 10000, null);
    var r =
        race(
            () ->
                fechamento.fechar(
                    a,
                    t.id(),
                    c2,
                    cash2,
                    UUID.randomUUID(),
                    new Fechar(conf.id(), conf.versaoTurno(), null)),
            () -> pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, UUID.randomUUID(), d));
    assertEquals(409, r.get(0));
    assertEquals(200, r.get(1));
    assertEquals("ABERTO", caixas.turno(a, t.id()).estado());
  }

  @Test
  void sangriasSimultaneasNaoTornamDinheiroNegativo() throws Exception {
    var t = team(open());
    var d = new Movimento("SANGRIA", 8000, "Retirada concorrente", t.versao(), null);
    one(
        race(
            () -> caixa.movimentar(a, t.id(), c1, cash1, UUID.randomUUID(), d),
            () -> caixa.movimentar(a, t.id(), c2, cash2, UUID.randomUUID(), d)));
    assertEquals(2000, caixas.resumo(a, t.id()).dinheiroEsperadoCentavos());
  }

  @Test
  void turnoFechadoNaoAceitaNovosMovimentos() {
    var t = open();
    close(t);
    assertEquals(
        409,
        code(
            () ->
                caixa.movimentar(
                    a,
                    t.id(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Movimento(
                        "REFORCO",
                        100,
                        "Apos fechamento",
                        caixas.turno(a, t.id()).versao(),
                        null))));
  }

  @Test
  void revogacaoConfirmadaAntesDaConfirmacaoImpedePagamento() {
    var t = open();
    order();
    var d = payment(Meio.PIX, 10000);
    grant(cash1, "PAGAMENTO_REGISTRAR", false);
    assertEquals(
        403,
        code(() -> pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, UUID.randomUUID(), d)));
    assertEquals(0, contas.conta(a, comanda.id()).pagoCentavos());
  }

  @Test
  void compensacoesConcorrentesNaoDuplicamEstorno() throws Exception {
    var t = open();
    order();
    var l = pay(t, Meio.PIX, 10000);
    var d = compensation(t, l, "CORRECAO_LANCAMENTO");
    one(
        race(
            () -> pagamentos.compensar(a, t.id(), l.id(), c1, cash1, UUID.randomUUID(), d),
            () -> pagamentos.compensar(a, t.id(), l.id(), c1, cash1, UUID.randomUUID(), d)));
    assertEquals(0, account().pagoCentavos());
  }

  @Test
  void reaberturaEFechamentoConcorrentesRespeitamVersao() throws Exception {
    var t = open();
    var conf = conference(t, 10000, null);
    close(t);
    var closed = caixas.turno(a, t.id());
    var r =
        race(
            () ->
                fechamento.reabrir(
                    a,
                    t.id(),
                    admin,
                    UUID.randomUUID(),
                    new Reabrir("Revisao controlada", closed.versao())),
            () ->
                fechamento.fechar(
                    a,
                    t.id(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Fechar(conf.id(), conf.versaoTurno(), null)));
    assertEquals(200, r.get(0));
    assertEquals(409, r.get(1));
    assertEquals(2, caixas.turno(a, t.id()).ciclo());
    assertEquals(1, caixas.fechamentos(a, t.id()).size());
  }
}
