package com.kitchentable.caixa;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ExternoMigrationTest {
  @Test
  void upgrade19PreservaHistoricoCredencialChecksumsEVinculos() {
    var f = new FinanceiroMigrationTest();
    try (var pg = f.banco()) {
      pg.start();
      f.flyway(pg, "19").migrate();
      var j =
          new JdbcTemplate(
              new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
      var sums =
          j.queryForList(
              "SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
      UUID user = UUID.randomUUID(),
          u = UUID.randomUUID(),
          v = UUID.randomUUID(),
          c = UUID.randomUUID();
      String hash =
          new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4)
              .encode(UUID.randomUUID().toString());
      j.update(
          "INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,'Pessoa legada"
              + " ficticia','legacy28@example.com',?)",
          user,
          hash);
      j.update("INSERT INTO estabelecimentos(id,nome) VALUES(?,'Legado ficticio28')", u);
      j.update(
          "INSERT INTO usuarios_estabelecimentos(id,usuario_id,estabelecimento_id,funcao_id) SELECT"
              + " ?,?,?,id FROM funcoes WHERE codigo='ATENDENTE'",
          v,
          user,
          u);
      j.update(
          "INSERT INTO comandas(id,estabelecimento_id,codigo,modalidade,aberto_por)"
              + " VALUES(?,?,'C-LEGADO28','BALCAO',?)",
          c,
          u,
          v);
      f.flyway(pg, null).migrate();
      assertEquals(
          sums,
          j.queryForList(
              "SELECT version,checksum FROM flyway_schema_history WHERE version::int<=19 ORDER BY"
                  + " installed_rank"));
      assertEquals(
          hash, j.queryForObject("SELECT senha_hash FROM usuarios WHERE id=?", String.class, user));
      assertEquals(
          "ABERTA", j.queryForObject("SELECT estado FROM comandas WHERE id=?", String.class, c));
      assertEquals(
          23, j.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
      assertEquals(
          0, j.queryForObject("SELECT count(*) FROM atendimentos_externos", Integer.class));
      assertEquals(
          0,
          j.queryForObject(
              "SELECT count(*) FROM funcoes_permissoes fp JOIN permissoes p ON p.id=fp.permissao_id"
                  + " WHERE p.codigo LIKE 'DELIVERY_%' OR p.codigo LIKE 'VENDA_RAPIDA_%'",
              Integer.class));
      f.flyway(pg, null).validate();
    }
  }
}
