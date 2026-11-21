package com.kitchentable.caixa;

import static com.kitchentable.caixa.CaixaDtos.*;
import static com.kitchentable.pagamentos.PagamentoDtos.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.dockerjava.api.model.*;
import com.kitchentable.cardapio.*;
import com.kitchentable.comandas.*;
import com.kitchentable.funcionarios.*;
import com.kitchentable.pagamentos.*;
import com.kitchentable.pedidos.*;
import com.kitchentable.relatorios.*;
import java.util.*;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "server.address=127.0.0.1",
      "debug=false",
      "logging.level.org.springframework=INFO"
    })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
abstract class FinanceiroPostgresBase {
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("kt_caixa_test")
          .withUsername("kt_test")
          .withPassword(UUID.randomUUID().toString())
          .withReuse(false)
          .withLabel("com.kitchentable.scope", "discardable-caixa-2-7")
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

  @DynamicPropertySource
  static synchronized void db(DynamicPropertyRegistry p) {
    if (!PG.isRunning()) {
      PG.start();
      Flyway.configure()
          .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
          .target("16")
          .load()
          .migrate();
      var j =
          new JdbcTemplate(
              new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
      j.update(
          "INSERT INTO usuarios(nome,email,senha_hash) VALUES('Legado"
              + " financeiro','financeiro-legacy@example.com',?)",
          HASH);
      checksums =
          j.queryForList(
              "SELECT version,checksum FROM flyway_schema_history WHERE version::int<=16 ORDER BY"
                  + " installed_rank");
    }
    p.add("spring.datasource.url", PG::getJdbcUrl);
    p.add("spring.datasource.username", PG::getUsername);
    p.add("spring.datasource.password", PG::getPassword);
  }

  @Autowired JdbcTemplate j;
  @Autowired CaixaService caixa;
  @Autowired CaixaRepository caixas;
  @Autowired FechamentoCaixaService fechamento;
  @Autowired AutorizacaoFinanceiraService aprovacoes;
  @Autowired PagamentoService pagamentos;
  @Autowired ContaRepository contas;
  @Autowired OperacaoFinanceiraRepository ops;
  @Autowired FuncionarioService funcionarios;
  @Autowired PermissoesService permissoes;
  @Autowired OperacionalService op;
  @Autowired CardapioService cardapio;
  @Autowired CardapioRepository catalogo;
  @Autowired ComandaService comandas;
  @Autowired com.kitchentable.mesas.MesaService mesas;
  @Autowired PedidoRegistroService pedidos;
  @Autowired PedidoRepository pedidoRepo;
  @Autowired ProducaoService producao;
  @Autowired AlteracaoPedidoService alteracoes;
  @Autowired RelatorioFinanceiroService relatorios;
  @Autowired MockMvc mvc;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  UUID a, b, owner, cash1, cash2, at, coz, bar, manager, managerLink;
  String email, managerEmail;
  Authentication admin, gerente, c1, c2, waiter, kitchen, drinks, managerOp;
  CardapioDtos.Produto food, drink, combo;
  CardapioDtos.Categoria categoria;
  ComandaDtos.Comanda comanda;
  static final List<String> FIN =
      List.of(
          "CAIXA_OPERAR",
          "CAIXA_ABRIR",
          "CAIXA_OPERADORES_CONFIGURAR",
          "CAIXA_HISTORICO_CONSULTAR",
          "PAGAMENTO_REGISTRAR",
          "PAGAMENTO_ESTORNAR",
          "CAIXA_SANGRIA_REGISTRAR",
          "CAIXA_SUPRIMENTO_REGISTRAR",
          "CAIXA_CONFERIR",
          "CAIXA_FECHAR",
          "CONTA_CONSULTAR",
          "COMANDA_FINANCEIRO_ENCERRAR",
          "COMANDA_DESCONTO_APLICAR",
          "RELATORIO_FINANCEIRO_CONSULTAR",
          "FUNCIONARIO_HISTORICO_FINANCEIRO_CONSULTAR");

  @BeforeEach
  void setup() {
    a = unit();
    b = unit();
    owner = user();
    email = mail(owner);
    link(owner, a, "PROPRIETARIO");
    link(owner, b, "PROPRIETARIO");
    admin = auth(email, "ROLE_USER");
    manager = user();
    managerEmail = mail(manager);
    managerLink = link(manager, a, "GERENTE");
    pin(managerLink, a);
    gerente = auth(managerEmail, "ROLE_USER");
    managerOp = login(managerLink, a);
    cash1 = employee(a, "Caixa um", "CAIXA");
    cash2 = employee(a, "Caixa dois", "CAIXA");
    at = employee(a, "Atendente", "ATENDENTE");
    coz = employee(a, "Cozinha", "COZINHA");
    bar = employee(a, "Bar", "BAR");
    for (UUID id : List.of(cash1, cash2)) for (String code : FIN) grant(id, code, true);
    grant(at, "CONTA_CONSULTAR", true);
    for (UUID id : List.of(coz, bar))
      for (String code : List.of("PEDIDO_CANCELAR", "PEDIDO_ENVIADO_ALTERAR"))
        grant(id, code, true);
    c1 = login(cash1, a);
    c2 = login(cash2, a);
    waiter = login(at, a);
    kitchen = login(coz, a);
    drinks = login(bar, a);
    config(a, 2, 1000, 2000, 10000, 0);
    config(b, 2, 1000, 2000, 10000, 0);
    categoria =
        cardapio.categoria(
            a,
            null,
            admin,
            new CardapioDtos.CategoriaEntrada("Cardapio financeiro", 0, true, null));
    food =
        cardapio.produto(
            a,
            null,
            admin,
            new CardapioDtos.ProdutoEntrada(
                categoria.id(),
                "Prato",
                null,
                CardapioDtos.Tipo.SIMPLES,
                10000,
                CardapioDtos.Setor.COZINHA,
                true,
                true,
                true,
                0,
                List.of(),
                null));
    food =
        cardapio.ajustar(
            a, food.id(), admin, new CardapioDtos.Ajuste(20, "Saldo ficticio", food.versao()));
    drink =
        cardapio.produto(
            a,
            null,
            admin,
            new CardapioDtos.ProdutoEntrada(
                categoria.id(),
                "Bebida",
                null,
                CardapioDtos.Tipo.SIMPLES,
                5000,
                CardapioDtos.Setor.BAR,
                true,
                true,
                false,
                0,
                List.of(),
                null));
    combo =
        cardapio.produto(
            a,
            null,
            admin,
            new CardapioDtos.ProdutoEntrada(
                categoria.id(),
                "Combo",
                null,
                CardapioDtos.Tipo.COMBO,
                18000,
                null,
                true,
                true,
                false,
                0,
                List.of(
                    new CardapioDtos.Componente(food.id(), 1),
                    new CardapioDtos.Componente(drink.id(), 1)),
                null));
    comanda =
        comandas.abrir(
            a,
            waiter,
            at,
            UUID.randomUUID(),
            new ComandaDtos.Abertura(ComandaDtos.Modalidade.BALCAO, null, null));
  }

  UUID unit() {
    UUID u = UUID.randomUUID();
    j.update("INSERT INTO estabelecimentos(id,nome) VALUES(?,'Unidade ficticia financeiro')", u);
    return u;
  }

  String mail(UUID id) {
    return id + "@example.com";
  }

  UUID user() {
    UUID u = UUID.randomUUID();
    j.update(
        "INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,'Pessoa teste',?,?)",
        u,
        mail(u),
        HASH);
    return u;
  }

  UUID link(UUID user, UUID unit, String role) {
    UUID id = UUID.randomUUID();
    j.update(
        "INSERT INTO usuarios_estabelecimentos(id,usuario_id,estabelecimento_id,funcao_id) SELECT"
            + " ?,?,?,id FROM funcoes WHERE codigo=?",
        id,
        user,
        unit,
        role);
    return id;
  }

  void pin(UUID id, UUID unit) {
    j.update(
        "INSERT INTO credenciais_operacionais(vinculo_id,estabelecimento_id,pin_hash)"
            + " VALUES(?,?,?)",
        id,
        unit,
        PINHASH);
  }

  UUID employee(UUID u, String name, String role) {
    var f =
        funcionarios.cadastrar(
            u, email, UUID.randomUUID(), new FuncionarioDtos.Cadastro(name, role, null, null));
    pin(f.vinculoId(), u);
    return f.vinculoId();
  }

  Authentication auth(Object who, String role) {
    return new UsernamePasswordAuthenticationToken(
        who, null, List.of(new SimpleGrantedAuthority(role)));
  }

  Authentication login(UUID id, UUID u) {
    var p = op.entrar(new OperacionalDtos.Entrada(u.toString(), id.toString(), PIN), null);
    assertNotNull(p);
    return auth(p, "ROLE_OPERADOR");
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

  void config(UUID u, Integer limit, long desconto, int bps, long sangria, long divergence) {
    var p = caixa.politica(u, admin);
    caixa.configurar(
        u, admin, new Configuracao(limit, desconto, bps, sangria, divergence, p.versao()));
  }

  Turno open() {
    return open(10000);
  }

  Turno open(long inicial) {
    return caixa.abrir(a, c1, cash1, UUID.randomUUID(), new Abertura(cash1, inicial));
  }

  Turno team(Turno t) {
    return caixa.equipeAdmin(
        a,
        t.id(),
        admin,
        new Equipe(
            List.of(cash1, cash2),
            cash1,
            caixas.turno(a, t.id()).versao(),
            "Operadores ficticios autorizados"));
  }

  PedidoDtos.Confirmacao form(UUID product) {
    var o =
        pedidos.ofertas(a, waiter, at).stream()
            .filter(p -> p.produto().id().equals(product))
            .findFirst()
            .orElseThrow();
    return new PedidoDtos.Confirmacao(
        false,
        List.of(
            new PedidoDtos.LinhaEntrada(
                product, 1, o.produto().precoCentavos(), o.assinaturaOferta(), null)));
  }

  PedidoDtos.Pedido order() {
    return order(food.id());
  }

  PedidoDtos.Pedido order(UUID id) {
    return pedidos.registrar(a, comanda.id(), waiter, at, UUID.randomUUID(), form(id));
  }

  void deliver(PedidoDtos.Pedido p) {
    for (var i : pedidoRepo.pedido(a, p.id()).itens()) {
      Authentication actor = i.setor().equals("COZINHA") ? kitchen : drinks;
      UUID id = i.setor().equals("COZINHA") ? coz : bar;
      producao.preparar(
          a,
          i.setor(),
          i.id(),
          actor,
          id,
          new PedidoDtos.Atualizacao(PedidoDtos.EstadoItem.EM_PREPARO, i.versao()));
      producao.preparar(
          a,
          i.setor(),
          i.id(),
          actor,
          id,
          new PedidoDtos.Atualizacao(PedidoDtos.EstadoItem.PRONTO, i.versao() + 1));
    }
    var latest = pedidoRepo.pedido(a, p.id());
    producao.entregar(
        a,
        p.id(),
        waiter,
        at,
        new PedidoDtos.Entrega(
            latest.itens().stream()
                .map(i -> new PedidoDtos.Referencia(i.id(), i.versao()))
                .toList()));
  }

  Conta account() {
    return pagamentos.consultar(a, comanda.id(), c1, cash1);
  }

  Receber payment(Meio meio, long amount) {
    return new Receber(
        meio,
        amount,
        meio == Meio.DINHEIRO ? amount : null,
        meio != Meio.DINHEIRO,
        account().revisao());
  }

  Lancamento pay(Turno t, Meio method, long amount) {
    return pagamentos.receber(
        a, t.id(), comanda.id(), c1, cash1, UUID.randomUUID(), payment(method, amount));
  }

  UUID approve(Turno t, String tipo, UUID entity, Object body) {
    var request =
        aprovacoes.solicitar(
            a,
            c1,
            cash1,
            UUID.randomUUID(),
            new SolicitarAutorizacao(
                tipo, t.id(), entity, ops.node(body), "Solicitacao ficticia para validacao"));
    aprovacoes.decidir(
        a,
        request.id(),
        admin,
        new Autorizar(request.versao(), "APROVAR", "Aprovacao explicita de teste"));
    return request.id();
  }

  Compensar compensation(Turno t, Lancamento l, String natureza) {
    var d = new Compensar(natureza, "Compensacao ficticia auditada", account().revisao(), null);
    UUID id = approve(t, l.tipo().equals("DESCONTO") ? "DESCONTO_ESTORNO" : "ESTORNO", l.id(), d);
    return new Compensar(d.natureza(), d.motivo(), d.revisaoConta(), id);
  }

  Quitacao settle(Turno t) {
    return pagamentos.encerrar(
        a, t.id(), comanda.id(), c1, cash1, UUID.randomUUID(), new Encerrar(account().revisao()));
  }

  Conferencia conference(Turno t, long amount, String reason) {
    return fechamento.conferir(
        a,
        t.id(),
        c1,
        cash1,
        UUID.randomUUID(),
        new Conferir(amount, reason, caixas.turno(a, t.id()).versao()));
  }

  Fechamento close(Turno t) {
    var c = conference(t, caixas.resumo(a, t.id()).dinheiroEsperadoCentavos(), null);
    return fechamento.fechar(
        a, t.id(), c1, cash1, UUID.randomUUID(), new Fechar(c.id(), c.versaoTurno(), null));
  }

  int code(Runnable action) {
    try {
      action.run();
      return 200;
    } catch (ResponseStatusException e) {
      return e.getStatusCode().value();
    }
  }

  PedidoDtos.Solicitacao cancel(PedidoDtos.Pedido p) {
    return alteracoes.solicitar(
        a,
        p.id(),
        waiter,
        at,
        UUID.randomUUID(),
        new PedidoDtos.SolicitacaoEntrada(
            PedidoDtos.TipoSolicitacao.CANCELAMENTO,
            "Cancelar antes do preparo",
            null,
            pedidoRepo.pedido(a, p.id()).versao()));
  }

  PedidoDtos.ResultadoDecisao decide(PedidoDtos.Solicitacao req, String sector) {
    var s = pedidoRepo.solicitacao(a, req.id());
    var d = s.decisoes().stream().filter(x -> x.setor().equals(sector)).findFirst().orElseThrow();
    return alteracoes.decidir(
        a,
        s.id(),
        sector,
        sector.equals("COZINHA") ? kitchen : drinks,
        sector.equals("COZINHA") ? coz : bar,
        new PedidoDtos.DecisaoEntrada(true, null, d.versao(), s.versaoPedido()));
  }
}
