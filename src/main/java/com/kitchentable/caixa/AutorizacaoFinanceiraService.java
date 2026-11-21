package com.kitchentable.caixa;

import static com.kitchentable.caixa.CaixaDtos.*;
import static com.kitchentable.cardapio.CardapioRepository.erro;

import com.fasterxml.jackson.databind.*;
import com.kitchentable.funcionarios.*;
import com.kitchentable.pagamentos.*;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Autorizacao pontual do proprietario; nao implementa delegacao temporaria generica. */
@Service
@Transactional
public class AutorizacaoFinanceiraService {
  private final CaixaRepository repo;
  private final OperacaoFinanceiraRepository ops;
  private final AcessoAtendimentoService acesso;
  private final AuditoriaAdministrativaService audit;
  private final Validator validator;

  public AutorizacaoFinanceiraService(
      CaixaRepository r,
      OperacaoFinanceiraRepository o,
      AcessoAtendimentoService a,
      AuditoriaAdministrativaService au,
      Validator v) {
    repo = r;
    ops = o;
    acesso = a;
    audit = au;
    validator = v;
  }

  public static String capacidade(String tipo) {
    return switch (tipo) {
      case "DESCONTO", "DESCONTO_ESTORNO" -> "COMANDA_DESCONTO_APLICAR";
      case "SANGRIA" -> "CAIXA_SANGRIA_REGISTRAR";
      case "ESTORNO" -> "PAGAMENTO_ESTORNAR";
      case "FECHAMENTO" -> "CAIXA_FECHAR";
      default -> throw erro(400, "Tipo de autorizacao nao suportado");
    };
  }

