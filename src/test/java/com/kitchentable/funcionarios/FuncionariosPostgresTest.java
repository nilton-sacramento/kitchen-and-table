package com.kitchentable.funcionarios;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.github.dockerjava.api.model.*;
import static com.kitchentable.funcionarios.FuncionarioDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"server.address=127.0.0.1","debug=false","logging.level.org.springframework=INFO"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class FuncionariosPostgresTest {
    // Exclusivamente descartavel: sem URL externa, porta fixa, volume nomeado ou dados reais.
    static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("kt_funcionarios_test").withUsername("kt_test")
        .withPassword(UUID.randomUUID().toString()).withReuse(false)
        .withLabel("com.kitchentable.scope","discardable-test-2-1")
        .withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),new ExposedPort(5432))));
    static final UUID LEGACY_USER=UUID.randomUUID(),LEGACY_UNIT=UUID.randomUUID(),LEGACY_LINK=UUID.randomUUID();
    static final String LEGACY_EMAIL="legacy-owner-test@example.com";
    static final String PASSWORD=UUID.randomUUID().toString();
    static final String LEGACY_HASH=new BCryptPasswordEncoder().encode(PASSWORD);
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry props) {
        PG.start();
        // Migrar primeiro somente V1, inserir estado legado e deixar Spring aplicar V2-V7.
        Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()).target("1").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        jdbc.update("INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,'Legado de teste',?,?)",LEGACY_USER,LEGACY_EMAIL,LEGACY_HASH);
        jdbc.update("INSERT INTO estabelecimentos(id,nome) VALUES(?,'Unidade legada de teste')",LEGACY_UNIT);
        jdbc.update("INSERT INTO usuarios_estabelecimentos(id,usuario_id,estabelecimento_id,funcao_id) SELECT ?,?,?,id FROM funcoes WHERE codigo='PROPRIETARIO'",LEGACY_LINK,LEGACY_USER,LEGACY_UNIT);
        props.add("spring.datasource.url",PG::getJdbcUrl);props.add("spring.datasource.username",PG::getUsername);props.add("spring.datasource.password",PG::getPassword);
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FuncionarioService service;
    @Autowired AssociacaoFuncionarioService associacoes;
    @Autowired PermissoesService permissoes;
    UUID a,b,c,owner,manager,managerLink;
    String ownerEmail,managerEmail;
    MockHttpSession ownerSession,managerSession;
    @BeforeEach
    void fixture() throws Exception {
        // Cada teste usa identidades/unidades novas; nao ha TRUNCATE/limpeza de dados existentes.
        a=unit();b=unit();c=unit();owner=UUID.randomUUID();manager=UUID.randomUUID();
        ownerEmail="owner-"+owner+"@example.com";managerEmail="manager-"+manager+"@example.com";
        jdbc.update("INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,'Proprietario de teste',?,?)",owner,ownerEmail,LEGACY_HASH);
        jdbc.update("INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,'Gerente de teste',?,?)",manager,managerEmail,LEGACY_HASH);
        link(owner,a,"PROPRIETARIO");link(owner,b,"PROPRIETARIO");link(owner,c,"PROPRIETARIO");managerLink=link(manager,a,"GERENTE");
        ownerSession=login(ownerEmail);managerSession=login(managerEmail);
    }
    UUID unit() { UUID id=UUID.randomUUID();jdbc.update("INSERT INTO estabelecimentos(id,nome) VALUES(?,'Unidade de teste')",id);return id; }
    UUID link(UUID user,UUID unit,String role) {
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO usuarios_estabelecimentos(id,usuario_id,estabelecimento_id,funcao_id) SELECT ?,?,?,id FROM funcoes WHERE codigo=?",id,user,unit,role);return id;
    }
    MockHttpSession login(String email) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/v1/auth/login").with(csrf()).param("email",email).param("password",PASSWORD))
            .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
    String path(UUID unit) { return "/api/v1/estabelecimentos/"+unit+"/funcionarios"; }
    JsonNode create(UUID unit,String nome,String role) throws Exception {
        var body=new Cadastro(nome,role,null,null);
        return json.readTree(mvc.perform(post(path(unit)).session(ownerSession).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    UUID id(JsonNode row) { return UUID.fromString(row.get("vinculoId").asText()); }
    void grant(boolean allowed) {
        service.delegar(a,managerLink,ownerEmail,new Delegacao(allowed,allowed,allowed,allowed,allowed,allowed,allowed?List.of("ATENDENTE"):List.of(),0L));
    }
    UUID request(UUID source,UUID target,UUID employee,String email) { return associacoes.solicitar(source,employee,email,new PedidoAssociacao(target)).id(); }
    int statusOf(Runnable work) { try{work.run();return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();} }
    List<Integer> race(Callable<Integer> one,Callable<Integer> two) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try { var x=pool.submit(()->{start.await();return one.call();});var y=pool.submit(()->{start.await();return two.call();});start.countDown();return List.of(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS)); }
        finally { pool.shutdownNow(); }
    }
    @Test void migracoesPreservamV1EProprietario() throws Exception {
        assertEquals(LEGACY_HASH,jdbc.queryForObject("SELECT senha_hash FROM usuarios WHERE id=?",String.class,LEGACY_USER));
        assertEquals(LEGACY_EMAIL,jdbc.queryForObject("SELECT email FROM usuarios WHERE id=?",String.class,LEGACY_USER));
        assertEquals(0L,jdbc.queryForObject("SELECT versao FROM usuarios_estabelecimentos WHERE id=?",Long.class,LEGACY_LINK));
        assertEquals(10,jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success=true AND version IS NOT NULL",Integer.class));
        assertTrue(jdbc.queryForObject("SELECT version()",String.class).contains("PostgreSQL 16"));
        mvc.perform(get("/api/v1/auth/me").session(login(LEGACY_EMAIL))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(LEGACY_USER.toString()));
    }
    @Test void cadastroSemEmailSenhaOuPin() throws Exception {
        var row=create(a,"Pessoa operacional","ATENDENTE");UUID user=UUID.fromString(row.get("usuarioId").asText());
        var raw=jdbc.queryForMap("SELECT email,senha_hash,pin_hash FROM usuarios WHERE id=?",user);
        assertNull(raw.get("email"));assertNull(raw.get("senha_hash"));assertNull(raw.get("pin_hash"));assertFalse(row.get("acessoOperacionalDisponivel").asBoolean());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='FUNCIONARIO_CADASTRADO'",Integer.class,a));
    }
    @Test void contatoNaoSeTornaLoginAdministrativo() throws Exception {
        var dto=new Cadastro("Contato opcional","ATENDENTE",ownerEmail,"123");var f=service.cadastrar(a,ownerEmail,UUID.randomUUID(),dto);
        assertEquals(ownerEmail,f.emailContato());assertNull(jdbc.queryForObject("SELECT email FROM usuarios WHERE id=?",String.class,f.usuarioId()));
        mvc.perform(get("/api/v1/auth/me").session(ownerSession)).andExpect(status().isOk());
    }
    @Test void gerenteSemDelegacaoNaoRecebePrivilegiosAutomaticos() throws Exception {
        mvc.perform(get(path(a)).session(managerSession)).andExpect(status().isForbidden());
        mvc.perform(post(path(a)).session(managerSession).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsBytes(new Cadastro("Negado","ATENDENTE",null,null)))).andExpect(status().isForbidden());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM delegacoes_administrativas WHERE vinculo_id=?",Integer.class,managerLink));
    }
    @Test void gerenteAutorizadoRespeitaFuncoesRecebidas() throws Exception {
        grant(true);
        mvc.perform(post(path(a)).session(managerSession).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsBytes(new Cadastro("Permitido","ATENDENTE",null,null)))).andExpect(status().isCreated());
        mvc.perform(post(path(a)).session(managerSession).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsBytes(new Cadastro("Negado","BAR",null,null)))).andExpect(status().isForbidden());
        mvc.perform(post(path(a)).session(managerSession).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsBytes(new Cadastro("Negado","GERENTE",null,null)))).andExpect(status().isForbidden());
    }
    @Test void gerenteNaoSeAutorizaNemPromoveFuncionario() throws Exception {
        grant(true);var target=create(a,"Pessoa","ATENDENTE");
        mvc.perform(patch(path(a)+"/"+id(target)+"/funcao-principal").session(managerSession).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(new Funcao("GERENTE",0L)))).andExpect(status().isForbidden());
        mvc.perform(put(path(a)+"/"+managerLink+"/autorizacao-administrativa").session(managerSession).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(new Delegacao(true,true,true,true,true,true,List.of("BAR"),1L)))).andExpect(status().isForbidden());
    }
    @Test void proprietarioPodePromoverMasDelegacaoNaoRessurge() throws Exception {
        grant(true);service.funcao(a,managerLink,ownerEmail,new Funcao("ATENDENTE",1L));service.funcao(a,managerLink,ownerEmail,new Funcao("GERENTE",2L));
        assertFalse(service.consultarDelegacao(a,managerLink,ownerEmail).cadastrar());
        mvc.perform(get(path(a)).session(managerSession)).andExpect(status().isForbidden());
    }
    @Test void acessoDoProprietarioNaoPodeSerAlterado() throws Exception {
        UUID ownerLink=jdbc.queryForObject("SELECT id FROM usuarios_estabelecimentos WHERE usuario_id=? AND estabelecimento_id=?",UUID.class,owner,a);
        mvc.perform(patch(path(a)+"/"+ownerLink+"/situacao").session(ownerSession).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(new Situacao(false,0L)))).andExpect(status().isForbidden());
        grant(true);mvc.perform(patch(path(a)+"/"+ownerLink).session(managerSession).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(new Edicao("Indevido",null,null,0L)))).andExpect(status().isForbidden());
    }
    @Test void idsDeOutraUnidadeNaoDaoAcesso() throws Exception {
        var target=create(b,"Outra unidade","ATENDENTE");
        mvc.perform(get(path(a)+"/"+id(target)).session(ownerSession)).andExpect(status().isNotFound());
        mvc.perform(patch(path(a)+"/"+id(target)+"/situacao").session(ownerSession).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(new Situacao(false,0L)))).andExpect(status().isNotFound());
        mvc.perform(get(path(b)).session(managerSession)).andExpect(status().isForbidden());
        assertTrue(jdbc.queryForObject("SELECT ativo FROM usuarios_estabelecimentos WHERE id=?",Boolean.class,id(target)));
    }
    @Test void edicaoLocalNaoAlteraIdentidadeOuOutraUnidade() throws Exception {
        var target=create(a,"Original","ATENDENTE");UUID req=request(a,b,id(target),ownerEmail);var second=associacoes.aceitar(b,req,ownerEmail,UUID.randomUUID(),new Aceite("BAR"));
        service.editar(a,id(target),ownerEmail,new Edicao("Nome local","contato@example.com","321",0L));
        assertEquals("Original",jdbc.queryForObject("SELECT nome FROM usuarios WHERE id=?",String.class,second.usuarioId()));
        assertEquals("Original",service.consultar(b,second.vinculoId(),ownerEmail).nome());
        assertNull(service.consultar(b,second.vinculoId(),ownerEmail).emailContato());
    }
    @Test void desativacaoEReativacaoPreservamOutrasUnidadesEHistorico() throws Exception {
        var target=create(a,"Pessoa","ATENDENTE");var second=associacoes.aceitar(b,request(a,b,id(target),ownerEmail),ownerEmail,UUID.randomUUID(),new Aceite("BAR"));
        service.situacao(a,id(target),ownerEmail,new Situacao(false,0L));
        assertTrue(service.consultar(b,second.vinculoId(),ownerEmail).ativo());assertTrue(jdbc.queryForObject("SELECT ativo FROM usuarios WHERE id=?",Boolean.class,second.usuarioId()));
        service.situacao(a,id(target),ownerEmail,new Situacao(true,1L));assertTrue(service.consultar(a,id(target),ownerEmail).ativo());
        assertEquals(Set.of("FUNCIONARIO_CADASTRADO","ASSOCIACAO_SOLICITADA","FUNCIONARIO_DESATIVADO","FUNCIONARIO_ATIVADO"),new HashSet<>(jdbc.queryForList("SELECT tipo_evento FROM auditoria_eventos WHERE estabelecimento_id=? AND entidade_id=?",String.class,a,id(target))));
    }
    @Test void sessaoExistenteRevalidaVinculoEDelegacao() throws Exception {
        grant(true);mvc.perform(get(path(a)).session(managerSession)).andExpect(status().isOk());
        service.delegar(a,managerLink,ownerEmail,new Delegacao(false,false,false,false,false,false,List.of(),1L));
        mvc.perform(get(path(a)).session(managerSession)).andExpect(status().isForbidden());
        service.situacao(a,managerLink,ownerEmail,new Situacao(false,2L));
        mvc.perform(get(path(a)+"/capacidades").session(managerSession)).andExpect(status().isForbidden());
    }
    @Test void concorrenciaNaoSobrescreveEdicao() throws Exception {
        var target=create(a,"Original","ATENDENTE");
        var results=race(()->statusOf(()->service.editar(a,id(target),ownerEmail,new Edicao("Edicao A",null,null,0L))),()->statusOf(()->service.editar(a,id(target),ownerEmail,new Edicao("Edicao B",null,null,0L))));
        assertEquals(Set.of(200,409),new HashSet<>(results));assertEquals(1L,service.consultar(a,id(target),ownerEmail).versao());
    }
    @Test void cadastroConcorrenteIdempotenteCriaUmaIdentidade() throws Exception {
        UUID key=UUID.randomUUID();var dto=new Cadastro("Idempotente","ATENDENTE",null,null);
        var results=race(()->{service.cadastrar(a,ownerEmail,key,dto);return 200;},()->{service.cadastrar(a,ownerEmail,key,dto);return 200;});assertEquals(List.of(200,200),results);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM usuarios_estabelecimentos WHERE estabelecimento_id=? AND nome_exibicao='Idempotente'",Integer.class,a));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='FUNCIONARIO_CADASTRADO'",Integer.class,a));
    }
    @Test void idempotenciaNaoAceitaPayloadDiferente() {
        UUID key=UUID.randomUUID();service.cadastrar(a,ownerEmail,key,new Cadastro("Primeiro","ATENDENTE",null,null));
        assertEquals(409,statusOf(()->service.cadastrar(a,ownerEmail,key,new Cadastro("Segundo","ATENDENTE",null,null))));
    }
    @Test void auditoriaRegistraExecutorAntesEDepoisSemCredenciais() throws Exception {
        var target=create(a,"Antes","ATENDENTE");service.editar(a,id(target),ownerEmail,new Edicao("Depois",null,null,0L));
        var event=jdbc.queryForMap("SELECT usuario_id,dados_evento::text AS dados FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='FUNCIONARIO_EDITADO'",a);
        assertEquals(owner,event.get("usuario_id"));String data=(String)event.get("dados");assertTrue(data.contains("Antes"));assertTrue(data.contains("Depois"));assertFalse(data.contains("senha_hash"));assertFalse(data.contains("pin_hash"));assertFalse(data.contains(PASSWORD));
    }
    @Test void falhaDeAuditoriaReverteCadastroInteiro() throws Exception {
        // Constraint criada somente no Postgres descartavel e restrita a esta fixture.
        jdbc.execute("ALTER TABLE auditoria_eventos ADD CONSTRAINT fail_"+a.toString().replace("-","")+" CHECK (estabelecimento_id <> '"+a+"'::uuid) NOT VALID");
        mvc.perform(post(path(a)).session(ownerSession).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsBytes(new Cadastro("Rollback-"+a,"ATENDENTE",null,null)))).andExpect(status().isConflict());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM usuarios WHERE nome=?",Integer.class,"Rollback-"+a));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operacoes_administrativas WHERE estabelecimento_id=?",Integer.class,a));
    }
    @Test void associacaoExigeOrigemAutorizadaEDestinoCorreto() throws Exception {
        var target=create(a,"Pessoa","ATENDENTE");
        assertEquals(403,statusOf(()->request(a,b,id(target),managerEmail)));
        UUID req=request(a,b,id(target),ownerEmail);
        mvc.perform(post(path(c)+"/associacoes/"+req+"/aceitar").session(ownerSession).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsBytes(new Aceite("BAR")))).andExpect(status().isNotFound());
    }
    @Test void associacaoPreservaIdentidadeEConsomeUmaVez() throws Exception {
        var target=create(a,"Pessoa","ATENDENTE");UUID req=request(a,b,id(target),ownerEmail),key=UUID.randomUUID();
        var result=associacoes.aceitar(b,req,ownerEmail,key,new Aceite("BAR"));assertEquals(target.get("usuarioId").asText(),result.usuarioId().toString());
        assertEquals(result.vinculoId(),associacoes.aceitar(b,req,ownerEmail,key,new Aceite("BAR")).vinculoId());
        assertEquals(409,statusOf(()->associacoes.aceitar(b,req,ownerEmail,UUID.randomUUID(),new Aceite("BAR"))));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM usuarios_estabelecimentos WHERE usuario_id=?",Integer.class,result.usuarioId()));
    }
    @Test void associacaoExpiradaNaoCriaVinculo() throws Exception {
        var target=create(a,"Pessoa","ATENDENTE");UUID req=request(a,b,id(target),ownerEmail);
        jdbc.update("UPDATE associacoes_funcionarios SET criado_em=clock_timestamp()-interval '2 hour',expira_em=clock_timestamp()-interval '1 hour' WHERE id=?",req);
        assertEquals(409,statusOf(()->associacoes.aceitar(b,req,ownerEmail,UUID.randomUUID(),new Aceite("BAR"))));
    }
    @Test void revogacaoDaOrigemInvalidaSolicitacaoPendente() throws Exception {
        grant(true);var target=create(a,"Pessoa","ATENDENTE");UUID req=request(a,b,id(target),managerEmail);
        service.delegar(a,managerLink,ownerEmail,new Delegacao(false,false,false,false,false,false,List.of(),1L));
        assertEquals(409,statusOf(()->associacoes.aceitar(b,req,ownerEmail,UUID.randomUUID(),new Aceite("BAR"))));
    }
    @Test void associacaoConcorrenteNaoDuplicaVinculo() throws Exception {
        var target=create(a,"Pessoa","ATENDENTE");UUID req=request(a,b,id(target),ownerEmail);
        var results=race(()->statusOf(()->associacoes.aceitar(b,req,ownerEmail,UUID.randomUUID(),new Aceite("BAR"))),()->statusOf(()->associacoes.aceitar(b,req,ownerEmail,UUID.randomUUID(),new Aceite("BAR"))));
        assertEquals(Set.of(200,409),new HashSet<>(results));
    }
    @Test void vinculoExistenteMesmoInativoNaoEDuplicado() throws Exception {
        var target=create(a,"Pessoa","ATENDENTE");var existente=associacoes.aceitar(b,request(a,b,id(target),ownerEmail),ownerEmail,UUID.randomUUID(),new Aceite("BAR"));
        service.situacao(b,existente.vinculoId(),ownerEmail,new Situacao(false,0L));
        UUID next=request(a,b,id(target),ownerEmail);assertEquals(409,statusOf(()->associacoes.aceitar(b,next,ownerEmail,UUID.randomUUID(),new Aceite("BAR"))));
    }
    @Test void csrfEAutenticacaoContinuamObrigatorios() throws Exception {
        mvc.perform(get(path(a))).andExpect(status().isUnauthorized());
        mvc.perform(post(path(a)).session(ownerSession).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsBytes(new Cadastro("Sem CSRF","ATENDENTE",null,null)))).andExpect(status().isForbidden());
    }
    @Test void funcoesSaoIndependentesNaAssociacao() throws Exception {
        UUID local=link(manager,b,"GERENTE");
        service.delegar(b,local,ownerEmail,new Delegacao(true,false,false,false,false,true,List.of("ATENDENTE"),0L));
        var origem=create(a,"Gerente na origem","GERENTE");UUID req=request(a,b,id(origem),ownerEmail);
        var result=associacoes.aceitar(b,req,managerEmail,UUID.randomUUID(),new Aceite("ATENDENTE"));
        assertEquals("ATENDENTE",result.funcao());assertEquals("GERENTE",service.consultar(a,id(origem),ownerEmail).funcao());
    }
    @Test void desativarGerenteEmUmaUnidadeNaoBloqueiaOutra() throws Exception {
        grant(true);UUID local=link(manager,b,"GERENTE");
        service.delegar(b,local,ownerEmail,new Delegacao(true,false,false,false,false,false,List.of("ATENDENTE"),0L));
        service.situacao(a,managerLink,ownerEmail,new Situacao(false,1L));
        mvc.perform(get(path(a)).session(managerSession)).andExpect(status().isForbidden());
        mvc.perform(get(path(b)).session(managerSession)).andExpect(status().isOk());
    }
    @Test void usuarioGlobalInativoNaoAdministraComSessaoAntiga() throws Exception {
        grant(true);jdbc.update("UPDATE usuarios SET ativo=false WHERE id=?",manager);
        mvc.perform(get(path(a)).session(managerSession)).andExpect(status().isForbidden());
    }
    @Test void versaoAusenteNaoEInterpretadaComoZero() throws Exception {
        var target=create(a,"Pessoa","ATENDENTE");
        mvc.perform(patch(path(a)+"/"+id(target)+"/situacao").session(ownerSession).with(csrf()).contentType("application/json").content("{\"ativo\":false}"))
            .andExpect(status().isBadRequest());
        mvc.perform(patch(path(a)+"/"+id(target)+"/situacao").session(ownerSession).with(csrf()).contentType("application/json").content("{\"versao\":0}"))
            .andExpect(status().isBadRequest());
        assertTrue(service.consultar(a,id(target),ownerEmail).ativo());
    }
    @Test void camposExtrasNaoElevamPrivilegios() throws Exception {
        grant(true);var target=create(a,"Pessoa","ATENDENTE");
        mvc.perform(patch(path(a)+"/"+id(target)).session(managerSession).with(csrf()).contentType("application/json")
            .content("{\"nome\":\"Nome local\",\"versao\":0,\"funcao\":\"GERENTE\",\"proprietario\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.funcao").value("ATENDENTE"));
    }
    @Test void assetNovoPublicoSemLiberarApi() throws Exception {
        mvc.perform(get("/assets/funcionarios.js")).andExpect(status().isOk());
        mvc.perform(get(path(a)+"/capacidades")).andExpect(status().isUnauthorized());
    }
    // Segura a transacao da primeira operacao e observa o bloqueio real da segunda no PostgreSQL.
    List<Integer> serializadas(Runnable primeira,Runnable segunda) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var aplicada=new CountDownLatch(1);var liberar=new CountDownLatch(1);
        var pid=new CompletableFuture<Integer>();
        try {
            var x=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx->{
                primeira.run();aplicada.countDown();
                try { if(!liberar.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Barreira excedida"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return 200;
            }));
            assertTrue(aplicada.await(5,TimeUnit.SECONDS));
            var y=pool.submit(()->statusOf(()->new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
                pid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));segunda.run();
            })));
            int backend=pid.get(5,TimeUnit.SECONDS);boolean bloqueada=false;
            for(int i=0;i<100;i++) {
                if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,backend))){bloqueada=true;break;}
                Thread.sleep(20);
            }
            assertTrue(bloqueada,"A segunda mutacao deve aguardar o lock da primeira");
            liberar.countDown();return List.of(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS));
        } finally {liberar.countDown();pool.shutdownNow();}
    }
    @Test void leiturasNaoAguardamLockDeMutacaoDaUnidade() throws Exception {
        var pessoa=create(a,"Antes do commit","ATENDENTE");grant(true);
        UUID incoming=request(b,a,id(create(b,"Recebida","ATENDENTE")),ownerEmail);
        var pool=Executors.newFixedThreadPool(2);var aplicada=new CountDownLatch(1);var liberar=new CountDownLatch(1);
        try {
            var writer=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
                service.editar(a,id(pessoa),ownerEmail,new Edicao("Depois do commit",null,null,0L));aplicada.countDown();
                try {if(!liberar.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Barreira excedida");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));
            assertTrue(aplicada.await(5,TimeUnit.SECONDS));
            var reader=pool.submit(()->{
                assertTrue(service.capacidades(a,ownerEmail).proprietario());
                assertTrue(service.listar(a,ownerEmail).stream().anyMatch(f->f.nome().equals("Antes do commit")));
                assertEquals("Antes do commit",service.consultar(a,id(pessoa),ownerEmail).nome());
                assertTrue(service.consultarDelegacao(a,managerLink,ownerEmail).consultar());
                assertTrue(associacoes.listar(a,ownerEmail).stream().anyMatch(r->r.id().equals(incoming)));
            });
            reader.get(3,TimeUnit.SECONDS); // FOR UPDATE faria este timeout falhar.
            liberar.countDown();writer.get(5,TimeUnit.SECONDS);
            assertEquals("Depois do commit",service.consultar(a,id(pessoa),ownerEmail).nome());
        } finally {liberar.countDown();pool.shutdownNow();}
    }
    @Test void leiturasRevalidamUnidadeEVinculoSemLock() throws Exception {
        grant(true);service.situacao(a,managerLink,ownerEmail,new Situacao(false,1L));
        assertEquals(403,statusOf(()->service.listar(a,managerEmail)));
        assertEquals(403,statusOf(()->associacoes.listar(a,managerEmail)));
        jdbc.update("UPDATE estabelecimentos SET ativo=false WHERE id=?",a);
        assertEquals(404,statusOf(()->service.capacidades(a,ownerEmail)));
        assertEquals(404,statusOf(()->service.consultarDelegacao(a,managerLink,ownerEmail)));
        assertEquals(404,statusOf(()->associacoes.listar(a,ownerEmail)));
    }
    UUID gerenteDestino() {
        UUID local=link(manager,b,"GERENTE");
        service.delegar(b,local,ownerEmail,new Delegacao(true,false,false,false,false,true,List.of("ATENDENTE"),0L));return local;
    }
    void revogar(UUID unidade,UUID vinculo) {
        service.delegar(unidade,vinculo,ownerEmail,new Delegacao(false,false,false,false,false,false,List.of(),1L));
    }
    void semAceite(UUID req,UUID pessoa) {
        assertNull(jdbc.queryForObject("SELECT consumida_em FROM associacoes_funcionarios WHERE id=?",java.sql.Timestamp.class,req));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM usuarios_estabelecimentos WHERE usuario_id=? AND estabelecimento_id=?",Integer.class,pessoa,b));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='ASSOCIACAO_ACEITA'",Integer.class,b));
    }
    @Test void revogacaoNoDestinoImpedeAceiteComSessaoExistente() throws Exception {
        UUID gerente=gerenteDestino();var pessoa=create(a,"Pessoa","ATENDENTE");UUID req=request(a,b,id(pessoa),ownerEmail);
        assertEquals(1,associacoes.listar(b,managerEmail).size());revogar(b,gerente);
        mvc.perform(post(path(b)+"/associacoes/"+req+"/aceitar").session(managerSession).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsBytes(new Aceite("ATENDENTE")))).andExpect(status().isForbidden());
        assertEquals(403,statusOf(()->associacoes.listar(b,managerEmail)));semAceite(req,UUID.fromString(pessoa.get("usuarioId").asText()));
    }
    @Test void revogacaoOrigemPrimeiroSerializaEImpedeAceite() throws Exception {
        grant(true);var pessoa=create(a,"Pessoa","ATENDENTE");UUID req=request(a,b,id(pessoa),managerEmail);
        assertEquals(List.of(200,409),serializadas(()->revogar(a,managerLink),()->associacoes.aceitar(b,req,ownerEmail,UUID.randomUUID(),new Aceite("ATENDENTE"))));
        semAceite(req,UUID.fromString(pessoa.get("usuarioId").asText()));
    }
    @Test void aceitePrimeiroSerializaRevogacaoDaOrigemSemApagarVinculo() throws Exception {
        grant(true);var pessoa=create(a,"Pessoa","ATENDENTE");UUID req=request(a,b,id(pessoa),managerEmail);
        assertEquals(List.of(200,200),serializadas(()->associacoes.aceitar(b,req,ownerEmail,UUID.randomUUID(),new Aceite("ATENDENTE")),()->revogar(a,managerLink)));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM usuarios_estabelecimentos WHERE usuario_id=? AND estabelecimento_id=?",Integer.class,UUID.fromString(pessoa.get("usuarioId").asText()),b));
        assertEquals(403,statusOf(()->request(a,c,id(pessoa),managerEmail)));
    }
    @Test void revogacaoDestinoPrimeiroRevalidaAposLock() throws Exception {
        UUID gerente=gerenteDestino();var pessoa=create(a,"Pessoa","ATENDENTE");UUID req=request(a,b,id(pessoa),ownerEmail);
        assertEquals(List.of(200,403),serializadas(()->revogar(b,gerente),()->associacoes.aceitar(b,req,managerEmail,UUID.randomUUID(),new Aceite("ATENDENTE"))));
        semAceite(req,UUID.fromString(pessoa.get("usuarioId").asText()));
    }
    @Test void aceitePrimeiroSerializaRevogacaoDoDestino() throws Exception {
        UUID gerente=gerenteDestino();var pessoa=create(a,"Pessoa","ATENDENTE");UUID req=request(a,b,id(pessoa),ownerEmail);
        assertEquals(List.of(200,200),serializadas(()->associacoes.aceitar(b,req,managerEmail,UUID.randomUUID(),new Aceite("ATENDENTE")),()->revogar(b,gerente)));
        assertEquals(403,statusOf(()->associacoes.aceitar(b,req,managerEmail,UUID.randomUUID(),new Aceite("ATENDENTE"))));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='ASSOCIACAO_ACEITA'",Integer.class,b));
    }
    @Test void expiracaoNaoListaNemConsomeOuAuditaAceite() throws Exception {
        var pessoa=create(a,"Pessoa","ATENDENTE");UUID req=request(a,b,id(pessoa),ownerEmail);
        jdbc.update("UPDATE associacoes_funcionarios SET criado_em=clock_timestamp()-interval '2 hour',expira_em=clock_timestamp()-interval '1 hour' WHERE id=?",req);
        assertTrue(associacoes.listar(b,ownerEmail).isEmpty());
        assertEquals(409,statusOf(()->associacoes.aceitar(b,req,ownerEmail,UUID.randomUUID(),new Aceite("ATENDENTE"))));
        semAceite(req,UUID.fromString(pessoa.get("usuarioId").asText()));
    }
    @Test void solicitacaoNaoVazaEmOutraUnidadeNemAceitaMesmoComIdConhecido() throws Exception {
        var pessoa=create(a,"Pessoa","ATENDENTE");UUID req=request(a,b,id(pessoa),ownerEmail);
        assertTrue(associacoes.listar(c,ownerEmail).isEmpty());
        assertEquals(404,statusOf(()->associacoes.aceitar(c,req,ownerEmail,UUID.randomUUID(),new Aceite("ATENDENTE"))));
        assertEquals(403,statusOf(()->associacoes.aceitar(b,req,managerEmail,UUID.randomUUID(),new Aceite("ATENDENTE"))));
        semAceite(req,UUID.fromString(pessoa.get("usuarioId").asText()));
    }
    @Test void replayConsumidoRevalidaAutorizacaoSemDuplicarAuditoria() throws Exception {
        UUID gerente=gerenteDestino();var pessoa=create(a,"Pessoa","ATENDENTE");UUID req=request(a,b,id(pessoa),ownerEmail),key=UUID.randomUUID();
        associacoes.aceitar(b,req,managerEmail,key,new Aceite("ATENDENTE"));revogar(b,gerente);
        assertEquals(403,statusOf(()->associacoes.aceitar(b,req,managerEmail,key,new Aceite("ATENDENTE"))));
        assertEquals(409,statusOf(()->associacoes.aceitar(b,req,ownerEmail,UUID.randomUUID(),new Aceite("ATENDENTE"))));
        assertTrue(associacoes.listar(b,ownerEmail).isEmpty());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='ASSOCIACAO_ACEITA'",Integer.class,b));
    }
    PermissoesDtos.Efetiva efetiva(UUID unit,UUID target,String codigo){
        return permissoes.painel(unit,target,ownerEmail).permissoes().stream().filter(p->p.permissao().codigo().equals(codigo)).findFirst().orElseThrow();
    }
    void estado(UUID unit,UUID target,String codigo,PermissoesDtos.Estado estado){
        permissoes.excecao(unit,target,codigo,ownerEmail,new PermissoesDtos.Excecao(estado,service.consultar(unit,target,ownerEmail).versao()));
    }
    void limitesNovos(List<UUID> targets,boolean adicionais,boolean grant,boolean revoke,String... codes){
        permissoes.limites(a,managerLink,ownerEmail,new PermissoesDtos.Limites(adicionais,targets,Arrays.stream(codes).map(c->new PermissoesDtos.LimiteCodigo(c,grant,revoke)).toList(),service.consultar(a,managerLink,ownerEmail).versao()));
    }
    @Test void adicionaisUnemHerancaSemHabilitarModuloFuturo() throws Exception {
        var f=create(a,"Multifuncao","ATENDENTE");var p=permissoes.adicionais(a,id(f),ownerEmail,new PermissoesDtos.Adicionais(List.of("COZINHA","BAR"),0L));
        assertEquals(List.of("BAR","COZINHA"),p.adicionais());
        assertEquals(Set.of("ATENDENTE","COZINHA","BAR"),new HashSet<>(efetiva(a,id(f),"PRODUTO_CONSULTAR").herdadaDe()));
        assertTrue(efetiva(a,id(f),"COZINHA_FILA_CONSULTAR").configurada());assertFalse(efetiva(a,id(f),"COZINHA_FILA_CONSULTAR").efetiva());
    }
    @Test void negarPrevaleceSobreTodasFuncoesERetornoHerdarRestaura() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");permissoes.adicionais(a,id(f),ownerEmail,new PermissoesDtos.Adicionais(List.of("BAR"),0L));
        estado(a,id(f),"PRODUTO_CONSULTAR",PermissoesDtos.Estado.NEGAR);assertFalse(efetiva(a,id(f),"PRODUTO_CONSULTAR").configurada());
        estado(a,id(f),"PRODUTO_CONSULTAR",PermissoesDtos.Estado.HERDAR);assertTrue(efetiva(a,id(f),"PRODUTO_CONSULTAR").configurada());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM usuarios_permissoes WHERE vinculo_id=?",Integer.class,id(f)));
    }
    @Test void concessaoIndividualNaoHerdadaPodeSerRevogada() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");assertFalse(efetiva(a,id(f),"ESTOQUE_AJUSTAR").configurada());
        estado(a,id(f),"ESTOQUE_AJUSTAR",PermissoesDtos.Estado.PERMITIR);assertTrue(efetiva(a,id(f),"ESTOQUE_AJUSTAR").configurada());
        estado(a,id(f),"ESTOQUE_AJUSTAR",PermissoesDtos.Estado.NEGAR);assertFalse(efetiva(a,id(f),"ESTOQUE_AJUSTAR").configurada());
    }
    @Test void adicionaisAdministrativasPrincipalRepetidaEDuplicadasSaoNegadas() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");
        for(var roles:List.of(List.of("GERENTE"),List.of("PROPRIETARIO"),List.of("ATENDENTE"),List.of("BAR","BAR")))
            assertEquals(400,statusOf(()->permissoes.adicionais(a,id(f),ownerEmail,new PermissoesDtos.Adicionais(roles,0L))));
        assertTrue(permissoes.painel(a,id(f),ownerEmail).adicionais().isEmpty());
    }
    @Test void bancoTambemImpedeAdicionalAdministrativa() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO vinculos_funcoes_adicionais(vinculo_id,estabelecimento_id,funcao_id) SELECT ?,?,id FROM funcoes WHERE codigo='GERENTE'",id(f),a));
    }
    @Test void funcoesOperacionaisNaoConcedemAdministracao() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");permissoes.adicionais(a,id(f),ownerEmail,new PermissoesDtos.Adicionais(List.of("BAR","COZINHA","CAIXA"),0L));
        estado(a,id(f),"FUNCIONARIO_CADASTRAR",PermissoesDtos.Estado.PERMITIR);
        assertFalse(efetiva(a,id(f),"FUNCIONARIO_CADASTRAR").efetiva());
        assertTrue(permissoes.painel(a,id(f),ownerEmail).permissoes().stream().filter(p->p.permissao().categoria().equals("FINANCEIRA")).noneMatch(PermissoesDtos.Efetiva::configurada));
    }
    @Test void gerenteSemNovaDelegacaoNaoPodeConsultarOuAlterarExcecoes() throws Exception {
        grant(true);var f=create(a,"Pessoa","ATENDENTE");
        assertEquals(403,statusOf(()->permissoes.painel(a,id(f),managerEmail)));
        assertEquals(403,statusOf(()->permissoes.excecao(a,id(f),"PRODUTO_CONSULTAR",managerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.NEGAR,0L))));
    }
    @Test void limitesIndividuaisDistinguemConcederRevogarEHerdar() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");limitesNovos(List.of(id(f)),false,true,false,"PRODUTO_CONSULTAR");
        permissoes.excecao(a,id(f),"PRODUTO_CONSULTAR",managerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.PERMITIR,0L));
        assertEquals(403,statusOf(()->permissoes.excecao(a,id(f),"PRODUTO_CONSULTAR",managerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.NEGAR,1L))));
        assertEquals(403,statusOf(()->permissoes.excecao(a,id(f),"PRODUTO_CONSULTAR",managerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.HERDAR,1L))));
    }
    @Test void delegadoRevogadorNaoPodeUsarHerdarParaConceder() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");limitesNovos(List.of(id(f)),false,false,true,"PRODUTO_CONSULTAR");
        permissoes.excecao(a,id(f),"PRODUTO_CONSULTAR",managerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.NEGAR,0L));
        assertEquals(403,statusOf(()->permissoes.excecao(a,id(f),"PRODUTO_CONSULTAR",managerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.HERDAR,1L))));
    }
    @Test void limitesDeAlvoECodigoSaoObrigatorios() throws Exception {
        grant(true);var f=create(a,"Permitido","COZINHA");var outra=create(a,"Fora","ATENDENTE");limitesNovos(List.of(id(f)),false,true,true,"PRODUTO_CONSULTAR");
        assertEquals(1,permissoes.alvos(a,managerEmail).size());
        assertTrue(service.listar(a,managerEmail).stream().noneMatch(row->row.vinculoId().equals(id(f))));
        assertEquals(403,statusOf(()->permissoes.painel(a,id(outra),managerEmail)));
        assertEquals(403,statusOf(()->permissoes.excecao(a,id(f),"CAIXA_ABRIR",managerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.PERMITIR,0L))));
    }
    @Test void gerenteNaoAlteraPropriosLimitesOuPermissoes() throws Exception {
        assertEquals(403,statusOf(()->permissoes.limites(a,managerLink,managerEmail,new PermissoesDtos.Limites(true,List.of(),List.of(),0L))));
        assertEquals(403,statusOf(()->permissoes.excecao(a,managerLink,"FUNCIONARIO_EDITAR",managerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.PERMITIR,0L))));
    }
    @Test void proprietarioNaoPodeSerAlvoNemReceberExcecao() {
        UUID target=jdbc.queryForObject("SELECT id FROM usuarios_estabelecimentos WHERE usuario_id=? AND estabelecimento_id=?",UUID.class,owner,a);
        assertEquals(403,statusOf(()->permissoes.excecao(a,target,"FUNCIONARIO_CONSULTAR",ownerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.NEGAR,0L))));
        assertEquals(403,statusOf(()->permissoes.adicionais(a,target,ownerEmail,new PermissoesDtos.Adicionais(List.of("BAR"),0L))));
        assertEquals(403,statusOf(()->permissoes.limites(a,managerLink,ownerEmail,new PermissoesDtos.Limites(false,List.of(target),List.of(),0L))));
    }
    @Test void limitesNaoAceitamGerenteOutraUnidadeOuCodigoExclusivo() throws Exception {
        var outro=create(a,"Gerente","GERENTE");var estrangeiro=create(b,"Pessoa","ATENDENTE");
        assertEquals(403,statusOf(()->permissoes.limites(a,managerLink,ownerEmail,new PermissoesDtos.Limites(false,List.of(id(outro)),List.of(),0L))));
        assertEquals(404,statusOf(()->permissoes.limites(a,managerLink,ownerEmail,new PermissoesDtos.Limites(false,List.of(id(estrangeiro)),List.of(),0L))));
        assertEquals(400,statusOf(()->permissoes.limites(a,managerLink,ownerEmail,new PermissoesDtos.Limites(false,List.of(),List.of(new PermissoesDtos.LimiteCodigo("GERENTE_NOMEAR",true,true)),0L))));
    }
    @Test void criticasExigemCodigoExpressoMesmoComOutrasDelegacoes() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");limitesNovos(List.of(id(f)),false,true,true,"CAIXA_OPERAR");
        assertEquals(403,statusOf(()->permissoes.excecao(a,id(f),"CAIXA_FECHAR",managerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.PERMITIR,0L))));
        limitesNovos(List.of(id(f)),false,true,true,"CAIXA_FECHAR");
        permissoes.excecao(a,id(f),"CAIXA_FECHAR",managerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.PERMITIR,0L));
        var p=efetiva(a,id(f),"CAIXA_FECHAR");assertTrue(p.configurada());assertFalse(p.efetiva());
    }
    @Test void limitesAdicionaisSaoExpressosEIndependentes() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");limitesNovos(List.of(id(f)),false,true,true,"PRODUTO_CONSULTAR");
        assertEquals(403,statusOf(()->permissoes.adicionais(a,id(f),managerEmail,new PermissoesDtos.Adicionais(List.of("BAR"),0L))));
        limitesNovos(List.of(id(f)),true,false,false);
        permissoes.adicionais(a,id(f),managerEmail,new PermissoesDtos.Adicionais(List.of("BAR"),0L));assertEquals(List.of("BAR"),permissoes.painel(a,id(f),managerEmail).adicionais());
    }
    @Test void revogarLimitesAfetaSessaoJaAberta() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");limitesNovos(List.of(id(f)),true,true,true,"PRODUTO_CONSULTAR");
        mvc.perform(get("/api/v1/estabelecimentos/"+a+"/permissoes/funcionarios/"+id(f)).session(managerSession)).andExpect(status().isOk());
        limitesNovos(List.of(),false,false,false);
        mvc.perform(get("/api/v1/estabelecimentos/"+a+"/permissoes/funcionarios/"+id(f)).session(managerSession)).andExpect(status().isForbidden());
    }
    @Test void negacaoIndividualRevogaAcaoAntigaSemNovoLogin() throws Exception {
        grant(true);mvc.perform(get(path(a)).session(managerSession)).andExpect(status().isOk());
        estado(a,managerLink,"FUNCIONARIO_CONSULTAR",PermissoesDtos.Estado.NEGAR);
        mvc.perform(get(path(a)).session(managerSession)).andExpect(status().isForbidden());
        estado(a,managerLink,"FUNCIONARIO_CONSULTAR",PermissoesDtos.Estado.HERDAR);
        mvc.perform(get(path(a)).session(managerSession)).andExpect(status().isOk());
    }
    @Test void permitirNaoContornaAsSeisDelegacoes() {
        estado(a,managerLink,"FUNCIONARIO_CADASTRAR",PermissoesDtos.Estado.PERMITIR);
        assertEquals(403,statusOf(()->service.cadastrar(a,managerEmail,UUID.randomUUID(),new Cadastro("Negado","ATENDENTE",null,null))));
    }
    @Test void negacaoDeFuncaoPrincipalNaoSeConfundeComEditarContato() throws Exception {
        grant(true);var f=create(a,"Pessoa","ATENDENTE");estado(a,managerLink,"FUNCIONARIO_FUNCAO_PRINCIPAL_ALTERAR",PermissoesDtos.Estado.NEGAR);
        assertFalse(permissoes.capacidades(a,managerEmail).alterarPrincipal());
        service.editar(a,id(f),managerEmail,new Edicao("Local",null,null,0L));
        assertEquals(403,statusOf(()->service.funcao(a,id(f),managerEmail,new Funcao("ATENDENTE",1L))));
    }
    @Test void isolamentoDasExcecoesEntreVinculosEMesmaIdentidade() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");var second=associacoes.aceitar(b,request(a,b,id(f),ownerEmail),ownerEmail,UUID.randomUUID(),new Aceite("BAR"));
        permissoes.adicionais(a,id(f),ownerEmail,new PermissoesDtos.Adicionais(List.of("COZINHA"),0L));estado(a,id(f),"PRODUTO_CONSULTAR",PermissoesDtos.Estado.NEGAR);
        assertTrue(permissoes.painel(b,second.vinculoId(),ownerEmail).adicionais().isEmpty());assertEquals(PermissoesDtos.Estado.HERDAR,efetiva(b,second.vinculoId(),"PRODUTO_CONSULTAR").estado());
        assertEquals(404,statusOf(()->permissoes.painel(a,second.vinculoId(),ownerEmail)));
    }
    @Test void negacaoDeUnidadeRemoveSelecaoEImpedeAPIsProtegidas() throws Exception {
        grant(true);UUID other=link(manager,b,"GERENTE");estado(a,managerLink,"ESTABELECIMENTO_VISUALIZAR",PermissoesDtos.Estado.NEGAR);
        mvc.perform(get("/api/v1/estabelecimentos").session(managerSession)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(b.toString()));
        mvc.perform(get(path(a)).session(managerSession)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/estabelecimentos/"+a+"/permissoes/minhas").session(managerSession)).andExpect(status().isForbidden());
        assertTrue(permissoes.minhas(b,managerEmail).stream().anyMatch(p->p.permissao().codigo().equals("ESTABELECIMENTO_VISUALIZAR")&&p.efetiva()));
    }
    @Test void alteracaoConcorrenteDeExcecaoRetornaConflito() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");
        var results=race(()->statusOf(()->permissoes.excecao(a,id(f),"PRODUTO_CONSULTAR",ownerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.PERMITIR,0L))),()->statusOf(()->permissoes.excecao(a,id(f),"PRODUTO_CONSULTAR",ownerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.NEGAR,0L))));
        assertEquals(Set.of(200,409),new HashSet<>(results));assertEquals(1L,service.consultar(a,id(f),ownerEmail).versao());
    }
    @Test void revogacaoPrimeiroImpedeAlteracaoDePermissaoAposLock() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");limitesNovos(List.of(id(f)),false,true,true,"PRODUTO_CONSULTAR");
        assertEquals(List.of(200,403),serializadas(()->limitesNovos(List.of(),false,false,false),()->permissoes.excecao(a,id(f),"PRODUTO_CONSULTAR",managerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.PERMITIR,0L))));
        assertEquals(PermissoesDtos.Estado.HERDAR,efetiva(a,id(f),"PRODUTO_CONSULTAR").estado());
    }
    @Test void alteracaoPrimeiroPreservaAuditoriaAntesDaRevogacao() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");limitesNovos(List.of(id(f)),false,true,true,"PRODUTO_CONSULTAR");
        assertEquals(List.of(200,200),serializadas(()->permissoes.excecao(a,id(f),"PRODUTO_CONSULTAR",managerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.PERMITIR,0L)),()->limitesNovos(List.of(),false,false,false)));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='PERMISSAO_INDIVIDUAL_ALTERADA'",Integer.class,a));
    }
    @Test void trocaPrincipalRemoveRepeticaoERevogaLimitesNovos() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");limitesNovos(List.of(id(f)),true,true,true,"PRODUTO_CONSULTAR");
        permissoes.adicionais(a,managerLink,ownerEmail,new PermissoesDtos.Adicionais(List.of("BAR"),1L));
        service.funcao(a,managerLink,ownerEmail,new Funcao("BAR",2L));assertTrue(permissoes.painel(a,managerLink,ownerEmail).adicionais().isEmpty());
        service.funcao(a,managerLink,ownerEmail,new Funcao("GERENTE",3L));assertTrue(permissoes.capacidades(a,managerEmail).alvos().isEmpty());
        String event=jdbc.queryForObject("SELECT dados_evento::text FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='FUNCAO_PRINCIPAL_ALTERADA' ORDER BY ocorrido_em LIMIT 1",String.class,a);
        assertTrue(event.contains("adicionais"));assertTrue(event.contains("PRODUTO_CONSULTAR"));
    }
    @Test void falhaAuditoriaReverteExcecaoEVersao() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");
        jdbc.execute("ALTER TABLE auditoria_eventos ADD CONSTRAINT permission_fail_"+a.toString().replace("-","")+" CHECK (estabelecimento_id <> '"+a+"'::uuid OR tipo_evento <> 'PERMISSAO_INDIVIDUAL_ALTERADA') NOT VALID");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->estado(a,id(f),"PRODUTO_CONSULTAR",PermissoesDtos.Estado.NEGAR));
        assertEquals(0L,service.consultar(a,id(f),ownerEmail).versao());assertEquals(PermissoesDtos.Estado.HERDAR,efetiva(a,id(f),"PRODUTO_CONSULTAR").estado());
    }
    @Test void novosEndpointsExigemAutenticacaoCsrfEValidamEntradas() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");String base="/api/v1/estabelecimentos/"+a+"/permissoes";
        mvc.perform(get(base+"/catalogo")).andExpect(status().isUnauthorized());
        mvc.perform(put(base+"/funcionarios/"+id(f)+"/adicionais").session(ownerSession).contentType("application/json").content("{\"funcoes\":[\"BAR\"],\"versao\":0}")).andExpect(status().isForbidden());
        for(String body:List.of("{}","{\"funcoes\":null,\"versao\":0}","{\"funcoes\":[\"BAR\"],\"versao\":-1}"))
            mvc.perform(put(base+"/funcionarios/"+id(f)+"/adicionais").session(ownerSession).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(put(base+"/funcionarios/"+id(f)+"/individuais/PRODUTO_CONSULTAR").session(ownerSession).with(csrf()).contentType("application/json").content("{\"estado\":\"INVENTADO\",\"versao\":0}")).andExpect(status().isBadRequest());
    }
    @Test void catalogoPreservaLegadosSemAutoridadeAutomatica() {
        var catalog=permissoes.catalogo(a,ownerEmail);assertEquals(56,catalog.size());
        for(String c:List.of("FUNCIONARIO_GERENCIAR","PERMISSAO_GERENCIAR"))assertFalse(catalog.stream().filter(p->p.codigo().equals(c)).findFirst().orElseThrow().disponivel());
        assertTrue(permissoes.minhas(a,managerEmail).stream().filter(p->p.permissao().categoria().equals("ADMINISTRATIVA")&&!p.permissao().codigo().equals("ESTABELECIMENTO_VISUALIZAR")).noneMatch(PermissoesDtos.Efetiva::efetiva));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM funcoes_permissoes fp JOIN permissoes p ON p.id=fp.permissao_id WHERE p.categoria='FINANCEIRA'",Integer.class));
    }
    @Test void migracaoV4ParaV7PreservaCredenciaisEDelegacoes() {
        String name="kt_upgrade_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE DATABASE "+name);
        String url=PG.getJdbcUrl().replace("/kt_funcionarios_test","/"+name);var ds=new DriverManagerDataSource(url,PG.getUsername(),PG.getPassword());
        var flyway=Flyway.configure().dataSource(ds).target("4").load();flyway.migrate();var db=new JdbcTemplate(ds);
        UUID user=UUID.randomUUID(),unit=UUID.randomUUID(),link=UUID.randomUUID();
        db.update("INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,'Legado',?,?)",user,"upgrade@example.com",LEGACY_HASH);
        db.update("INSERT INTO estabelecimentos(id,nome) VALUES(?,'Upgrade')",unit);
        db.update("INSERT INTO usuarios_estabelecimentos(id,usuario_id,estabelecimento_id,funcao_id) SELECT ?,?,?,id FROM funcoes WHERE codigo='GERENTE'",link,user,unit);
        db.update("INSERT INTO delegacoes_administrativas(vinculo_id,consultar,funcoes) VALUES(?,true,ARRAY['ATENDENTE'])",link);
        db.update("INSERT INTO funcoes_permissoes(funcao_id,permissao_id) SELECT f.id,p.id FROM funcoes f CROSS JOIN permissoes p WHERE f.codigo='GERENTE' AND p.codigo='ESTABELECIMENTO_VISUALIZAR'");
        var before=db.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        Flyway.configure().dataSource(ds).target("7").load().migrate();
        var beforeV8=db.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        Flyway.configure().dataSource(ds).load().migrate();
        assertEquals(beforeV8,db.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7') ORDER BY installed_rank"));
        assertEquals(before,db.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2','3','4') ORDER BY installed_rank"));
        assertEquals(LEGACY_HASH,db.queryForObject("SELECT senha_hash FROM usuarios WHERE id=?",String.class,user));assertTrue(db.queryForObject("SELECT consultar FROM delegacoes_administrativas WHERE vinculo_id=?",Boolean.class,link));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM limites_gestao_permissoes",Integer.class));
        assertEquals(10,db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL",Integer.class));
    }
    @Test void falhaAuditoriaTambemReverteAdicionaisELimites() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");
        jdbc.execute("ALTER TABLE auditoria_eventos ADD CONSTRAINT roles_fail_"+a.toString().replace("-","")+" CHECK (estabelecimento_id <> '"+a+"'::uuid OR tipo_evento NOT IN ('FUNCOES_ADICIONAIS_ALTERADAS','LIMITES_PERMISSOES_ALTERADOS')) NOT VALID");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->permissoes.adicionais(a,id(f),ownerEmail,new PermissoesDtos.Adicionais(List.of("BAR"),0L)));
        assertTrue(permissoes.painel(a,id(f),ownerEmail).adicionais().isEmpty());assertEquals(0L,service.consultar(a,id(f),ownerEmail).versao());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->limitesNovos(List.of(id(f)),true,true,true,"PRODUTO_CONSULTAR"));
        assertTrue(permissoes.capacidades(a,managerEmail).alvos().isEmpty());assertEquals(0L,service.consultar(a,managerLink,ownerEmail).versao());
    }
    @Test void executorDoJsonNaoSubstituiIdentidadeDaSessao() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");String base="/api/v1/estabelecimentos/"+a+"/permissoes/funcionarios/"+id(f);
        mvc.perform(put(base+"/individuais/PRODUTO_CONSULTAR").session(managerSession).with(csrf()).contentType("application/json")
            .content("{\"estado\":\"PERMITIR\",\"versao\":0,\"executor\":\""+owner+"\"}")).andExpect(status().isForbidden());
        mvc.perform(put(base+"/individuais/PRODUTO_CONSULTAR").session(ownerSession).with(csrf()).contentType("application/json")
            .content("{\"estado\":\"NEGAR\",\"versao\":0,\"executor\":\""+manager+"\"}")).andExpect(status().isOk());
        assertEquals(owner,jdbc.queryForObject("SELECT usuario_id FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='PERMISSAO_INDIVIDUAL_ALTERADA'",UUID.class,a));
    }
    @Test void codigoDesconhecidoEAutoridadeExclusivaNaoAceitamExcecao() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");
        assertEquals(400,statusOf(()->permissoes.excecao(a,id(f),"INVENTADA",ownerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.PERMITIR,0L))));
        for(String code:List.of("GERENTE_NOMEAR","PERMISSOES_INDIVIDUAIS_GERENCIAR","FUNCIONARIO_GERENCIAR"))
            assertEquals(403,statusOf(()->permissoes.excecao(a,id(f),code,ownerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.PERMITIR,0L))));
        assertEquals(0L,service.consultar(a,id(f),ownerEmail).versao());
    }
    @Test void revogacaoPrimeiroImpedeCadastroProtegidoNaSessaoExistente() throws Exception {
        grant(true);
        assertEquals(List.of(200,403),serializadas(()->estado(a,managerLink,"FUNCIONARIO_CADASTRAR",PermissoesDtos.Estado.NEGAR),()->service.cadastrar(a,managerEmail,UUID.randomUUID(),new Cadastro("Nao criar","ATENDENTE",null,null))));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM usuarios_estabelecimentos WHERE estabelecimento_id=? AND nome_exibicao='Nao criar'",Integer.class,a));
    }
    @Test void cadastroPrimeiroConfirmaAntesDaRevogacaoSemPerderHistorico() throws Exception {
        grant(true);
        assertEquals(List.of(200,200),serializadas(()->service.cadastrar(a,managerEmail,UUID.randomUUID(),new Cadastro("Criado antes","ATENDENTE",null,null)),()->estado(a,managerLink,"FUNCIONARIO_CADASTRAR",PermissoesDtos.Estado.NEGAR)));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='FUNCIONARIO_CADASTRADO' AND usuario_id=?",Integer.class,a,manager));
        assertEquals(403,statusOf(()->service.cadastrar(a,managerEmail,UUID.randomUUID(),new Cadastro("Depois","ATENDENTE",null,null))));
    }
    @Test void alvoPromovidoAGerenteDeixaDeSerAdministravelPorDelegado() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");limitesNovos(List.of(id(f)),true,true,true,"PRODUTO_CONSULTAR");
        service.funcao(a,id(f),ownerEmail,new Funcao("GERENTE",0L));
        assertTrue(permissoes.alvos(a,managerEmail).isEmpty());assertEquals(403,statusOf(()->permissoes.painel(a,id(f),managerEmail)));
        assertEquals(403,statusOf(()->permissoes.adicionais(a,id(f),managerEmail,new PermissoesDtos.Adicionais(List.of("BAR"),1L))));
        service.funcao(a,id(f),ownerEmail,new Funcao("ATENDENTE",1L));
        assertEquals(403,statusOf(()->permissoes.painel(a,id(f),managerEmail)));
    }
    @Test void auditoriaDistingueGerenteExecutorEProprietarioAutorizador() throws Exception {
        var f=create(a,"Pessoa","ATENDENTE");limitesNovos(List.of(id(f)),false,true,true,"PRODUTO_CONSULTAR");
        permissoes.excecao(a,id(f),"PRODUTO_CONSULTAR",managerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.NEGAR,0L));
        var event=jdbc.queryForMap("SELECT usuario_id,dados_evento::text AS dados FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='PERMISSAO_INDIVIDUAL_ALTERADA'",a);
        var data=json.readTree((String)event.get("dados"));assertEquals(manager,event.get("usuario_id"));
        assertEquals(owner.toString(),data.get("depois").get("autorizador").asText());assertEquals(1L,data.get("depois").get("versaoAutorizacao").asLong());
        assertEquals("APLICADA",data.get("depois").get("resultado").asText());
    }
    @Test void painelNaoApresentaPermissaoEfetivaParaVinculoInativo() {
        grant(true);assertTrue(efetiva(a,managerLink,"FUNCIONARIO_CONSULTAR").efetiva());
        service.situacao(a,managerLink,ownerEmail,new Situacao(false,1L));
        assertTrue(permissoes.painel(a,managerLink,ownerEmail).permissoes().stream().noneMatch(PermissoesDtos.Efetiva::efetiva));
    }
    @Test void negacaoDeUnidadeTambemBloqueiaResultadoCentralDoGerente() {
        grant(true);estado(a,managerLink,"ESTABELECIMENTO_VISUALIZAR",PermissoesDtos.Estado.NEGAR);
        assertTrue(permissoes.painel(a,managerLink,ownerEmail).permissoes().stream().noneMatch(PermissoesDtos.Efetiva::efetiva));
        assertFalse(permissoes.podeExistente(managerLink,"FUNCIONARIO_CADASTRAR"));
    }
    void verificarConcorrenciaDiretaDeFuncoes(boolean principalPrimeiro) throws Exception {
        var f=create(a,"Concorrencia SQL","ATENDENTE");UUID target=id(f);
        Runnable principal=()->jdbc.update("UPDATE usuarios_estabelecimentos SET funcao_id=(SELECT id FROM funcoes WHERE codigo='BAR') WHERE id=?",target);
        Runnable adicional=()->jdbc.update("INSERT INTO vinculos_funcoes_adicionais(vinculo_id,estabelecimento_id,funcao_id) SELECT ?,?,id FROM funcoes WHERE codigo='BAR'",target,a);
        var pool=Executors.newFixedThreadPool(2);var primeiraPronta=new CountDownLatch(1);var liberar=new CountDownLatch(1);var pid=new CompletableFuture<Integer>();
        try {
            var x=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
                (principalPrimeiro?principal:adicional).run();primeiraPronta.countDown();
                try{if(!liberar.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Barreira excedida");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));
            assertTrue(primeiraPronta.await(5,TimeUnit.SECONDS));
            var y=pool.submit(()->{
                try{new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
                    pid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));(principalPrimeiro?adicional:principal).run();
                });return 200;}catch(org.springframework.dao.DataIntegrityViolationException e){return 409;}
            });
            int backend=pid.get(5,TimeUnit.SECONDS);
            for(int i=0;i<100&&!y.isDone();i++){
                if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,backend)))break;
                Thread.sleep(20);
            }
            liberar.countDown();x.get(5,TimeUnit.SECONDS);int resultado=y.get(5,TimeUnit.SECONDS);
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM vinculos_funcoes_adicionais va JOIN usuarios_estabelecimentos ue ON ue.id=va.vinculo_id WHERE ue.id=? AND va.funcao_id=ue.funcao_id",Integer.class,target),"Principal e adicional nao podem coincidir mesmo com SQL concorrente");
            assertEquals(409,resultado,"A segunda transacao deve rejeitar a combinacao inconsistente");
        }finally{liberar.countDown();pool.shutdownNow();}
    }
    @Test void principalConcorrenteComAdicionalMantemIntegridadeNoBanco() throws Exception {verificarConcorrenciaDiretaDeFuncoes(true);}
    @Test void adicionalConcorrenteComPrincipalMantemIntegridadeNoBanco() throws Exception {verificarConcorrenciaDiretaDeFuncoes(false);}
}
