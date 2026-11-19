package com.kitchentable.pedidos;

import static com.kitchentable.cardapio.CardapioRepository.erro;
import static com.kitchentable.pedidos.PedidoDtos.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PedidoRepository {
  private final JdbcTemplate j;
  private final ObjectMapper json;

  public PedidoRepository(JdbcTemplate j, ObjectMapper m) {
    this.j = j;
    json = m;
  }

  public JdbcTemplate jdbc() {
    return j;
  }

  public String json(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Falha de snapshot");
    }
  }

  public Confirmacao proposta(String s) {
    try {
      return json.readValue(s, Confirmacao.class);
    } catch (Exception e) {
      throw new IllegalStateException("Proposta invalida");
    }
  }

  public void bloquearComanda(UUID u, UUID id) {
    var list =
        j.queryForList(
            "SELECT estado FROM comandas WHERE estabelecimento_id=? AND id=? FOR UPDATE", u, id);
    if (list.isEmpty()) throw erro(404, "Comanda nao encontrada nesta unidade");
    if (!"ABERTA".equals(list.get(0).get("estado")))
      throw erro(409, "Comanda finalizada nao recebe operacoes de pedido");
  }

  public void bloquearPedido(UUID u, UUID id) {
    if (j.queryForList(
            "SELECT id FROM pedidos WHERE estabelecimento_id=? AND id=? FOR UPDATE", u, id)
        .isEmpty()) throw erro(404, "Pedido nao encontrado nesta unidade");
  }

  public Pedido pedido(UUID u, UUID id) {
    var rows =
        j.queryForList(
            "SELECT p.*,c.codigo AS comanda,m.identificacao AS"
                + " mesa,COALESCE(ue.nome_exibicao,usr.nome) AS autor FROM pedidos p JOIN comandas"
                + " c ON c.id=p.comanda_id AND c.estabelecimento_id=p.estabelecimento_id LEFT JOIN"
                + " mesas m ON m.id=c.mesa_id AND m.estabelecimento_id=c.estabelecimento_id JOIN"
                + " usuarios_estabelecimentos ue ON ue.id=p.aberto_por JOIN usuarios usr ON"
                + " usr.id=ue.usuario_id WHERE p.estabelecimento_id=? AND p.id=?",
            u,
            id);
    if (rows.isEmpty()) throw erro(404, "Pedido nao encontrado nesta unidade");
    var r = rows.get(0);
    var lines =
        j.query(
            "SELECT * FROM linhas_pedido WHERE estabelecimento_id=? AND pedido_id=? ORDER BY id",
            (rs, n) -> {
              List<ComponenteSnapshot> composition;
              try {
                composition =
                    json.readValue(
                        rs.getString("composicao"),
                        new TypeReference<List<ComponenteSnapshot>>() {});
              } catch (Exception e) {
                throw new IllegalStateException("Snapshot invalido");
              }
              return new Linha(
                  rs.getObject("id", UUID.class),
                  rs.getObject("produto_id", UUID.class),
                  rs.getString("nome"),
                  rs.getString("tipo"),
                  rs.getInt("quantidade"),
                  rs.getLong("preco_unitario_centavos"),
                  rs.getLong("total_centavos"),
                  rs.getString("observacao"),
                  composition);
            },
            u,
            id);
    var items = itens(u, id);
    long total = 0;
    for (var l : lines) total = Math.addExact(total, l.totalCentavos());
    return new Pedido(
        id,
        (UUID) r.get("comanda_id"),
        (String) r.get("codigo"),
        (String) r.get("comanda"),
        (String) r.get("mesa"),
        (UUID) r.get("aberto_por"),
        (String) r.get("autor"),
        ((java.sql.Timestamp) r.get("aberto_em")).toInstant(),
        (Boolean) r.get("tudo_junto"),
        estado(items),
        total,
        ((Number) r.get("versao")).longValue(),
        (UUID) r.get("substitui_id"),
        lines,
        items);
  }

  public List<Item> itens(UUID u, UUID id) {
    return j.query(
        "SELECT * FROM itens_preparo WHERE estabelecimento_id=? AND pedido_id=? ORDER BY id",
        (r, n) ->
            new Item(
                r.getObject("id", UUID.class),
                r.getObject("linha_id", UUID.class),
                r.getObject("produto_id", UUID.class),
                r.getString("nome"),
                r.getString("setor"),
                r.getInt("quantidade"),
                r.getString("observacao"),
                r.getString("estado"),
                r.getLong("versao")),
        u,
        id);
  }

  public List<Pedido> listar(UUID u, UUID comanda) {
    var ids =
        j.query(
            "SELECT id FROM pedidos WHERE estabelecimento_id=? AND comanda_id=? ORDER BY"
                + " aberto_em,id",
            (r, n) -> r.getObject(1, UUID.class),
            u,
            comanda);
    return ids.stream().map(id -> pedido(u, id)).toList();
  }

  public static String estado(List<Item> items) {
    if (items.isEmpty()) return "AGUARDANDO";
    if (items.stream().allMatch(x -> x.estado().equals("CANCELADO"))) return "CANCELADO";
    var active = items.stream().filter(x -> !x.estado().equals("CANCELADO")).toList();
    if (active.stream().allMatch(x -> x.estado().equals("ENTREGUE"))) return "ENTREGUE";
    if (active.stream().anyMatch(x -> x.estado().equals("ENTREGUE")))
      return "PARCIALMENTE_ENTREGUE";
    if (active.stream().allMatch(x -> x.estado().equals("PRONTO"))) return "PRONTO";
    if (active.stream().anyMatch(x -> !x.estado().equals("AGUARDANDO"))) return "EM_PREPARO";
    return "AGUARDANDO";
  }

  public UUID repeticao(UUID u, UUID key, UUID actor, String hash) {
    j.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", (r, n) -> 1, u + ":pedido:" + key);
    var rows =
        j.queryForList(
            "SELECT * FROM operacoes_pedido WHERE estabelecimento_id=? AND chave=?", u, key);
    if (rows.isEmpty()) return null;
    var r = rows.get(0);
    if (!actor.equals(r.get("executor")) || !hash.equals(r.get("requisicao_hash")))
      throw erro(409, "Chave de pedido usada com outro conteudo ou operador");
    return (UUID) r.get("pedido_id");
  }

  public void memorizar(UUID u, UUID key, UUID actor, String hash, UUID id) {
    j.update(
        "INSERT INTO operacoes_pedido(estabelecimento_id,chave,executor,requisicao_hash,pedido_id)"
            + " VALUES(?,?,?,?,?)",
        u,
        key,
        actor,
        hash,
        id);
  }

  public List<Decisao> decisoes(UUID u, UUID id) {
    return j.query(
        "SELECT * FROM decisoes_pedido WHERE estabelecimento_id=? AND solicitacao_id=? ORDER BY"
            + " setor",
        (r, n) ->
            new Decisao(
                r.getString("setor"),
                r.getString("estado"),
                r.getObject("executor", UUID.class),
                r.getString("motivo"),
                r.getLong("versao")),
        u,
        id);
  }

  public Solicitacao solicitacao(UUID u, UUID id) {
    var rows =
        j.queryForList(
            "SELECT s.*,p.versao AS versao_pedido FROM solicitacoes_pedido s JOIN pedidos p ON"
                + " p.id=s.pedido_id AND p.estabelecimento_id=s.estabelecimento_id WHERE"
                + " s.estabelecimento_id=? AND s.id=?",
            u,
            id);
    if (rows.isEmpty()) throw erro(404, "Solicitacao nao encontrada nesta unidade");
    var r = rows.get(0);
    return new Solicitacao(
        id,
        (UUID) r.get("pedido_id"),
        (String) r.get("tipo"),
        (String) r.get("motivo"),
        (String) r.get("estado"),
        (String) r.get("resultado_motivo"),
        (UUID) r.get("solicitado_por"),
        ((java.sql.Timestamp) r.get("criado_em")).toInstant(),
        (UUID) r.get("novo_pedido_id"),
        ((Number) r.get("versao_pedido")).longValue(),
        decisoes(u, id));
  }

  public List<Solicitacao> solicitacoes(UUID u, UUID pedido) {
    return j
        .query(
            "SELECT id FROM solicitacoes_pedido WHERE estabelecimento_id=? AND pedido_id=? ORDER BY"
                + " criado_em,id",
            (r, n) -> r.getObject(1, UUID.class),
            u,
            pedido)
        .stream()
        .map(id -> solicitacao(u, id))
        .toList();
  }

  public List<Pendencia> pendencias(UUID u, UUID pedido, String setor) {
    return j.query(
        "SELECT s.id,s.tipo,s.motivo,d.estado,d.versao,p.versao AS versao_pedido FROM"
            + " solicitacoes_pedido s JOIN decisoes_pedido d ON d.solicitacao_id=s.id AND"
            + " d.estabelecimento_id=s.estabelecimento_id JOIN pedidos p ON p.id=s.pedido_id AND"
            + " p.estabelecimento_id=s.estabelecimento_id WHERE s.estabelecimento_id=? AND"
            + " s.pedido_id=? AND s.estado='PENDENTE' AND d.setor=?",
        (r, n) ->
            new Pendencia(
                r.getObject(1, UUID.class),
                r.getString(2),
                r.getString(3),
                r.getString(4),
                r.getLong(5),
                r.getLong(6)),
        u,
        pedido,
        setor);
  }

  public List<FilaItem> fila(UUID u, String setor) {
    return j.query(
        "SELECT i.*,p.codigo AS pedido,p.tudo_junto,c.codigo AS comanda,m.identificacao AS mesa"
            + " FROM itens_preparo i JOIN pedidos p ON p.id=i.pedido_id AND"
            + " p.estabelecimento_id=i.estabelecimento_id JOIN comandas c ON c.id=p.comanda_id AND"
            + " c.estabelecimento_id=p.estabelecimento_id LEFT JOIN mesas m ON m.id=c.mesa_id AND"
            + " m.estabelecimento_id=c.estabelecimento_id WHERE i.estabelecimento_id=? AND"
            + " i.setor=? AND i.estado NOT IN ('CANCELADO','ENTREGUE') ORDER BY p.aberto_em,i.id",
        (r, n) ->
            new FilaItem(
                r.getObject("id", UUID.class),
                r.getObject("pedido_id", UUID.class),
                r.getString("pedido"),
                r.getString("comanda"),
                r.getString("mesa"),
                r.getString("nome"),
                r.getInt("quantidade"),
                r.getString("observacao"),
                r.getString("setor"),
                r.getString("estado"),
                r.getLong("versao"),
                r.getBoolean("tudo_junto"),
                pendencias(u, r.getObject("pedido_id", UUID.class), setor)),
        u,
        setor);
  }

  public void atualizarVersao(UUID u, UUID pedido) {
    j.update("UPDATE pedidos SET versao=versao+1 WHERE estabelecimento_id=? AND id=?", u, pedido);
  }

  public List<ItemProposto> propostos(String data) {
    try {
      return json.readValue(data, new TypeReference<List<ItemProposto>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Proposta historica invalida");
    }
  }

  public List<DecisaoFila> filaSolicitacoes(UUID u, String setor) {
    return j.query(
        "SELECT s.*,d.estado AS decisao_estado,d.versao AS decisao_versao,p.versao AS"
            + " pedido_versao,p.codigo AS pedido,c.codigo AS comanda,m.identificacao AS mesa FROM"
            + " solicitacoes_pedido s JOIN decisoes_pedido d ON d.solicitacao_id=s.id AND"
            + " d.estabelecimento_id=s.estabelecimento_id JOIN pedidos p ON p.id=s.pedido_id AND"
            + " p.estabelecimento_id=s.estabelecimento_id JOIN comandas c ON c.id=p.comanda_id AND"
            + " c.estabelecimento_id=p.estabelecimento_id LEFT JOIN mesas m ON m.id=c.mesa_id AND"
            + " m.estabelecimento_id=c.estabelecimento_id WHERE s.estabelecimento_id=? AND"
            + " d.setor=? AND s.estado='PENDENTE' ORDER BY s.criado_em,s.id",
        (r, n) ->
            new DecisaoFila(
                r.getObject("id", UUID.class),
                r.getObject("pedido_id", UUID.class),
                r.getString("pedido"),
                r.getString("comanda"),
                r.getString("mesa"),
                r.getString("tipo"),
                r.getString("motivo"),
                r.getString("decisao_estado"),
                r.getLong("decisao_versao"),
                r.getLong("pedido_versao"),
                propostos(r.getString("proposta_itens")).stream()
                    .filter(x -> x.setor().equals(setor))
                    .toList()),
        u,
        setor);
  }

  public List<Evento> historico(UUID u, UUID id) {
    return j.query(
        "SELECT tipo_evento,dados_evento->>'executorNome' AS nome,ocorrido_em FROM"
            + " auditoria_eventos WHERE estabelecimento_id=? AND entidade_tipo IN"
            + " ('PEDIDO','SOLICITACAO_PEDIDO') AND (entidade_id=? OR"
            + " dados_evento->'depois'->>'pedidoId'=?) ORDER BY ocorrido_em,id",
        (r, n) -> new Evento(r.getString(1), r.getString(2), r.getTimestamp(3).toInstant()),
        u,
        id,
        id.toString());
  }
}
