package com.kitchentable.comandas;

import static com.kitchentable.comandas.ComandaDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.dockerjava.api.model.*;
import com.kitchentable.funcionarios.*;
import com.kitchentable.mesas.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "server.address=127.0.0.1",
      "debug=false",
      "logging.level.org.springframework=INFO"
    })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class MesasComandasPostgresTest {
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("kt_atendimento_test")
          .withUsername("kt_test")
          .withPassword(UUID.randomUUID().toString())
          .withReuse(false)
          .withLabel("com.kitchentable.scope", "discardable-test-2-4")
          .withCreateContainerCmdModifier(
              c ->
                  c.getHostConfig()
                      .withPortBindings(
                          new PortBinding(
                              Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(5432))));
  static final String PASSWORD = UUID.randomUUID().toString(),
      HASH =
          new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD);
  static final UUID LEGACY = UUID.randomUUID();
  static List<Map<String, Object>> checksums;

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry p) {
    PG.start();
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
        .target("10")
        .load()
        .migrate();
    var j =
        new JdbcTemplate(
            new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    j.update(
        "INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,'Legado',?,?)",
        LEGACY,
        "legacy24@example.com",
        HASH);
    checksums =
        j.queryForList(
            "SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY"
                + " installed_rank");
    p.add("spring.datasource.url", PG::getJdbcUrl);
    p.add("spring.datasource.username", PG::getUsername);
    p.add("spring.datasource.password", PG::getPassword);
  }

  @Autowired JdbcTemplate j;
  @Autowired MesaService mesas;
  @Autowired ComandaService comandas;
  @Autowired FuncionarioService funcionarios;
  @Autowired PermissoesService permissoes;
  @Autowired OperacionalService op;
  @Autowired MockMvc mvc;
  UUID a, b, owner, manager, managerLink;
  String email, pin;
  FuncionarioDtos.Funcionario f, g;
  Authentication admin, gerente, p, q;

  @BeforeEach
  void fixture() {
    a = unit();
    b = unit();
    owner = user();
    manager = user();
    email = mail(owner);
    link(owner, a, "PROPRIETARIO");
    link(owner, b, "PROPRIETARIO");
    managerLink = link(manager, a, "GERENTE");
    admin = auth(email, "ROLE_USER");
    gerente = auth(mail(manager), "ROLE_USER");
    f =
        funcionarios.cadastrar(
            a,
            email,
            UUID.randomUUID(),
            new FuncionarioDtos.Cadastro("Operador um", "ATENDENTE", null, null));
    g =
        funcionarios.cadastrar(
            a,
            email,
            UUID.randomUUID(),
            new FuncionarioDtos.Cadastro("Operador dois", "ATENDENTE", null, null));
    pin = String.format(Locale.ROOT, "%06d", new java.security.SecureRandom().nextInt(1000000));
    p = enter(f);
    q = enter(g);
  }

  UUID unit() {
    UUID id = UUID.randomUUID();
    j.update("INSERT INTO estabelecimentos(id,nome) VALUES(?,'Unidade ficticia')", id);
    return id;
  }

  String mail(UUID u) {
    return u + "@example.com";
  }

  UUID user() {
    UUID id = UUID.randomUUID();
    j.update(
        "INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,'Pessoa ficticia',?,?)",
        id,
        mail(id),
        HASH);
    return id;
  }

  UUID link(UUID u, UUID e, String r) {
    UUID id = UUID.randomUUID();
    j.update(
        "INSERT INTO usuarios_estabelecimentos(id,usuario_id,estabelecimento_id,funcao_id) SELECT"
            + " ?,?,?,id FROM funcoes WHERE codigo=?",
        id,
        u,
        e,
        r);
    return id;
  }

  Authentication auth(Object who, String role) {
    return new UsernamePasswordAuthenticationToken(
        who, null, List.of(new SimpleGrantedAuthority(role)));
  }

  Authentication enter(FuncionarioDtos.Funcionario person) {
    op.definir(a, person.vinculoId(), email, new OperacionalDtos.Definicao(pin, person.versao()));
    return auth(
        op.entrar(
            new OperacionalDtos.Entrada(a.toString(), person.vinculoId().toString(), pin), null),
        "ROLE_OPERADOR");
  }

  MesaDtos.Mesa mesa() {
    return mesas.criar(a, admin, new MesaDtos.Criacao("01"));
  }

  Comanda open(UUID m) {
    return comandas.abrir(
        a,
        p,
        f.vinculoId(),
        UUID.randomUUID(),
        new Abertura(m == null ? Modalidade.BALCAO : Modalidade.MESA, m, null));
  }

  void grant(UUID l, String c, boolean allow) {
    permissoes.excecao(
        a,
        l,
        c,
        email,
        new PermissoesDtos.Excecao(
            allow ? PermissoesDtos.Estado.PERMITIR : PermissoesDtos.Estado.NEGAR,
            funcionarios.consultar(a, l, email).versao()));
  }

  int statusOf(Runnable action) {
    try {
      action.run();
      return 200;
    } catch (ResponseStatusException e) {
      return e.getStatusCode().value();
    }
  }

  long count(String table) {
    return j.queryForObject(
        "SELECT count(*) FROM "
            + table
            + " WHERE estabelecimento_id=?"
            + (table.equals("auditoria_eventos") ? " AND entidade_tipo IN ('MESA','COMANDA')" : ""),
        Long.class,
        a);
  }

  List<Integer> race(Callable<Integer> one, Callable<Integer> two) throws Exception {
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var x =
          pool.submit(
              () -> {
                start.await();
                return one.call();
              });
      var y =
          pool.submit(
              () -> {
                start.await();
                return two.call();
              });
      start.countDown();
      return List.of(x.get(20, TimeUnit.SECONDS), y.get(20, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void migraV10PreservaChecksumsECredencial() throws Exception {
    assertEquals(
        checksums,
        j.queryForList(
            "SELECT version,checksum FROM flyway_schema_history WHERE version::int<=10 ORDER BY"
                + " installed_rank"));
    assertEquals(19,
        j.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL", Integer.class));
    assertTrue(j.queryForObject("SELECT version()", String.class).contains("PostgreSQL 16"));
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .param("email", "legacy24@example.com")
                .param("password", PASSWORD))
        .andExpect(status().isNoContent());
  }

  @Test
  void identificacaoUnicaPorUnidade() {
    mesa();
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> mesas.criar(a, admin, new MesaDtos.Criacao(" 01 ")));
    assertNotNull(mesas.criar(b, admin, new MesaDtos.Criacao("01")));
  }

  @Test
  void editarAtivarDesativarEAuditar() {
    var m = mesa();
    m = mesas.editar(a, m.id(), admin, new MesaDtos.Edicao("Mesa nova", m.versao()));
    m = mesas.situacao(a, m.id(), admin, new MesaDtos.Situacao(false, m.versao()));
    assertEquals("INDISPONIVEL", m.situacao());
    m = mesas.situacao(a, m.id(), admin, new MesaDtos.Situacao(true, m.versao()));
    assertEquals("LIVRE", m.situacao());
    assertEquals(4, count("auditoria_eventos"));
  }

  @Test
  void versaoAntigaNaoSobrescreve() {
    var m = mesa();
    mesas.editar(a, m.id(), admin, new MesaDtos.Edicao("02", m.versao()));
    assertEquals(
        409, statusOf(() -> mesas.editar(a, m.id(), admin, new MesaDtos.Edicao("03", m.versao()))));
  }

  @Test
  void ocupadaNaoDesativa() {
    var m = mesa();
    open(m.id());
    assertEquals("OCUPADA", mesas.buscar(a, m.id(), p, f.vinculoId()).situacao());
    assertEquals(
        409,
        statusOf(() -> mesas.situacao(a, m.id(), admin, new MesaDtos.Situacao(false, m.versao()))));
  }

  @Test
  void inativaNaoAbre() {
    var m = mesa();
    mesas.situacao(a, m.id(), admin, new MesaDtos.Situacao(false, m.versao()));
    assertEquals(409, statusOf(() -> open(m.id())));
  }

  @Test
  void balcaoGeraCodigoSequencial() {
    var c = open(null);
    assertEquals("C-000001", c.codigo());
    assertNotEquals(c.codigo(), open(null).codigo());
    assertNull(c.mesaId());
  }

  @Test
  void modalidadeInvalidaRecusada() {
    assertEquals(
        400,
        statusOf(
            () ->
                comandas.abrir(
                    a,
                    p,
                    f.vinculoId(),
                    UUID.randomUUID(),
                    new Abertura(Modalidade.MESA, null, null))));
    assertEquals(
        400,
        statusOf(
            () ->
                comandas.abrir(
                    a,
                    p,
                    f.vinculoId(),
                    UUID.randomUUID(),
                    new Abertura(Modalidade.BALCAO, mesa().id(), null))));
  }

  @Test
  void qualquerAutorizadoAtendePreservandoAbertura() {
    var c = open(mesa().id());
    grant(g.vinculoId(), "COMANDA_ENCERRAR", true);
    var end = comandas.finalizar(a, c.id(), q, g.vinculoId(), new Transicao(0L, null), false);
    assertEquals(f.vinculoId(), end.abertoPor());
    assertEquals(g.vinculoId(), end.finalizadoPor());
    var events = comandas.historico(a, c.id(), q, g.vinculoId());
    assertEquals(2, events.size());
    assertEquals(g.usuarioId(), events.get(1).executor());
    assertEquals("Operador dois", events.get(1).nomeExecutor());
  }

  @Test
  void criticasSemConcessaoAutomatica() {
    assertEquals(403, statusOf(() -> mesas.criar(a, gerente, new MesaDtos.Criacao("01"))));
    var c = open(null);
    assertEquals(
        403,
        statusOf(
            () -> comandas.finalizar(a, c.id(), p, f.vinculoId(), new Transicao(0L, null), false)));
  }

  @Test
  void gerenteExigeConcessaoAtual() {
    grant(managerLink, "MESA_GERENCIAR", true);
    assertNotNull(mesas.criar(a, gerente, new MesaDtos.Criacao("01")));
    grant(managerLink, "MESA_GERENCIAR", false);
    assertEquals(403, statusOf(() -> mesas.criar(a, gerente, new MesaDtos.Criacao("02"))));
  }

  @Test
  void pinNaoAdministraMesas() {
    assertEquals(403, statusOf(() -> mesas.criar(a, p, new MesaDtos.Criacao("01"))));
  }

  @Test
  void senhaAdministrativaNaoSubstituiPin() {
    assertEquals(403, statusOf(() -> comandas.listar(a, admin, f.vinculoId(), null)));
  }

  @Test
  void isolamentoUnidadeEMesa() {
    var m = mesas.criar(b, admin, new MesaDtos.Criacao("01"));
    assertEquals(403, statusOf(() -> mesas.listar(b, p, f.vinculoId())));
    assertEquals(404, statusOf(() -> open(m.id())));
  }

  @Test
  void comandaOutraUnidadeNaoVaza() {
    var c = open(null);
    assertEquals(403, statusOf(() -> comandas.buscar(b, c.id(), p, f.vinculoId())));
    assertEquals(404, statusOf(() -> comandas.buscar(a, UUID.randomUUID(), p, f.vinculoId())));
  }

  @Test
  void headerNaoFalsificaExecutor() {
    assertEquals(
        403,
        statusOf(
            () ->
                comandas.abrir(
                    a,
                    p,
                    g.vinculoId(),
                    UUID.randomUUID(),
                    new Abertura(Modalidade.BALCAO, null, null))));
  }

  @Test
  void repeticaoIdempotenteNaoDuplica() {
    UUID key = UUID.randomUUID();
    var dto = new Abertura(Modalidade.BALCAO, null, "Cliente ficticio");
    var c = comandas.abrir(a, p, f.vinculoId(), key, dto);
    assertEquals(c.id(), comandas.abrir(a, p, f.vinculoId(), key, dto).id());
    assertEquals(1, count("comandas"));
    assertEquals(1, count("auditoria_eventos"));
    assertEquals(409, statusOf(() -> comandas.abrir(a, q, g.vinculoId(), key, dto)));
    assertEquals(
        409,
        statusOf(
            () ->
                comandas.abrir(
                    a, p, f.vinculoId(), key, new Abertura(Modalidade.BALCAO, null, "Outro"))));
  }

  @Test
  void revogacaoBloqueiaInclusiveRepeticao() {
    UUID key = UUID.randomUUID();
    var dto = new Abertura(Modalidade.BALCAO, null, null);
    comandas.abrir(a, p, f.vinculoId(), key, dto);
    grant(f.vinculoId(), "ATENDIMENTO_ABRIR", false);
    assertEquals(403, statusOf(() -> comandas.abrir(a, p, f.vinculoId(), key, dto)));
  }

  @Test
  void vinculoInativoBloqueia() {
    j.update("UPDATE usuarios_estabelecimentos SET ativo=false WHERE id=?", f.vinculoId());
    assertEquals(423, statusOf(() -> open(null)));
  }

  @Test
  void credencialBloqueadaImpedeOperacao() {
    j.update(
        "UPDATE credenciais_operacionais SET bloqueado=true WHERE vinculo_id=?", f.vinculoId());
    assertEquals(423, statusOf(() -> open(null)));
  }

  @Test
  void sessaoExpiradaNaoRenova() {
    var principal = (OperadorPrincipal) p.getPrincipal();
    j.update(
        "UPDATE sessoes_operacionais SET expira_em=clock_timestamp()-interval '1 second' WHERE"
            + " id=?",
        principal.sessaoId());
    assertEquals(423, statusOf(() -> open(null)));
  }

  @Test
  void cancelamentoExigeMotivo() {
    grant(f.vinculoId(), "COMANDA_CANCELAR", true);
    var c = open(null);
    assertEquals(
        400,
        statusOf(
            () -> comandas.finalizar(a, c.id(), p, f.vinculoId(), new Transicao(0L, " "), true)));
    var end =
        comandas.finalizar(
            a, c.id(), p, f.vinculoId(), new Transicao(0L, "Cliente desistiu"), true);
    assertEquals("CANCELADA", end.estado());
    assertEquals(
        "Cliente desistiu", comandas.historico(a, c.id(), p, f.vinculoId()).get(1).justificativa());
  }

  @Test
  void encerramentoLiberaMesaSemRepresentarPagamento() {
    grant(f.vinculoId(), "COMANDA_ENCERRAR", true);
    var m = mesa();
    var c = open(m.id());
    var end = comandas.finalizar(a, c.id(), p, f.vinculoId(), new Transicao(0L, null), false);
    assertEquals("ENCERRADA", end.estado());
    assertEquals("LIVRE", mesas.buscar(a, m.id(), admin, null).situacao());
    assertEquals(1, mesas.historico(a, m.id(), admin).size());
    assertEquals(
        409,
        statusOf(
            () -> comandas.finalizar(a, c.id(), p, f.vinculoId(), new Transicao(1L, null), false)));
  }

  @Test
  void consumoEPendenciasImpedemFinalizacao() {
    grant(f.vinculoId(), "COMANDA_ENCERRAR", true);
    for (String field : List.of("teve_consumo", "possui_pendencias")) {
      var c = open(null);
      j.update("UPDATE comandas SET " + field + "=true WHERE id=?", c.id());
      assertEquals(
          409,
          statusOf(
              () ->
                  comandas.finalizar(a, c.id(), p, f.vinculoId(), new Transicao(0L, null), false)));
    }
  }

  @Test
  void integracaoFuturaFalhaFechado() {
    grant(f.vinculoId(), "COMANDA_ENCERRAR", true);
    var c = open(null);
    try {
      j.update("UPDATE permissoes SET disponivel=true WHERE codigo='DESPESA_REGISTRAR'");
      assertEquals(
          409,
          statusOf(
              () ->
                  comandas.finalizar(a, c.id(), p, f.vinculoId(), new Transicao(0L, null), false)));
    } finally {
      j.update("UPDATE permissoes SET disponivel=false WHERE codigo='DESPESA_REGISTRAR'");
    }
  }

  @Test
  void concorrenciaMesaAbreUmaVez() throws Exception {
    var m = mesa();
    var results =
        race(
            () -> statusOf(() -> open(m.id())),
            () ->
                statusOf(
                    () ->
                        comandas.abrir(
                            a,
                            q,
                            g.vinculoId(),
                            UUID.randomUUID(),
                            new Abertura(Modalidade.MESA, m.id(), null))));
    assertTrue(results.containsAll(List.of(200, 409)));
    assertEquals(1, count("comandas"));
  }

  @Test
  void concorrenciaRepeticaoRetornaMesmoRegistro() throws Exception {
    UUID key = UUID.randomUUID();
    var dto = new Abertura(Modalidade.BALCAO, null, null);
    assertEquals(
        List.of(200, 200),
        race(
            () -> statusOf(() -> comandas.abrir(a, p, f.vinculoId(), key, dto)),
            () -> statusOf(() -> comandas.abrir(a, p, f.vinculoId(), key, dto))));
    assertEquals(1, count("comandas"));
  }

  @Test
  void concorrenciaEncerramentoUmEvento() throws Exception {
    grant(f.vinculoId(), "COMANDA_ENCERRAR", true);
    grant(g.vinculoId(), "COMANDA_ENCERRAR", true);
    var c = open(mesa().id());
    assertTrue(
        race(
                () ->
                    statusOf(
                        () ->
                            comandas.finalizar(
                                a, c.id(), p, f.vinculoId(), new Transicao(0L, null), false)),
                () ->
                    statusOf(
                        () ->
                            comandas.finalizar(
                                a, c.id(), q, g.vinculoId(), new Transicao(0L, null), false)))
            .containsAll(List.of(200, 409)));
    assertEquals(2, comandas.historico(a, c.id(), p, f.vinculoId()).size());
  }

  @Test
  void bancoPreservaHistoricoEAbertura() {
    var c = open(mesa().id());
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> j.update("DELETE FROM comandas WHERE id=?", c.id()));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> j.update("UPDATE comandas SET aberto_por=? WHERE id=?", g.vinculoId(), c.id()));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> j.update("DELETE FROM mesas WHERE id=?", c.mesaId()));
  }

  @Test
  void bancoImpedeSegundaComandaAtiva() {
    var c = open(mesa().id());
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            j.update(
                "INSERT INTO comandas(estabelecimento_id,codigo,modalidade,mesa_id,aberto_por)"
                    + " VALUES(?,'Outra','MESA',?,?)",
                a,
                c.mesaId(),
                f.vinculoId()));
  }

  @Test
  void httpExigeAutenticacaoPinECsrf() throws Exception {
    String url = "/api/v1/operacional/estabelecimentos/" + a + "/comandas";
    mvc.perform(get(url)).andExpect(status().isUnauthorized());
    mvc.perform(
            post(url)
                .with(authentication(p))
                .header("X-Operador-Vinculo", f.vinculoId())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content("{\"modalidade\":\"BALCAO\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(get(url).with(authentication(admin)).header("X-Operador-Vinculo", f.vinculoId()))
        .andExpect(status().isForbidden());
  }

  @Test
  void httpNaoAceitaExecutorDoCliente() throws Exception {
    mvc.perform(
            post("/api/v1/operacional/estabelecimentos/" + a + "/comandas")
                .with(authentication(p))
                .with(csrf())
                .header("X-Operador-Vinculo", f.vinculoId())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content("{\"modalidade\":\"BALCAO\",\"abertoPor\":\"" + g.vinculoId() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.abertoPor").value(f.vinculoId().toString()));
  }

  @Test
  void revogarFinalizacaoBloqueiaSessaoAberta() {
    grant(f.vinculoId(), "COMANDA_ENCERRAR", true);
    var c = open(null);
    grant(f.vinculoId(), "COMANDA_ENCERRAR", false);
    assertEquals(
        403,
        statusOf(
            () -> comandas.finalizar(a, c.id(), p, f.vinculoId(), new Transicao(0L, null), false)));
    assertEquals("ABERTA", comandas.buscar(a, c.id(), p, f.vinculoId()).estado());
  }

  @Test
  void bancoImpedeMesaEExecutorDeOutraUnidade() {
    var m = mesas.criar(b, admin, new MesaDtos.Criacao("Outra unidade"));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            j.update(
                "INSERT INTO comandas(estabelecimento_id,codigo,modalidade,mesa_id,aberto_por)"
                    + " VALUES(?,'Invalida','MESA',?,?)",
                a,
                m.id(),
                f.vinculoId()));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            j.update(
                "INSERT INTO comandas(estabelecimento_id,codigo,modalidade,aberto_por)"
                    + " VALUES(?,'Invalida','BALCAO',?)",
                b,
                f.vinculoId()));
  }

  @Test
  void auditoriaFalhaReverteAberturaEIdempotencia() {
    j.execute(
        "CREATE FUNCTION falha_auditoria_24() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
            + " NEW.tipo_evento='COMANDA_ABERTA' THEN RAISE EXCEPTION 'Teste transacional'; END IF;"
            + " RETURN NEW; END $$");
    j.execute(
        "CREATE TRIGGER falha_auditoria_24 BEFORE INSERT ON auditoria_eventos FOR EACH ROW EXECUTE"
            + " FUNCTION falha_auditoria_24()");
    try {
      assertThrows(org.springframework.dao.DataAccessException.class, () -> open(null));
      assertEquals(0, count("comandas"));
      assertEquals(0, count("operacoes_abertura_comanda"));
      assertEquals(0, count("sequencias_comandas"));
    } finally {
      j.execute("DROP TRIGGER falha_auditoria_24 ON auditoria_eventos");
      j.execute("DROP FUNCTION falha_auditoria_24()");
    }
  }

  @Test
  void concorrenciaDesativacaoEAberturaNaoInvalidaMesa() throws Exception {
    var m = mesa();
    var result =
        race(
            () -> statusOf(() -> open(m.id())),
            () ->
                statusOf(() -> mesas.situacao(a, m.id(), admin, new MesaDtos.Situacao(false, 0L))));
    assertTrue(result.containsAll(List.of(200, 409)));
    var atual = mesas.buscar(a, m.id(), admin, null);
    assertTrue(atual.ativo() ? atual.comandaAtiva() != null : atual.comandaAtiva() == null);
  }

  @Test
  void unidadeDesativadaBloqueiaPinEAdministrador() {
    j.update("UPDATE estabelecimentos SET ativo=false WHERE id=?", a);
    assertEquals(423, statusOf(() -> open(null)));
    assertEquals(404, statusOf(() -> mesas.listar(a, admin, null)));
  }

  @Test
  void nomeExecutorPreservadoNoEventoAposEdicao() {
    var c = open(null);
    j.update(
        "UPDATE usuarios_estabelecimentos SET nome_exibicao='Nome alterado depois' WHERE id=?",
        f.vinculoId());
    assertEquals(
        "Operador um", comandas.historico(a, c.id(), p, f.vinculoId()).get(0).nomeExecutor());
  }

  @Test
  void bancoNaoPermiteApagarMarcaDeConsumo() {
    var c = open(null);
    j.update("UPDATE comandas SET teve_consumo=true WHERE id=?", c.id());
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> j.update("UPDATE comandas SET teve_consumo=false WHERE id=?", c.id()));
  }

  @Test
  void idempotenciaDistingueAusenciaDeNomeDoTextoNull() {
    UUID key = UUID.randomUUID();
    comandas.abrir(a, p, f.vinculoId(), key, new Abertura(Modalidade.BALCAO, null, null));
    assertEquals(
        409,
        statusOf(
            () ->
                comandas.abrir(
                    a, p, f.vinculoId(), key, new Abertura(Modalidade.BALCAO, null, "null"))));
  }
}
