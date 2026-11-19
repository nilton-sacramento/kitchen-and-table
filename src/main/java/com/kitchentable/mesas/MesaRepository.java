package com.kitchentable.mesas;

import static com.kitchentable.mesas.MesaDtos.*;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class MesaRepository {
  private final JdbcTemplate jdbc;

  public MesaRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final String SELECT =
      "SELECT m.*,c.id AS comanda_ativa,c.codigo FROM mesas m LEFT JOIN comandas c ON"
          + " c.mesa_id=m.id AND c.estabelecimento_id=m.estabelecimento_id AND c.estado='ABERTA' ";

  private Mesa map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Mesa(
        r.getObject("id", UUID.class),
        r.getString("identificacao"),
        r.getBoolean("ativo"),
        !r.getBoolean("ativo")
            ? "INDISPONIVEL"
            : r.getObject("comanda_ativa") != null ? "OCUPADA" : "LIVRE",
        r.getLong("versao"),
        r.getObject("comanda_ativa", UUID.class),
        r.getString("codigo"));
  }

  public List<Mesa> listar(UUID unidade) {
    return jdbc.query(
        SELECT + "WHERE m.estabelecimento_id=? ORDER BY lower(m.identificacao),m.id",
        this::map,
        unidade);
  }

  public Mesa buscar(UUID unidade, UUID id, boolean bloquear) {
    if (bloquear) {
      var ids =
          jdbc.query(
              "SELECT id FROM mesas WHERE estabelecimento_id=? AND id=? FOR UPDATE",
              (r, n) -> r.getObject(1, UUID.class),
              unidade,
              id);
      if (ids.isEmpty()) throw ausente();
    }
    var rows =
        jdbc.query(SELECT + "WHERE m.estabelecimento_id=? AND m.id=?", this::map, unidade, id);
    if (rows.isEmpty()) throw ausente();
    return rows.get(0);
  }

  public Mesa criar(UUID unidade, String nome) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mesas(id,estabelecimento_id,identificacao) VALUES(?,?,?)", id, unidade, nome);
    return buscar(unidade, id, false);
  }

  public Mesa editar(UUID unidade, UUID id, String nome) {
    jdbc.update(
        "UPDATE mesas SET identificacao=?,versao=versao+1 WHERE estabelecimento_id=? AND id=?",
        nome,
        unidade,
        id);
    return buscar(unidade, id, false);
  }

  public Mesa situacao(UUID unidade, UUID id, boolean ativo) {
    jdbc.update(
        "UPDATE mesas SET ativo=?,versao=versao+1 WHERE estabelecimento_id=? AND id=?",
        ativo,
        unidade,
        id);
    return buscar(unidade, id, false);
  }

  public List<Uso> historico(UUID unidade, UUID id) {
    return jdbc.query(
        "SELECT id,codigo,estado,aberto_em,finalizado_em FROM comandas WHERE estabelecimento_id=?"
            + " AND mesa_id=? ORDER BY aberto_em DESC,id",
        (r, n) ->
            new Uso(
                r.getObject(1, UUID.class),
                r.getString(2),
                r.getString(3),
                r.getTimestamp(4).toInstant(),
                r.getTimestamp(5) == null ? null : r.getTimestamp(5).toInstant()),
        unidade,
        id);
  }

  private ResponseStatusException ausente() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Mesa nao encontrada nesta unidade");
  }
}
