package com.kitchentable.caixa;

import static com.kitchentable.externos.ExternoDtos.*;
import static com.kitchentable.pagamentos.PagamentoDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.kitchentable.externos.*;
import com.kitchentable.pedidos.PedidoDtos;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class ExternoPostgresTest extends ExternoPostgresBase {
  @Test
  void deliveryCriaBalcaoPedidoEstoqueEFilaReais() {
    var d = create(delivery());
    assertEquals("BALCAO", comandas.buscar(a, d.comandaId(), waiter, at).modalidade());
    assertEquals(19, stock());
    assertEquals(d.pedidoId(), producao.fila(a, "COZINHA", kitchen, coz).get(0).pedidoId());
    assertEquals(0, count("lancamentos_financeiros"));
  }

  @Test
  void referenciaOpcionalNaoExigeContatoOuEndereco() {
    var d = create(new Criacao(Tipo.DELIVERY, Origem.TELEFONE, null, form(food.id()), null));
    assertNull(externo.buscar(a, d.comandaId(), waiter, at).referencia());
  }

  @Test
  void origemIncompativelRejeitada() {
    assertEquals(
        400,
        code(() -> create(new Criacao(Tipo.DELIVERY, Origem.BALCAO, null, form(food.id()), null))));
    assertEquals(20, stock());
  }

  @Test
  void vendaRapidaRecebeTrocoSemSimularPreparo() {
    var t = open();
    var d = quick(t);
    var account = pagamentos.consultar(a, d.comandaId(), c1, cash1);
    assertEquals(0, account.saldoCentavos());
    assertEquals(2000, account.lancamentos().get(0).trocoCentavos());
    assertEquals("AGUARDANDO", account.pedidos().get(0).itens().get(0).estado());
    assertEquals("REGISTRADO", externo.buscar(a, d.comandaId(), c1, cash1).estado());
  }

  @Test
  void pagamentoNaoPermiteQuitacaoAntesDaProducaoELogistica() {
    var t = open();
    var d = quick(t);
    assertEquals(
        409,
        code(
            () ->
                pagamentos.encerrar(
                    a,
                    t.id(),
                    d.comandaId(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Encerrar(pagamentos.consultar(a, d.comandaId(), c1, cash1).revisao()))));
  }

  @Test
  void entregaGenericaNaoContornaConclusaoLogistica() {
    var t = open();
    var d = quick(t);
    deliver(pedidoRepo.pedido(a, d.pedidoId()));
    assertEquals(
        409,
        code(
            () ->
                pagamentos.encerrar(
                    a,
                    t.id(),
                    d.comandaId(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Encerrar(pagamentos.consultar(a, d.comandaId(), c1, cash1).revisao()))));
    status(d, Estado.RETIRADO);
    assertNotNull(
        pagamentos.encerrar(
            a,
            t.id(),
            d.comandaId(),
            c1,
            cash1,
            UUID.randomUUID(),
            new Encerrar(pagamentos.consultar(a, d.comandaId(), c1, cash1).revisao())));
  }

  @Test
  void vendaSemPagamentoReverteComandaPedidoEEstoque() {
    long n = count("comandas");
    assertEquals(
        400,
        code(
            () ->
                create(
                    new Criacao(Tipo.VENDA_RAPIDA, Origem.BALCAO, null, form(food.id()), null))));
    assertEquals(n, count("comandas"));
    assertEquals(20, stock());
    assertEquals(0, count("pedidos"));
  }

  @Test
  void pagamentoInsuficienteReverteTudo() {
    var t = open();
    long before = count("lancamentos_financeiros");
    assertEquals(
        409,
        code(() -> externo.criar(a, c1, cash1, UUID.randomUUID(), quick(t, Meio.PIX, 9999, null))));
    assertEquals(20, stock());
    assertEquals(0, count("atendimentos_externos"));
    assertEquals(before, count("lancamentos_financeiros"));
  }

  @Test
  void semEquipeNaoCriaVendaNemBaixaEstoque() {
    var t = open();
    assertEquals(
        403,
        code(
            () -> externo.criar(a, c2, cash2, UUID.randomUUID(), quick(t, Meio.PIX, 10000, null))));
    assertEquals(20, stock());
  }

  @Test
  void caixaFechadoRejeitaVendaAtomica() {
    var t = open();
    close(t);
    assertEquals(409, code(() -> quick(t)));
    assertEquals(20, stock());
    assertEquals(0, count("atendimentos_externos"));
  }

  @Test
  void produtoSemEstoqueNaoAbreComandaExterna() {
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
            0, "Esgotamento ficticio", current.versao()));
    long n = count("comandas");
    assertEquals(409, code(() -> create(delivery())));
    assertEquals(n, count("comandas"));
  }

  @Test
  void comboTemUmaCobrancaEDoisSetores() {
    var d = create(new Criacao(Tipo.DELIVERY, Origem.OUTRO, null, form(combo.id()), null));
    var p = pedidoRepo.pedido(a, d.pedidoId());
    assertEquals(18000, p.totalCentavos());
    assertEquals(
        Set.of("COZINHA", "BAR"),
        new HashSet<>(p.itens().stream().map(PedidoDtos.Item::setor).toList()));
    assertEquals(19, stock());
  }

  @Test
  void retryMesmaChaveNaoDuplicaEstoquePedidoOuPagamento() {
    var t = open();
    var key = UUID.randomUUID();
    var body = quick(t, Meio.PIX, 10000, null);
    var first = externo.criar(a, c1, cash1, key, body);
    assertEquals(first, externo.criar(a, c1, cash1, key, body));
    assertEquals(19, stock());
    assertEquals(1, count("atendimentos_externos"));
    assertEquals(1, count("pedidos"));
    assertEquals(1, pagamentos.consultar(a, first.comandaId(), c1, cash1).lancamentos().size());
  }

  @Test
  void chaveComOutroCorpoConflita() {
    var key = UUID.randomUUID();
    externo.criar(a, waiter, at, key, delivery());
    assertEquals(
        409,
        code(
            () ->
                externo.criar(
                    a,
                    waiter,
                    at,
                    key,
                    new Criacao(Tipo.DELIVERY, Origem.OUTRO, null, form(food.id()), null))));
    assertEquals(19, stock());
  }

  @Test
  void chaveDeOutroOperadorNaoPermiteRepeticao() {
    var key = UUID.randomUUID();
    externo.criar(a, waiter, at, key, delivery());
    assertEquals(409, code(() -> externo.criar(a, c1, cash1, key, delivery())));
  }

  @Test
  void respostaPerdidaPodeSerConsultadaPeloAutor() {
    var key = UUID.randomUUID();
    var d = externo.criar(a, waiter, at, key, delivery());
    assertEquals(d, externo.operacao(a, Tipo.DELIVERY, key, waiter, at));
    assertEquals(404, code(() -> externo.operacao(a, Tipo.DELIVERY, key, c1, cash1)));
  }

  @Test
  void revogacaoImpedeReplayEConsultaDaIntencao() {
    var key = UUID.randomUUID();
    externo.criar(a, waiter, at, key, delivery());
    grant(at, "DELIVERY_REGISTRAR", false);
    assertEquals(403, code(() -> externo.criar(a, waiter, at, key, delivery())));
    assertEquals(403, code(() -> externo.operacao(a, Tipo.DELIVERY, key, waiter, at)));
  }

  @Test
  void unidadeErradaNaoConsultaOuAltera() {
    var d = create(delivery());
    assertEquals(403, code(() -> externo.buscar(b, d.comandaId(), waiter, at)));
    assertEquals(
        403,
        code(
            () ->
                externo.transicionar(
                    b,
                    d.comandaId(),
                    waiter,
                    at,
                    UUID.randomUUID(),
                    new Transicao(Estado.ENTREGUE, 0L, "Tentativa cruzada"))));
  }

  @Test
  void identificadorDeOutraUnidadeNaoEEncontrado() {
    var d = create(delivery());
    UUID e = employee(b, "Atendente B", "ATENDENTE");
    permissoes.excecao(
        b,
        e,
        "DELIVERY_CONSULTAR",
        email,
        new com.kitchentable.funcionarios.PermissoesDtos.Excecao(
            com.kitchentable.funcionarios.PermissoesDtos.Estado.PERMITIR,
            funcionarios.consultar(b, e, email).versao()));
    assertEquals(404, code(() -> externo.buscar(b, d.comandaId(), login(e, b), e)));
  }

  @Test
  void sessaoAdministrativaNaoCriaDelivery() {
    assertEquals(403, code(() -> externo.criar(a, admin, at, UUID.randomUUID(), delivery())));
  }

  @Test
  void sessaoOperacionalBloqueadaNaoRegistra() {
    op.bloquearSessao((com.kitchentable.funcionarios.OperadorPrincipal) waiter.getPrincipal());
    assertEquals(423, code(() -> create(delivery())));
  }

  @Test
  void origemPlataformaNaoPresumeReceitaOuEntradaFisica() {
    var d = create(new Criacao(Tipo.DELIVERY, Origem.PLATAFORMA, null, form(food.id()), null));
    assertEquals(0, count("lancamentos_financeiros"));
    assertEquals(10000, pagamentos.consultar(a, d.comandaId(), c1, cash1).saldoCentavos());
  }

  @Test
  void dinheiroRecebidoForaDoCaixaERecusado() {
    var t = open();
    var pay = new Pagamento(t.id(), Meio.DINHEIRO, 10000, 10000L, false, false);
    assertEquals(
        409,
        code(
            () ->
                externo.criar(
                    a,
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Criacao(Tipo.DELIVERY, Origem.PLATAFORMA, null, form(food.id()), pay))));
    assertEquals(20, stock());
  }

  @Test
  void pixConferidoNaoAumentaDinheiroFisico() {
    var t = open(0);
    externo.criar(a, c1, cash1, UUID.randomUUID(), quick(t, Meio.PIX, 10000, null));
    assertEquals(0, caixas.resumo(a, t.id()).dinheiroEsperadoCentavos());
  }

  @Test
  void cartaoSemConfirmacaoExternaReverteVenda() {
    var t = open();
    var pay = new Pagamento(t.id(), Meio.CREDITO, 10000, null, false, false);
    assertEquals(
        400,
        code(
            () ->
                externo.criar(
                    a,
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Criacao(Tipo.VENDA_RAPIDA, Origem.BALCAO, null, form(food.id()), pay))));
    assertEquals(20, stock());
  }

  @Test
  void naoDespachaEnquantoPreparoIncompleto() {
    var d = create(delivery());
    assertEquals(409, code(() -> status(d, Estado.SAIU_PARA_ENTREGA)));
    assertEquals("REGISTRADO", externo.buscar(a, d.comandaId(), waiter, at).estado());
  }

  @Test
  void preparoDerivadoNaoAlteraEstadoFinanceiro() {
    var d = create(delivery());
    ready(d);
    assertEquals("PRONTO", externo.buscar(a, d.comandaId(), waiter, at).preparo());
    status(d, Estado.SAIU_PARA_ENTREGA);
    assertEquals(10000, pagamentos.consultar(a, d.comandaId(), c1, cash1).saldoCentavos());
  }

  @Test
  void deliveryEntreguePodeContinuarNaoPago() {
    var d = create(delivery());
    ready(d);
    status(d, Estado.SAIU_PARA_ENTREGA);
    status(d, Estado.ENTREGUE);
    assertEquals(10000, pagamentos.consultar(a, d.comandaId(), c1, cash1).saldoCentavos());
    assertEquals("ENTREGUE", pedidoRepo.pedido(a, d.pedidoId()).estado());
  }

  @Test
  void vendaRapidaNaoAceitaEstadoDeEntregador() {
    var d = quick(open());
    ready(d);
    assertEquals(409, code(() -> status(d, Estado.SAIU_PARA_ENTREGA)));
  }

  @Test
  void versaoObsoletaNaoSobrescreveLogistica() {
    var d = create(delivery());
    ready(d);
    status(d, Estado.SAIU_PARA_ENTREGA);
    assertEquals(
        409,
        code(
            () ->
                externo.transicionar(
                    a,
                    d.comandaId(),
                    waiter,
                    at,
                    UUID.randomUUID(),
                    new Transicao(Estado.ENTREGUE, 0L, "Versao antiga"))));
  }

  @Test
  void despachoBloqueiaNovoPedidoInclusivePelaApiAntiga() {
    var d = create(delivery());
    ready(d);
    status(d, Estado.SAIU_PARA_ENTREGA);
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> pedidos.registrar(a, d.comandaId(), waiter, at, UUID.randomUUID(), form(food.id())));
    assertEquals(19, stock());
  }

  @Test
  void cancelamentoExigeFluxoSetorialERegularizacao() {
    var d = create(delivery());
    assertEquals(409, code(() -> status(d, Estado.CANCELADO)));
    var request = cancel(pedidoRepo.pedido(a, d.pedidoId()));
    decide(request, "COZINHA");
    status(d, Estado.CANCELADO);
    assertEquals(20, stock());
    assertEquals("CANCELADO", pedidoRepo.pedido(a, d.pedidoId()).estado());
  }

  @Test
  void cancelamentoPagoNaoContornaRegularizacaoFinanceira() {
    var t = open();
    var d = quick(t);
    assertEquals(409, code(() -> status(d, Estado.CANCELADO)));
    assertEquals(409, code(() -> cancel(pedidoRepo.pedido(a, d.pedidoId()))));
  }

  @Test
  void historicoEIndividualESemCredenciais() {
    var d = create(delivery());
    ready(d);
    status(d, Estado.RETIRADO);
    var history = externo.historico(a, d.comandaId(), waiter, at);
    assertEquals(2, history.size());
    assertNotNull(history.get(0).executor());
    var text = json.valueToTree(history).toString();
    assertFalse(text.contains(PIN));
    assertFalse(text.contains("pin_hash"));
    assertEquals(
        1,
        j.queryForObject(
            "SELECT count(*) FROM auditoria_eventos WHERE entidade_id=? AND"
                + " dados_evento->>'vinculoExecutor'=? AND tipo_evento='DELIVERY_REGISTRADO'",
            Integer.class,
            d.comandaId(),
            at.toString()));
  }

  @Test
  void estadoLogisticoNaoPodeSerApagadoNoBanco() {
    var d = create(delivery());
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> j.update("DELETE FROM atendimentos_externos WHERE comanda_id=?", d.comandaId()));
  }

  @Test
  void pagamentosCombinadosUsamContaExistente() {
    var t = open();
    var d = create(delivery());
    for (var spec :
        List.of(
            new Object[] {Meio.DINHEIRO, 4000L},
            new Object[] {Meio.PIX, 3500L},
            new Object[] {Meio.DEBITO, 2500L})) {
      var meio = (Meio) spec[0];
      long value = (Long) spec[1];
      pagamentos.receber(
          a,
          t.id(),
          d.comandaId(),
          c1,
          cash1,
          UUID.randomUUID(),
          new Receber(
              meio,
              value,
              meio == Meio.DINHEIRO ? 4000L : null,
              meio != Meio.DINHEIRO,
              pagamentos.consultar(a, d.comandaId(), c1, cash1).revisao()));
    }
    assertEquals(0, pagamentos.consultar(a, d.comandaId(), c1, cash1).saldoCentavos());
    assertEquals(14000, caixas.resumo(a, t.id()).dinheiroEsperadoCentavos());
  }

  @Test
  void relatorioCanalNaoDuplicaVendas() {
    var t = open();
    var d = quick(t);
    ready(d);
    status(d, Estado.RETIRADO);
    pagamentos.encerrar(
        a,
        t.id(),
        d.comandaId(),
        c1,
        cash1,
        UUID.randomUUID(),
        new Encerrar(pagamentos.consultar(a, d.comandaId(), c1, cash1).revisao()));
    var report =
        relatorios.administrativo(
            a, admin, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), null);
    assertEquals("10000", report.get("vendasLiquidadasCentavos"));
    assertTrue(json.valueToTree(report.get("canais")).toString().contains("VENDA_RAPIDA"));
    assertTrue(json.valueToTree(report.get("canais")).toString().contains("10000"));
  }

  @Test
  void csrfObrigatorioNaNovaApi() throws Exception {
    mvc.perform(
            post("/api/v1/operacional/estabelecimentos/" + a + "/externos")
                .with(authentication(waiter))
                .header("X-Operador-Vinculo", at)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsBytes(delivery())))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
  }

  @Test
  void falhaDeAuditoriaExternaRevertePedidoBaixaEComanda() {
    String name = "teste_audit_" + UUID.randomUUID().toString().replace("-", "");
    j.execute(
        "CREATE FUNCTION "
            + name
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.estabelecimento_id='"
            + a
            + "'::uuid AND NEW.entidade_tipo='ATENDIMENTO_EXTERNO' THEN RAISE EXCEPTION 'Falha"
            + " sintetica de auditoria' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
    j.execute(
        "CREATE TRIGGER "
            + name
            + " BEFORE INSERT ON auditoria_eventos FOR EACH ROW EXECUTE FUNCTION "
            + name
            + "()");
    try {
      long before = count("comandas");
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class, () -> create(delivery()));
      assertEquals(before, count("comandas"));
      assertEquals(20, stock());
      assertEquals(0, count("pedidos"));
      assertEquals(0, count("atendimentos_externos"));
    } finally {
      j.execute("DROP TRIGGER " + name + " ON auditoria_eventos");
      j.execute("DROP FUNCTION " + name + "()");
    }
  }

  @Test
  void vendaRapidaNaoAceitaNovoConsumoPelaApiGenerica() {
    var d = quick(open());
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> pedidos.registrar(a, d.comandaId(), waiter, at, UUID.randomUUID(), form(food.id())));
    assertEquals(19, stock());
    assertEquals(1, pedidoRepo.listar(a, d.comandaId()).size());
  }

  @Test
  void cabecalhoDeOutroOperadorNaoAutenticaNovaApi() {
    assertEquals(403, code(() -> externo.criar(a, waiter, cash1, UUID.randomUUID(), delivery())));
    assertEquals(0, count("atendimentos_externos"));
  }

  @Test
  void revogarPagamentoImpedeReplayDeVendaRapida() {
    var t = open();
    var key = UUID.randomUUID();
    var body = quick(t, Meio.PIX, 10000, null);
    externo.criar(a, c1, cash1, key, body);
    grant(cash1, "PAGAMENTO_REGISTRAR", false);
    assertEquals(403, code(() -> externo.criar(a, c1, cash1, key, body)));
    assertEquals(19, stock());
  }

  @Test
  void revogarPermissaoLogisticaImpedeNovaTransicao() {
    var d = create(delivery());
    ready(d);
    grant(at, "DELIVERY_STATUS_ALTERAR", false);
    assertEquals(403, code(() -> status(d, Estado.RETIRADO)));
    assertEquals("REGISTRADO", externo.buscar(a, d.comandaId(), waiter, at).estado());
  }

  @Test
  void precoManipuladoReverteAberturaESemBaixa() {
    var original = form(food.id());
    var line = original.linhas().get(0);
    var fake =
        new PedidoDtos.Confirmacao(
            false,
            List.of(
                new PedidoDtos.LinhaEntrada(
                    line.produtoId(), 1, line.precoCentavos() - 1, line.assinaturaOferta(), null)));
    assertEquals(
        409, code(() -> create(new Criacao(Tipo.DELIVERY, Origem.OUTRO, null, fake, null))));
    assertEquals(0, count("atendimentos_externos"));
    assertEquals(20, stock());
  }

  @Test
  void despachoImpedeCancelamentoPeloFluxoAntigo() {
    var d = create(delivery());
    ready(d);
    status(d, Estado.SAIU_PARA_ENTREGA);
    assertEquals(409, code(() -> cancel(pedidoRepo.pedido(a, d.pedidoId()))));
    assertEquals("SAIU_PARA_ENTREGA", externo.buscar(a, d.comandaId(), waiter, at).estado());
    assertEquals(0, count("solicitacoes_pedido"));
  }
}
