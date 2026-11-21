package com.kitchentable.pagamentos;

import static com.kitchentable.cardapio.CardapioRepository.erro;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Gate compartilhado da configuracao permite fechar o caixa sem phantoms de novos consumos. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class IntegridadeFinanceiraService {
  private final JdbcTemplate j;

  public IntegridadeFinanceiraService(JdbcTemplate j) {
    this.j = j;
  }

  public void compartilhar(UUID unidade) {
    j.queryForList(
        "SELECT estabelecimento_id FROM configuracoes_caixa WHERE estabelecimento_id=? FOR SHARE",
        unidade);
  }

  public void exigirContaSemLancamentoEfetivo(UUID unidade, UUID comanda) {
    var r =
        j.queryForMap(
            "SELECT COALESCE(sum(CASE WHEN tipo='PAGAMENTO' THEN valor_centavos WHEN tipo='ESTORNO'"
                + " THEN -valor_centavos ELSE 0 END),0) AS pago,COALESCE(sum(CASE WHEN"
                + " tipo='DESCONTO' THEN valor_centavos WHEN tipo='DESCONTO_ESTORNO' THEN"
                + " -valor_centavos ELSE 0 END),0) AS desconto FROM lancamentos_financeiros WHERE"
                + " estabelecimento_id=? AND comanda_id=?",
            unidade,
            comanda);
    if (Dinheiro.exato(r.get("pago")) != 0 || Dinheiro.exato(r.get("desconto")) != 0)
      throw erro(
          409,
          "A conta possui pagamento ou desconto. Regularize os lancamentos financeiros antes de"
              + " alterar ou cancelar pedidos");
  }
}
