package com.kitchentable.caixa;

import static com.kitchentable.caixa.CaixaDtos.*;
import static com.kitchentable.cardapio.CardapioRepository.erro;

import com.kitchentable.pagamentos.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CaixaRepository {
  private final JdbcTemplate j;
  private final OperacaoFinanceiraRepository ops;

  public CaixaRepository(JdbcTemplate j, OperacaoFinanceiraRepository o) {
    this.j = j;
    ops = o;
  }

  public JdbcTemplate jdbc() {
    return j;
  }

  public Politica politica(UUID u, boolean exclusiva) {
    var rows =
        j.queryForList(
            "SELECT * FROM configuracoes_caixa WHERE estabelecimento_id=?"
                + (exclusiva ? " FOR UPDATE" : " FOR SHARE"),
            u);
    if (rows.isEmpty()) return new Politica(false, null, null, null, null, null, 0);
    var r = rows.get(0);
    return new Politica(
        true,
        (Integer) r.get("limite_operadores"),
        n(r, "desconto_limite_centavos"),
        (Integer) r.get("desconto_limite_bps"),
        n(r, "sangria_limite_centavos"),
        n(r, "divergencia_limite_centavos"),
        n(r, "versao"));
  }

  public Politica configurada(UUID u, boolean exclusiva) {
    var p = politica(u, exclusiva);
    if (!p.configurada())
      throw erro(409, "O proprietario precisa configurar os limites do caixa antes da abertura");
    return p;
  }

  public Map<String, Object> turnoRow(UUID u, UUID id, boolean exclusivo) {
    var rows =
        j.queryForList(
            "SELECT * FROM turnos_caixa WHERE estabelecimento_id=? AND id=? FOR "
                + (exclusivo ? "UPDATE" : "SHARE"),
            u,
            id);
    if (rows.isEmpty()) throw erro(404, "Turno nao encontrado nesta unidade");
    return rows.get(0);
  }

  public void aberto(UUID u, UUID id) {
    if (!"ABERTO".equals(turnoRow(u, id, true).get("estado")))
      throw erro(409, "O turno esta fechado");
  }

  public void versao(UUID u, UUID id, Long expected) {
    if (expected == null || n(turnoRow(u, id, true), "versao") != expected)
      throw erro(409, "O caixa mudou. Atualize a situacao e confira os valores");
  }

  public void membro(UUID u, UUID id, UUID actor) {
    if (j.queryForList(
            "SELECT vinculo_id FROM operadores_caixa WHERE estabelecimento_id=? AND turno_id=? AND"
                + " vinculo_id=? AND ativo",
            u,
            id,
            actor)
        .isEmpty()) throw erro(403, "Voce nao esta entre os operadores autorizados deste turno");
  }

  public void atualizar(UUID u, UUID id) {
    j.update("UPDATE turnos_caixa SET versao=versao+1 WHERE estabelecimento_id=? AND id=?", u, id);
  }

  public List<Operador> equipe(UUID u, UUID id) {
    return j.query(
        "SELECT o.vinculo_id,COALESCE(ue.nome_exibicao,usr.nome),o.ativo FROM operadores_caixa o"
            + " JOIN usuarios_estabelecimentos ue ON ue.id=o.vinculo_id AND"
            + " ue.estabelecimento_id=o.estabelecimento_id JOIN usuarios usr ON"
            + " usr.id=ue.usuario_id WHERE o.estabelecimento_id=? AND o.turno_id=? ORDER BY"
            + " 2,o.vinculo_id",
        (r, n) -> new Operador(r.getObject(1, UUID.class), r.getString(2), r.getBoolean(3)),
        u,
        id);
  }

  public Turno turno(UUID u, UUID id) {
    var rows =
        j.queryForList(
            "SELECT t.*,COALESCE(r.nome_exibicao,ur.nome) AS"
                + " responsavel,COALESCE(a.nome_exibicao,ua.nome) AS autor FROM turnos_caixa t JOIN"
                + " usuarios_estabelecimentos r ON r.id=t.responsavel_id AND"
                + " r.estabelecimento_id=t.estabelecimento_id JOIN usuarios ur ON"
                + " ur.id=r.usuario_id JOIN usuarios_estabelecimentos a ON a.id=t.aberto_por AND"
                + " a.estabelecimento_id=t.estabelecimento_id JOIN usuarios ua ON"
                + " ua.id=a.usuario_id WHERE t.estabelecimento_id=? AND t.id=?",
            u,
            id);
    if (rows.isEmpty()) throw erro(404, "Turno nao encontrado nesta unidade");
    var r = rows.get(0);
    return new Turno(
        id,
        (String) r.get("estado"),
        (UUID) r.get("responsavel_id"),
        (String) r.get("responsavel"),
        (UUID) r.get("aberto_por"),
        (String) r.get("autor"),
        instant(r, "aberto_em"),
        instant(r, "fechado_em"),
        n(r, "valor_inicial_centavos"),
        n(r, "versao"),
        ((Number) r.get("ciclo")).intValue(),
        equipe(u, id));
  }

  public List<Turno> turnos(UUID u) {
    return j
        .query(
            "SELECT id FROM turnos_caixa WHERE estabelecimento_id=? ORDER BY aberto_em DESC,id",
            (r, n) -> r.getObject(1, UUID.class),
            u)
        .stream()
        .map(id -> turno(u, id))
        .toList();
  }

  private List<PagamentoDtos.Lancamento> buscarLancamentos(String condition, Object... args) {
    return j.query(
        "SELECT l.*,EXISTS(SELECT 1 FROM confirmacoes_estorno c WHERE c.estorno_id=l.id AND"
            + " c.estabelecimento_id=l.estabelecimento_id) AS confirmado FROM"
            + " lancamentos_financeiros l WHERE "
            + condition
            + " ORDER BY ocorrido_em,id",
        (r, n) ->
            new PagamentoDtos.Lancamento(
                r.getObject("id", UUID.class),
                r.getObject("turno_id", UUID.class),
                r.getObject("comanda_id", UUID.class),
                r.getString("tipo"),
                r.getString("natureza"),
                r.getString("meio"),
                r.getLong("valor_centavos"),
                r.getObject("recebido_centavos", Long.class),
                r.getLong("troco_centavos"),
                r.getObject("original_id", UUID.class),
                r.getObject("executor", UUID.class),
                r.getString("executor_nome"),
                r.getString("motivo"),
                r.getObject("autorizacao_id", UUID.class),
                r.getTimestamp("ocorrido_em").toInstant(),
                r.getString("tipo").equals("ESTORNO")
                    && "ESTORNO_PAGAMENTO".equals(r.getString("natureza"))
                    && !"DINHEIRO".equals(r.getString("meio"))
                    && !r.getBoolean("confirmado")),
        args);
  }

  public List<PagamentoDtos.Lancamento> lancamentos(UUID u, UUID turno, UUID comanda) {
    return buscarLancamentos(
        "l.estabelecimento_id=? AND " + (turno != null ? "l.turno_id=?" : "l.comanda_id=?"),
        u,
        turno != null ? turno : comanda);
  }

  public List<PagamentoDtos.Lancamento> periodo(UUID u, Instant from, Instant to, UUID executor) {
    var params =
        new ArrayList<Object>(
            List.of(u, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to)));
    if (executor != null) params.add(executor);
    return buscarLancamentos(
        "l.estabelecimento_id=? AND l.ocorrido_em>=? AND l.ocorrido_em<?"
            + (executor == null ? "" : " AND l.executor=?"),
        params.toArray());
  }

  public PagamentoDtos.Lancamento lancamento(UUID u, UUID id) {
    var list = buscarLancamentos("l.estabelecimento_id=? AND l.id=?", u, id);
    if (list.isEmpty()) throw erro(404, "Lancamento nao encontrado nesta unidade");
    return list.get(0);
  }

  public PagamentoDtos.Lancamento inserir(
      UUID u,
      UUID turno,
      UUID comanda,
      String tipo,
      String meio,
      long value,
      Long recebido,
      UUID original,
      com.kitchentable.funcionarios.AcessoAtendimentoService.Ator actor,
      String reason,
      UUID approval) {
    return inserir(
        u, turno, comanda, tipo, meio, value, recebido, original, actor, reason, approval, null);
  }

  public PagamentoDtos.Lancamento inserir(
      UUID u,
      UUID turno,
      UUID comanda,
      String tipo,
      String meio,
      long value,
      Long recebido,
      UUID original,
      com.kitchentable.funcionarios.AcessoAtendimentoService.Ator actor,
      String reason,
      UUID approval,
      String natureza) {
    UUID id = UUID.randomUUID();
    j.update(
        "INSERT INTO"
            + " lancamentos_financeiros(id,estabelecimento_id,turno_id,comanda_id,tipo,meio,valor_centavos,recebido_centavos,original_id,executor,executor_nome,motivo,autorizacao_id,natureza)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        u,
        turno,
        comanda,
        tipo,
        meio,
        value,
        recebido,
        original,
        actor.vinculo(),
        actor.nome(),
        reason,
        approval,
        natureza);
    atualizar(u, turno);
    return lancamento(u, id);
  }

  public Resumo resumo(UUID u, UUID id) {
    var t = turno(u, id);
    var list = lancamentos(u, id, null);
    long recebido = 0, pago = 0, troco = 0, sangria = 0, reforco = 0, refund = 0, discount = 0;
    var totals = new ArrayList<MeioResumo>();
    for (var l : list) {
      if (l.tipo().equals("PAGAMENTO") && "DINHEIRO".equals(l.meio())) {
        recebido = Math.addExact(recebido, l.recebidoCentavos());
        pago = Math.addExact(pago, l.valorCentavos());
        troco = Math.addExact(troco, l.trocoCentavos());
      }
      if (l.tipo().equals("SANGRIA")) sangria = Math.addExact(sangria, l.valorCentavos());
      if (l.tipo().equals("REFORCO")) reforco = Math.addExact(reforco, l.valorCentavos());
      if (l.tipo().equals("ESTORNO") && "DINHEIRO".equals(l.meio()))
        refund = Math.addExact(refund, l.valorCentavos());
      if (l.tipo().equals("DESCONTO")) discount = Math.addExact(discount, l.valorCentavos());
      if (l.tipo().equals("DESCONTO_ESTORNO"))
        discount = Math.subtractExact(discount, l.valorCentavos());
    }
    for (String method : List.of("DINHEIRO", "PIX", "DEBITO", "CREDITO")) {
      long p = 0, e = 0, count = 0;
      for (var l : list)
        if (method.equals(l.meio())) {
          if (l.tipo().equals("PAGAMENTO")) {
            p = Math.addExact(p, l.valorCentavos());
            count++;
          }
          if (l.tipo().equals("ESTORNO")) e = Math.addExact(e, l.valorCentavos());
        }
      totals.add(new MeioResumo(method, p, e, Math.subtractExact(p, e), count));
    }
    long expected =
        Math.subtractExact(
            Math.addExact(Math.addExact(t.valorInicialCentavos(), pago), reforco),
            Math.addExact(sangria, refund));
    if (expected < 0) throw erro(409, "Inconsistencia no saldo fisico do caixa");
    long cancels =
        j.queryForObject(
            "SELECT count(*) FROM pedidos p WHERE p.estabelecimento_id=? AND p.aberto_em>=? AND"
                + " (?::timestamptz IS NULL OR p.aberto_em<=?) AND EXISTS(SELECT 1 FROM"
                + " itens_preparo i WHERE i.pedido_id=p.id) AND NOT EXISTS(SELECT 1 FROM"
                + " itens_preparo i WHERE i.pedido_id=p.id AND i.estado<>'CANCELADO')",
            Long.class,
            u,
            java.sql.Timestamp.from(t.abertoEm()),
            t.fechadoEm() == null ? null : java.sql.Timestamp.from(t.fechadoEm()),
            t.fechadoEm() == null ? null : java.sql.Timestamp.from(t.fechadoEm()));
    return new Resumo(
        t,
        recebido,
        pago,
        troco,
        sangria,
        reforco,
        refund,
        expected,
        discount,
        Dinheiro.exato(
            j.queryForObject(
                "SELECT COALESCE(sum(consumo_centavos-desconto_centavos),0) FROM quitacoes_comanda"
                    + " WHERE estabelecimento_id=? AND turno_id=?",
                java.math.BigDecimal.class,
                u,
                id)),
        cancels,
        totals,
        list.stream().filter(PagamentoDtos.Lancamento::confirmacaoExternaPendente).count(),
        list);
  }

  public Conferencia conferencia(UUID u, UUID id) {
    var rows =
        j.queryForList(
            "SELECT * FROM conferencias_caixa WHERE estabelecimento_id=? AND id=?", u, id);
    if (rows.isEmpty()) throw erro(404, "Conferencia nao encontrada nesta unidade");
    var r = rows.get(0);
    return new Conferencia(
        id,
        (UUID) r.get("turno_id"),
        n(r, "versao_turno"),
        n(r, "esperado_centavos"),
        n(r, "informado_centavos"),
        n(r, "diferenca_centavos"),
        (String) r.get("motivo"),
        (UUID) r.get("executor"),
        instant(r, "criado_em"));
  }

  public List<Fechamento> fechamentos(UUID u, UUID turno) {
    return j.query(
        "SELECT * FROM relatorios_fechamento WHERE estabelecimento_id=? AND turno_id=? ORDER BY"
            + " ciclo",
        (r, n) ->
            new Fechamento(
                r.getObject("id", UUID.class),
                turno,
                r.getInt("ciclo"),
                r.getTimestamp("criado_em").toInstant(),
                ops.ler(r.getString("dados"), com.fasterxml.jackson.databind.JsonNode.class)),
        u,
        turno);
  }

  public Resumo resumoVisivel(UUID u, UUID turno, UUID actor, boolean detalhes) {
    var r = resumo(u, turno);
    return new Resumo(
        r.turno(),
        r.dinheiroRecebidoCentavos(),
        r.pagamentosDinheiroCentavos(),
        r.trocoCentavos(),
        r.sangriasCentavos(),
        r.reforcosCentavos(),
        r.estornosDinheiroCentavos(),
        r.dinheiroEsperadoCentavos(),
        r.descontosCentavos(),
        r.vendasLiquidadasCentavos(),
        r.cancelamentosOperacionais(),
        r.meios(),
        r.pendenciasExternas(),
        detalhes
            ? r.lancamentos()
            : r.lancamentos().stream().filter(l -> actor.equals(l.executor())).toList());
  }

  public static Fechamento visivel(Fechamento f, boolean detalhes) {
    if (detalhes) return f;
    com.fasterxml.jackson.databind.node.ObjectNode data = f.dados().deepCopy();
    data.remove(List.of("executor", "nomeExecutor"));
    if (data.path("resumo").isObject()) {
      var resumo = (com.fasterxml.jackson.databind.node.ObjectNode) data.path("resumo");
      resumo.remove("lancamentos");
      if (resumo.path("turno").isObject())
        ((com.fasterxml.jackson.databind.node.ObjectNode) resumo.path("turno"))
            .remove(List.of("operadores", "abertoPor", "nomeAbertura"));
    }
    if (data.path("conferencia").isObject())
      ((com.fasterxml.jackson.databind.node.ObjectNode) data.path("conferencia"))
          .remove(List.of("executor", "motivo"));
    return new Fechamento(f.id(), f.turnoId(), f.ciclo(), f.criadoEm(), data);
  }

  public static long n(Map<String, Object> r, String key) {
    return Dinheiro.exato(r.get(key));
  }

  public static Instant instant(Map<String, Object> r, String key) {
    return r.get(key) == null ? null : ((java.sql.Timestamp) r.get(key)).toInstant();
  }
}
