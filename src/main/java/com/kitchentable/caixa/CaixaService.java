package com.kitchentable.caixa;

import static com.kitchentable.caixa.CaixaDtos.*;
import static com.kitchentable.cardapio.CardapioRepository.erro;

import com.kitchentable.funcionarios.*;
import com.kitchentable.pagamentos.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CaixaService {
  private final CaixaRepository repo;
  private final OperacaoFinanceiraRepository ops;
  private final AcessoAtendimentoService acesso;
  private final AcessoCaixaService caixas;
  private final AuditoriaAdministrativaService audit;
  private final AutorizacaoFinanceiraService auth;

  public CaixaService(
      CaixaRepository r,
      OperacaoFinanceiraRepository o,
      AcessoAtendimentoService a,
      AcessoCaixaService c,
      AuditoriaAdministrativaService au,
      AutorizacaoFinanceiraService approval) {
    repo = r;
    ops = o;
    acesso = a;
    caixas = c;
    audit = au;
    auth = approval;
  }

  public Map<String, Boolean> capacidadesAdmin(UUID u, Authentication a) {
    var actor = acesso.administrador(u, a, false, "ESTABELECIMENTO_VISUALIZAR");
    var result = new LinkedHashMap<String, Boolean>();
    for (var entry :
        Map.of(
                "configurar",
                "CAIXA_POLITICA_CONFIGURAR",
                "autorizar",
                "CAIXA_AUTORIZACAO_GERENCIAR",
                "operadores",
                "CAIXA_OPERADORES_CONFIGURAR",
                "reabrir",
                "CAIXA_REABRIR",
                "historico",
                "CAIXA_HISTORICO_CONSULTAR",
                "relatorios",
                "RELATORIO_FINANCEIRO_CONSULTAR",
                "contas",
                "CONTA_CONSULTAR",
                "historicoIndividual",
                "FUNCIONARIO_HISTORICO_FINANCEIRO_CONSULTAR")
            .entrySet())
      result.put(entry.getKey(), acesso.vinculoPossui(u, actor.vinculo(), entry.getValue()));
    return result;
  }

  public Politica politicaOperacional(UUID u, Authentication a, UUID expected) {
    caixas.consulta(u, a, expected);
    return repo.politica(u, false);
  }

  public Turno atual(UUID u, Authentication a, UUID expected) {
    caixas.consulta(u, a, expected);
    var ids =
        repo.jdbc()
            .queryForList(
                "SELECT id FROM turnos_caixa WHERE estabelecimento_id=? AND estado='ABERTO'",
                UUID.class,
                u);
    return ids.isEmpty() ? null : repo.turno(u, ids.get(0));
  }

  public Map<String, Object> consultarOperacao(UUID u, UUID key, Authentication a, UUID expected) {
    var actor = acesso.operador(u, a, expected, "CAIXA_OPERAR");
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT tipo,resultado::text AS resultado FROM operacoes_financeiras WHERE"
                    + " estabelecimento_id=? AND chave=? AND executor=?",
                u,
                key,
                actor.vinculo());
    if (rows.isEmpty())
      throw erro(404, "Registro desta tentativa ainda nao encontrado no servidor");
    var r = rows.get(0);
    var result =
        ops.ler((String) r.get("resultado"), com.fasterxml.jackson.databind.JsonNode.class);
    String type = (String) r.get("tipo");
    String cap =
        switch (type) {
          case "ABERTURA" -> "CAIXA_ABRIR";
          case "PAGAMENTO" -> "PAGAMENTO_REGISTRAR";
          case "DESCONTO", "DESCONTO_ESTORNO" -> "COMANDA_DESCONTO_APLICAR";
          case "ESTORNO", "CONFIRMAR_ESTORNO" -> "PAGAMENTO_ESTORNAR";
          case "MOVIMENTO" ->
              result.path("tipo").asText().equals("SANGRIA")
                  ? "CAIXA_SANGRIA_REGISTRAR"
                  : "CAIXA_SUPRIMENTO_REGISTRAR";
          case "CONFERENCIA" -> "CAIXA_CONFERIR";
          case "FECHAMENTO" -> "CAIXA_FECHAR";
          case "QUITACAO" -> "COMANDA_FINANCEIRO_ENCERRAR";
          case "AUTORIZACAO" ->
              AutorizacaoFinanceiraService.capacidade(result.path("tipo").asText());
          default -> throw erro(403, "Consulta desta operacao exige acesso administrativo");
        };
    acesso.operador(u, a, expected, cap);
    return Map.of("tipo", type, "resultado", result, "registrada", true);
  }

  public Map<String, Object> consultarOperacaoAdmin(UUID u, UUID key, Authentication a) {
    var actor = acesso.administrador(u, a, false, "CAIXA_REABRIR");
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT resultado::text AS resultado FROM operacoes_financeiras WHERE"
                    + " estabelecimento_id=? AND chave=? AND executor=? AND tipo='REABERTURA'",
                u,
                key,
                actor.vinculo());
    if (rows.isEmpty())
      throw erro(404, "Registro desta tentativa ainda nao encontrado no servidor");
    return Map.of(
        "tipo",
        "REABERTURA",
        "registrada",
        true,
        "resultado",
        ops.ler(
            (String) rows.get(0).get("resultado"), com.fasterxml.jackson.databind.JsonNode.class));
  }

  public Politica politica(UUID u, Authentication a) {
    acesso.administrador(u, a, false, "CAIXA_POLITICA_CONFIGURAR");
    return repo.politica(u, false);
  }

  public Politica configurar(UUID u, Authentication a, Configuracao d) {
    var actor = acesso.administrador(u, a, true, "CAIXA_POLITICA_CONFIGURAR");
    ops.serializar(u);
    var before = repo.politica(u, true);
    if (d.versao() == null || d.versao() != before.versao())
      throw erro(409, "Politica alterada. Atualize antes de configurar");
    if (d.descontoLimiteCentavos() == null
        || d.descontoLimiteBps() == null
        || d.sangriaLimiteCentavos() == null
        || d.divergenciaLimiteCentavos() == null
        || d.descontoLimiteBps() < 0
        || d.descontoLimiteBps() > 10000
        || d.limiteOperadores() != null && d.limiteOperadores() < 1)
      throw erro(400, "Configure os limites explicitamente");
    Dinheiro.valor(d.descontoLimiteCentavos(), true);
    Dinheiro.valor(d.sangriaLimiteCentavos(), true);
    Dinheiro.valor(d.divergenciaLimiteCentavos(), true);
    if (d.limiteOperadores() != null) {
      long count =
          repo.jdbc()
              .queryForObject(
                  "SELECT count(*) FROM operadores_caixa o JOIN turnos_caixa t ON t.id=o.turno_id"
                      + " AND t.estabelecimento_id=o.estabelecimento_id WHERE"
                      + " o.estabelecimento_id=? AND o.ativo AND t.estado='ABERTO'",
                  Long.class,
                  u);
      if (count > d.limiteOperadores())
        throw erro(409, "Remova operadores excedentes antes de reduzir o limite");
    }
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " configuracoes_caixa(estabelecimento_id,limite_operadores,desconto_limite_centavos,desconto_limite_bps,sangria_limite_centavos,divergencia_limite_centavos,atualizado_por,versao)"
                + " VALUES(?,?,?,?,?,?,?,1) ON CONFLICT(estabelecimento_id) DO UPDATE SET"
                + " limite_operadores=EXCLUDED.limite_operadores,desconto_limite_centavos=EXCLUDED.desconto_limite_centavos,desconto_limite_bps=EXCLUDED.desconto_limite_bps,sangria_limite_centavos=EXCLUDED.sangria_limite_centavos,divergencia_limite_centavos=EXCLUDED.divergencia_limite_centavos,atualizado_por=EXCLUDED.atualizado_por,atualizado_em=clock_timestamp(),versao=configuracoes_caixa.versao+1",
            u,
            d.limiteOperadores(),
            d.descontoLimiteCentavos(),
            d.descontoLimiteBps(),
            d.sangriaLimiteCentavos(),
            d.divergenciaLimiteCentavos(),
            actor.vinculo());
    var result = repo.politica(u, false);
    evento(u, actor, "CAIXA_POLITICA_CONFIGURADA", u, before, result);
    return result;
  }

  public Turno abrir(UUID u, Authentication a, UUID expected, UUID key, Abertura d) {
    var actor = caixas.operador(u, a, expected, "CAIXA_ABRIR", true);
    Dinheiro.valor(d.valorInicialCentavos(), true);
    ops.serializar(u);
    var prior = ops.repetir(u, key, actor.vinculo(), "ABERTURA", d, Turno.class);
    if (prior != null) return prior;
    var policy = repo.configurada(u, false);
    if (!repo.jdbc()
        .queryForList(
            "SELECT id FROM turnos_caixa WHERE estabelecimento_id=? AND estado='ABERTO'", u)
        .isEmpty()) throw erro(409, "Ja existe um turno aberto nesta unidade");
    caixas.candidato(u, d.responsavelId());
    var members = new LinkedHashSet<UUID>(List.of(actor.vinculo(), d.responsavelId()));
    if (policy.limiteOperadores() != null && members.size() > policy.limiteOperadores())
      throw erro(
          409,
          "A abertura excede o limite de operadores. Escolha o proprio responsavel ou revise a"
              + " configuracao");
    UUID id = UUID.randomUUID();
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " turnos_caixa(id,estabelecimento_id,responsavel_id,aberto_por,valor_inicial_centavos)"
                + " VALUES(?,?,?,?,?)",
            id,
            u,
            d.responsavelId(),
            actor.vinculo(),
            d.valorInicialCentavos());
    for (UUID member : members)
      repo.jdbc()
          .update(
              "INSERT INTO operadores_caixa(turno_id,estabelecimento_id,vinculo_id) VALUES(?,?,?)",
              id,
              u,
              member);
    repo.inserir(
        u,
        id,
        null,
        "ABERTURA",
        "DINHEIRO",
        d.valorInicialCentavos(),
        null,
        null,
        actor,
        null,
        null);
    var result = repo.turno(u, id);
    evento(u, actor, "CAIXA_ABERTO", id, null, result);
    ops.salvar(u, key, actor.vinculo(), "ABERTURA", d, result);
    return result;
  }

  public List<Turno> listar(UUID u, Authentication a, UUID expected) {
    acesso.operador(u, a, expected, "CAIXA_HISTORICO_CONSULTAR");
    return repo.turnos(u);
  }

  public List<Turno> listarAdmin(UUID u, Authentication a) {
    acesso.administrador(u, a, false, "CAIXA_HISTORICO_CONSULTAR");
    return repo.turnos(u);
  }

  public Resumo resumo(UUID u, UUID id, Authentication a, UUID expected) {
    caixas.consulta(u, a, expected);
    if (!"ABERTO".equals(repo.turnoRow(u, id, false).get("estado"))
        && !acesso.vinculoPossui(u, expected, "CAIXA_HISTORICO_CONSULTAR"))
      throw erro(403, "Historico de turno fechado exige permissao especifica");
    return repo.resumoVisivel(
        u,
        id,
        expected,
        acesso.vinculoPossui(u, expected, "FUNCIONARIO_HISTORICO_FINANCEIRO_CONSULTAR"));
  }

  public Resumo resumoAdmin(UUID u, UUID id, Authentication a) {
    var actor = acesso.administrador(u, a, false, "CAIXA_HISTORICO_CONSULTAR");
    repo.turnoRow(u, id, false);
    return repo.resumoVisivel(
        u,
        id,
        actor.vinculo(),
        acesso.vinculoPossui(u, actor.vinculo(), "FUNCIONARIO_HISTORICO_FINANCEIRO_CONSULTAR"));
  }

  public List<Operador> candidatosAdmin(UUID u, Authentication a) {
    acesso.administrador(u, a, false, "CAIXA_OPERADORES_CONFIGURAR");
    return candidatos(u);
  }

  public List<Operador> candidatosOperador(UUID u, Authentication a, UUID expected) {
    caixas.operador(u, a, expected, "CAIXA_OPERADORES_CONFIGURAR", false);
    return candidatos(u);
  }

  private List<Operador> candidatos(UUID u) {
    var all =
        repo.jdbc()
            .query(
                "SELECT ue.id,COALESCE(ue.nome_exibicao,usr.nome) AS nome FROM"
                    + " usuarios_estabelecimentos ue JOIN usuarios usr ON usr.id=ue.usuario_id JOIN"
                    + " credenciais_operacionais c ON c.vinculo_id=ue.id AND"
                    + " c.estabelecimento_id=ue.estabelecimento_id WHERE ue.estabelecimento_id=?"
                    + " AND ue.ativo AND usr.ativo AND NOT c.bloqueado ORDER BY nome,ue.id",
                (r, n) -> new Operador(r.getObject(1, UUID.class), r.getString(2), true),
                u);
    return all.stream()
        .filter(o -> acesso.vinculoPossui(u, o.vinculoId(), "CAIXA_OPERAR"))
        .toList();
  }

  public Turno equipeAdmin(UUID u, UUID id, Authentication a, Equipe d) {
    var actor = acesso.administrador(u, a, true, "CAIXA_OPERADORES_CONFIGURAR");
    return equipe(u, id, actor, d);
  }

  public Turno equipeOperador(UUID u, UUID id, Authentication a, UUID expected, Equipe d) {
    var actor = caixas.operador(u, a, expected, "CAIXA_OPERADORES_CONFIGURAR", true);
    ops.serializar(u);
    repo.membro(u, id, actor.vinculo());
    return equipe(u, id, actor, d);
  }

  private Turno equipe(UUID u, UUID id, AcessoAtendimentoService.Ator actor, Equipe d) {
    ops.serializar(u);
    var p = repo.configurada(u, false);
    repo.aberto(u, id);
    repo.versao(u, id, d.versao());
    if (d.operadores() == null
        || d.operadores().isEmpty()
        || !d.operadores().contains(d.responsavelId())
        || new HashSet<>(d.operadores()).size() != d.operadores().size())
      throw erro(400, "Informe operadores unicos e o responsavel entre eles");
    if (p.limiteOperadores() != null && d.operadores().size() > p.limiteOperadores())
      throw erro(409, "Limite de operadores simultaneos excedido");
    for (UUID member : d.operadores()) caixas.candidato(u, member);
    var before = repo.turno(u, id);
    repo.jdbc()
        .update(
            "UPDATE operadores_caixa SET ativo=false WHERE estabelecimento_id=? AND turno_id=?",
            u,
            id);
    for (UUID member : d.operadores())
      repo.jdbc()
          .update(
              "INSERT INTO operadores_caixa(turno_id,estabelecimento_id,vinculo_id) VALUES(?,?,?)"
                  + " ON CONFLICT(turno_id,vinculo_id) DO UPDATE SET ativo=true",
              id,
              u,
              member);
    repo.jdbc()
        .update(
            "UPDATE turnos_caixa SET responsavel_id=?,versao=versao+1 WHERE estabelecimento_id=?"
                + " AND id=?",
            d.responsavelId(),
            u,
            id);
    var result = repo.turno(u, id);
    evento(
        u,
        actor,
        "CAIXA_OPERADORES_ALTERADOS",
        id,
        before,
        Map.of("turno", result, "motivo", Dinheiro.motivo(d.motivo())));
    return result;
  }

  public PagamentoDtos.Lancamento movimentar(
      UUID u, UUID id, Authentication a, UUID expected, UUID key, Movimento d) {
    String cap =
        switch (d.tipo()) {
          case "SANGRIA" -> "CAIXA_SANGRIA_REGISTRAR";
          case "REFORCO" -> "CAIXA_SUPRIMENTO_REGISTRAR";
          default -> throw erro(400, "Escolha sangria ou reforco");
        };
    var actor = caixas.operador(u, a, expected, cap, true);
    Dinheiro.valor(d.valorCentavos(), false);
    String reason = Dinheiro.motivo(d.motivo());
    ops.serializar(u);
    repo.membro(u, id, actor.vinculo());
    var prior =
        ops.repetir(
            u,
            key,
            actor.vinculo(),
            "MOVIMENTO",
            Arrays.asList(id, d),
            PagamentoDtos.Lancamento.class);
    if (prior != null) return prior;
    var policy = repo.configurada(u, false);
    repo.aberto(u, id);
    repo.versao(u, id, d.versaoTurno());
    UUID approval = null;
    if (d.tipo().equals("SANGRIA")) {
      if (repo.resumo(u, id).dinheiroEsperadoCentavos() < d.valorCentavos())
        throw erro(409, "Dinheiro fisico estimado insuficiente para a sangria");
      if (d.valorCentavos() > policy.sangriaLimiteCentavos() || d.autorizacaoId() != null)
        approval = auth.consumir(u, id, "SANGRIA", id, d, d.autorizacaoId(), actor);
    } else if (d.autorizacaoId() != null)
      throw erro(400, "Reforco nao utiliza autorizacao de sangria");
    var result =
        repo.inserir(
            u,
            id,
            null,
            d.tipo(),
            "DINHEIRO",
            d.valorCentavos(),
            null,
            null,
            actor,
            reason,
            approval);
    evento(u, actor, "CAIXA_" + d.tipo(), result.id(), null, result);
    ops.salvar(u, key, actor.vinculo(), "MOVIMENTO", Arrays.asList(id, d), result);
    return result;
  }

  private void evento(
      UUID u, AcessoAtendimentoService.Ator a, String type, UUID id, Object before, Object after) {
    audit.registrarOperacao(
        u, a.usuario(), a.vinculo(), a.nome(), type, "CAIXA", id, before, after);
  }
}
