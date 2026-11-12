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
        // Migrar primeiro somente V1, inserir estado legado e deixar Spring aplicar V2-V4.
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
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success=true AND version IS NOT NULL",Integer.class));
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
}
