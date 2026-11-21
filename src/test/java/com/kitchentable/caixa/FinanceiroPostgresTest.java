package com.kitchentable.caixa;

import static com.kitchentable.caixa.CaixaDtos.*;
import static com.kitchentable.pagamentos.PagamentoDtos.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FinanceiroPostgresTest extends FinanceiroPostgresBase {
  @Test
  void aberturaRegistraValorInicialSemReceita() {
    var t = open();
    var r = caixas.resumo(a, t.id());
    assertEquals(10000, r.dinheiroEsperadoCentavos());
    assertEquals(0, r.meios().stream().mapToLong(MeioResumo::recebidoCentavos).sum());
    assertEquals(0, r.vendasLiquidadasCentavos());
    assertEquals(cash1, t.responsavelId());
    assertEquals(1, r.lancamentos().size());
    assertEquals("ABERTURA", r.lancamentos().get(0).tipo());
  }

  @Test
  void aberturaZeroEValida() {
    var t = open(0);
    assertEquals(0, caixas.resumo(a, t.id()).dinheiroEsperadoCentavos());
  }

  @Test
  void aberturaDuplicadaComOutraChaveRetornaConflito() {
    open();
    assertEquals(409, code(() -> open()));
  }

  @Test
  void aberturaIdempotente() {
    UUID key = UUID.randomUUID();
    var d = new Abertura(cash1, 10000);
    var t = caixa.abrir(a, c1, cash1, key, d);
    assertEquals(t.id(), caixa.abrir(a, c1, cash1, key, d).id());
    assertEquals(1, caixas.turnos(a).size());
  }

  @Test
  void dinheiroRecebidoETrocoNaoAumentamReceita() {
    var t = open();
    order();
    var l =
        pagamentos.receber(
            a,
            t.id(),
            comanda.id(),
            c1,
            cash1,
            UUID.randomUUID(),
            new Receber(Meio.DINHEIRO, 10000, 12000L, false, account().revisao()));
    assertEquals(2000, l.trocoCentavos());
    assertEquals(10000, account().pagoCentavos());
    assertEquals(0, account().saldoCentavos());
    var r = caixas.resumo(a, t.id());
    assertEquals(12000, r.dinheiroRecebidoCentavos());
    assertEquals(20000, r.dinheiroEsperadoCentavos());
  }

  @Test
  void pixExigeConfirmacaoExterna() {
    var t = open();
    order();
    assertEquals(
        400,
        code(
            () ->
                pagamentos.receber(
                    a,
                    t.id(),
                    comanda.id(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Receber(Meio.PIX, 10000, null, false, account().revisao()))));
    assertEquals(10000, account().saldoCentavos());
    pay(t, Meio.PIX, 10000);
    assertEquals(10000, caixas.resumo(a, t.id()).dinheiroEsperadoCentavos());
  }

  @Test
  void debitoECreditoSaoRegistradosSemMovimentarDinheiro() {
    var t = open();
    order();
    pay(t, Meio.DEBITO, 4000);
    pay(t, Meio.CREDITO, 6000);
    assertEquals(0, account().saldoCentavos());
    assertEquals(10000, caixas.resumo(a, t.id()).dinheiroEsperadoCentavos());
  }

  @Test
  void parcialECombinadoQuitamExatamente() {
    var t = open();
    order();
    pay(t, Meio.DINHEIRO, 4000);
    assertEquals(6000, account().saldoCentavos());
    pay(t, Meio.PIX, 6000);
    assertEquals(0, account().saldoCentavos());
    assertEquals(14000, caixas.resumo(a, t.id()).dinheiroEsperadoCentavos());
  }

  @Test
  void pagamentoAcimaDoSaldoRejeitado() {
    var t = open();
    order();
    assertEquals(409, code(() -> pay(t, Meio.PIX, 10001)));
    assertEquals(0, account().pagoCentavos());
  }

  @Test
  void recebidoDinheiroInsuficienteRejeitado() {
    var t = open();
    order();
    assertEquals(
        400,
        code(
            () ->
                pagamentos.receber(
                    a,
                    t.id(),
                    comanda.id(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Receber(Meio.DINHEIRO, 10000, 9999L, false, account().revisao()))));
  }

  @Test
  void mesmaChaveNaoDuplicaRecebimento() {
    var t = open();
    order();
    var d = payment(Meio.DINHEIRO, 10000);
    UUID key = UUID.randomUUID();
    var l = pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, key, d);
    assertEquals(l.id(), pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, key, d).id());
    assertEquals(20000, caixas.resumo(a, t.id()).dinheiroEsperadoCentavos());
  }

  @Test
  void mesmaChaveComOutroConteudoConflita() {
    var t = open();
    order();
    UUID key = UUID.randomUUID();
    var d = payment(Meio.PIX, 4000);
    pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, key, d);
    assertEquals(
        409,
        code(
            () ->
                pagamentos.receber(
                    a,
                    t.id(),
                    comanda.id(),
                    c1,
                    cash1,
                    key,
                    new Receber(Meio.CREDITO, 4000, null, true, d.revisaoConta()))));
  }

  @Test
  void revisaoAntigaNaoPagaConsumoAlterado() {
    var t = open();
    order();
    var d = payment(Meio.PIX, 10000);
    order(drink.id());
    assertEquals(
        409,
        code(() -> pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, UUID.randomUUID(), d)));
    assertEquals(15000, account().saldoCentavos());
  }

  @Test
  void descontoBaixoExigePermissaoELimiteConfigurado() {
    var t = open();
    order();
    pagamentos.descontar(
        a,
        t.id(),
        comanda.id(),
        c1,
        cash1,
        UUID.randomUUID(),
        new Desconto(
            "VALOR", 500L, null, "Desconto autorizado no limite", account().revisao(), null));
    assertEquals(9500, account().saldoCentavos());
    assertEquals(10000, account().consumoCentavos());
  }

  @Test
  void descontoAltoExigeAprovacaoPontual() {
    var t = open();
    order();
    var d = new Desconto("VALOR", 2500L, null, "Concessao especial", account().revisao(), null);
    assertEquals(
        403,
        code(() -> pagamentos.descontar(a, t.id(), comanda.id(), c1, cash1, UUID.randomUUID(), d)));
    UUID id = approve(t, "DESCONTO", comanda.id(), d);
    pagamentos.descontar(
        a,
        t.id(),
        comanda.id(),
        c1,
        cash1,
        UUID.randomUUID(),
        new Desconto(d.tipo(), d.valorCentavos(), null, d.motivo(), d.revisaoConta(), id));
    assertEquals(7500, account().saldoCentavos());
  }

  @Test
  void percentualUsaArredondamentoHalfUpEmCentavos() {
    assertEquals(1, com.kitchentable.pagamentos.Dinheiro.percentual(1, 5000));
    assertEquals(3333, com.kitchentable.pagamentos.Dinheiro.percentual(10000, 3333));
    var t = open();
    order();
    pagamentos.descontar(
        a,
        t.id(),
        comanda.id(),
        c1,
        cash1,
        UUID.randomUUID(),
        new Desconto("PERCENTUAL", null, 500, "Cinco por cento", account().revisao(), null));
    assertEquals(9500, account().saldoCentavos());
  }

  @Test
  void descontoNaoUltrapassaSaldoParcial() {
    var t = open();
    order();
    pay(t, Meio.PIX, 9900);
    assertEquals(
        409,
        code(
            () ->
                pagamentos.descontar(
                    a,
                    t.id(),
                    comanda.id(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Desconto("VALOR", 101L, null, "Invalido", account().revisao(), null))));
  }

  @Test
  void sangriaEReforcoNaoSaoVendas() {
    var t = open();
    caixa.movimentar(
        a,
        t.id(),
        c1,
        cash1,
        UUID.randomUUID(),
        new Movimento(
            "REFORCO",
            5000,
            "Dinheiro ficticio para troco",
            caixas.turno(a, t.id()).versao(),
            null));
    caixa.movimentar(
        a,
        t.id(),
        c1,
        cash1,
        UUID.randomUUID(),
        new Movimento(
            "SANGRIA", 3000, "Retirada ficticia", caixas.turno(a, t.id()).versao(), null));
    var r = caixas.resumo(a, t.id());
    assertEquals(12000, r.dinheiroEsperadoCentavos());
    assertEquals(0, r.meios().stream().mapToLong(MeioResumo::recebidoCentavos).sum());
  }

  @Test
  void sangriaMaiorQueDinheiroDisponivelBloqueia() {
    var t = open();
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
                        "SANGRIA",
                        10001,
                        "Retirada maior que saldo",
                        caixas.turno(a, t.id()).versao(),
                        null))));
  }

  @Test
  void sangriaAltaComAprovacao() {
    var t = open(30000);
    var d =
        new Movimento(
            "SANGRIA", 15000, "Retirada especial", caixas.turno(a, t.id()).versao(), null);
    assertEquals(403, code(() -> caixa.movimentar(a, t.id(), c1, cash1, UUID.randomUUID(), d)));
    UUID id = approve(t, "SANGRIA", t.id(), d);
    caixa.movimentar(
        a,
        t.id(),
        c1,
        cash1,
        UUID.randomUUID(),
        new Movimento(d.tipo(), d.valorCentavos(), d.motivo(), d.versaoTurno(), id));
    assertEquals(15000, caixas.resumo(a, t.id()).dinheiroEsperadoCentavos());
  }

  @Test
  void canceladoNaoIntegraConta() {
    var p = order();
    var req = cancel(p);
    decide(req, "COZINHA");
    assertEquals(0, account().consumoCentavos());
    assertEquals("CANCELADO", account().pedidos().get(0).estado());
  }

  @Test
  void substituidoNaoECobradoJuntoComNovo() {
    var p = order();
    var r =
        alteracoes.solicitar(
            a,
            p.id(),
            waiter,
            at,
            UUID.randomUUID(),
            new com.kitchentable.pedidos.PedidoDtos.SolicitacaoEntrada(
                com.kitchentable.pedidos.PedidoDtos.TipoSolicitacao.ALTERACAO,
                "Troca ficticia",
                form(drink.id()),
                p.versao()));
    decide(r, "COZINHA");
    decide(r, "BAR");
    assertEquals(5000, account().consumoCentavos());
    assertEquals(2, account().pedidos().size());
  }

  @Test
  void pagamentoBloqueiaCancelamentoOperacionalSilencioso() {
    var t = open();
    var p = order();
    pay(t, Meio.PIX, 1000);
    assertEquals(409, code(() -> cancel(p)));
    assertEquals(9000, account().saldoCentavos());
  }

  @Test
  void pedidoPendenteBloqueiaRecebimento() {
    var t = open();
    var p = order();
    cancel(p);
    assertEquals(409, code(() -> pay(t, Meio.PIX, 10000)));
  }

  @Test
  void quitacaoExigeSaldoZero() {
    var t = open();
    var p = order();
    deliver(p);
    pay(t, Meio.PIX, 9000);
    assertEquals(409, code(() -> settle(t)));
  }

  @Test
  void quitacaoExigeEntrega() {
    var t = open();
    order();
    pay(t, Meio.PIX, 10000);
    assertEquals(409, code(() -> settle(t)));
  }

  @Test
  void quitacaoPreservaConsumoLiberaMesaEImpedeNovosPedidos() {
    var mesa = mesas.criar(a, admin, new com.kitchentable.mesas.MesaDtos.Criacao("Financeiro 01"));
    comanda =
        comandas.abrir(
            a,
            waiter,
            at,
            UUID.randomUUID(),
            new com.kitchentable.comandas.ComandaDtos.Abertura(
                com.kitchentable.comandas.ComandaDtos.Modalidade.MESA, mesa.id(), null));
    assertEquals("OCUPADA", mesas.buscar(a, mesa.id(), waiter, at).situacao());
    var t = open();
    var p = order();
    deliver(p);
    pay(t, Meio.PIX, 10000);
    assertEquals("OCUPADA", mesas.buscar(a, mesa.id(), waiter, at).situacao());
    var q = settle(t);
    var livre = mesas.buscar(a, mesa.id(), waiter, at);
    assertEquals("LIVRE", livre.situacao());
    assertNull(livre.comandaAtiva());
    assertEquals(10000, q.pagoCentavos());
    assertTrue(
        j.queryForObject(
            "SELECT teve_consumo AND possui_pendencias AND estado='ENCERRADA' FROM comandas WHERE"
                + " id=?",
            Boolean.class,
            comanda.id()));
    assertEquals(409, code(() -> order()));
    assertEquals("ENCERRADA", account().estado());
  }

  @Test
  void pagamentoNaoLiberaEncerramentoOperacionalAntigo() {
    var t = open();
    var p = order();
    deliver(p);
    pay(t, Meio.PIX, 10000);
    grant(at, "COMANDA_ENCERRAR", true);
    long v = j.queryForObject("SELECT versao FROM comandas WHERE id=?", Long.class, comanda.id());
    assertEquals(
        409,
        code(
            () ->
                comandas.finalizar(
                    a,
                    comanda.id(),
                    waiter,
                    at,
                    new com.kitchentable.comandas.ComandaDtos.Transicao(v, null),
                    false)));
  }

  @Test
  void fechamentoBloqueiaComandaComConsumoAbertaMesmoQuitada() {
    var t = open();
    order();
    pay(t, Meio.PIX, 10000);
    assertEquals(409, code(() -> close(t)));
  }

  @Test
  void comandaVaziaNaoBloqueiaTurnoNemEEncerradaAutomaticamente() {
    var t = open();
    close(t);
    assertEquals("FECHADO", caixas.turno(a, t.id()).estado());
    assertEquals("ABERTA", account().estado());
  }

  @Test
  void fechamentoCriaRelatorioImutavelReaberturaPreservaAnterior() {
    var t = open();
    var report = close(t);
    var re =
        fechamento.reabrir(
            a,
            t.id(),
            admin,
            UUID.randomUUID(),
            new Reabrir("Reabrir para conferencia", caixas.turno(a, t.id()).versao()));
    assertEquals(2, re.ciclo());
    var second = close(re);
    assertNotEquals(report.id(), second.id());
    assertEquals(2, caixas.fechamentos(a, t.id()).size());
    assertEquals(report.dados(), caixas.fechamentos(a, t.id()).get(0).dados());
  }

  @Test
  void divergenciaExigeMotivoEAprovacao() {
    var t = open();
    assertEquals(400, code(() -> conference(t, 9900, null)));
    var c = conference(t, 9900, "Falta ficticia documentada");
    var d = new Fechar(c.id(), c.versaoTurno(), null);
    assertEquals(403, code(() -> fechamento.fechar(a, t.id(), c1, cash1, UUID.randomUUID(), d)));
    UUID id = approve(t, "FECHAMENTO", t.id(), d);
    var r =
        fechamento.fechar(
            a, t.id(), c1, cash1, UUID.randomUUID(), new Fechar(c.id(), c.versaoTurno(), id));
    assertEquals("-100", r.dados().path("conferencia").path("diferencaCentavos").asText());
  }

  @Test
  void conferenciaObsoletaNaoFecha() {
    var t = open();
    var c = conference(t, 10000, null);
    caixa.movimentar(
        a,
        t.id(),
        c1,
        cash1,
        UUID.randomUUID(),
        new Movimento("REFORCO", 100, "Novo reforco", caixas.turno(a, t.id()).versao(), null));
    assertEquals(
        409,
        code(
            () ->
                fechamento.fechar(
                    a,
                    t.id(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Fechar(c.id(), c.versaoTurno(), null))));
  }

  @Test
  void compensacaoDinheiroReabreSaldoSemApagarOriginal() {
    var t = open();
    order();
    var l = pay(t, Meio.DINHEIRO, 10000);
    var d = compensation(t, l, "ESTORNO_PAGAMENTO");
    var e = pagamentos.compensar(a, t.id(), l.id(), c1, cash1, UUID.randomUUID(), d);
    assertEquals(l.id(), e.originalId());
    assertEquals(10000, account().saldoCentavos());
    assertEquals(10000, caixas.resumo(a, t.id()).dinheiroEsperadoCentavos());
    assertEquals(2, account().lancamentos().size());
  }

  @Test
  void estornoExternoNaoAfirmaDevolucaoAutomatica() {
    var t = open();
    order();
    var l = pay(t, Meio.PIX, 10000);
    var e =
        pagamentos.compensar(
            a,
            t.id(),
            l.id(),
            c1,
            cash1,
            UUID.randomUUID(),
            compensation(t, l, "ESTORNO_PAGAMENTO"));
    assertTrue(e.confirmacaoExternaPendente());
    assertEquals(1, caixas.resumo(a, t.id()).pendenciasExternas());
    assertEquals(
        400,
        code(
            () ->
                pagamentos.confirmarExterno(
                    a,
                    t.id(),
                    e.id(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new ConfirmarExterno("Confirmar", false))));
    pagamentos.confirmarExterno(
        a,
        t.id(),
        e.id(),
        c1,
        cash1,
        UUID.randomUUID(),
        new ConfirmarExterno("Conferido externamente", true));
    assertFalse(caixas.lancamento(a, e.id()).confirmacaoExternaPendente());
  }

  @Test
  void correcaoDeRegistroNaoSimulaDevolucaoBancaria() {
    var t = open();
    order();
    var l = pay(t, Meio.PIX, 10000);
    var e =
        pagamentos.compensar(
            a,
            t.id(),
            l.id(),
            c1,
            cash1,
            UUID.randomUUID(),
            compensation(t, l, "CORRECAO_LANCAMENTO"));
    assertEquals("CORRECAO_LANCAMENTO", e.natureza());
    assertFalse(e.confirmacaoExternaPendente());
    assertEquals(10000, account().saldoCentavos());
  }

  @Test
  void estornoDuplicadoNaoRegistraSegundaCompensacao() {
    var t = open();
    order();
    var l = pay(t, Meio.PIX, 10000);
    var d = compensation(t, l, "CORRECAO_LANCAMENTO");
    UUID key = UUID.randomUUID();
    var e = pagamentos.compensar(a, t.id(), l.id(), c1, cash1, key, d);
    assertEquals(e.id(), pagamentos.compensar(a, t.id(), l.id(), c1, cash1, key, d).id());
    assertEquals(
        409,
        code(
            () ->
                pagamentos.compensar(
                    a,
                    t.id(),
                    l.id(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Compensar(
                        d.natureza(), d.motivo(), account().revisao(), d.autorizacaoId()))));
  }

  @Test
  void descontoCompensadoPreservaPrecoOriginal() {
    var t = open();
    order();
    var l =
        pagamentos.descontar(
            a,
            t.id(),
            comanda.id(),
            c1,
            cash1,
            UUID.randomUUID(),
            new Desconto("VALOR", 500L, null, "Oferta", account().revisao(), null));
    pagamentos.compensar(
        a, t.id(), l.id(), c1, cash1, UUID.randomUUID(), compensation(t, l, "REVERSAO_DESCONTO"));
    assertEquals(10000, account().consumoCentavos());
    assertEquals(0, account().descontosCentavos());
  }

  @Test
  void estornoDepoisEncerramentoBloqueiaSemReabrirComandaSilenciosamente() {
    var t = open();
    var p = order();
    deliver(p);
    var l = pay(t, Meio.PIX, 10000);
    settle(t);
    assertEquals(
        409,
        code(
            () ->
                pagamentos.compensar(
                    a,
                    t.id(),
                    l.id(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Compensar(
                        "ESTORNO_PAGAMENTO",
                        "Requer fluxo especifico",
                        account().revisao(),
                        null))));
  }

  @Test
  void valoresFinanceirosJsonSaoStringsDeCentavos() throws Exception {
    var t = open();
    order();
    var l = pay(t, Meio.PIX, 10000);
    assertTrue(json.readTree(json.writeValueAsString(l)).path("valorCentavos").isTextual());
    assertTrue(json.readTree(json.writeValueAsString(account())).path("saldoCentavos").isTextual());
  }

  @Test
  void relatorioSeparaVendaLiquidadaDeCaixaFisico() {
    var t = open();
    var p = order();
    deliver(p);
    pay(t, Meio.PIX, 10000);
    settle(t);
    close(t);
    assertEquals(10000, caixas.resumo(a, t.id()).vendasLiquidadasCentavos());
    var r =
        relatorios.administrativo(
            a, admin, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(60), null);
    assertEquals("10000", r.get("vendasLiquidadasCentavos"));
    assertEquals("0", r.get("reforcosCentavos"));
    assertEquals(1, ((List<?>) r.get("relatoriosFechamento")).size());
  }

  @Test
  void consolidadoFiltraUnidadesAutorizadas() {
    var t = open();
    order();
    pay(t, Meio.PIX, 10000);
    var r =
        relatorios.consolidado(
            List.of(a, b), admin, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(60));
    assertEquals(2, ((List<?>) r.get("unidades")).size());
  }
}
