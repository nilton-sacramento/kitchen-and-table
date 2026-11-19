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
    consumir(u, auth, esperado, operacao, consumo, "PEDIDO_ENVIAR");
  }

  public void consumir(
      UUID u,
      Authentication auth,
      UUID esperado,
      UUID operacao,
      Map<UUID, Integer> consumo,
      String capacidade) {
    if (!Set.of("PEDIDO_ENVIAR", "PEDIDO_ENVIADO_ALTERAR").contains(capacidade))
      throw erro(403, "Contrato de consumo nao autorizado");
    var ator = acesso.operador(u, auth, esperado, capacidade, true);
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
              "UPDATE produtos_cardapio SET"
                  + " quantidade=?,versao=versao+1,atualizado_em=clock_timestamp() WHERE"
                  + " estabelecimento_id=? AND id=?",
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

  public void devolver(
      UUID u,
      Authentication auth,
      UUID esperado,
      UUID pedido,
      Map<UUID, Integer> devolucao,
      String capacidade) {
    if (!Set.of("PEDIDO_CANCELAR", "PEDIDO_ENVIADO_ALTERAR").contains(capacidade))
      throw erro(403, "Devolucao nao autorizada");
    var ator = acesso.operador(u, auth, esperado, capacidade, true);
    for (UUID id : new TreeSet<>(devolucao.keySet())) {
      int amount = devolucao.get(id);
      if (amount <= 0) throw erro(400, "Quantidade de devolucao invalida");
      var p = repo.produto(u, id, true);
      var original =
          repo.jdbc()
              .queryForList(
                  "SELECT anterior,posterior FROM movimentos_cardapio WHERE estabelecimento_id=?"
                      + " AND produto_id=? AND operacao_id=? AND tipo='CONSUMO'",
                  u,
                  id,
                  pedido);
      if (original.isEmpty()) continue;
      var row = original.get(0);
      int consumed = (Integer) row.get("anterior") - (Integer) row.get("posterior");
      if (amount > consumed) throw erro(409, "Devolucao acima do consumo original");
      var prior =
          repo.jdbc()
              .queryForList(
                  "SELECT anterior,posterior FROM movimentos_cardapio WHERE estabelecimento_id=?"
                      + " AND produto_id=? AND operacao_id=? AND tipo='DEVOLUCAO'",
                  u,
                  id,
                  pedido);
      if (!prior.isEmpty()) {
        var r = prior.get(0);
        if ((Integer) r.get("posterior") - (Integer) r.get("anterior") != amount)
          throw erro(409, "Devolucao repetida com outra quantidade");
        continue;
      }
      int next;
      try {
        next = Math.addExact(p.quantidade(), amount);
      } catch (ArithmeticException e) {
        throw erro(409, "Saldo acima do limite de quantidade");
      }
      repo.jdbc()
          .update(
              "UPDATE produtos_cardapio SET"
                  + " quantidade=?,versao=versao+1,atualizado_em=clock_timestamp() WHERE"
                  + " estabelecimento_id=? AND id=?",
              next,
              u,
              id);
      repo.jdbc()
          .update(
              "INSERT INTO"
                  + " movimentos_cardapio(estabelecimento_id,produto_id,operacao_id,tipo,anterior,posterior,executor,motivo)"
                  + " VALUES(?,?,?,'DEVOLUCAO',?,?,?,'Cancelamento autorizado antes do preparo')",
              u,
              id,
              pedido,
              p.quantidade(),
              next,
              ator.vinculo());
      audit.registrarOperacao(
          u,
          ator.usuario(),
          ator.vinculo(),
          ator.nome(),
          "ESTOQUE_DEVOLVIDO",
          "ESTOQUE",
          id,
          Map.of("quantidade", p.quantidade()),
          Map.of("quantidade", next, "pedidoId", pedido));
    }
  }
}
