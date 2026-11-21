package com.kitchentable.caixa;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dockerjava.api.model.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class FinanceiroMigrationTest {
  PostgreSQLContainer banco() {
    return new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("kt_fin_migration")
        .withUsername("kt_test")
        .withPassword(UUID.randomUUID().toString())
        .withReuse(false)
        .withLabel("com.kitchentable.scope", "discardable-financeiro-2-7")
        .withCreateContainerCmdModifier(
            c ->
                c.getHostConfig()
                    .withPortBindings(
                        new PortBinding(
                            Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(5432))));
  }

  Flyway flyway(PostgreSQLContainer p, String target) {
    var f = Flyway.configure().dataSource(p.getJdbcUrl(), p.getUsername(), p.getPassword());
    if (target != null) f.target(target);
    return f.load();
  }

  @Test
  void instalacaoLimpaPostgres16SemConcessaoFinanceiraAutomatica() {
    try (var pg = banco()) {
      pg.start();
      flyway(pg, null).migrate();
      var j =
          new JdbcTemplate(
              new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
      assertEquals(
          19,
          j.queryForObject(
              "SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL",
              Integer.class));
      assertEquals(68, j.queryForObject("SELECT count(*) FROM permissoes", Integer.class));
      assertEquals(
          0,
          j.queryForObject(
              "SELECT count(*) FROM funcoes_permissoes fp JOIN permissoes p ON p.id=fp.permissao_id"
                  + " WHERE p.categoria='FINANCEIRA'",
              Integer.class));
      assertEquals(0, j.queryForObject("SELECT count(*) FROM turnos_caixa", Integer.class));
      assertTrue(j.queryForObject("SELECT version()", String.class).contains("PostgreSQL 16"));
      flyway(pg, null).validate();
    }
  }

  @Test
  void atualizaV16PreservaCredencialConsumoEChecksums() {
    try (var pg = banco()) {
      pg.start();
      flyway(pg, "16").migrate();
      var j =
          new JdbcTemplate(
              new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
      var checks =
          j.queryForList(
              "SELECT version,checksum FROM flyway_schema_history WHERE version::int<=16 ORDER BY"
                  + " installed_rank");
      UUID user = UUID.randomUUID(),
          unit = UUID.randomUUID(),
          link = UUID.randomUUID(),
          comanda = UUID.randomUUID();
      String hash =
          new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4)
              .encode(UUID.randomUUID().toString());
      j.update(
          "INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,'Legado"
              + " financeiro','legacy27@example.com',?)",
          user,
          hash);
      j.update("INSERT INTO estabelecimentos(id,nome) VALUES(?,'Unidade ficticia migracao')", unit);
      j.update(
          "INSERT INTO usuarios_estabelecimentos(id,usuario_id,estabelecimento_id,funcao_id) SELECT"
              + " ?,?,?,id FROM funcoes WHERE codigo='ATENDENTE'",
          link,
          user,
          unit);
      j.update(
          "INSERT INTO"
              + " comandas(id,estabelecimento_id,codigo,modalidade,aberto_por,teve_consumo,possui_pendencias)"
              + " VALUES(?,?,'C-LEGADO','BALCAO',?,true,true)",
          comanda,
          unit,
          link);
      flyway(pg, null).migrate();
      assertEquals(
          hash, j.queryForObject("SELECT senha_hash FROM usuarios WHERE id=?", String.class, user));
      assertEquals(
          checks,
          j.queryForList(
              "SELECT version,checksum FROM flyway_schema_history WHERE version::int<=16 ORDER BY"
                  + " installed_rank"));
      assertTrue(
          j.queryForObject(
              "SELECT teve_consumo AND possui_pendencias AND estado='ABERTA' AND quitacao_id IS"
                  + " NULL FROM comandas WHERE id=?",
              Boolean.class,
              comanda));
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () -> j.update("UPDATE comandas SET teve_consumo=false WHERE id=?", comanda));
      flyway(pg, null).validate();
    }
  }
}
