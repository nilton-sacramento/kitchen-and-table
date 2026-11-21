package com.kitchentable.pagamentos;

import static com.kitchentable.cardapio.CardapioRepository.erro;
import static com.kitchentable.pagamentos.PagamentoDtos.*;

import com.kitchentable.caixa.*;
import com.kitchentable.pedidos.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContaRepository {
  private final JdbcTemplate j;
  private final PedidoRepository pedidos;
  private final CaixaRepository caixa;
  private final OperacaoFinanceiraRepository ops;

  public ContaRepository(
      JdbcTemplate j, PedidoRepository p, CaixaRepository c, OperacaoFinanceiraRepository o) {
    this.j = j;
    pedidos = p;
    caixa = c;
    ops = o;
  }

  public Map<String, Object> bloquear(UUID u, UUID id, boolean exclusivo) {
    var rows =
        j.queryForList(
            "SELECT * FROM comandas WHERE estabelecimento_id=? AND id=? FOR "
                + (exclusivo ? "UPDATE" : "SHARE"),
            u,
            id);
    if (rows.isEmpty()) throw erro(404, "Comanda nao encontrada nesta unidade");
    j.queryForList(
        "SELECT id FROM pedidos WHERE estabelecimento_id=? AND comanda_id=? ORDER BY id FOR "
            + (exclusivo ? "UPDATE" : "SHARE"),
        u,
        id);
    return rows.get(0);
  }

  public void aberta(UUID u, UUID id) {
    if (!"ABERTA".equals(bloquear(u, id, true).get("estado")))
      throw erro(
          409,
          "A comanda esta encerrada. Correcao apos encerramento exige fluxo financeiro especifico");
  }

  public Conta conta(UUID u, UUID id) {
    var cs =
        j.queryForList(
            "SELECT c.*,m.identificacao AS mesa FROM comandas c LEFT JOIN mesas m ON m.id=c.mesa_id"
                + " AND m.estabelecimento_id=c.estabelecimento_id WHERE c.estabelecimento_id=? AND"
                + " c.id=?",
            u,
            id);
    if (cs.isEmpty()) throw erro(404, "Comanda nao encontrada nesta unidade");
    var c = cs.get(0);
    var list = pedidos.listar(u, id);
    long gross = 0;
    for (var p : list)
      if (!p.estado().equals("CANCELADO")) gross = Math.addExact(gross, p.totalCentavos());
    var ledger = caixa.lancamentos(u, null, id);
    long discount = 0, paid = 0;
    for (var l : ledger)
      switch (l.tipo()) {
        case "PAGAMENTO" -> paid = Math.addExact(paid, l.valorCentavos());
        case "ESTORNO" -> paid = Math.subtractExact(paid, l.valorCentavos());
        case "DESCONTO" -> discount = Math.addExact(discount, l.valorCentavos());
        case "DESCONTO_ESTORNO" -> discount = Math.subtractExact(discount, l.valorCentavos());
        default -> {}
      }
    long pending =
        j.queryForObject(
            "SELECT count(*) FROM solicitacoes_pedido s JOIN pedidos p ON p.id=s.pedido_id AND"
                + " p.estabelecimento_id=s.estabelecimento_id WHERE s.estabelecimento_id=? AND"
                + " p.comanda_id=? AND s.estado='PENDENTE'",
            Long.class,
            u,
            id);
    long balance = Math.subtractExact(Math.subtractExact(gross, discount), paid);
    if (balance < 0 || discount < 0 || paid < 0)
      throw erro(409, "Inconsistencia na conta; nenhuma operacao financeira foi autorizada");
    boolean notDelivered =
        list.stream()
            .flatMap(p -> p.itens().stream())
            .anyMatch(i -> !Set.of("ENTREGUE", "CANCELADO").contains(i.estado()));
    String hash =
        ops.hash(
            Arrays.asList(
                id,
                c.get("estado"),
                c.get("versao"),
                list.stream()
                    .map(
                        p ->
                            Arrays.asList(
                                p.id(), p.estado().equals("CANCELADO"), p.totalCentavos()))
                    .toList(),
                ledger.stream().map(Lancamento::id).toList(),
                pending));
    return new Conta(
        id,
        (String) c.get("codigo"),
        (String) c.get("modalidade"),
        (String) c.get("mesa"),
        (String) c.get("estado"),
        gross,
        discount,
        paid,
        balance,
        hash,
        list,
        ledger,
        pending,
        notDelivered);
  }

  public Conta exigirRevisao(UUID u, UUID id, String revision) {
    var c = conta(u, id);
    if (revision == null || !revision.equals(c.revisao()))
      throw erro(
          409, "A conta mudou. Atualize e revise consumo, pagamentos e saldo antes de confirmar");
    if (c.solicitacoesPendentes() > 0)
      throw erro(
          409, "Resolva as solicitacoes de alteracao ou cancelamento antes de movimentar a conta");
    return c;
  }
}
