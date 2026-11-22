package com.kitchentable.relatorios;

import static com.kitchentable.cardapio.CardapioRepository.erro;

import com.kitchentable.caixa.*;
import com.kitchentable.funcionarios.*;
import com.kitchentable.pagamentos.*;
import java.time.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Snapshot consistente; valores monetarios em strings de centavos no contrato JSON. */
@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class RelatorioFinanceiroService {
  private final CaixaRepository repo;
  private final AcessoAtendimentoService acesso;
  private final OperacaoFinanceiraRepository ops;

  public RelatorioFinanceiroService(
      CaixaRepository r, AcessoAtendimentoService a, OperacaoFinanceiraRepository o) {
    repo = r;
    acesso = a;
    ops = o;
  }

  private void intervalo(Instant from, Instant to) {
    if (from == null
        || to == null
        || !from.isBefore(to)
        || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0)
      throw erro(400, "Informe inicio anterior ao fim, em um periodo de ate 366 dias");
  }

  private boolean detalhe(UUID u, UUID actor, UUID filter) {
    boolean allowed = acesso.vinculoPossui(u, actor, "FUNCIONARIO_HISTORICO_FINANCEIRO_CONSULTAR");
    if (filter != null) {
      if (!allowed) throw erro(403, "Historico individual exige permissao financeira especifica");
      if (repo.jdbc()
          .queryForList(
              "SELECT id FROM usuarios_estabelecimentos WHERE estabelecimento_id=? AND id=?",
              u,
              filter)
          .isEmpty()) throw erro(404, "Funcionario nao encontrado nesta unidade");
    }
    return allowed;
  }

  public Map<String, Object> operacional(
      UUID u, Authentication a, UUID expected, Instant from, Instant to, UUID funcionario) {
    var actor = acesso.operador(u, a, expected, "RELATORIO_FINANCEIRO_CONSULTAR");
    intervalo(from, to);
    return consultar(u, from, to, funcionario, detalhe(u, actor.vinculo(), funcionario));
  }

  public Map<String, Object> administrativo(
      UUID u, Authentication a, Instant from, Instant to, UUID funcionario) {
    var actor = acesso.administrador(u, a, false, "RELATORIO_FINANCEIRO_CONSULTAR");
    intervalo(from, to);
    return consultar(u, from, to, funcionario, detalhe(u, actor.vinculo(), funcionario));
  }

  public List<CaixaDtos.Operador> funcionarios(UUID u, Authentication a, UUID expected) {
    if (expected == null)
      acesso.administrador(u, a, false, "FUNCIONARIO_HISTORICO_FINANCEIRO_CONSULTAR");
    else acesso.operador(u, a, expected, "FUNCIONARIO_HISTORICO_FINANCEIRO_CONSULTAR");
    return repo.jdbc()
        .query(
            "SELECT ue.id,COALESCE(ue.nome_exibicao,usr.nome),ue.ativo FROM"
                + " usuarios_estabelecimentos ue JOIN usuarios usr ON usr.id=ue.usuario_id WHERE"
                + " ue.estabelecimento_id=? ORDER BY 2,ue.id",
            (r, n) ->
                new CaixaDtos.Operador(r.getObject(1, UUID.class), r.getString(2), r.getBoolean(3)),
            u);
  }

  public Map<String, Object> consolidado(
      List<UUID> unidades, Authentication a, Instant from, Instant to) {
    intervalo(from, to);
    if (unidades == null
        || unidades.isEmpty()
        || new HashSet<>(unidades).size() != unidades.size()
        || unidades.size() > 100) throw erro(400, "Selecione unidades unicas e autorizadas");
    var reports = new ArrayList<Map<String, Object>>();
    long sales = 0;
    for (UUID u : new TreeSet<>(unidades)) {
      var actor = acesso.administrador(u, a, false, "RELATORIO_FINANCEIRO_CONSULTAR");
      boolean owner =
          Boolean.TRUE.equals(
              repo.jdbc()
                  .queryForObject(
                      "SELECT EXISTS(SELECT 1 FROM usuarios_estabelecimentos ue JOIN funcoes f ON"
                          + " f.id=ue.funcao_id WHERE ue.id=? AND ue.estabelecimento_id=? AND"
                          + " f.codigo='PROPRIETARIO')",
                      Boolean.class,
                      actor.vinculo(),
                      u));
      if (!owner) throw erro(403, "Consolidacao exige autoridade de proprietario em cada unidade");
      var r = consultar(u, from, to, null, true);
      reports.add(r);
      sales = Math.addExact(sales, Long.parseLong(r.get("vendasLiquidadasCentavos").toString()));
    }
    return Map.of(
        "inicio",
        from,
        "fimExclusivo",
        to,
        "unidades",
        reports,
        "vendasLiquidadasCentavos",
        Long.toString(sales));
  }

  private Map<String, Object> consultar(
      UUID u, Instant from, Instant to, UUID funcionario, boolean details) {
    var entries = repo.periodo(u, from, to, funcionario);
    var args =
        new ArrayList<Object>(
            List.of(u, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to)));
    if (funcionario != null) args.add(funcionario);
    String actorSql = funcionario == null ? "" : " AND executor=?";
    var sales =
        repo.jdbc()
            .queryForMap(
                "SELECT count(*) AS quantidade,COALESCE(sum(consumo_centavos-desconto_centavos),0)"
                    + " AS total FROM quitacoes_comanda WHERE estabelecimento_id=? AND criado_em>=?"
                    + " AND criado_em<?"
                    + actorSql,
                args.toArray());
    var methods = new ArrayList<CaixaDtos.MeioResumo>();
    long discount = 0, sangria = 0, reforco = 0, refund = 0, correction = 0, returned = 0;
    for (var l : entries) {
      switch (l.tipo()) {
        case "DESCONTO" -> discount = Math.addExact(discount, l.valorCentavos());
        case "DESCONTO_ESTORNO" -> discount = Math.subtractExact(discount, l.valorCentavos());
        case "SANGRIA" -> sangria = Math.addExact(sangria, l.valorCentavos());
        case "REFORCO" -> reforco = Math.addExact(reforco, l.valorCentavos());
        case "ESTORNO" -> {
          refund = Math.addExact(refund, l.valorCentavos());
          if ("CORRECAO_LANCAMENTO".equals(l.natureza()))
            correction = Math.addExact(correction, l.valorCentavos());
          else returned = Math.addExact(returned, l.valorCentavos());
        }
        default -> {}
      }
    }
    for (String method : List.of("DINHEIRO", "PIX", "DEBITO", "CREDITO")) {
      long p = 0, r = 0, count = 0;
      for (var l : entries)
        if (method.equals(l.meio())) {
          if (l.tipo().equals("PAGAMENTO")) {
            p = Math.addExact(p, l.valorCentavos());
            count++;
          }
          if (l.tipo().equals("ESTORNO")) r = Math.addExact(r, l.valorCentavos());
        }
      methods.add(new CaixaDtos.MeioResumo(method, p, r, Math.subtractExact(p, r), count));
    }
    var conferences =
        repo
            .jdbc()
            .query(
                "SELECT id FROM conferencias_caixa WHERE estabelecimento_id=? AND criado_em>=? AND"
                    + " criado_em<?"
                    + actorSql
                    + " ORDER BY criado_em,id",
                (r, n) -> r.getObject(1, UUID.class),
                args.toArray())
            .stream()
            .map(id -> repo.conferencia(u, id))
            .toList();
    var reports =
        repo
            .jdbc()
            .queryForList(
                "SELECT id,turno_id,ciclo,dados,criado_em FROM relatorios_fechamento WHERE"
                    + " estabelecimento_id=? AND criado_em>=? AND criado_em<?"
                    + actorSql
                    + " ORDER BY criado_em,id",
                args.toArray())
            .stream()
            .map(
                r ->
                    new CaixaDtos.Fechamento(
                        (UUID) r.get("id"),
                        (UUID) r.get("turno_id"),
                        ((Number) r.get("ciclo")).intValue(),
                        CaixaRepository.instant(r, "criado_em"),
                        ops.ler(
                            r.get("dados").toString(),
                            com.fasterxml.jackson.databind.JsonNode.class)))
            .toList();
    var auditArgs =
        new ArrayList<Object>(
            List.of(u, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to)));
    String auditActor = "";
    if (funcionario != null) {
      auditActor =
          " AND (dados_evento->>'vinculoExecutor'=? OR dados_evento->'depois'->>'solicitante'=?)";
      auditArgs.add(funcionario.toString());
      auditArgs.add(funcionario.toString());
    }
    var events =
        details
            ? repo.jdbc()
                .queryForList(
                    "SELECT"
                        + " tipo_evento,entidade_tipo,entidade_id,ocorrido_em,dados_evento->>'executorNome'"
                        + " AS executor_nome,dados_evento->'depois'->>'motivo' AS motivo FROM"
                        + " auditoria_eventos WHERE estabelecimento_id=? AND ocorrido_em>=? AND"
                        + " ocorrido_em<? AND entidade_tipo IN"
                        + " ('CAIXA','LANCAMENTO_FINANCEIRO','CONTA_FINANCEIRA','FECHAMENTO_CAIXA','AUTORIZACAO_FINANCEIRA')"
                        + auditActor
                        + " ORDER BY ocorrido_em,id",
                    auditArgs.toArray())
            : List.of();
    var cancellations =
        repo.jdbc()
            .queryForMap(
                "SELECT count(*) FILTER(WHERE"
                    + " COALESCE(dados_evento->'depois'->>'substituto','')='') AS"
                    + " cancelamentos,count(*) FILTER(WHERE"
                    + " COALESCE(dados_evento->'depois'->>'substituto','')<>'') AS substituicoes"
                    + " FROM auditoria_eventos WHERE estabelecimento_id=? AND ocorrido_em>=? AND"
                    + " ocorrido_em<? AND tipo_evento='PEDIDO_CANCELADO_AUTORIZADO'",
                u,
                java.sql.Timestamp.from(from),
                java.sql.Timestamp.from(to));
    var unit =
        repo.jdbc().queryForMap("SELECT nome,fuso_horario FROM estabelecimentos WHERE id=?", u);
    var result = new LinkedHashMap<String, Object>();
    result.put("estabelecimentoId", u);
    result.put("estabelecimento", unit.get("nome"));
    result.put("fusoHorario", unit.get("fuso_horario"));
    result.put("inicio", from);
    result.put("fimExclusivo", to);
    result.put("funcionarioId", funcionario);
    result.put("detalhesFuncionariosPermitidos", details);
    result.put("vendasLiquidadasCentavos", Long.toString(Dinheiro.exato(sales.get("total"))));
    result.put("comandasLiquidadas", Dinheiro.exato(sales.get("quantidade")));
    result.put(
        "canais",
        repo.jdbc()
            .query(
                "SELECT COALESCE(e.tipo,'COMANDA') AS canal,count(*) AS"
                    + " quantidade,sum(q.consumo_centavos-q.desconto_centavos)::text AS total FROM"
                    + " quitacoes_comanda q LEFT JOIN atendimentos_externos e ON"
                    + " e.comanda_id=q.comanda_id AND e.estabelecimento_id=q.estabelecimento_id"
                    + " WHERE q.estabelecimento_id=? AND q.criado_em>=? AND q.criado_em<?"
                    + (funcionario == null ? "" : " AND q.executor=?")
                    + " GROUP BY COALESCE(e.tipo,'COMANDA') ORDER BY canal",
                (r, n) ->
                    Map.of(
                        "canal",
                        r.getString(1),
                        "quantidade",
                        r.getLong(2),
                        "vendasLiquidadasCentavos",
                        r.getString(3)),
                args.toArray()));
    result.put("meios", methods);
    result.put("descontosLiquidosCentavos", Long.toString(discount));
    result.put("sangriasCentavos", Long.toString(sangria));
    result.put("reforcosCentavos", Long.toString(reforco));
    result.put("estornosCentavos", Long.toString(refund));
    result.put("correcoesRegistroCentavos", Long.toString(correction));
    result.put("estornosPagamentoCentavos", Long.toString(returned));
    result.put(
        "estornosExternosPendentes",
        entries.stream().filter(PagamentoDtos.Lancamento::confirmacaoExternaPendente).count());
    result.put("cancelamentosOperacionaisNaUnidade", cancellations.get("cancelamentos"));
    result.put("substituicoesNaUnidade", cancellations.get("substituicoes"));
    result.put(
        "turnos",
        repo.turnos(u).stream()
            .filter(
                t ->
                    t.abertoEm().isBefore(to)
                        && (t.fechadoEm() == null || !t.fechadoEm().isBefore(from)))
            .map(
                t ->
                    details
                        ? (Object) t
                        : Map.of(
                            "id",
                            t.id(),
                            "estado",
                            t.estado(),
                            "ciclo",
                            t.ciclo(),
                            "abertoEm",
                            t.abertoEm()))
            .toList());
    result.put(
        "conferencias",
        details
            ? conferences
            : conferences.stream()
                .map(
                    c ->
                        Map.of(
                            "id",
                            c.id(),
                            "turnoId",
                            c.turnoId(),
                            "esperadoCentavos",
                            Long.toString(c.esperadoCentavos()),
                            "informadoCentavos",
                            Long.toString(c.informadoCentavos()),
                            "diferencaCentavos",
                            Long.toString(c.diferencaCentavos()),
                            "criadoEm",
                            c.criadoEm()))
                .toList());
    result.put(
        "relatoriosFechamento",
        reports.stream().map(f -> CaixaRepository.visivel(f, details)).toList());
    result.put("lancamentos", details ? entries : List.of());
    result.put("eventosFinanceiros", events);
    return result;
  }
}
