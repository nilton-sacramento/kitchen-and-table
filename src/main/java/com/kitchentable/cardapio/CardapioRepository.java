package com.kitchentable.cardapio;

import static com.kitchentable.cardapio.CardapioDtos.*;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class CardapioRepository {
  private final JdbcTemplate jdbc;

  public CardapioRepository(JdbcTemplate j) {
    jdbc = j;
  }

  public JdbcTemplate jdbc() {
    return jdbc;
  }

  public List<Categoria> categorias(UUID u) {
    return jdbc.query(
        "SELECT * FROM categorias_cardapio WHERE estabelecimento_id=? ORDER BY"
            + " ordem,lower(nome),id",
        (r, n) ->
            new Categoria(
                r.getObject("id", UUID.class),
                r.getString("nome"),
                r.getInt("ordem"),
                r.getBoolean("ativo"),
                r.getLong("versao")),
        u);
  }

  public Categoria categoria(UUID u, UUID id) {
    return categorias(u).stream()
        .filter(x -> x.id().equals(id))
        .findFirst()
        .orElseThrow(() -> erro(404, "Categoria nao encontrada nesta unidade"));
  }

  public List<Componente> componentes(UUID u, UUID id) {
    return jdbc.query(
        "SELECT produto_id,quantidade FROM componentes_combo WHERE estabelecimento_id=? AND"
            + " combo_id=? ORDER BY produto_id",
        (r, n) -> new Componente(r.getObject(1, UUID.class), r.getInt(2)),
        u,
        id);
  }

  public List<Produto> produtos(UUID u) {
    var base =
        jdbc.query(
            "SELECT p.*,c.nome AS categoria,c.ativo AS categoria_ativa FROM produtos_cardapio p"
                + " JOIN categorias_cardapio c ON c.id=p.categoria_id AND"
                + " c.estabelecimento_id=p.estabelecimento_id WHERE p.estabelecimento_id=? ORDER BY"
                + " lower(p.nome),p.id",
            (r, n) ->
                new Produto(
                    r.getObject("id", UUID.class),
                    r.getObject("categoria_id", UUID.class),
                    r.getString("categoria"),
                    r.getString("nome"),
                    r.getString("descricao"),
                    Tipo.valueOf(r.getString("tipo")),
                    r.getLong("preco_centavos"),
                    r.getString("setor") == null ? null : Setor.valueOf(r.getString("setor")),
                    r.getBoolean("ativo"),
                    r.getBoolean("disponivel"),
                    r.getBoolean("controla_quantidade"),
                    r.getInt("quantidade"),
                    r.getLong("versao"),
                    r.getBoolean("ativo")
                        && r.getBoolean("disponivel")
                        && r.getBoolean("categoria_ativa")
                        && (!r.getBoolean("controla_quantidade") || r.getInt("quantidade") > 0),
                    List.of()),
            u);
    var all = new HashMap<UUID, Produto>();
    for (var p : base) all.put(p.id(), p);
    var result = new ArrayList<Produto>();
    for (var p : base) {
      var components = componentes(u, p.id());
      boolean available = p.vendavel();
      if (p.tipo() == Tipo.COMBO)
        available &=
            !components.isEmpty()
                && components.stream()
                    .allMatch(
                        c -> {
                          var s = all.get(c.produtoId());
                          return s != null
                              && s.tipo() == Tipo.SIMPLES
                              && s.vendavel()
                              && (!s.controlaQuantidade() || s.quantidade() >= c.quantidade());
                        });
      result.add(
          new Produto(
              p.id(),
              p.categoriaId(),
              p.categoria(),
              p.nome(),
              p.descricao(),
              p.tipo(),
              p.precoCentavos(),
              p.setor(),
              p.ativo(),
              p.disponivel(),
              p.controlaQuantidade(),
              p.quantidade(),
              p.versao(),
              available,
              components));
    }
    return result;
  }

  public Produto produto(UUID u, UUID id, boolean lock) {
    if (lock
        && jdbc.query(
                "SELECT id FROM produtos_cardapio WHERE estabelecimento_id=? AND id=? FOR UPDATE",
                (r, n) -> r.getObject(1, UUID.class),
                u,
                id)
            .isEmpty()) throw erro(404, "Produto nao encontrado nesta unidade");
    return produtos(u).stream()
        .filter(p -> p.id().equals(id))
        .findFirst()
        .orElseThrow(() -> erro(404, "Produto nao encontrado nesta unidade"));
  }

  public void componentes(UUID u, UUID combo, List<Componente> list) {
    jdbc.update(
        "DELETE FROM componentes_combo WHERE estabelecimento_id=? AND combo_id=?", u, combo);
    for (var c : list)
      jdbc.update(
          "INSERT INTO componentes_combo(estabelecimento_id,combo_id,produto_id,quantidade)"
              + " VALUES(?,?,?,?)",
          u,
          combo,
          c.produtoId(),
          c.quantidade());
  }

  public List<Movimento> movimentos(UUID u, UUID id) {
    return jdbc.query(
        "SELECT * FROM movimentos_cardapio WHERE estabelecimento_id=? AND produto_id=? ORDER BY"
            + " ocorrido_em,id",
        (r, n) ->
            new Movimento(
                r.getObject("id", UUID.class),
                r.getString("tipo"),
                r.getInt("anterior"),
                r.getInt("posterior"),
                r.getString("motivo"),
                r.getTimestamp("ocorrido_em").toInstant()),
        u,
        id);
  }

  public static ResponseStatusException erro(int status, String message) {
    return new ResponseStatusException(HttpStatus.valueOf(status), message);
  }
}
