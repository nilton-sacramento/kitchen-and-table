package com.kitchentable.pagamentos;

import static com.kitchentable.cardapio.CardapioRepository.erro;
import static com.kitchentable.pagamentos.PagamentoDtos.*;

import com.kitchentable.caixa.*;
import com.kitchentable.funcionarios.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PagamentoService {
  private final CaixaRepository caixa;
  private final ContaRepository contas;
  private final OperacaoFinanceiraRepository ops;
  private final AcessoAtendimentoService acesso;
  private final AcessoCaixaService financeiro;
  private final AutorizacaoFinanceiraService auth;
  private final AuditoriaAdministrativaService audit;

  public PagamentoService(
      CaixaRepository c,
      ContaRepository r,
      OperacaoFinanceiraRepository o,
      AcessoAtendimentoService a,
      AcessoCaixaService f,
      AutorizacaoFinanceiraService ap,
      AuditoriaAdministrativaService au) {
    caixa = c;
    contas = r;
    ops = o;
    acesso = a;
    financeiro = f;
    auth = ap;
    audit = au;
  }

  public Conta consultar(UUID u, UUID comanda, Authentication a, UUID expected) {
    acesso.operador(u, a, expected, "CONTA_CONSULTAR");
    contas.bloquear(u, comanda, false);
    return contas.conta(u, comanda);
  }

  public Conta consultarAdmin(UUID u, UUID comanda, Authentication a) {
    acesso.administrador(u, a, false, "CONTA_CONSULTAR");
    contas.bloquear(u, comanda, false);
    return contas.conta(u, comanda);
  }

  private void contexto(UUID u, UUID turno, UUID member) {
    caixa.configurada(u, false);
    caixa.aberto(u, turno);
    caixa.membro(u, turno, member);
  }

  public Lancamento receber(
      UUID u, UUID turno, UUID comanda, Authentication a, UUID expected, UUID key, Receber d) {
    var actor = financeiro.operador(u, a, expected, "PAGAMENTO_REGISTRAR", true);
    Dinheiro.valor(d.valorCentavos(), false);
    if (d.meio() == null) throw erro(400, "Informe o meio de pagamento");
    if (d.meio() == Meio.DINHEIRO) {
      if (d.recebidoCentavos() == null || d.recebidoCentavos() < d.valorCentavos())
        throw erro(400, "Dinheiro recebido deve cobrir o valor deste pagamento");
      Dinheiro.valor(d.recebidoCentavos(), true);
      if (d.confirmadoExternamente()) throw erro(400, "Dinheiro nao utiliza confirmacao bancaria");
    } else if (!d.confirmadoExternamente() || d.recebidoCentavos() != null)
      throw erro(
          400,
          "Confira externamente o recebimento de Pix/cartao e confirme; nao informe dinheiro"
              + " recebido");
    ops.serializar(u);
    caixa.membro(u, turno, actor.vinculo());
    Object intent = Arrays.asList(turno, comanda, d);
    var old = ops.repetir(u, key, actor.vinculo(), "PAGAMENTO", intent, Lancamento.class);
    if (old != null) return old;
    contexto(u, turno, actor.vinculo());
    contas.aberta(u, comanda);
    var account = contas.exigirRevisao(u, comanda, d.revisaoConta());
    if (d.valorCentavos() > account.saldoCentavos())
      throw erro(
          409,
          "Pagamento superior ao saldo. Atualize a conta; dinheiro recebido pode exceder apenas"
              + " para calcular troco");
    var result =
        caixa.inserir(
            u,
            turno,
            comanda,
            "PAGAMENTO",
            d.meio().name(),
            d.valorCentavos(),
            d.recebidoCentavos(),
            null,
            actor,
            d.meio() == Meio.DINHEIRO ? null : "Recebimento conferido externamente pelo operador",
            null);
    evento(u, actor, "PAGAMENTO_REGISTRADO", "LANCAMENTO_FINANCEIRO", result.id(), account, result);
    ops.salvar(u, key, actor.vinculo(), "PAGAMENTO", intent, result);
    return result;
  }

  public Lancamento descontar(
      UUID u, UUID turno, UUID comanda, Authentication a, UUID expected, UUID key, Desconto d) {
    var actor = financeiro.operador(u, a, expected, "COMANDA_DESCONTO_APLICAR", true);
    String reason = Dinheiro.motivo(d.motivo());
    ops.serializar(u);
    caixa.membro(u, turno, actor.vinculo());
    Object intent = Arrays.asList(turno, comanda, d);
    var old = ops.repetir(u, key, actor.vinculo(), "DESCONTO", intent, Lancamento.class);
    if (old != null) return old;
    contexto(u, turno, actor.vinculo());
    contas.aberta(u, comanda);
    var account = contas.exigirRevisao(u, comanda, d.revisaoConta());
    long amount;
    switch (d.tipo()) {
      case "VALOR" -> {
        if (d.valorCentavos() == null || d.percentualBps() != null)
          throw erro(400, "Informe somente o valor do desconto");
        amount = Dinheiro.valor(d.valorCentavos(), false);
      }
      case "PERCENTUAL" -> {
        if (d.valorCentavos() != null || d.percentualBps() == null)
          throw erro(400, "Informe somente o percentual do desconto");
        amount =
            Dinheiro.valor(
                Dinheiro.percentual(account.consumoCentavos(), d.percentualBps()), false);
      }
      default -> throw erro(400, "Escolha desconto por valor ou percentual");
    }
    if (amount > account.saldoCentavos())
      throw erro(409, "Desconto nao pode ultrapassar o saldo nem criar credito negativo");
    var policy = caixa.configurada(u, false);
    long total = Math.addExact(account.descontosCentavos(), amount);
    UUID approval = null;
    if (total > policy.descontoLimiteCentavos()
        || total > Dinheiro.percentual(account.consumoCentavos(), policy.descontoLimiteBps())
        || d.autorizacaoId() != null)
      approval = auth.consumir(u, turno, "DESCONTO", comanda, d, d.autorizacaoId(), actor);
    var result =
        caixa.inserir(
            u, turno, comanda, "DESCONTO", null, amount, null, null, actor, reason, approval);
    evento(
        u,
        actor,
        "COMANDA_DESCONTO_APLICADO",
        "LANCAMENTO_FINANCEIRO",
        result.id(),
        account,
        result);
    ops.salvar(u, key, actor.vinculo(), "DESCONTO", intent, result);
    return result;
  }

  public Lancamento compensar(
      UUID u, UUID turno, UUID original, Authentication a, UUID expected, UUID key, Compensar d) {
    financeiro.operador(u, a, expected, "CAIXA_OPERAR", true);
    var reference = caixa.lancamento(u, original);
    String tipo =
        switch (reference.tipo()) {
          case "PAGAMENTO" -> "ESTORNO";
          case "DESCONTO" -> "DESCONTO_ESTORNO";
          default -> throw erro(409, "Somente pagamento ou desconto original aceita compensacao");
        };
    var actor =
        financeiro.operador(
            u,
            a,
            expected,
            tipo.equals("ESTORNO") ? "PAGAMENTO_ESTORNAR" : "COMANDA_DESCONTO_APLICAR",
            true);
    String reason = Dinheiro.motivo(d.motivo());
    if (d.natureza() == null
        || tipo.equals("ESTORNO")
            && !Set.of("CORRECAO_LANCAMENTO", "ESTORNO_PAGAMENTO").contains(d.natureza())
        || tipo.equals("DESCONTO_ESTORNO") && !"REVERSAO_DESCONTO".equals(d.natureza()))
      throw erro(400, "Informe a natureza correta da compensacao");
    ops.serializar(u);
    caixa.membro(u, turno, actor.vinculo());
    Object intent = Arrays.asList(turno, original, d);
    var old = ops.repetir(u, key, actor.vinculo(), tipo, intent, Lancamento.class);
    if (old != null) return old;
    contexto(u, turno, actor.vinculo());
    if (!reference.turnoId().equals(turno))
      throw erro(403, "Compensacao exige o turno original aberto, ou reaberto com autorizacao");
    contas.aberta(u, reference.comandaId());
    var account = contas.exigirRevisao(u, reference.comandaId(), d.revisaoConta());
    if (!caixa
        .jdbc()
        .queryForList(
            "SELECT id FROM lancamentos_financeiros WHERE estabelecimento_id=? AND original_id=?",
            u,
            original)
        .isEmpty())
      throw erro(409, "Lancamento ja compensado; nenhuma segunda devolucao foi registrada");
    if (tipo.equals("ESTORNO")
        && "DINHEIRO".equals(reference.meio())
        && caixa.resumo(u, turno).dinheiroEsperadoCentavos() < reference.valorCentavos())
      throw erro(409, "Dinheiro fisico estimado insuficiente para o estorno");
    UUID approval = auth.consumir(u, turno, tipo, original, d, d.autorizacaoId(), actor);
    var result =
        caixa.inserir(
            u,
            turno,
            reference.comandaId(),
            tipo,
            reference.meio(),
            reference.valorCentavos(),
            null,
            original,
            actor,
            reason,
            approval,
            d.natureza());
    evento(
        u,
        actor,
        tipo.equals("ESTORNO")
            ? (d.natureza().equals("CORRECAO_LANCAMENTO")
                ? "LANCAMENTO_FINANCEIRO_CORRIGIDO"
                : "PAGAMENTO_ESTORNADO_INTERNAMENTE")
            : "DESCONTO_COMPENSADO",
        "LANCAMENTO_FINANCEIRO",
        result.id(),
        reference,
        result);
    ops.salvar(u, key, actor.vinculo(), tipo, intent, result);
    return result;
  }

  public Lancamento confirmarExterno(
      UUID u,
      UUID turno,
      UUID estorno,
      Authentication a,
      UUID expected,
      UUID key,
      ConfirmarExterno d) {
    var actor = financeiro.operador(u, a, expected, "PAGAMENTO_ESTORNAR", true);
    if (!d.confirmadoExternamente())
      throw erro(400, "Confirme somente apos conferir a devolucao na instituicao externa");
    String reason = Dinheiro.motivo(d.motivo());
    ops.serializar(u);
    caixa.membro(u, turno, actor.vinculo());
    Object intent = Arrays.asList(turno, estorno, d);
    var old = ops.repetir(u, key, actor.vinculo(), "CONFIRMAR_ESTORNO", intent, Lancamento.class);
    if (old != null) return old;
    contexto(u, turno, actor.vinculo());
    var l = caixa.lancamento(u, estorno);
    if (!l.turnoId().equals(turno) || !l.tipo().equals("ESTORNO") || "DINHEIRO".equals(l.meio()))
      throw erro(409, "Lancamento nao e um estorno externo deste turno");
    if (!l.confirmacaoExternaPendente()) throw erro(409, "Devolucao externa ja confirmada");
    caixa
        .jdbc()
        .update(
            "INSERT INTO confirmacoes_estorno(estorno_id,estabelecimento_id,executor)"
                + " VALUES(?,?,?)",
            estorno,
            u,
            actor.vinculo());
    caixa.atualizar(u, turno);
    var result = caixa.lancamento(u, estorno);
    evento(
        u,
        actor,
        "ESTORNO_DEVOLUCAO_EXTERNA_CONFERIDA",
        "LANCAMENTO_FINANCEIRO",
        estorno,
        null,
        Map.of("motivo", reason, "confirmacaoRegistradaPeloOperador", true));
    ops.salvar(u, key, actor.vinculo(), "CONFIRMAR_ESTORNO", intent, result);
    return result;
  }

  public Quitacao encerrar(
      UUID u, UUID turno, UUID comanda, Authentication a, UUID expected, UUID key, Encerrar d) {
    var actor = financeiro.operador(u, a, expected, "COMANDA_FINANCEIRO_ENCERRAR", true);
    ops.serializar(u);
    caixa.membro(u, turno, actor.vinculo());
    Object intent = Arrays.asList(turno, comanda, d);
    var old = ops.repetir(u, key, actor.vinculo(), "QUITACAO", intent, Quitacao.class);
    if (old != null) return old;
    contexto(u, turno, actor.vinculo());
    var ref =
        caixa
            .jdbc()
            .queryForList(
                "SELECT mesa_id FROM comandas WHERE estabelecimento_id=? AND id=?", u, comanda);
    if (ref.isEmpty()) throw erro(404, "Comanda nao encontrada nesta unidade");
    UUID mesa = (UUID) ref.get(0).get("mesa_id");
    if (mesa != null)
      caixa
          .jdbc()
          .queryForList(
              "SELECT id FROM mesas WHERE estabelecimento_id=? AND id=? FOR UPDATE", u, mesa);
    contas.aberta(u, comanda);
    var account = contas.exigirRevisao(u, comanda, d.revisaoConta());
    if (account.pedidos().isEmpty())
      throw erro(409, "Comanda sem consumo utiliza o encerramento operacional existente");
    if (account.saldoCentavos() != 0) throw erro(409, "A comanda possui saldo pendente");
    if (account.possuiItensNaoEntregues())
      throw erro(
          409,
          "Confirme a entrega de todos os itens ou conclua o cancelamento autorizado antes de"
              + " encerrar");
    if (account.lancamentos().stream().anyMatch(Lancamento::confirmacaoExternaPendente))
      throw erro(409, "Confirme a devolucao externa dos estornos pendentes antes de encerrar");
    UUID id = UUID.randomUUID();
    caixa
        .jdbc()
        .update(
            "INSERT INTO"
                + " quitacoes_comanda(id,estabelecimento_id,turno_id,comanda_id,consumo_centavos,desconto_centavos,pago_centavos,executor)"
                + " VALUES(?,?,?,?,?,?,?,?)",
            id,
            u,
            turno,
            comanda,
            account.consumoCentavos(),
            account.descontosCentavos(),
            account.pagoCentavos(),
            actor.vinculo());
    caixa
        .jdbc()
        .update(
            "UPDATE comandas SET"
                + " estado='ENCERRADA',quitacao_id=?,finalizado_por=?,finalizado_em=clock_timestamp(),versao=versao+1"
                + " WHERE estabelecimento_id=? AND id=?",
            id,
            actor.vinculo(),
            u,
            comanda);
    caixa.atualizar(u, turno);
    var created =
        caixa
            .jdbc()
            .queryForObject(
                "SELECT criado_em FROM quitacoes_comanda WHERE id=?", java.sql.Timestamp.class, id)
            .toInstant();
    var result =
        new Quitacao(
            id,
            comanda,
            turno,
            account.consumoCentavos(),
            account.descontosCentavos(),
            account.pagoCentavos(),
            actor.vinculo(),
            created);
    evento(
        u,
        actor,
        "COMANDA_ENCERRADA_FINANCEIRAMENTE",
        "CONTA_FINANCEIRA",
        comanda,
        account,
        result);
    ops.salvar(u, key, actor.vinculo(), "QUITACAO", intent, result);
    return result;
  }

  private void evento(
      UUID u,
      AcessoAtendimentoService.Ator actor,
      String type,
      String entity,
      UUID id,
      Object before,
      Object after) {
    audit.registrarOperacao(
        u, actor.usuario(), actor.vinculo(), actor.nome(), type, entity, id, before, after);
  }
}
