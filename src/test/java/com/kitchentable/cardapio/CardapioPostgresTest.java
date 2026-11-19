package com.kitchentable.cardapio;

import static com.kitchentable.cardapio.CardapioDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.dockerjava.api.model.*;
import com.kitchentable.comandas.*;
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
class CardapioPostgresTest {
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("kt_cardapio_test")
          .withUsername("kt_test")
          .withPassword(UUID.randomUUID().toString())
          .withReuse(false)
          .withLabel("com.kitchentable.scope", "discardable-cardapio-2-5")
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
        .target("12")
        .load()
        .migrate();
    var j =
        new JdbcTemplate(
            new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    j.update(
        "INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,'Legado"
            + " cardapio','legacy25@example.com',?)",
        LEGACY,
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
  @Autowired CardapioService service;
  @Autowired CardapioRepository repo;
  @Autowired CopiaCardapioService copias;
  @Autowired FuncionarioService funcionarios;
  @Autowired PermissoesService permissoes;
  @Autowired OperacionalService op;
  @Autowired MockMvc mvc;
  @Autowired MesaService mesas;
  @Autowired ComandaService comandas;
  UUID a, b, owner, manager, managerLink;
  String email, pin;
  FuncionarioDtos.Funcionario f;
  Authentication admin, gerente, operador;
  Categoria categoria;

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
            new FuncionarioDtos.Cadastro("Operador cardapio", "ATENDENTE", null, null));
    pin = String.format(Locale.ROOT, "%06d", new java.security.SecureRandom().nextInt(1000000));
    op.definir(a, f.vinculoId(), email, new OperacionalDtos.Definicao(pin, f.versao()));
    operador =
        auth(
            op.entrar(
                new OperacionalDtos.Entrada(a.toString(), f.vinculoId().toString(), pin), null),
            "ROLE_OPERADOR");
    categoria = service.categoria(a, null, admin, new CategoriaEntrada("Refeicoes", 1, true, null));
  }

  UUID unit() {
    UUID id = UUID.randomUUID();
    j.update("INSERT INTO estabelecimentos(id,nome) VALUES(?,'Unidade ficticia cardapio')", id);
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

  int statusOf(Runnable action) {
    try {
      action.run();
      return 200;
    } catch (ResponseStatusException e) {
      return e.getStatusCode().value();
    }
  }

  ProdutoEntrada entrada(String name, boolean stock) {
    return new ProdutoEntrada(
        categoria.id(),
        name,
        null,
        Tipo.SIMPLES,
        1250,
        Setor.COZINHA,
        true,
        true,
        stock,
        0,
        List.of(),
        null);
  }

  Produto produto(String name, boolean stock) {
    return service.produto(a, null, admin, entrada(name, stock));
  }

  ProdutoEntrada alterar(Produto p, long price, boolean active, boolean available) {
    return new ProdutoEntrada(
        p.categoriaId(),
        p.nome(),
        p.descricao(),
        p.tipo(),
        price,
        p.setor(),
        active,
        available,
        p.controlaQuantidade(),
        p.quantidade(),
        p.componentes(),
        p.versao());
  }

  Produto combo(Produto... produtos) {
    return service.produto(
        a,
        null,
        admin,
        new ProdutoEntrada(
            categoria.id(),
            "Combo teste",
            null,
            Tipo.COMBO,
            2000,
            null,
            true,
            true,
            false,
            0,
            Arrays.stream(produtos).map(p -> new Componente(p.id(), 2)).toList(),
            null));
  }

  void grant(UUID id, String code, boolean allow) {
    permissoes.excecao(
        a,
        id,
        code,
        email,
        new PermissoesDtos.Excecao(
            allow ? PermissoesDtos.Estado.PERMITIR : PermissoesDtos.Estado.NEGAR,
            funcionarios.consultar(a, id, email).versao()));
  }

  @Test
  void migracoesV12PreservadasELoginLegado() throws Exception {
    assertEquals(
        checksums,
        j.queryForList(
            "SELECT version,checksum FROM flyway_schema_history WHERE version::int<=12 ORDER BY"
                + " installed_rank"));
    assertEquals(16,
        j.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL", Integer.class));
    assertTrue(j.queryForObject("SELECT version()", String.class).contains("PostgreSQL 16"));
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .param("email", "legacy25@example.com")
                .param("password", PASSWORD))
        .andExpect(status().isNoContent());
  }

  @Test
  void categoriaOrdenaEditaEDesativa() {
    var next = service.categoria(a, null, admin, new CategoriaEntrada("Bebidas", 0, true, null));
    assertEquals(next.id(), service.consultar(a, admin, null).categorias().get(0).id());
    next =
        service.categoria(
            a, next.id(), admin, new CategoriaEntrada("Bebidas frias", 2, false, next.versao()));
    assertFalse(next.ativo());
    assertEquals(1, next.versao());
  }

  @Test
  void categoriaUnicaNaUnidade() {
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            service.categoria(a, null, admin, new CategoriaEntrada(" refeicoes ", 0, true, null)));
    assertNotNull(
        service.categoria(b, null, admin, new CategoriaEntrada("Refeicoes", 0, true, null)));
  }

