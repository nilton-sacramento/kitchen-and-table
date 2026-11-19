package com.kitchentable.cardapio;

import static com.kitchentable.cardapio.CardapioRepository.erro;

import com.kitchentable.funcionarios.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Contrato interno para confirmacao futura de pedidos. Nenhum endpoint de consumo na 2.5. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class EstoqueCardapioService {
  private final CardapioRepository repo;
  private final AcessoAtendimentoService acesso;
  private final AuditoriaAdministrativaService audit;

  public EstoqueCardapioService(
      CardapioRepository r, AcessoAtendimentoService a, AuditoriaAdministrativaService ad) {
    repo = r;
    acesso = a;
    audit = ad;
  }

  public void consumir(
      UUID u, Authentication auth, UUID esperado, UUID operacao, Map<UUID, Integer> consumo) {
    var ator = acesso.operador(u, auth, esperado, "PEDIDO_ENVIAR", true);
    if (operacao == null || consumo.isEmpty()) throw erro(400, "Operacao de consumo invalida");
    repo.jdbc()
        .queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
            (r, n) -> 1,
            u + ":estoque:" + operacao);
    for (UUID id : new TreeSet<>(consumo.keySet())) {
      int amount = consumo.get(id);
      if (amount <= 0) throw erro(400, "Quantidade invalida");
      var p = repo.produto(u, id, true);
      if (p.tipo() != CardapioDtos.Tipo.SIMPLES)
        throw erro(400, "Consumo exige decomposicao em produtos simples");
      var previous =
          repo.jdbc()
              .queryForList(
                  "SELECT anterior,posterior FROM movimentos_cardapio WHERE estabelecimento_id=?"
                      + " AND produto_id=? AND operacao_id=? AND tipo='CONSUMO'",
                  u,
                  id,
                  operacao);
      if (!previous.isEmpty()) {
        var m = previous.get(0);
        if ((Integer) m.get("anterior") - (Integer) m.get("posterior") != amount)
          throw erro(409, "Operacao repetida com outra quantidade");
        continue;
      }
      if (!p.vendavel()) throw erro(409, "Produto indisponivel");
      if (!p.controlaQuantidade()) continue;
      if (p.quantidade() < amount) throw erro(409, "Quantidade disponivel insuficiente");
      int next = p.quantidade() - amount;
      repo.jdbc()
          .update(
              "UPDATE produtos_cardapio SET quantidade=?,versao=versao+1 WHERE estabelecimento_id=?"
                  + " AND id=?",
              next,
              u,
              id);
      repo.jdbc()
          .update(
              "INSERT INTO"
                  + " movimentos_cardapio(estabelecimento_id,produto_id,operacao_id,tipo,anterior,posterior,executor,motivo)"
                  + " VALUES(?,?,?,'CONSUMO',?,?,?,'Confirmacao de pedido')",
              u,
              id,
              operacao,
              p.quantidade(),
              next,
              ator.vinculo());
      audit.registrarOperacao(
          u,
          ator.usuario(),
          ator.vinculo(),
          ator.nome(),
          "ESTOQUE_CONSUMIDO",
          "ESTOQUE",
          id,
          Map.of("quantidade", p.quantidade()),
          Map.of("quantidade", next, "operacao", operacao));
    }
  }
}
