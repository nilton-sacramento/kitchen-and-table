package com.kitchentable.caixa;

import static com.kitchentable.caixa.CaixaDtos.*;
import static com.kitchentable.pagamentos.PagamentoDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class FinanceiroSegurancaTest extends FinanceiroPostgresBase {
  @Test
  void gerenteExistenteNaoRecebePoderFinanceiroAutomatico() {
    assertEquals(
        403,
        code(
            () ->
                caixa.abrir(
                    a, managerOp, managerLink, UUID.randomUUID(), new Abertura(managerLink, 0))));
    assertEquals(
        403, code(() -> caixa.configurar(a, gerente, new Configuracao(null, 0L, 0, 0L, 0L, 0L))));
    assertEquals(403, code(() -> aprovacoes.listarAdmin(a, gerente)));
  }

  @Test
  void operadorSemDelegacaoNaoAdministraLimitesNemAutoriza() {
    var t = open();
    assertEquals(
        403, code(() -> caixa.configurar(a, c1, new Configuracao(null, 0L, 0, 0L, 0L, 0L))));
    assertEquals(403, code(() -> aprovacoes.listarAdmin(a, c1)));
  }

  @Test
  void outraUnidadeNaoAceitaSessaoOuIdentificadores() {
    var t = open();
    order();
    assertEquals(403, code(() -> pagamentos.consultar(b, comanda.id(), c1, cash1)));
    assertEquals(404, code(() -> pagamentos.consultar(a, UUID.randomUUID(), c1, cash1)));
    assertEquals(403, code(() -> caixa.atual(b, c1, cash1)));
  }

  @Test
  void cabecalhoDeOutroOperadorNaoAutentica() {
    assertEquals(403, code(() -> caixa.atual(a, c1, cash2)));
  }

  @Test
  void permissaoRevogadaBloqueiaConsultaDoResultadoJaGravado() {
    var t = open();
    order();
    var key = UUID.randomUUID();
    pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, key, payment(Meio.PIX, 10000));
    grant(cash1, "PAGAMENTO_REGISTRAR", false);
    assertEquals(403, code(() -> caixa.consultarOperacao(a, key, c1, cash1)));
  }

  @Test
  void chaveDeOutroOperadorNaoExibeResultado() {
    var t = team(open());
    order();
    var key = UUID.randomUUID();
    pagamentos.receber(a, t.id(), comanda.id(), c1, cash1, key, payment(Meio.PIX, 10000));
    assertEquals(404, code(() -> caixa.consultarOperacao(a, key, c2, cash2)));
  }

  @Test
  void incluirNaEquipeNaoConcedePermissaoFinanceira() {
    var t = open();
    assertEquals(
        403,
        code(
            () ->
                caixa.equipeAdmin(
                    a,
                    t.id(),
                    admin,
                    new Equipe(
                        List.of(cash1, at), cash1, t.versao(), "Nao conceder privilegios"))));
    assertEquals(
        403,
        code(
            () ->
                pagamentos.receber(
                    a,
                    t.id(),
                    comanda.id(),
                    waiter,
                    at,
                    UUID.randomUUID(),
                    new Receber(Meio.PIX, 100, null, true, "fake"))));
  }

  @Test
  void limiteDaEquipeERevalidado() {
    var t = team(open());
    long v = caixa.politica(a, admin).versao();
    var d = new Configuracao(1, 1000L, 2000, 10000L, 0L, v);
    assertEquals(409, code(() -> caixa.configurar(a, admin, d)));
    assertEquals(2, caixas.turno(a, t.id()).operadores().stream().filter(Operador::ativo).count());
  }

  @Test
  void retirarOperadorBloqueiaNovaMutacao() {
    var t = team(open());
    caixa.equipeAdmin(
        a, t.id(), admin, new Equipe(List.of(cash2), cash2, t.versao(), "Transferencia explicita"));
    order();
    assertEquals(
        403,
        code(
            () ->
                pagamentos.receber(
                    a,
                    t.id(),
                    comanda.id(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    payment(Meio.PIX, 10000))));
  }

  @Test
  void aprovacaoNaoServeParaOutroValor() {
    var t = open();
    order();
    var d = new Desconto("VALOR", 2000L, null, "Revisao", account().revisao(), null);
    var id = approve(t, "DESCONTO", comanda.id(), d);
    assertEquals(
        403,
        code(
            () ->
                pagamentos.descontar(
                    a,
                    t.id(),
                    comanda.id(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Desconto("VALOR", 2100L, null, d.motivo(), d.revisaoConta(), id))));
    assertEquals("APROVADA", aprovacoes.buscar(a, id).estado());
  }

  @Test
  void revogarAutorizacaoImpedeConsumo() {
    var t = open();
    order();
    var d = new Desconto("VALOR", 2000L, null, "Revisao", account().revisao(), null);
    var id = approve(t, "DESCONTO", comanda.id(), d);
    var r = aprovacoes.buscar(a, id);
    aprovacoes.decidir(a, id, admin, new Autorizar(r.versao(), "REVOGAR", "Desistencia"));
    assertEquals(
        403,
        code(
            () ->
                pagamentos.descontar(
                    a,
                    t.id(),
                    comanda.id(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Desconto(
                        d.tipo(), d.valorCentavos(), null, d.motivo(), d.revisaoConta(), id))));
    assertEquals(0, account().descontosCentavos());
  }

  @Test
  void alterarPoliticaInvalidaAprovacaoAnterior() {
    var t = open();
    order();
    var d = new Desconto("VALOR", 2000L, null, "Revisao", account().revisao(), null);
    var id = approve(t, "DESCONTO", comanda.id(), d);
    config(a, 2, 500, 1000, 10000, 0);
    assertEquals(
        403,
        code(
            () ->
                pagamentos.descontar(
                    a,
                    t.id(),
                    comanda.id(),
                    c1,
                    cash1,
                    UUID.randomUUID(),
                    new Desconto(
                        d.tipo(), d.valorCentavos(), null, d.motivo(), d.revisaoConta(), id))));
  }

  @Test
  void autorizacaoConsumidaNaoPodeSerReutilizada() {
    var t = open();
    order();
    var d = new Desconto("VALOR", 2000L, null, "Revisao", account().revisao(), null);
    var id = approve(t, "DESCONTO", comanda.id(), d);
    var approved =
        new Desconto(d.tipo(), d.valorCentavos(), null, d.motivo(), d.revisaoConta(), id);
    pagamentos.descontar(a, t.id(), comanda.id(), c1, cash1, UUID.randomUUID(), approved);
    assertEquals("CONSUMIDA", aprovacoes.buscar(a, id).estado());
    assertEquals(
        409,
        code(
            () ->
                pagamentos.descontar(
                    a, t.id(), comanda.id(), c1, cash1, UUID.randomUUID(), approved)));
    assertEquals(2000, account().descontosCentavos());
  }

  @Test
  void operadorSemHistoricoIndividualNaoVeLancamentosDeColega() {
    var t = team(open());
    order();
    pay(t, Meio.PIX, 10000);
    grant(cash2, "FUNCIONARIO_HISTORICO_FINANCEIRO_CONSULTAR", false);
    assertTrue(caixa.resumo(a, t.id(), c2, cash2).lancamentos().isEmpty());
    var report =
        relatorios.operacional(
            a,
            c2,
            cash2,
            java.time.Instant.now().minusSeconds(60),
            java.time.Instant.now().plusSeconds(60),
            null);
    assertEquals(List.of(), report.get("lancamentos"));
    assertEquals(
        403,
        code(
            () ->
                relatorios.operacional(
                    a,
                    c2,
                    cash2,
                    java.time.Instant.now().minusSeconds(60),
                    java.time.Instant.now(),
                    cash1)));
  }

  @Test
  void csrfObrigatorioMesmoComOperadorAutenticado() throws Exception {
    mvc.perform(
            post("/api/v1/operacional/estabelecimentos/" + a + "/caixa/turnos")
                .with(authentication(c1))
                .header("X-Operador-Vinculo", cash1)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsBytes(new Abertura(cash1, 0))))
        .andExpect(status().isForbidden());
  }

  @Test
  void centavosFracionariosSaoRejeitadosNaApi() throws Exception {
    var t = open();
    order();
    String body =
        "{\"meio\":\"PIX\",\"valorCentavos\":1.5,\"confirmadoExternamente\":true,\"revisaoConta\":\""
            + account().revisao()
            + "\"}";
    mvc.perform(
            post("/api/v1/operacional/estabelecimentos/"
                    + a
                    + "/caixa/turnos/"
                    + t.id()
                    + "/comandas/"
                    + comanda.id()
                    + "/pagamentos")
                .with(authentication(c1))
                .with(csrf())
                .header("X-Operador-Vinculo", cash1)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void ledgerImutavelERejeitaExclusao() {
    var t = open();
    var id = caixas.resumo(a, t.id()).lancamentos().get(0).id();
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> j.update("DELETE FROM lancamentos_financeiros WHERE id=?", id));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> j.update("UPDATE lancamentos_financeiros SET valor_centavos=1 WHERE id=?", id));
  }

  @Test
  void descontoIntegralPermiteQuitacaoSemPagamentoFicticio() {
    var t = open();
    var p = order();
    deliver(p);
    var d = new Desconto("VALOR", 10000L, null, "Cortesia autorizada", account().revisao(), null);
    var id = approve(t, "DESCONTO", comanda.id(), d);
    pagamentos.descontar(
        a,
        t.id(),
        comanda.id(),
        c1,
        cash1,
        UUID.randomUUID(),
        new Desconto(d.tipo(), d.valorCentavos(), null, d.motivo(), d.revisaoConta(), id));
    assertEquals(0, settle(t).pagoCentavos());
    assertFalse(account().lancamentos().stream().anyMatch(l -> l.tipo().equals("PAGAMENTO")));
  }

  @Test
  void autorizacaoExpiradaNaoExecutaMesmoComDadosIguais() {
    var t = open();
    order();
    var d = new Desconto("VALOR", 2000L, null, "Revisao", account().revisao(), null);
    UUID source = approve(t, "DESCONTO", comanda.id(), d), expired = UUID.randomUUID();
    j.update(
        "INSERT INTO"
            + " autorizacoes_financeiras(id,estabelecimento_id,turno_id,solicitante,credencial_versao,tipo,entidade_id,requisicao_hash,dados,motivo,estado,criado_em,expira_em,autorizador,motivo_decisao,decidido_em)"
            + " SELECT"
            + " ?,estabelecimento_id,turno_id,solicitante,credencial_versao,tipo,entidade_id,requisicao_hash,dados,motivo,estado,clock_timestamp()-interval"
            + " '20 minutes',clock_timestamp()-interval '10"
            + " minutes',autorizador,motivo_decisao,clock_timestamp()-interval '19 minutes' FROM"
            + " autorizacoes_financeiras WHERE id=?",
        expired,
        source);
    var denied =
        new Desconto(d.tipo(), d.valorCentavos(), null, d.motivo(), d.revisaoConta(), expired);
    assertEquals(
        403,
        code(
            () ->
                pagamentos.descontar(
                    a, t.id(), comanda.id(), c1, cash1, UUID.randomUUID(), denied)));
    assertEquals(0, account().descontosCentavos());
  }

  @Test
  void autorizacaoDeColegaNaoPodeSerUsada() {
    var t = team(open());
    order();
    var d = new Desconto("VALOR", 2000L, null, "Revisao", account().revisao(), null);
    var id = approve(t, "DESCONTO", comanda.id(), d);
    var denied = new Desconto(d.tipo(), d.valorCentavos(), null, d.motivo(), d.revisaoConta(), id);
    assertEquals(
        403,
        code(
            () ->
                pagamentos.descontar(
                    a, t.id(), comanda.id(), c2, cash2, UUID.randomUUID(), denied)));
    assertEquals("APROVADA", aprovacoes.buscar(a, id).estado());
  }

  @Test
  void revogarPermissaoDoSolicitanteInvalidaAutorizacao() {
    var t = open();
    order();
    var d = new Desconto("VALOR", 2000L, null, "Revisao", account().revisao(), null);
    var id = approve(t, "DESCONTO", comanda.id(), d);
    grant(cash1, "COMANDA_DESCONTO_APLICAR", false);
    var denied = new Desconto(d.tipo(), d.valorCentavos(), null, d.motivo(), d.revisaoConta(), id);
    assertEquals(
        403,
        code(
            () ->
                pagamentos.descontar(
                    a, t.id(), comanda.id(), c1, cash1, UUID.randomUUID(), denied)));
    assertEquals("APROVADA", aprovacoes.buscar(a, id).estado());
  }

  @Test
  void reaberturaRetentavelPodeSerConsultadaSomentePeloAutorAdmin() {
    var t = open();
    close(t);
    var key = UUID.randomUUID();
    var d = new Reabrir("Revisao autorizada", caixas.turno(a, t.id()).versao());
    var result = fechamento.reabrir(a, t.id(), admin, key, d);
    assertEquals(
        result.id().toString(),
        ((com.fasterxml.jackson.databind.JsonNode)
                caixa.consultarOperacaoAdmin(a, key, admin).get("resultado"))
            .path("id")
            .asText());
    assertEquals(403, code(() -> caixa.consultarOperacaoAdmin(a, key, gerente)));
  }

  @Test
  void caixaSemTurnoRetorna204SemJsonVazio() throws Exception {
    mvc.perform(
            get("/api/v1/operacional/estabelecimentos/" + a + "/caixa/atual")
                .with(authentication(c1))
                .header("X-Operador-Vinculo", cash1))
        .andExpect(status().isNoContent());
    open();
    mvc.perform(
            get("/api/v1/operacional/estabelecimentos/" + a + "/caixa/atual")
                .with(authentication(c1))
                .header("X-Operador-Vinculo", cash1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estado").value("ABERTO"));
  }
}
