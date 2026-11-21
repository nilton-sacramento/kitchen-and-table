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
public class FechamentoCaixaService {
  private final CaixaRepository repo;
  private final OperacaoFinanceiraRepository ops;
  private final AcessoCaixaService caixas;
  private final AcessoAtendimentoService acesso;
  private final AutorizacaoFinanceiraService auth;
  private final AuditoriaAdministrativaService audit;

  public FechamentoCaixaService(
      CaixaRepository r,
      OperacaoFinanceiraRepository o,
      AcessoCaixaService c,
      AcessoAtendimentoService a,
      AutorizacaoFinanceiraService ap,
      AuditoriaAdministrativaService au) {
    repo = r;
    ops = o;
    caixas = c;
    acesso = a;
    auth = ap;
    audit = au;
  }

  public Conferencia conferir(
      UUID u, UUID id, Authentication a, UUID expected, UUID key, Conferir d) {
    var actor = caixas.operador(u, a, expected, "CAIXA_CONFERIR", true);
    Dinheiro.valor(d.informadoCentavos(), true);
    ops.serializar(u);
    repo.membro(u, id, actor.vinculo());
    var old =
        ops.repetir(
            u, key, actor.vinculo(), "CONFERENCIA", Arrays.asList(id, d), Conferencia.class);
    if (old != null) return old;
    repo.configurada(u, false);
    repo.aberto(u, id);
    repo.versao(u, id, d.versaoTurno());
    var summary = repo.resumo(u, id);
    String reason = d.motivo() == null ? null : d.motivo().strip();
    if (d.informadoCentavos() != summary.dinheiroEsperadoCentavos())
      reason = Dinheiro.motivo(reason);
    if (reason != null && reason.length() > 500) throw erro(400, "Justificativa acima do limite");
    UUID cid = UUID.randomUUID();
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " conferencias_caixa(id,estabelecimento_id,turno_id,versao_turno,esperado_centavos,informado_centavos,motivo,executor)"
                + " VALUES(?,?,?,?,?,?,?,?)",
            cid,
            u,
            id,
            summary.turno().versao(),
            summary.dinheiroEsperadoCentavos(),
            d.informadoCentavos(),
            reason,
            actor.vinculo());
    var result = repo.conferencia(u, cid);
    evento(u, actor, "CAIXA_CONFERIDO", cid, null, result);
    ops.salvar(u, key, actor.vinculo(), "CONFERENCIA", Arrays.asList(id, d), result);
    return result;
  }

  public Fechamento fechar(UUID u, UUID id, Authentication a, UUID expected, UUID key, Fechar d) {
    var actor = caixas.operador(u, a, expected, "CAIXA_FECHAR", true);
    ops.serializar(u);
    repo.membro(u, id, actor.vinculo());
    var old =
        ops.repetir(u, key, actor.vinculo(), "FECHAMENTO", Arrays.asList(id, d), Fechamento.class);
    if (old != null) return old;
    var policy = repo.configurada(u, true);
    repo.aberto(u, id);
    repo.versao(u, id, d.versaoTurno());
    var conference = repo.conferencia(u, d.conferenciaId());
    var before = repo.resumo(u, id);
    if (!conference.turnoId().equals(id)
        || conference.versaoTurno() != before.turno().versao()
        || conference.esperadoCentavos() != before.dinheiroEsperadoCentavos())
      throw erro(409, "A conferencia esta obsoleta. Confira novamente os valores atuais");
    repo.jdbc()
        .queryForList(
            "SELECT id FROM comandas WHERE estabelecimento_id=? AND estado='ABERTA' ORDER BY id FOR"
                + " UPDATE",
            u);
    long pending =
        repo.jdbc()
            .queryForObject(
                "SELECT count(*) FROM comandas c WHERE c.estabelecimento_id=? AND c.estado='ABERTA'"
                    + " AND (c.teve_consumo OR c.possui_pendencias OR EXISTS(SELECT 1 FROM pedidos"
                    + " p WHERE p.estabelecimento_id=c.estabelecimento_id AND p.comanda_id=c.id) OR"
                    + " EXISTS(SELECT 1 FROM lancamentos_financeiros l WHERE"
                    + " l.estabelecimento_id=c.estabelecimento_id AND l.comanda_id=c.id))",
                Long.class,
                u);
    if (pending > 0)
      throw erro(
          409,
          "Existem comandas com consumo ainda abertas. Quite e encerre cada comanda antes de fechar"
              + " o caixa");
    if (repo.jdbc()
            .queryForObject(
                "SELECT count(*) FROM solicitacoes_pedido WHERE estabelecimento_id=? AND"
                    + " estado='PENDENTE'",
                Long.class,
                u)
        > 0)
      throw erro(409, "Resolva as solicitacoes operacionais pendentes antes de fechar o caixa");
    if (before.pendenciasExternas() > 0)
      throw erro(409, "Confirme as devolucoes externas de estornos pendentes antes do fechamento");
    UUID approval = null;
    if (Math.abs(conference.diferencaCentavos()) > policy.divergenciaLimiteCentavos()
        || d.autorizacaoId() != null)
      approval = auth.consumir(u, id, "FECHAMENTO", id, d, d.autorizacaoId(), actor);
    UUID report = UUID.randomUUID();
    var data =
        Map.of(
            "situacaoNoFechamento",
            "FECHADO",
            "resumo",
            before,
            "conferencia",
            conference,
            "executor",
            actor.vinculo(),
            "nomeExecutor",
            actor.nome());
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " relatorios_fechamento(id,estabelecimento_id,turno_id,ciclo,conferencia_id,executor,autorizacao_id,dados)"
                + " VALUES(?,?,?,?,?,?,?,?::jsonb)",
            report,
            u,
            id,
            before.turno().ciclo(),
            conference.id(),
            actor.vinculo(),
            approval,
            ops.json(data));
    var result =
        repo.fechamentos(u, id).stream()
            .filter(r -> r.id().equals(report))
            .findFirst()
            .orElseThrow();
    repo.jdbc()
        .update(
            "UPDATE turnos_caixa SET estado='FECHADO',fechado_em=?,fechado_por=?,versao=versao+1"
                + " WHERE estabelecimento_id=? AND id=?",
            java.sql.Timestamp.from(result.criadoEm()),
            actor.vinculo(),
            u,
            id);
    evento(
        u,
        actor,
        "CAIXA_FECHADO",
        id,
        before,
        Map.of("relatorioId", report, "conferencia", conference, "ciclo", before.turno().ciclo()));
    ops.salvar(u, key, actor.vinculo(), "FECHAMENTO", Arrays.asList(id, d), result);
    return result;
  }

  public Turno reabrir(UUID u, UUID id, Authentication a, UUID key, Reabrir d) {
    var actor = acesso.administrador(u, a, true, "CAIXA_REABRIR");
    String reason = Dinheiro.motivo(d.motivo());
    ops.serializar(u);
    var old = ops.repetir(u, key, actor.vinculo(), "REABERTURA", Arrays.asList(id, d), Turno.class);
    if (old != null) return old;
    repo.configurada(u, true);
    var before = repo.turno(u, id);
    repo.turnoRow(u, id, true);
    if (!before.estado().equals("FECHADO"))
      throw erro(409, "Somente turno fechado pode ser reaberto");
    repo.versao(u, id, d.versao());
    if (!repo.jdbc()
        .queryForList(
            "SELECT id FROM turnos_caixa WHERE estabelecimento_id=? AND estado='ABERTO'", u)
        .isEmpty()) throw erro(409, "Outra abertura esta ativa nesta unidade");
    repo.jdbc()
        .update(
            "UPDATE turnos_caixa SET"
                + " estado='ABERTO',fechado_em=NULL,fechado_por=NULL,ciclo=ciclo+1,versao=versao+1"
                + " WHERE estabelecimento_id=? AND id=?",
            u,
            id);
    var result = repo.turno(u, id);
    evento(u, actor, "CAIXA_REABERTO", id, before, Map.of("turno", result, "motivo", reason));
    ops.salvar(u, key, actor.vinculo(), "REABERTURA", Arrays.asList(id, d), result);
    return result;
  }

  public List<Fechamento> historico(UUID u, UUID id, Authentication a, UUID expected) {
    acesso.operador(u, a, expected, "CAIXA_HISTORICO_CONSULTAR");
    repo.turnoRow(u, id, false);
    return repo.fechamentos(u, id).stream()
        .map(
            f ->
                CaixaRepository.visivel(
                    f,
                    acesso.vinculoPossui(
                        u, expected, "FUNCIONARIO_HISTORICO_FINANCEIRO_CONSULTAR")))
        .toList();
  }

  public Conferencia conferenciaAdmin(UUID u, UUID id, Authentication a) {
    acesso.administrador(u, a, false, "CAIXA_HISTORICO_CONSULTAR");
    return repo.conferencia(u, id);
  }

  public List<Fechamento> historicoAdmin(UUID u, UUID id, Authentication a) {
    var actor = acesso.administrador(u, a, false, "CAIXA_HISTORICO_CONSULTAR");
    repo.turnoRow(u, id, false);
    return repo.fechamentos(u, id).stream()
        .map(
            f ->
                CaixaRepository.visivel(
                    f,
                    acesso.vinculoPossui(
                        u, actor.vinculo(), "FUNCIONARIO_HISTORICO_FINANCEIRO_CONSULTAR")))
        .toList();
  }

  private void evento(
      UUID u, AcessoAtendimentoService.Ator a, String type, UUID id, Object before, Object after) {
    audit.registrarOperacao(
        u, a.usuario(), a.vinculo(), a.nome(), type, "FECHAMENTO_CAIXA", id, before, after);
  }
}