  @Test
  void cadastroPrecoExatoESetor() {
    var p = produto("Prato", false);
    assertEquals(1250, p.precoCentavos());
    assertEquals(Setor.COZINHA, p.setor());
    assertTrue(p.vendavel());
    assertFalse(service.historico(a, p.id(), admin).isEmpty());
  }

  @Test
  void precoNegativoRejeitado() {
    var d = entrada("Teste", false);
    assertEquals(
        400,
        statusOf(
            () ->
                service.produto(
                    a,
                    null,
                    admin,
                    new ProdutoEntrada(
                        d.categoriaId(),
                        d.nome(),
                        null,
                        d.tipo(),
                        -1,
                        d.setor(),
                        true,
                        true,
                        false,
                        0,
                        List.of(),
                        null))));
  }

  @Test
  void semSetorNaoCadastraSimples() {
    assertEquals(
        400,
        statusOf(
            () ->
                service.produto(
                    a,
                    null,
                    admin,
                    new ProdutoEntrada(
                        categoria.id(),
                        "Sem setor",
                        null,
                        Tipo.SIMPLES,
                        100,
                        null,
                        true,
                        true,
                        false,
                        0,
                        List.of(),
                        null))));
  }

  @Test
  void produtoNomeUnicoESemEmailFalso() {
    produto("Cafe", false);
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> produto("cafe", false));
    assertNull(
        j.queryForObject("SELECT email FROM usuarios WHERE id=?", String.class, f.usuarioId()));
  }

  @Test
  void edicaoAuditaPrecoESetor() {
    var p = produto("Prato", false);
    p =
        service.produto(
            a,
            p.id(),
            admin,
            new ProdutoEntrada(
                categoria.id(),
                p.nome(),
                "Descricao",
                p.tipo(),
                1955,
                Setor.BAR,
                true,
                true,
                false,
                0,
                List.of(),
                p.versao()));
    assertEquals(1955, p.precoCentavos());
    assertEquals(Setor.BAR, p.setor());
    assertEquals(2, service.historico(a, p.id(), admin).size());
  }

  @Test
  void versaoAntigaNaoSobrescreve() {
    var p = produto("Prato", false);
    service.produto(a, p.id(), admin, alterar(p, 1500, true, true));
    assertEquals(
        409, statusOf(() -> service.produto(a, p.id(), admin, alterar(p, 1200, true, true))));
  }

  @Test
  void categoriaInativaImpedeDisponibilidade() {
    var p = produto("Prato", false);
    service.categoria(
        a,
        categoria.id(),
        admin,
        new CategoriaEntrada(categoria.nome(), 1, false, categoria.versao()));
    assertFalse(repo.produto(a, p.id(), false).vendavel());
  }

  @Test
  void produtoInativoOuIndisponivelNaoVende() {
    var p = produto("Prato", false);
    p = service.produto(a, p.id(), admin, alterar(p, p.precoCentavos(), true, false));
    assertFalse(p.vendavel());
    p = service.produto(a, p.id(), admin, alterar(p, p.precoCentavos(), false, true));
    assertFalse(p.vendavel());
  }

  @Test
  void estoqueZeroImpedeVendaEAjusteAudita() {
    var p = produto("Prato", true);
    assertFalse(p.vendavel());
    p = service.ajustar(a, p.id(), admin, new Ajuste(5, "Contagem inicial", p.versao()));
    assertTrue(p.vendavel());
    assertEquals(5, p.quantidade());
    assertEquals(1, service.movimentos(a, p.id(), admin).size());
  }

  @Test
  void quantidadeNaoAlteraPeloCadastro() {
    var p = produto("Prato", true);
    assertEquals(
        400,
        statusOf(
            () ->
                service.produto(
                    a,
                    p.id(),
                    admin,
                    new ProdutoEntrada(
                        p.categoriaId(),
                        p.nome(),
                        null,
                        p.tipo(),
                        p.precoCentavos(),
                        p.setor(),
                        true,
                        true,
                        true,
                        3,
                        List.of(),
                        p.versao()))));
  }

  @Test
  void ajusteNegativoOuSemMotivoRejeitado() {
    var p = produto("Prato", true);
    assertEquals(
        400, statusOf(() -> service.ajustar(a, p.id(), admin, new Ajuste(-1, "Motivo", 0L))));
    assertEquals(400, statusOf(() -> service.ajustar(a, p.id(), admin, new Ajuste(1, " ", 0L))));
  }

  @Test
  void comboSemComponentesNaoExiste() {
    assertEquals(400, statusOf(() -> combo()));
  }

  @Test
  void comboTemPrecoProprioEComponentes() {
    var p = produto("Prato", false);
    var drink =
        service.produto(
            a,
            null,
            admin,
            new ProdutoEntrada(
                categoria.id(),
                "Bebida",
                null,
                Tipo.SIMPLES,
                500,
                Setor.BAR,
                true,
                true,
                false,
                0,
                List.of(),
                null));
    var combo = combo(p, drink);
    assertEquals(2000, combo.precoCentavos());
    assertEquals(2, combo.componentes().size());
    assertTrue(combo.vendavel());
    assertFalse(combo.controlaQuantidade());
  }

  @Test
  void comboConsideraQuantidadeMultiplicada() {
    var p = produto("Prato", true);
    p = service.ajustar(a, p.id(), admin, new Ajuste(1, "Contagem", 0L));
    var c = combo(p);
    assertFalse(c.vendavel());
    service.ajustar(a, p.id(), admin, new Ajuste(2, "Contagem", p.versao()));
    assertTrue(repo.produto(a, c.id(), false).vendavel());
  }

  @Test
  void recursaoEComponenteRepetidoRejeitados() {
    var p = produto("Prato", false);
    var c = combo(p);
    assertEquals(
        400,
        statusOf(
            () ->
                service.produto(
                    a,
                    null,
                    admin,
                    new ProdutoEntrada(
                        categoria.id(),
                        "Recursivo",
                        null,
                        Tipo.COMBO,
                        100,
                        null,
                        true,
                        true,
                        false,
                        0,
                        List.of(new Componente(c.id(), 1)),
                        null))));
    assertEquals(
        400,
        statusOf(
            () ->
                service.produto(
                    a,
                    null,
                    admin,
                    new ProdutoEntrada(
                        categoria.id(),
                        "Duplicado",
                        null,
                        Tipo.COMBO,
                        100,
                        null,
                        true,
                        true,
                        false,
                        0,
                        List.of(new Componente(p.id(), 1), new Componente(p.id(), 2)),
                        null))));
  }

  @Test
  void isolamentoCategoriaEProduto() {
    var c = service.categoria(b, null, admin, new CategoriaEntrada("Bebidas", 0, true, null));
    assertEquals(
        404,
        statusOf(
            () ->
                service.produto(
                    a,
                    null,
                    admin,
                    new ProdutoEntrada(
                        c.id(),
                        "Outro",
                        null,
                        Tipo.SIMPLES,
                        100,
                        Setor.BAR,
                        true,
                        true,
                        false,
                        0,
                        List.of(),
                        null))));
    assertEquals(403, statusOf(() -> service.consultar(b, operador, f.vinculoId())));
  }

  @Test
  void gerenteNaoGanhaPoderAutomatico() {
    assertEquals(
        403,
        statusOf(
            () ->
                service.categoria(a, null, gerente, new CategoriaEntrada("Outra", 0, true, null))));
    grant(managerLink, "PRODUTO_GERENCIAR", true);
    assertNotNull(
        service.categoria(a, null, gerente, new CategoriaEntrada("Outra", 0, true, null)));
    assertEquals(403, statusOf(() -> service.produto(a, null, gerente, entrada("Preco", false))));
  }

  @Test
  void revogacaoBloqueiaSessaoOperacional() {
    assertNotNull(service.consultar(a, operador, f.vinculoId()));
    grant(f.vinculoId(), "PRODUTO_CONSULTAR", false);
    assertEquals(403, statusOf(() -> service.consultar(a, operador, f.vinculoId())));
  }

  @Test
  void pinNaoAdministraEAcessoBloqueadoNaoConsulta() {
    assertEquals(
        403,
        statusOf(
            () ->
                service.categoria(
                    a, null, operador, new CategoriaEntrada("Outra", 0, true, null))));
    j.update(
        "UPDATE credenciais_operacionais SET bloqueado=true WHERE vinculo_id=?", f.vinculoId());
    assertEquals(423, statusOf(() -> service.consultar(a, operador, f.vinculoId())));
  }

  @Test
  void copiaGeraIdentidadesEEstoqueIndependentes() {
    var p = produto("Prato", true);
    service.ajustar(a, p.id(), admin, new Ajuste(7, "Contagem", 0L));
    combo(repo.produto(a, p.id(), false));
    var preview = copias.previa(a, admin, new CopiaEntrada(b, null));
    assertTrue(preview.conflitos().isEmpty());
    var result = copias.confirmar(a, preview.id(), admin);
    assertEquals(2, result.produtos());
    var target = repo.produtos(b);
    assertTrue(target.stream().noneMatch(x -> x.id().equals(p.id())));
    assertTrue(
        target.stream()
            .filter(x -> x.tipo() == Tipo.SIMPLES)
            .allMatch(x -> x.quantidade() == 0 && !x.vendavel()));
    assertEquals(
        1,
        target.stream()
            .filter(x -> x.tipo() == Tipo.COMBO)
            .findFirst()
            .orElseThrow()
            .componentes()
            .size());
    assertEquals(result, copias.confirmar(a, preview.id(), admin));
    assertEquals(2, repo.produtos(b).size());
  }

  @Test
  void copiaConflitoExigePrefixoExplicito() {
    service.categoria(b, null, admin, new CategoriaEntrada("Refeicoes", 0, true, null));
    var preview = copias.previa(a, admin, new CopiaEntrada(b, null));
    assertFalse(preview.conflitos().isEmpty());
    assertEquals(409, statusOf(() -> copias.confirmar(a, preview.id(), admin)));
    var other = copias.previa(a, admin, new CopiaEntrada(b, "Copia"));
    assertTrue(other.conflitos().isEmpty());
    assertEquals(1, copias.confirmar(a, other.id(), admin).categorias());
  }

  @Test
  void copiaRevalidaOrigemDestinoEAlteracoes() {
    var preview = copias.previa(a, admin, new CopiaEntrada(b, null));
    produto("Alterado", false);
    assertEquals(409, statusOf(() -> copias.confirmar(a, preview.id(), admin)));
    assertEquals(403, statusOf(() -> copias.previa(a, gerente, new CopiaEntrada(b, null))));
  }

  @Test
  void copiaExpiradaNaoConfirma() {
    var preview = copias.previa(a, admin, new CopiaEntrada(b, null));
    j.update(
        "UPDATE copias_cardapio SET expira_em=clock_timestamp()-interval '1 second' WHERE id=?",
        preview.id());
    assertEquals(409, statusOf(() -> copias.confirmar(a, preview.id(), admin)));
  }

  @Test
  void concorrenciaAjusteUmaVersao() throws Exception {
    var p = produto("Prato", true);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Integer> task =
          () -> {
            start.await();
            return statusOf(
                () -> service.ajustar(a, p.id(), admin, new Ajuste(3, "Concorrencia", 0L)));
          };
      var x = pool.submit(task);
      var y = pool.submit(task);
      start.countDown();
      assertTrue(
          List.of(x.get(20, TimeUnit.SECONDS), y.get(20, TimeUnit.SECONDS))
              .containsAll(List.of(200, 409)));
      assertEquals(1, service.movimentos(a, p.id(), admin).size());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void falhaAuditoriaReverteProduto() {
    j.execute(
        "CREATE FUNCTION falha_cardapio_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
            + " NEW.tipo_evento='PRODUTO_SALVO' THEN RAISE EXCEPTION 'Teste rollback'; END IF;"
            + " RETURN NEW; END $$");
    j.execute(
        "CREATE TRIGGER falha_cardapio_test BEFORE INSERT ON auditoria_eventos FOR EACH ROW EXECUTE"
            + " FUNCTION falha_cardapio_test()");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class, () -> produto("Revertido", false));
      assertTrue(repo.produtos(a).isEmpty());
    } finally {
      j.execute("DROP TRIGGER falha_cardapio_test ON auditoria_eventos");
      j.execute("DROP FUNCTION falha_cardapio_test()");
    }
  }

  @Test
  void bancoProtegeEscopoETipoHistorico() {
    var p = produto("Prato", false);
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> j.update("DELETE FROM produtos_cardapio WHERE id=?", p.id()));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> j.update("UPDATE produtos_cardapio SET estabelecimento_id=? WHERE id=?", b, p.id()));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> j.update("UPDATE produtos_cardapio SET tipo='COMBO',setor=NULL WHERE id=?", p.id()));
    service.categoria(a, categoria.id(), admin, new CategoriaEntrada("Nova", 1, true, 0L));
  }

  @Test
  void csrfRolesEFormatoNoHttp() throws Exception {
    String url = "/api/v1/estabelecimentos/" + a + "/cardapio/categorias";
    mvc.perform(post(url).with(authentication(admin)).contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/operacional/estabelecimentos/" + a + "/cardapio")
                .with(authentication(admin))
                .header("X-Operador-Vinculo", f.vinculoId()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(url)
                .with(authentication(admin))
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void mesasEComandasContinuamSemPagamento() {
    var m = mesas.criar(a, admin, new MesaDtos.Criacao("01"));
    var c =
        comandas.abrir(
            a,
            operador,
            f.vinculoId(),
            UUID.randomUUID(),
            new ComandaDtos.Abertura(ComandaDtos.Modalidade.MESA, m.id(), null));
    assertEquals("ABERTA", c.estado());
    assertTrue(service.consultar(a, operador, f.vinculoId()).produtos().isEmpty());
  }

  void grantAt(UUID unit, UUID link, String code, boolean allow) {
    permissoes.excecao(
        unit,
        link,
        code,
        email,
        new PermissoesDtos.Excecao(
            allow ? PermissoesDtos.Estado.PERMITIR : PermissoesDtos.Estado.NEGAR,
            funcionarios.consultar(unit, link, email).versao()));
  }

  UUID copyManager() {
    UUID other = link(manager, b, "GERENTE");
    for (String c : List.of("CARDAPIO_COPIAR", "PRODUTO_CONSULTAR"))
      grantAt(a, managerLink, c, true);
    for (String c :
        List.of(
            "CARDAPIO_COPIAR",
            "PRODUTO_GERENCIAR",
            "PRODUTO_PRECO_ALTERAR",
            "PRODUTO_DISPONIBILIDADE_ALTERAR")) grantAt(b, other, c, true);
    return other;
  }

  @Test
  void copiaRevalidaRevogacaoNaOrigem() {
    copyManager();
    var preview = copias.previa(a, gerente, new CopiaEntrada(b, null));
    grantAt(a, managerLink, "CARDAPIO_COPIAR", false);
    assertEquals(403, statusOf(() -> copias.confirmar(a, preview.id(), gerente)));
    assertTrue(repo.categorias(b).isEmpty());
  }

  @Test
  void copiaRevalidaRevogacaoNoDestino() {
    UUID other = copyManager();
    var preview = copias.previa(a, gerente, new CopiaEntrada(b, null));
    grantAt(b, other, "CARDAPIO_COPIAR", false);
    assertEquals(403, statusOf(() -> copias.confirmar(a, preview.id(), gerente)));
    assertTrue(repo.categorias(b).isEmpty());
  }

  @Test
  void outroAdministradorNaoConsomePrevia() {
    copyManager();
    var preview = copias.previa(a, admin, new CopiaEntrada(b, null));
    assertEquals(403, statusOf(() -> copias.confirmar(a, preview.id(), gerente)));
  }

  @Test
  void precosCopiadosSaoIndependentes() {
    var p = produto("Prato", false);
    var preview = copias.previa(a, admin, new CopiaEntrada(b, null));
    copias.confirmar(a, preview.id(), admin);
    service.produto(a, p.id(), admin, alterar(p, 2400, true, true));
    assertEquals(1250, repo.produtos(b).get(0).precoCentavos());
  }

  @Test
  void copiaConcorrenteNaoDuplica() throws Exception {
    produto("Prato", false);
    var preview = copias.previa(a, admin, new CopiaEntrada(b, null));
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<ResultadoCopia> task =
          () -> {
            start.await();
            return copias.confirmar(a, preview.id(), admin);
          };
      var x = pool.submit(task);
      var y = pool.submit(task);
      start.countDown();
      assertEquals(x.get(20, TimeUnit.SECONDS), y.get(20, TimeUnit.SECONDS));
      assertEquals(1, repo.produtos(b).size());
      assertEquals(
          1,
          j.queryForObject(
              "SELECT count(*) FROM auditoria_eventos WHERE estabelecimento_id=? AND"
                  + " tipo_evento='CARDAPIO_COPIADO_DESTINO'",
              Integer.class,
              b));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void apiNaoTruncaCentavosFracionarios() throws Exception {
    mvc.perform(
            post("/api/v1/estabelecimentos/" + a + "/cardapio/produtos")
                .with(authentication(admin))
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"categoriaId\":\""
                        + categoria.id()
                        + "\",\"nome\":\"Fracao"
                        + " invalida\",\"tipo\":\"SIMPLES\",\"setor\":\"BAR\",\"ativo\":true,\"disponivel\":true,\"precoCentavos\":1.5}"))
        .andExpect(status().isBadRequest());
    assertTrue(repo.produtos(a).isEmpty());
  }
}