  private Object normalizar(String tipo, Object body) {
    JsonNode node = ops.node(body);
    if (node == null || !node.isObject())
      throw erro(400, "Informe os campos da operacao financeira");
    Object dto;
    try {
      dto =
          switch (tipo) {
            case "DESCONTO" -> ops.ler(node.toString(), PagamentoDtos.Desconto.class);
            case "DESCONTO_ESTORNO", "ESTORNO" ->
                ops.ler(node.toString(), PagamentoDtos.Compensar.class);
            case "SANGRIA" -> ops.ler(node.toString(), Movimento.class);
            case "FECHAMENTO" -> ops.ler(node.toString(), Fechar.class);
            default -> throw erro(400, "Tipo de autorizacao nao suportado");
          };
    } catch (IllegalStateException e) {
      throw erro(400, "Formato invalido da operacao financeira");
    }
    if (!validator.validate(dto).isEmpty())
      throw erro(400, "Confira os campos da operacao que precisa de autorizacao");
    var clean = ops.node(dto).deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) clean).remove("autorizacaoId");
    return clean;
  }

  private String resumo(UUID u, UUID turno, String tipo, UUID entity, Object body) {
    return ops.hash(
        Arrays.asList(
            turno, tipo, entity, normalizar(tipo, body), repo.configurada(u, false).versao()));
  }

  public Autorizacao solicitar(
      UUID u, Authentication a, UUID esperado, UUID key, SolicitarAutorizacao d) {
    String cap = capacidade(d.tipo());
    var actor = acesso.operador(u, a, esperado, cap, true);
    acesso.operador(u, a, esperado, "CAIXA_OPERAR");
    ops.serializar(u);
    var old = ops.repetir(u, key, actor.vinculo(), "AUTORIZACAO", d, Autorizacao.class);
    if (old != null) {
      repo.membro(u, d.turnoId(), actor.vinculo());
      return old;
    }
    repo.configurada(u, false);
    repo.aberto(u, d.turnoId());
    repo.membro(u, d.turnoId(), actor.vinculo());
    Object body = normalizar(d.tipo(), d.operacao());
    if (d.tipo().equals("SANGRIA") && !"SANGRIA".equals(((JsonNode) body).path("tipo").asText()))
      throw erro(400, "Autorizacao de sangria exige retirada de dinheiro");
    if (Set.of("DESCONTO").contains(d.tipo())) {
      if (d.entidadeId() == null
          || repo.jdbc()
              .queryForList(
                  "SELECT id FROM comandas WHERE estabelecimento_id=? AND id=? AND estado='ABERTA'",
                  u,
                  d.entidadeId())
              .isEmpty()) throw erro(404, "Comanda nao encontrada nesta unidade");
    } else if (Set.of("ESTORNO", "DESCONTO_ESTORNO").contains(d.tipo())) {
      var l = repo.lancamento(u, d.entidadeId());
      if (!l.turnoId().equals(d.turnoId())) throw erro(403, "O lancamento pertence a outro turno");
    } else if (d.entidadeId() != null && !d.entidadeId().equals(d.turnoId()))
      throw erro(400, "Escopo da autorizacao deve ser o turno");
    UUID id = UUID.randomUUID();
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " autorizacoes_financeiras(id,estabelecimento_id,turno_id,solicitante,credencial_versao,tipo,entidade_id,requisicao_hash,dados,motivo)"
                + " VALUES(?,?,?,?,?,?,?,?,?::jsonb,?)",
            id,
            u,
            d.turnoId(),
            actor.vinculo(),
            acesso.versaoCredencial(u, actor.vinculo()),
            d.tipo(),
            d.entidadeId(),
            resumo(u, d.turnoId(), d.tipo(), d.entidadeId(), body),
            ops.json(body),
            Dinheiro.motivo(d.motivo()));
    var result = buscar(u, id);
    evento(u, actor, "FINANCEIRO_AUTORIZACAO_SOLICITADA", id, result);
    ops.salvar(u, key, actor.vinculo(), "AUTORIZACAO", d, result);
    return result;
  }

  public Autorizacao buscar(UUID u, UUID id) {
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT r.*,COALESCE(s.nome_exibicao,us.nome) AS"
                    + " solicitante_nome,COALESCE(a.nome_exibicao,ua.nome) AS autorizador_nome FROM"
                    + " autorizacoes_financeiras r JOIN usuarios_estabelecimentos s ON"
                    + " s.id=r.solicitante AND s.estabelecimento_id=r.estabelecimento_id JOIN"
                    + " usuarios us ON us.id=s.usuario_id LEFT JOIN usuarios_estabelecimentos a ON"
                    + " a.id=r.autorizador AND a.estabelecimento_id=r.estabelecimento_id LEFT JOIN"
                    + " usuarios ua ON ua.id=a.usuario_id WHERE r.estabelecimento_id=? AND r.id=?",
                u,
                id);
    if (rows.isEmpty()) throw erro(404, "Autorizacao nao encontrada nesta unidade");
    var r = rows.get(0);
    return new Autorizacao(
        id,
        (UUID) r.get("turno_id"),
        (UUID) r.get("solicitante"),
        (String) r.get("solicitante_nome"),
        (String) r.get("tipo"),
        (UUID) r.get("entidade_id"),
        ops.ler(r.get("dados").toString(), JsonNode.class),
        (String) r.get("motivo"),
        (String) r.get("estado"),
        CaixaRepository.instant(r, "expira_em"),
        (UUID) r.get("autorizador"),
        (String) r.get("autorizador_nome"),
        (String) r.get("motivo_decisao"),
        CaixaRepository.n(r, "versao"));
  }

  public List<Autorizacao> listarAdmin(UUID u, Authentication a) {
    acesso.administrador(u, a, false, "CAIXA_AUTORIZACAO_GERENCIAR");
    return ids(u, null).stream().map(id -> buscar(u, id)).toList();
  }

  public List<Autorizacao> listarOperador(UUID u, Authentication a, UUID esperado) {
    var actor = acesso.operador(u, a, esperado, "CAIXA_OPERAR");
    return ids(u, actor.vinculo()).stream().map(id -> buscar(u, id)).toList();
  }

  private List<UUID> ids(UUID u, UUID actor) {
    return repo.jdbc()
        .query(
            "SELECT id FROM autorizacoes_financeiras WHERE estabelecimento_id=?"
                + (actor == null ? "" : " AND solicitante=?")
                + " ORDER BY criado_em DESC",
            (r, n) -> r.getObject(1, UUID.class),
            actor == null ? new Object[] {u} : new Object[] {u, actor});
  }

  public Autorizacao decidir(UUID u, UUID id, Authentication a, Autorizar d) {
    var actor = acesso.administrador(u, a, true, "CAIXA_AUTORIZACAO_GERENCIAR");
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT *,expira_em>clock_timestamp() AS valida FROM autorizacoes_financeiras WHERE"
                    + " estabelecimento_id=? AND id=? FOR UPDATE",
                u,
                id);
    if (rows.isEmpty()) throw erro(404, "Autorizacao nao encontrada nesta unidade");
    var r = rows.get(0);
    if (d.versao() == null || d.versao() != CaixaRepository.n(r, "versao"))
      throw erro(409, "Autorizacao alterada. Atualize a lista");
    String state = (String) r.get("estado");
    String next =
        switch (d.acao()) {
          case "APROVAR" -> {
            if (!state.equals("PENDENTE") || !Boolean.TRUE.equals(r.get("valida")))
              throw erro(409, "Solicitacao ja decidida ou expirada");
            yield "APROVADA";
          }
          case "RECUSAR" -> {
            if (!state.equals("PENDENTE")) throw erro(409, "Solicitacao ja decidida");
            yield "RECUSADA";
          }
          case "REVOGAR" -> {
            if (!Set.of("PENDENTE", "APROVADA").contains(state))
              throw erro(409, "Autorizacao nao pode mais ser revogada");
            yield "REVOGADA";
          }
          default -> throw erro(400, "Acao de autorizacao invalida");
        };
    repo.jdbc()
        .update(
            "UPDATE autorizacoes_financeiras SET"
                + " estado=?,autorizador=?,motivo_decisao=?,decidido_em=clock_timestamp(),versao=versao+1"
                + " WHERE estabelecimento_id=? AND id=?",
            next,
            actor.vinculo(),
            Dinheiro.motivo(d.motivo()),
            u,
            id);
    var result = buscar(u, id);
    evento(u, actor, "FINANCEIRO_AUTORIZACAO_" + next, id, result);
    return result;
  }

  public UUID consumir(
      UUID u,
      UUID turno,
      String tipo,
      UUID entity,
      Object body,
      UUID id,
      AcessoAtendimentoService.Ator actor) {
    if (id == null) throw new AutorizacaoFinanceiraNecessariaException();
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT *,expira_em>clock_timestamp() AS valida FROM autorizacoes_financeiras WHERE"
                    + " estabelecimento_id=? AND id=? FOR UPDATE",
                u,
                id);
    if (rows.isEmpty()) throw erro(403, "Autorizacao invalida para esta operacao");
    var r = rows.get(0);
    if (!"APROVADA".equals(r.get("estado"))
        || !Boolean.TRUE.equals(r.get("valida"))
        || !actor.vinculo().equals(r.get("solicitante"))
        || !turno.equals(r.get("turno_id"))
        || !tipo.equals(r.get("tipo"))
        || !Objects.equals(entity, r.get("entidade_id"))
        || !resumo(u, turno, tipo, entity, body).equals(r.get("requisicao_hash"))
        || !acesso.credencialVigente(u, actor.vinculo(), CaixaRepository.n(r, "credencial_versao"))
        || !acesso.vinculoPossui(u, (UUID) r.get("autorizador"), "CAIXA_AUTORIZACAO_GERENCIAR"))
      throw erro(
          403, "Autorizacao expirada, revogada, consumida ou diferente da operacao revisada");
    repo.jdbc()
        .update(
            "UPDATE autorizacoes_financeiras SET"
                + " estado='CONSUMIDA',consumida_em=clock_timestamp(),versao=versao+1 WHERE"
                + " estabelecimento_id=? AND id=?",
            u,
            id);
    evento(
        u,
        actor,
        "FINANCEIRO_AUTORIZACAO_CONSUMIDA",
        id,
        Map.of("tipo", tipo, "autorizador", r.get("autorizador")));
    return id;
  }

  private void evento(UUID u, AcessoAtendimentoService.Ator a, String type, UUID id, Object body) {
    audit.registrarOperacao(
        u, a.usuario(), a.vinculo(), a.nome(), type, "AUTORIZACAO_FINANCEIRA", id, null, body);
  }
}
