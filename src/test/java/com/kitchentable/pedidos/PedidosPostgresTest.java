package com.kitchentable.pedidos;

import static com.kitchentable.pedidos.PedidoDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.dockerjava.api.model.*;
import com.kitchentable.cardapio.*;
import com.kitchentable.comandas.*;
import com.kitchentable.funcionarios.*;
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
class PedidosPostgresTest {
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("kt_pedidos_test")
          .withUsername("kt_test")
          .withPassword(UUID.randomUUID().toString())
          .withReuse(false)
          .withLabel("com.kitchentable.scope", "discardable-pedidos-2-6")
          .withCreateContainerCmdModifier(
              c ->
                  c.getHostConfig()
                      .withPortBindings(
                          new PortBinding(
                              Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(5432))));
  static final String PASSWORD = UUID.randomUUID().toString(),
      PIN = String.format(Locale.ROOT, "%06d", new java.security.SecureRandom().nextInt(1000000)),
      HASH =
          new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(PASSWORD),
      PINHASH = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(PIN);
  static List<Map<String, Object>> checksums;
  static final UUID LEGACY = UUID.randomUUID();

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry p) {
    PG.start();
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
        .target("14")
        .load()
        .migrate();
    var j =
        new JdbcTemplate(
            new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    j.update(
        "INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,'Legado"
            + " pedidos','legacy26@example.com',?)",
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
  @Autowired CardapioService menu;
  @Autowired CardapioRepository catalog;
  @Autowired FuncionarioService staff;
  @Autowired PermissoesService permissions;
  @Autowired OperacionalService op;
  @Autowired ComandaService comandas;
  @Autowired PedidoRegistroService registro;
  @Autowired ProducaoService producao;
  @Autowired AlteracaoPedidoService alteracoes;
  @Autowired PedidoRepository repo;
  @Autowired MockMvc mvc;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
  UUID a, b, owner, at, other, coz, bar;
  String email;
  Authentication admin, waiter, second, kitchen, drinks;
  CardapioDtos.Categoria category;
  CardapioDtos.Produto food, drink, combo;
  ComandaDtos.Comanda comanda;

  Authentication auth(Object p, String role) {
    return new UsernamePasswordAuthenticationToken(
        p, null, List.of(new SimpleGrantedAuthority(role)));
  }

  UUID unit() {
    UUID x = UUID.randomUUID();
    j.update("INSERT INTO estabelecimentos(id,nome) VALUES(?,'Unidade descartavel pedidos')", x);
    return x;
  }

  UUID employee(String role) {
    var f =
        staff.cadastrar(
            a,
            email,
            UUID.randomUUID(),
            new FuncionarioDtos.Cadastro("Pessoa ficticia " + role, role, null, null));
    j.update(
        "INSERT INTO credenciais_operacionais(vinculo_id,estabelecimento_id,pin_hash)"
            + " VALUES(?,?,?)",
        f.vinculoId(),
        a,
        PINHASH);
    return f.vinculoId();
  }

  Authentication login(UUID id) {
    return auth(
        op.entrar(new OperacionalDtos.Entrada(a.toString(), id.toString(), PIN), null),
        "ROLE_OPERADOR");
  }

  @BeforeEach
  void setup() {
    a = unit();
    b = unit();
    owner = UUID.randomUUID();
    email = owner + "@example.com";
    j.update(
        "INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,'Proprietario teste',?,?)",
        owner,
        email,
        HASH);
    for (UUID u : List.of(a, b))
      j.update(
          "INSERT INTO usuarios_estabelecimentos(usuario_id,estabelecimento_id,funcao_id) SELECT"
              + " ?,?,id FROM funcoes WHERE codigo='PROPRIETARIO'",
          owner,
          u);
    admin = auth(email, "ROLE_USER");
    at = employee("ATENDENTE");
    other = employee("ATENDENTE");
    coz = employee("COZINHA");
    bar = employee("BAR");
    waiter = login(at);
    second = login(other);
    kitchen = login(coz);
    drinks = login(bar);
    for (UUID id : List.of(coz, bar))
      for (String code : List.of("PEDIDO_CANCELAR", "PEDIDO_ENVIADO_ALTERAR"))
        grant(id, code, true);
    grant(at, "COMANDA_ENCERRAR", true);
    category =
        menu.categoria(a, null, admin, new CardapioDtos.CategoriaEntrada("Menu", 0, true, null));
    food = product("Prato", CardapioDtos.Setor.COZINHA, true);
    drink = product("Bebida", CardapioDtos.Setor.BAR, true);
    for (var p : List.of(food, drink))
      menu.ajustar(
          a, p.id(), admin, new CardapioDtos.Ajuste(20, "Saldo ficticio inicial", p.versao()));
    food = catalog.produto(a, food.id(), false);
    drink = catalog.produto(a, drink.id(), false);
    combo =
        menu.produto(
            a,
            null,
            admin,
            new CardapioDtos.ProdutoEntrada(
                category.id(),
                "Combo",
                null,
                CardapioDtos.Tipo.COMBO,
                2500,
                null,
                true,
                true,
                false,
                0,
                List.of(
                    new CardapioDtos.Componente(food.id(), 2),
                    new CardapioDtos.Componente(drink.id(), 1)),
                null));
    comanda = open(waiter, at);
  }

  CardapioDtos.Produto product(String name, CardapioDtos.Setor setor, boolean stock) {
    return menu.produto(
        a,
        null,
        admin,
        new CardapioDtos.ProdutoEntrada(
            category.id(),
            name,
            null,
            CardapioDtos.Tipo.SIMPLES,
            1500,
            setor,
            true,
            true,
            stock,
            0,
            List.of(),
            null));
  }

  ComandaDtos.Comanda open(Authentication actor, UUID id) {
    return comandas.abrir(
        a,
        actor,
        id,
        UUID.randomUUID(),
        new ComandaDtos.Abertura(ComandaDtos.Modalidade.BALCAO, null, null));
  }

  void grant(UUID id, String code, boolean allow) {
    permissions.excecao(
        a,
        id,
        code,
        email,
        new PermissoesDtos.Excecao(
            allow ? PermissoesDtos.Estado.PERMITIR : PermissoesDtos.Estado.NEGAR,
            staff.consultar(a, id, email).versao()));
  }

  LinhaEntrada line(UUID id, int qty) {
    var o =
        registro.ofertas(a, waiter, at).stream()
            .filter(x -> x.produto().id().equals(id))
            .findFirst()
            .orElseThrow();
    return new LinhaEntrada(
        id, qty, o.produto().precoCentavos(), o.assinaturaOferta(), "Sem gelo / preparo de teste");
  }

  Confirmacao form(boolean together, UUID... ids) {
    return new Confirmacao(together, Arrays.stream(ids).map(x -> line(x, 1)).toList());
  }

  Pedido order(boolean together, UUID... ids) {
    return registro.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), form(together, ids));
  }

  Pedido fresh(Pedido p) {
    return producao.buscar(a, p.id(), waiter, at);
  }

  Item item(Pedido p, String sector) {
    return fresh(p).itens().stream()
        .filter(i -> i.setor().equals(sector))
        .findFirst()
        .orElseThrow();
  }

  void prepare(Pedido p, String sector, EstadoItem state) {
    var i = item(p, sector);
    producao.preparar(
        a,
        sector,
        i.id(),
        sector.equals("COZINHA") ? kitchen : drinks,
        sector.equals("COZINHA") ? coz : bar,
        new Atualizacao(state, i.versao()));
  }

  void ready(Pedido p, String sector) {
    prepare(p, sector, EstadoItem.EM_PREPARO);
    prepare(p, sector, EstadoItem.PRONTO);
  }

  Pedido deliver(Pedido p, Item... items) {
    return producao.entregar(
        a,
        p.id(),
        waiter,
        at,
        new Entrega(Arrays.stream(items).map(i -> new Referencia(i.id(), i.versao())).toList()));
  }

  Solicitacao cancel(Pedido p) {
    return alteracoes.solicitar(
        a,
        p.id(),
        waiter,
        at,
        UUID.randomUUID(),
        new SolicitacaoEntrada(
            TipoSolicitacao.CANCELAMENTO,
            "Cliente solicitou cancelamento",
            null,
            fresh(p).versao()));
  }

  ResultadoDecisao decide(Solicitacao s, String sector, boolean allow) {
    var latest = repo.solicitacao(a, s.id());
    var d =
        latest.decisoes().stream().filter(x -> x.setor().equals(sector)).findFirst().orElseThrow();
    return alteracoes.decidir(
        a,
        s.id(),
        sector,
        sector.equals("COZINHA") ? kitchen : drinks,
        sector.equals("COZINHA") ? coz : bar,
        new DecisaoEntrada(
            allow,
            allow ? null : "Preparo nao permite mudanca",
            d.versao(),
            latest.versaoPedido()));
  }

  int codeOf(Runnable r) {
    try {
      r.run();
      return 200;
    } catch (ResponseStatusException e) {
      return e.getStatusCode().value();
    }
  }

  int stock(UUID id) {
    return catalog.produto(a, id, false).quantidade();
  }

  CardapioDtos.Produto change(CardapioDtos.Produto p, long price, CardapioDtos.Setor sector) {
    p = catalog.produto(a, p.id(), false);
    return menu.produto(
        a,
        p.id(),
        admin,
        new CardapioDtos.ProdutoEntrada(
            p.categoriaId(),
            p.nome(),
            p.descricao(),
            p.tipo(),
            price,
            sector,
            p.ativo(),
            p.disponivel(),
            p.controlaQuantidade(),
            p.quantidade(),
            p.componentes(),
            p.versao()));
  }

  @Test
  void migraV14PreservaChecksumsLoginEPostgres16() throws Exception {
    assertEquals(
        checksums,
        j.queryForList(
            "SELECT version,checksum FROM flyway_schema_history WHERE version::int<=14 ORDER BY"
                + " installed_rank"));
    assertEquals(
        23,
        j.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL", Integer.class));
    assertTrue(j.queryForObject("SELECT version()", String.class).contains("PostgreSQL 16"));
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .param("email", "legacy26@example.com")
                .param("password", PASSWORD))
        .andExpect(status().isNoContent());
  }

  @Test
  void pedidoSimplesConsomeEstoqueERegistraExecutorReal() {
    var p = order(false, food.id());
    assertEquals(1500, p.totalCentavos());
    assertEquals(at, p.abertoPor());
    assertEquals(19, stock(food.id()));
    assertEquals("AGUARDANDO", p.estado());
    assertEquals(
        1,
        repo.historico(a, p.id()).stream().filter(e -> e.tipo().equals("PEDIDO_ENVIADO")).count());
  }

  @Test
  void atendimentoCompartilhadoMantemAutorDaComandaEDoPedido() {
    var p =
        registro.registrar(
            a, comanda.id(), second, other, UUID.randomUUID(), form(false, food.id()));
    assertEquals(other, p.abertoPor());
    assertEquals(
        at,
        j.queryForObject("SELECT aberto_por FROM comandas WHERE id=?", UUID.class, comanda.id()));
  }

  @Test
  void comboDecompoeSemCobrarComponentes() {
    var p = order(false, combo.id());
    assertEquals(2500, p.totalCentavos());
    assertEquals(1, p.linhas().size());
    assertEquals(2, p.itens().size());
    assertEquals(18, stock(food.id()));
    assertEquals(19, stock(drink.id()));
    assertEquals(2, item(p, "COZINHA").quantidade());
  }

  @Test
  void snapshotPreservaPrecoNomeSetorEComposicao() {
    var p = order(false, combo.id());
    change(food, 1900, CardapioDtos.Setor.BAR);
    change(combo, 9000, null);
    assertEquals(p.linhas(), fresh(p).linhas());
    assertEquals("COZINHA", item(p, "COZINHA").setor());
    assertEquals(2500, fresh(p).totalCentavos());
  }

  @Test
  void precoAlteradoExigeNovaConfirmacao() {
    var f = form(false, food.id());
    change(food, 1600, CardapioDtos.Setor.COZINHA);
    assertEquals(
        409, codeOf(() -> registro.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), f)));
    assertEquals(20, stock(food.id()));
  }

  @Test
  void composicaoAlteradaExigeNovaConfirmacao() {
    var f = form(false, combo.id());
    change(food, 1500, CardapioDtos.Setor.BAR);
    assertEquals(
        409, codeOf(() -> registro.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), f)));
  }

  @Test
  void precoForjadoNaoConfirma() {
    var l = line(food.id(), 1);
    var f =
        new Confirmacao(
            false, List.of(new LinhaEntrada(food.id(), 1, 1, l.assinaturaOferta(), null)));
    assertEquals(
        409, codeOf(() -> registro.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), f)));
  }

  @Test
  void produtoIndisponivelNaoVende() {
    var f = form(false, food.id());
    j.update("UPDATE produtos_cardapio SET disponivel=false WHERE id=?", food.id());
    assertEquals(
        409, codeOf(() -> registro.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), f)));
  }

  @Test
  void categoriaInativaNaoVende() {
    var f = form(false, food.id());
    menu.categoria(
        a,
        category.id(),
        admin,
        new CardapioDtos.CategoriaEntrada(category.nome(), 0, false, category.versao()));
    assertEquals(
        409, codeOf(() -> registro.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), f)));
  }

  @Test
  void saldoInsuficienteRollbackPedidoEMovimentos() {
    var f = new Confirmacao(false, List.of(line(food.id(), 21), line(drink.id(), 1)));
    assertEquals(
        409, codeOf(() -> registro.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), f)));
    assertEquals(20, stock(food.id()));
    assertEquals(20, stock(drink.id()));
    assertTrue(repo.listar(a, comanda.id()).isEmpty());
  }

  @Test
  void somaComponentesRepetidosNaoUltrapassaSaldo() {
    var f = new Confirmacao(false, List.of(line(combo.id(), 10), line(food.id(), 1)));
    assertEquals(
        409, codeOf(() -> registro.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), f)));
    assertEquals(20, stock(food.id()));
  }

  @Test
  void produtoSemControleNaoCriaMovimentoFisico() {
    var p = product("Sem saldo controlado", CardapioDtos.Setor.COZINHA, false);
    var o = order(false, p.id());
    assertEquals(
        0,
        j.queryForObject(
            "SELECT count(*) FROM movimentos_cardapio WHERE operacao_id=?", Integer.class, o.id()));
  }

  @Test
  void idempotenciaNaoDuplicaPedidoNemBaixa() {
    UUID key = UUID.randomUUID();
    var f = form(false, combo.id());
    var p = registro.registrar(a, comanda.id(), waiter, at, key, f);
    assertEquals(p.id(), registro.registrar(a, comanda.id(), waiter, at, key, f).id());
    assertEquals(18, stock(food.id()));
    assertEquals(1, repo.listar(a, comanda.id()).size());
  }

  @Test
  void chaveIdempotenteNaoAceitaOutroCorpoOuOperador() {
    UUID key = UUID.randomUUID();
    var f = form(false, food.id());
    registro.registrar(a, comanda.id(), waiter, at, key, f);
    assertEquals(
        409,
        codeOf(
            () -> registro.registrar(a, comanda.id(), waiter, at, key, form(false, drink.id()))));
    assertEquals(409, codeOf(() -> registro.registrar(a, comanda.id(), second, other, key, f)));
  }

  @Test
  void comandaEncerradaNaoRecebePedido() {
    comandas.finalizar(
        a, comanda.id(), waiter, at, new ComandaDtos.Transicao(comanda.versao(), null), false);
    assertEquals(409, codeOf(() -> order(false, food.id())));
  }

  @Test
  void comandaComPedidoNaoFechaNemSeFlagsForjadas() {
    order(false, food.id());
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            j.update(
                "UPDATE comandas SET teve_consumo=false,possui_pendencias=false WHERE id=?",
                comanda.id()));
    long v = j.queryForObject("SELECT versao FROM comandas WHERE id=?", Long.class, comanda.id());
    assertEquals(
        409,
        codeOf(
            () ->
                comandas.finalizar(
                    a, comanda.id(), waiter, at, new ComandaDtos.Transicao(v, null), false)));
  }

  @Test
  void unidadesSaoIsoladasEmTodasConsultas() {
    var p = order(false, food.id());
    assertEquals(403, codeOf(() -> producao.buscar(b, p.id(), waiter, at)));
    assertEquals(
        403,
        codeOf(
            () ->
                registro.registrar(
                    b, comanda.id(), waiter, at, UUID.randomUUID(), form(false, food.id()))));
    assertEquals(403, codeOf(() -> producao.fila(b, "COZINHA", kitchen, coz)));
    assertEquals(
        403,
        codeOf(
            () ->
                alteracoes.decidir(
                    b,
                    UUID.randomUUID(),
                    "COZINHA",
                    kitchen,
                    coz,
                    new DecisaoEntrada(true, null, 0L, 0L))));
  }

  @Test
  void administracaoNaoSubstituiPinOperacional() {
    assertEquals(403, codeOf(() -> registro.ofertas(a, admin, at)));
    assertEquals(
        403,
        codeOf(
            () ->
                registro.registrar(
                    a, comanda.id(), admin, at, UUID.randomUUID(), form(false, food.id()))));
  }

  @Test
  void vinculoForjadoNaoContornaPrincipal() {
    assertEquals(403, codeOf(() -> registro.ofertas(a, waiter, other)));
  }

  @Test
  void revogacaoBloqueiaNovoPedidoEReplay() {
    UUID key = UUID.randomUUID();
    var f = form(false, food.id());
    registro.registrar(a, comanda.id(), waiter, at, key, f);
    grant(at, "PEDIDO_ENVIAR", false);
    assertEquals(403, codeOf(() -> registro.registrar(a, comanda.id(), waiter, at, key, f)));
  }

  @Test
  void sessaoBloqueadaNaoRegistra() {
    op.bloquearSessao((OperadorPrincipal) waiter.getPrincipal());
    assertEquals(423, codeOf(() -> order(false, food.id())));
  }

  @Test
  void filaExibeSomenteSetorSemValores() throws Exception {
    order(false, combo.id());
    var q = producao.fila(a, "COZINHA", kitchen, coz);
    assertEquals(1, q.size());
    assertEquals("Prato", q.get(0).nome());
    String body = json.writeValueAsString(q);
    assertFalse(body.contains("Centavos"));
    assertFalse(body.contains("Bebida"));
    assertTrue(body.contains("\"solicitacoes\":[]"));
    assertEquals(403, codeOf(() -> producao.fila(a, "BAR", kitchen, coz)));
  }

  @Test
  void prontoNoBarNaoConcluiPedidoInteiro() {
    var p = order(false, combo.id());
    ready(p, "BAR");
    assertEquals("EM_PREPARO", fresh(p).estado());
    assertEquals("AGUARDANDO", item(p, "COZINHA").estado());
  }

  @Test
  void transicoesInvalidasEVersaoObsoletaSaoNegadas() {
    var p = order(false, food.id());
    var i = item(p, "COZINHA");
    assertEquals(
        409,
        codeOf(
            () ->
                producao.preparar(
                    a, "COZINHA", i.id(), kitchen, coz, new Atualizacao(EstadoItem.PRONTO, 0L))));
    prepare(p, "COZINHA", EstadoItem.EM_PREPARO);
    assertEquals(
        409,
        codeOf(
            () ->
                producao.preparar(
                    a, "COZINHA", i.id(), kitchen, coz, new Atualizacao(EstadoItem.PRONTO, 0L))));
    assertEquals(
        404,
        codeOf(
            () ->
                producao.preparar(
                    a, "BAR", i.id(), drinks, bar, new Atualizacao(EstadoItem.PRONTO, 1L))));
  }

  @Test
  void entregaParcialDepoisTotal() {
    var p = order(false, combo.id());
    ready(p, "BAR");
    assertEquals("PARCIALMENTE_ENTREGUE", deliver(p, item(p, "BAR")).estado());
    ready(p, "COZINHA");
    assertEquals("ENTREGUE", deliver(p, item(p, "COZINHA")).estado());
    assertTrue(producao.fila(a, "BAR", drinks, bar).isEmpty());
  }

  @Test
  void tudoJuntoAguardaTodosESoEntregaConjunto() {
    var p = order(true, combo.id());
    ready(p, "BAR");
    assertEquals(409, codeOf(() -> deliver(p, item(p, "BAR"))));
    ready(p, "COZINHA");
    assertEquals(409, codeOf(() -> deliver(p, item(p, "BAR"))));
    assertEquals("ENTREGUE", deliver(p, item(p, "BAR"), item(p, "COZINHA")).estado());
  }

  @Test
  void naoEntregaAguardandoNemDuplicaEntrega() {
    var p = order(false, food.id());
    assertEquals(409, codeOf(() -> deliver(p, item(p, "COZINHA"))));
    ready(p, "COZINHA");
    var i = item(p, "COZINHA");
    deliver(p, i);
    assertEquals(409, codeOf(() -> deliver(p, i)));
  }

  @Test
  void cancelamentoDependeDosDoisSetoresEDevolveApenasAntesPreparo() {
    var p = order(false, combo.id());
    var s = cancel(p);
    assertEquals("PENDENTE", decide(s, "COZINHA", true).estado());
    assertEquals(18, stock(food.id()));
    assertEquals("EXECUTADA", decide(s, "BAR", true).estado());
    assertEquals("CANCELADO", fresh(p).estado());
    assertEquals(20, stock(food.id()));
    assertEquals(20, stock(drink.id()));
    decide(s, "BAR", true);
    assertEquals(20, stock(food.id()));
  }

  @Test
  void preparoIniciadoNaoTemReposicaoAutomatica() {
    var p = order(false, combo.id());
    prepare(p, "COZINHA", EstadoItem.EM_PREPARO);
    ready(p, "BAR");
    var s = cancel(p);
    decide(s, "COZINHA", true);
    decide(s, "BAR", true);
    assertEquals(18, stock(food.id()));
    assertEquals(19, stock(drink.id()));
    assertEquals(
        0,
        j.queryForObject(
            "SELECT count(*) FROM movimentos_cardapio WHERE operacao_id=? AND tipo='DEVOLUCAO'",
            Integer.class,
            p.id()));
  }

  @Test
  void recusaPreservaPedidoEstoqueEPermitePreparo() {
    var p = order(false, combo.id());
    var s = cancel(p);
    assertEquals("RECUSADA", decide(s, "COZINHA", false).estado());
    assertEquals("AGUARDANDO", fresh(p).estado());
    assertEquals(18, stock(food.id()));
    prepare(p, "COZINHA", EstadoItem.EM_PREPARO);
  }

  @Test
  void setorQueConsentiuNaoContinuaPreparoAntesResultado() {
    var p = order(false, combo.id());
    var s = cancel(p);
    decide(s, "COZINHA", true);
    assertEquals(409, codeOf(() -> prepare(p, "COZINHA", EstadoItem.EM_PREPARO)));
    prepare(p, "BAR", EstadoItem.EM_PREPARO);
    decide(s, "BAR", false);
    prepare(p, "COZINHA", EstadoItem.EM_PREPARO);
  }

  @Test
  void funcionarioSemPermissaoCriticaNaoDecide() {
    var p = order(false, food.id());
    var s = cancel(p);
    grant(coz, "PEDIDO_CANCELAR", false);
    assertEquals(403, codeOf(() -> decide(s, "COZINHA", true)));
    assertEquals("PENDENTE", repo.solicitacao(a, s.id()).estado());
  }

  @Test
  void autorizacaoRevogadaAposConsentimentoImpedeExecucao() {
    var p = order(false, combo.id());
    var s = cancel(p);
    decide(s, "COZINHA", true);
    grant(coz, "PEDIDO_CANCELAR", false);
    assertEquals("RECUSADA", decide(s, "BAR", true).estado());
    assertEquals("AGUARDANDO", fresh(p).estado());
  }

  @Test
  void revogacaoDoSolicitanteImpedeExecucao() {
    var p = order(false, combo.id());
    var s = cancel(p);
    decide(s, "COZINHA", true);
    grant(at, "PEDIDO_ALTERACAO_SOLICITAR", false);
    assertEquals("RECUSADA", decide(s, "BAR", true).estado());
  }

  @Test
  void redefinirPinDeAprovadorInvalidaConsentimento() {
    var p = order(false, combo.id());
    var s = cancel(p);
    decide(s, "COZINHA", true);
    op.definir(
        a, coz, email, new OperacionalDtos.Definicao(PIN, staff.consultar(a, coz, email).versao()));
    assertEquals("RECUSADA", decide(s, "BAR", true).estado());
  }

  @Test
  void decisaoExigeVersaoAtualDoPreparo() {
    var p = order(false, food.id());
    var s = cancel(p);
    prepare(p, "COZINHA", EstadoItem.EM_PREPARO);
    assertEquals(
        409,
        codeOf(
            () ->
                alteracoes.decidir(
                    a,
                    s.id(),
                    "COZINHA",
                    kitchen,
                    coz,
                    new DecisaoEntrada(true, null, 0L, p.versao()))));
    assertEquals("EXECUTADA", decide(s, "COZINHA", true).estado());
    assertEquals(19, stock(food.id()));
  }

  @Test
  void alteracaoPreservaOriginalECriaSubstitutoComPrecoProprio() {
    var p = order(false, food.id());
    var s =
        alteracoes.solicitar(
            a,
            p.id(),
            waiter,
            at,
            UUID.randomUUID(),
            new SolicitacaoEntrada(
                TipoSolicitacao.ALTERACAO, "Trocar por combo", form(true, combo.id()), p.versao()));
    assertEquals(1, producao.filaSolicitacoes(a, "BAR", drinks, bar).size());
    assertEquals(1, producao.filaSolicitacoes(a, "BAR", drinks, bar).get(0).proposta().size());
    decide(s, "COZINHA", true);
    var result = decide(s, "BAR", true);
    assertEquals("EXECUTADA", result.estado());
    assertEquals("CANCELADO", fresh(p).estado());
    var next = repo.pedido(a, result.novoPedidoId());
    assertEquals(p.id(), next.substituiId());
    assertEquals(2500, next.totalCentavos());
    assertEquals(1500, fresh(p).totalCentavos());
    assertEquals(18, stock(food.id()));
    assertEquals(19, stock(drink.id()));
  }

  @Test
  void precoMudouDuranteSolicitacaoRecusaSemEfeitos() {
    var p = order(false, food.id());
    var s =
        alteracoes.solicitar(
            a,
            p.id(),
            waiter,
            at,
            UUID.randomUUID(),
            new SolicitacaoEntrada(
                TipoSolicitacao.ALTERACAO, "Trocar", form(false, drink.id()), p.versao()));
    decide(s, "COZINHA", true);
    change(drink, 1800, CardapioDtos.Setor.BAR);
    assertEquals("RECUSADA", decide(s, "BAR", true).estado());
    assertEquals(19, stock(food.id()));
    assertEquals(20, stock(drink.id()));
    assertEquals("AGUARDANDO", fresh(p).estado());
  }

  @Test
  void pedidoEntregueNaoRecebeCancelamentoFinanceiroFicticio() {
    var p = order(false, food.id());
    ready(p, "COZINHA");
    deliver(p, item(p, "COZINHA"));
    assertEquals(409, codeOf(() -> cancel(p)));
  }

  @Test
  void solicitacaoIdempotenteEPendenteUnica() {
    var p = order(false, food.id());
    UUID key = UUID.randomUUID();
    var d = new SolicitacaoEntrada(TipoSolicitacao.CANCELAMENTO, "Motivo teste", null, p.versao());
    var s = alteracoes.solicitar(a, p.id(), waiter, at, key, d);
    assertEquals(s.id(), alteracoes.solicitar(a, p.id(), waiter, at, key, d).id());
    assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> cancel(p));
  }

  @Test
  void snapshotsProtegidosNoBanco() {
    var p = order(false, food.id());
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            j.update(
                "UPDATE linhas_pedido SET preco_unitario_centavos=1 WHERE pedido_id=?", p.id()));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> j.update("DELETE FROM pedidos WHERE id=?", p.id()));
  }

  @Test
  void csrfEAutenticacaoProtegemEndpoints() throws Exception {
    String path =
        "/api/v1/operacional/estabelecimentos/" + a + "/comandas/" + comanda.id() + "/pedidos";
    mvc.perform(
            post(path)
                .with(authentication(waiter))
                .header("X-Operador-Vinculo", at)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsString(form(false, food.id()))))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/operacional/estabelecimentos/" + a + "/pedidos/ofertas")
                .header("X-Operador-Vinculo", at))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void concorrenciaUltimaUnidadeNaoVendeDuasVezes() throws Exception {
    var p = catalog.produto(a, food.id(), false);
    menu.ajustar(
        a, p.id(), admin, new CardapioDtos.Ajuste(1, "Ultima unidade ficticia", p.versao()));
    var otherComanda = open(second, other);
    var f = form(false, food.id());
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      var x =
          pool.submit(
              () -> {
                gate.await();
                return codeOf(
                    () -> registro.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), f));
              });
      var y =
          pool.submit(
              () -> {
                gate.await();
                return codeOf(
                    () ->
                        registro.registrar(
                            a, otherComanda.id(), second, other, UUID.randomUUID(), f));
              });
      gate.countDown();
      assertEquals(
          Set.of(200, 409),
          new HashSet<>(List.of(x.get(20, TimeUnit.SECONDS), y.get(20, TimeUnit.SECONDS))));
      assertEquals(0, stock(food.id()));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void setoresAtualizamEmParaleloSemPerderEstado() throws Exception {
    var p = order(false, combo.id());
    var pool = Executors.newFixedThreadPool(2);
    try {
      var x = pool.submit(() -> prepare(p, "COZINHA", EstadoItem.EM_PREPARO));
      var y = pool.submit(() -> prepare(p, "BAR", EstadoItem.EM_PREPARO));
      x.get(20, TimeUnit.SECONDS);
      y.get(20, TimeUnit.SECONDS);
      assertTrue(fresh(p).itens().stream().allMatch(i -> i.estado().equals("EM_PREPARO")));
      assertEquals(2, fresh(p).versao());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void modulosEstaticosCarregamAntesDoPinSemLiberarDados() throws Exception {
    for (String name :
        List.of("pedidos", "pedido-editor", "pedido-contratos", "pedido-ui", "producao"))
      mvc.perform(get("/assets/" + name + ".js")).andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/operacional/estabelecimentos/" + a + "/fila/COZINHA")
                .header("X-Operador-Vinculo", coz))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void entradasNulasEQuantidadeFracionariaRetornam400SemConsumir() throws Exception {
    String base = "/api/v1/operacional/estabelecimentos/" + a;
    for (String body :
        List.of(
            "{\"linhas\":[null]}",
            "{\"linhas\":[{\"produtoId\":\""
                + food.id()
                + "\",\"quantidade\":1.5,\"precoCentavos\":1500,\"assinaturaOferta\":\"teste\"}]}"))
      mvc.perform(
              post(base + "/comandas/" + comanda.id() + "/pedidos")
                  .with(csrf())
                  .with(authentication(waiter))
                  .header("X-Operador-Vinculo", at)
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest());
    var p = order(false, food.id());
    mvc.perform(
            post(base + "/pedidos/" + p.id() + "/entregas")
                .with(csrf())
                .with(authentication(waiter))
                .header("X-Operador-Vinculo", at)
                .contentType("application/json")
                .content("{\"itens\":[null]}"))
        .andExpect(status().isBadRequest());
    assertEquals(19, stock(food.id()));
  }

  @Test
  void falhaDeAuditoriaRevertePedidoSaldoEEncaminhamento() {
    String name = "audit_test_" + a.toString().replace("-", "");
    j.execute(
        "CREATE FUNCTION "
            + name
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.estabelecimento_id='"
            + a
            + "'::uuid AND NEW.tipo_evento='PEDIDO_ENVIADO' THEN RAISE EXCEPTION 'Auditoria"
            + " indisponivel no teste' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
    j.execute(
        "CREATE TRIGGER "
            + name
            + " BEFORE INSERT ON auditoria_eventos FOR EACH ROW EXECUTE FUNCTION "
            + name
            + "()");
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () -> order(false, combo.id()));
      assertEquals(20, stock(food.id()));
      assertEquals(20, stock(drink.id()));
      assertTrue(repo.listar(a, comanda.id()).isEmpty());
      assertEquals(
          0,
          j.queryForObject(
              "SELECT count(*) FROM itens_preparo WHERE estabelecimento_id=?", Integer.class, a));
    } finally {
      j.execute("DROP TRIGGER " + name + " ON auditoria_eventos");
      j.execute("DROP FUNCTION " + name + "()");
    }
  }

  @Test
  void confirmacoesConcorrentesComMesmaChaveGeramUmPedido() throws Exception {
    UUID key = UUID.randomUUID();
    var f = form(false, food.id());
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      Callable<UUID> action =
          () -> {
            gate.await();
            return registro.registrar(a, comanda.id(), waiter, at, key, f).id();
          };
      var x = pool.submit(action);
      var y = pool.submit(action);
      gate.countDown();
      assertEquals(x.get(20, TimeUnit.SECONDS), y.get(20, TimeUnit.SECONDS));
      assertEquals(19, stock(food.id()));
      assertEquals(1, repo.listar(a, comanda.id()).size());
    } finally {
      pool.shutdownNow();
    }
  }

  @Autowired com.kitchentable.mesas.MesaService mesaService;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  @Test
  void pedidoEmComandaDeMesaPreservaVinculo() {
    var m =
        mesaService.criar(a, admin, new com.kitchentable.mesas.MesaDtos.Criacao("Mesa pedidos"));
    var c =
        comandas.abrir(
            a,
            waiter,
            at,
            UUID.randomUUID(),
            new ComandaDtos.Abertura(ComandaDtos.Modalidade.MESA, m.id(), null));
    var p =
        registro.registrar(a, c.id(), second, other, UUID.randomUUID(), form(false, combo.id()));
    assertEquals("Mesa pedidos", p.mesa());
    assertEquals(c.id(), p.comandaId());
    assertEquals(other, p.abertoPor());
  }

  @Test
  void doisAtendentesConfirmamNaMesmaComandaSemPerderPedidos() throws Exception {
    var f = form(false, food.id());
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      var x =
          pool.submit(
              () -> {
                gate.await();
                return registro.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), f);
              });
      var y =
          pool.submit(
              () -> {
                gate.await();
                return registro.registrar(a, comanda.id(), second, other, UUID.randomUUID(), f);
              });
      gate.countDown();
      assertNotEquals(x.get(20, TimeUnit.SECONDS).id(), y.get(20, TimeUnit.SECONDS).id());
      assertEquals(2, repo.listar(a, comanda.id()).size());
      assertEquals(18, stock(food.id()));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void doisAtendentesNaoDuplicamEntregaConcorrente() throws Exception {
    var p = order(false, food.id());
    ready(p, "COZINHA");
    var i = item(p, "COZINHA");
    var d = new Entrega(List.of(new Referencia(i.id(), i.versao())));
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      var x =
          pool.submit(
              () -> {
                gate.await();
                return codeOf(() -> producao.entregar(a, p.id(), waiter, at, d));
              });
      var y =
          pool.submit(
              () -> {
                gate.await();
                return codeOf(() -> producao.entregar(a, p.id(), second, other, d));
              });
      gate.countDown();
      assertEquals(
          Set.of(200, 409),
          new HashSet<>(List.of(x.get(20, TimeUnit.SECONDS), y.get(20, TimeUnit.SECONDS))));
      assertEquals(
          1,
          repo.historico(a, p.id()).stream().filter(e -> e.tipo().equals("ITEM_ENTREGUE")).count());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void comboComComponenteIndisponivelNaoGeraPedido() {
    var f = form(false, combo.id());
    j.update("UPDATE produtos_cardapio SET disponivel=false WHERE id=?", drink.id());
    assertEquals(
        409, codeOf(() -> registro.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), f)));
    assertEquals(20, stock(food.id()));
    assertTrue(repo.listar(a, comanda.id()).isEmpty());
  }

  @Test
  void consultaConcorrenteNaoExibePedidoParcialAntesDoCommit() throws Exception {
    var f = form(false, combo.id());
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    var recorded = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor();
    try {
      var future =
          pool.submit(
              () ->
                  tx.execute(
                      s -> {
                        var p =
                            registro.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), f);
                        recorded.countDown();
                        try {
                          if (!release.await(15, TimeUnit.SECONDS))
                            throw new IllegalStateException("Timeout isolado");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                        return p;
                      }));
      assertTrue(recorded.await(15, TimeUnit.SECONDS));
      assertTrue(producao.listar(a, comanda.id(), second, other).isEmpty());
      release.countDown();
      var p = future.get(20, TimeUnit.SECONDS);
      var list = producao.listar(a, comanda.id(), second, other);
      assertEquals(1, list.size());
      assertEquals(2, list.get(0).itens().size());
      assertEquals(p.linhas(), list.get(0).linhas());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void logoutConcorrenteNaoDeixaBaixaParcialENegaNovoEnvio() throws Exception {
    var f = form(false, food.id());
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    var recorded = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var sending =
          pool.submit(
              () ->
                  tx.execute(
                      s -> {
                        var p =
                            registro.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), f);
                        recorded.countDown();
                        try {
                          if (!release.await(15, TimeUnit.SECONDS))
                            throw new IllegalStateException("Timeout isolado");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                        return p;
                      }));
      assertTrue(recorded.await(15, TimeUnit.SECONDS));
      var logout = pool.submit(() -> op.encerrar((OperadorPrincipal) waiter.getPrincipal()));
      release.countDown();
      sending.get(20, TimeUnit.SECONDS);
      logout.get(20, TimeUnit.SECONDS);
      assertEquals(1, repo.listar(a, comanda.id()).size());
      assertEquals(19, stock(food.id()));
      assertEquals(
          423, codeOf(() -> registro.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), f)));
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void solicitacaoEInicioDePreparoConcorrentesNaoCancelamSilenciosamente() throws Exception {
    var p = order(false, food.id());
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      var x =
          pool.submit(
              () -> {
                gate.await();
                return codeOf(
                    () ->
                        alteracoes.solicitar(
                            a,
                            p.id(),
                            waiter,
                            at,
                            UUID.randomUUID(),
                            new SolicitacaoEntrada(
                                TipoSolicitacao.CANCELAMENTO,
                                "Concorrencia ficticia",
                                null,
                                p.versao())));
              });
      var y =
          pool.submit(
              () -> {
                gate.await();
                prepare(p, "COZINHA", EstadoItem.EM_PREPARO);
                return 200;
              });
      gate.countDown();
      assertTrue(Set.of(200, 409).contains(x.get(20, TimeUnit.SECONDS)));
      assertEquals(200, y.get(20, TimeUnit.SECONDS));
      assertEquals("EM_PREPARO", fresh(p).estado());
      assertEquals(19, stock(food.id()));
    } finally {
      pool.shutdownNow();
    }
  }
}
