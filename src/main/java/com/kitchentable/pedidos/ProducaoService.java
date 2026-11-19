package com.kitchentable.pedidos;

import static com.kitchentable.cardapio.CardapioRepository.erro;
import static com.kitchentable.pedidos.PedidoDtos.*;

import com.kitchentable.funcionarios.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProducaoService {
  private final PedidoRepository repo;
  private final AcessoAtendimentoService acesso;
  private final PedidoRegistroService registro;

  public ProducaoService(PedidoRepository r, AcessoAtendimentoService a, PedidoRegistroService s) {
    repo = r;
    acesso = a;
    registro = s;
  }

  static String setor(String s) {
    if (!Set.of("COZINHA", "BAR").contains(s)) throw erro(400, "Setor invalido");
    return s;
  }

  public List<FilaItem> fila(UUID u, String sector, Authentication a, UUID esperado) {
    setor(sector);
    acesso.operador(u, a, esperado, sector + "_FILA_CONSULTAR");
    return repo.fila(u, sector);
  }

  public List<DecisaoFila> filaSolicitacoes(
      UUID u, String sector, Authentication a, UUID esperado) {
    setor(sector);
    acesso.operador(u, a, esperado, sector + "_FILA_CONSULTAR");
    return repo.filaSolicitacoes(u, sector);
  }

  public List<Pedido> listar(UUID u, UUID comanda, Authentication a, UUID esperado) {
    acesso.operador(u, a, esperado, "PEDIDO_CONSULTAR");
    if (repo.jdbc()
        .queryForList("SELECT id FROM comandas WHERE estabelecimento_id=? AND id=?", u, comanda)
        .isEmpty()) throw erro(404, "Comanda nao encontrada nesta unidade");
    return repo.listar(u, comanda);
  }

  public Pedido buscar(UUID u, UUID id, Authentication a, UUID esperado) {
    acesso.operador(u, a, esperado, "PEDIDO_CONSULTAR");
    return repo.pedido(u, id);
  }

  public List<Evento> historico(UUID u, UUID id, Authentication a, UUID esperado) {
    acesso.operador(u, a, esperado, "PEDIDO_CONSULTAR");
    repo.pedido(u, id);
    return repo.historico(u, id);
  }

  public Item preparar(
      UUID u, String sector, UUID id, Authentication a, UUID esperado, Atualizacao d) {
    setor(sector);
    var ator = acesso.operador(u, a, esperado, sector + "_PREPARO_ATUALIZAR", true);
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT pedido_id FROM itens_preparo WHERE estabelecimento_id=? AND id=? AND"
                    + " setor=?",
                u,
                id,
                sector);
    if (rows.isEmpty()) throw erro(404, "Item nao encontrado neste setor e unidade");
    UUID pedido = (UUID) rows.get(0).get("pedido_id");
    repo.bloquearPedido(u, pedido);
    var item =
        repo.itens(u, pedido).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    if (d.versao() == null || item.versao() != d.versao())
      throw erro(409, "Item alterado. Atualize a fila");
    boolean frozen =
        repo.pendencias(u, pedido, sector).stream()
            .anyMatch(x -> x.estadoDecisao().equals("ACEITA"));
    if (frozen)
      throw erro(409, "Setor ja aprovou alteracao pendente; aguarde a decisao dos demais setores");
    boolean valid =
        item.estado().equals("AGUARDANDO") && d.estado() == EstadoItem.EM_PREPARO
            || item.estado().equals("EM_PREPARO") && d.estado() == EstadoItem.PRONTO;
    if (!valid) throw erro(409, "Transicao de preparo invalida");
    repo.jdbc()
        .update(
            "UPDATE itens_preparo SET"
                + " estado=?,versao=versao+1,atualizado_por=?,atualizado_em=clock_timestamp() WHERE"
                + " estabelecimento_id=? AND id=?",
            d.estado().name(),
            ator.vinculo(),
            u,
            id);
    repo.atualizarVersao(u, pedido);
    registro.evento(
        u,
        ator,
        d.estado() == EstadoItem.PRONTO ? "ITEM_PRONTO" : "ITEM_EM_PREPARO",
        pedido,
        Map.of("itemId", id, "setor", sector, "estado", d.estado()));
    return repo.itens(u, pedido).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
  }

  public Pedido entregar(UUID u, UUID id, Authentication a, UUID esperado, Entrega d) {
    var ator = acesso.operador(u, a, esperado, "PEDIDO_ENTREGAR", true);
    repo.bloquearPedido(u, id);
    var p = repo.pedido(u, id);
    if (d.itens() == null || d.itens().isEmpty()) throw erro(400, "Selecione itens prontos");
    var selected = new HashMap<UUID, Long>();
    for (var ref : d.itens())
      if (selected.put(ref.id(), ref.versao()) != null) throw erro(400, "Item repetido");
    var live =
        p.itens().stream()
            .filter(i -> !Set.of("CANCELADO", "ENTREGUE").contains(i.estado()))
            .toList();
    if (p.tudoJunto()
        && (live.size() != selected.size()
            || live.stream()
                .anyMatch(i -> !i.estado().equals("PRONTO") || !selected.containsKey(i.id()))))
      throw erro(
          409,
          "Cliente solicitou tudo junto; aguarde todos os setores e entregue o conjunto completo");
    for (var entry : selected.entrySet()) {
      var item =
          p.itens().stream()
              .filter(x -> x.id().equals(entry.getKey()))
              .findFirst()
              .orElseThrow(() -> erro(404, "Item nao encontrado no pedido"));
      if (item.versao() != entry.getValue() || !item.estado().equals("PRONTO"))
        throw erro(409, "Item nao esta pronto ou ja foi atualizado");
      if (repo.pendencias(u, id, item.setor()).stream()
          .anyMatch(x -> x.estadoDecisao().equals("ACEITA")))
        throw erro(409, "Entrega bloqueada por decisao de alteracao pendente");
    }
    for (UUID item : selected.keySet()) {
      repo.jdbc()
          .update(
              "UPDATE itens_preparo SET"
                  + " estado='ENTREGUE',versao=versao+1,atualizado_por=?,atualizado_em=clock_timestamp(),entregue_em=clock_timestamp()"
                  + " WHERE estabelecimento_id=? AND id=?",
              ator.vinculo(),
              u,
              item);
      registro.evento(u, ator, "ITEM_ENTREGUE", id, Map.of("itemId", item));
    }
    repo.atualizarVersao(u, id);
    return repo.pedido(u, id);
  }

  public List<Solicitacao> solicitacoes(UUID u, UUID id, Authentication a, UUID esperado) {
    acesso.operador(u, a, esperado, "PEDIDO_CONSULTAR");
    repo.pedido(u, id);
    return repo.solicitacoes(u, id);
  }
}
