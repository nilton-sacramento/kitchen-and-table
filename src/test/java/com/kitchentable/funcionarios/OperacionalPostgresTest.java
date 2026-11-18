package com.kitchentable.funcionarios;

import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.security.SecureRandom;
import com.fasterxml.jackson.databind.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.github.dockerjava.api.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.kitchentable.funcionarios.OperacionalDtos.*;

@SpringBootTest(properties={"server.address=127.0.0.1","debug=false","logging.level.org.springframework=INFO"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class OperacionalPostgresTest {
    static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("kt_operacional_test").withUsername("kt_test").withPassword(UUID.randomUUID().toString()).withReuse(false)
        .withLabel("com.kitchentable.scope","discardable-test-2-3").withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),new ExposedPort(5432))));
    static final String ADMIN_PASSWORD=UUID.randomUUID().toString();
    static final String ADMIN_HASH=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(ADMIN_PASSWORD);
    static final UUID LEGACY=UUID.randomUUID();static List<Map<String,Object>> checksums;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){
        PG.start();Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()).target("8").load().migrate();
        var db=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        db.update("INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,'Legado de teste operacional',?,?)",LEGACY,"legacy-operacional@example.com",ADMIN_HASH);
        checksums=db.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        p.add("spring.datasource.url",PG::getJdbcUrl);p.add("spring.datasource.username",PG::getUsername);p.add("spring.datasource.password",PG::getPassword);
    }
    @Autowired JdbcTemplate jdbc;@Autowired OperacionalService op;@Autowired FuncionarioService funcionarios;@Autowired PermissoesService permissoes;@Autowired AssociacaoFuncionarioService associacoes;@Autowired MockMvc mvc;@Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    UUID a,b,owner,manager,managerLink;String ownerEmail,managerEmail,pin;FuncionarioDtos.Funcionario pessoa;
    @BeforeEach void fixture(){
        a=unit();b=unit();owner=user("Proprietario");manager=user("Gerente");ownerEmail=email(owner);managerEmail=email(manager);
        link(owner,a,"PROPRIETARIO");link(owner,b,"PROPRIETARIO");managerLink=link(manager,a,"GERENTE");
        pessoa=funcionarios.cadastrar(a,ownerEmail,UUID.randomUUID(),new FuncionarioDtos.Cadastro("Operador de teste","ATENDENTE",null,null));
        pin=String.format(Locale.ROOT,"%06d",new SecureRandom().nextInt(1000000));
    }
    UUID unit(){UUID u=UUID.randomUUID();jdbc.update("INSERT INTO estabelecimentos(id,nome) VALUES(?,'Unidade de teste PIN')",u);return u;}
    String email(UUID id){return "operacional-"+id+"@example.com";}
    UUID user(String name){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,?,?,?)",id,name,email(id),ADMIN_HASH);return id;}
    UUID link(UUID user,UUID unit,String role){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO usuarios_estabelecimentos(id,usuario_id,estabelecimento_id,funcao_id) SELECT ?,?,?,id FROM funcoes WHERE codigo=?",id,user,unit,role);return id;}
    long versao(UUID unit,UUID target){return funcionarios.consultar(unit,target,ownerEmail).versao();}
    Estado definir(){return op.definir(a,pessoa.vinculoId(),ownerEmail,new Definicao(pin,versao(a,pessoa.vinculoId())));}
    OperadorPrincipal entrar(){return op.entrar(new Entrada(a.toString(),pessoa.vinculoId().toString(),pin),null);}
    String errado(){return pin.equals("000000")?"111111":"000000";}
    void falhar(){assertNull(op.entrar(new Entrada(a.toString(),pessoa.vinculoId().toString(),errado()),null));}
    void autorizar(String... codes){
        funcionarios.delegar(a,managerLink,ownerEmail,new FuncionarioDtos.Delegacao(false,false,false,false,false,false,List.of("ATENDENTE"),versao(a,managerLink)));
        for(String code:codes)permissoes.excecao(a,managerLink,code,ownerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.PERMITIR,versao(a,managerLink)));
    }
    int statusOf(Runnable r){try{r.run();return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}
    MockHttpSession adminLogin() throws Exception {return (MockHttpSession)mvc.perform(post("/api/v1/auth/login").with(csrf()).param("email",ownerEmail).param("password",ADMIN_PASSWORD)).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);}
    MockHttpSession pinLogin(String value,MockHttpSession session) throws Exception {
        var request=post("/api/v1/operacional/entrar").with(csrf()).contentType("application/json").content(json.writeValueAsBytes(new Entrada(a.toString(),pessoa.vinculoId().toString(),value)));if(session!=null)request.session(session);
        return (MockHttpSession)mvc.perform(request).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
    OperadorPrincipal principal(MockHttpSession session){return (OperadorPrincipal)((SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT")).getAuthentication().getPrincipal();}
    @Test void migracoesV8ParaV10PreservamChecksumsECredenciais() throws Exception {
        assertEquals(checksums,jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8') ORDER BY installed_rank"));
        assertTrue(ADMIN_HASH.equals(jdbc.queryForObject("SELECT senha_hash FROM usuarios WHERE id=?",String.class,LEGACY)));
        assertEquals(10,jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success",Integer.class));
        assertTrue(jdbc.queryForObject("SELECT version()",String.class).contains("PostgreSQL 16"));
        mvc.perform(get("/api/v1/auth/me").session(adminLogin())).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(owner.toString()));
        assertNull(jdbc.queryForObject("SELECT pin_hash FROM usuarios WHERE id=?",String.class,LEGACY));
    }
    @Test void definirPinComHashSeguroSemModificarCredenciaisAdministrativas(){
        var s=definir();assertTrue(s.configurado());assertFalse(s.bloqueado());
        String hash=jdbc.queryForObject("SELECT pin_hash FROM credenciais_operacionais WHERE vinculo_id=?",String.class,pessoa.vinculoId());
        assertFalse(hash.equals(pin));assertTrue(hash.startsWith("$2a$12$")||hash.startsWith("$2b$12$"));assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches(pin,hash));
        assertNull(jdbc.queryForObject("SELECT senha_hash FROM usuarios WHERE id=?",String.class,pessoa.usuarioId()));assertNull(jdbc.queryForObject("SELECT pin_hash FROM usuarios WHERE id=?",String.class,pessoa.usuarioId()));
        assertTrue(ADMIN_HASH.equals(jdbc.queryForObject("SELECT senha_hash FROM usuarios WHERE id=?",String.class,owner)));
    }
    @Test void validarSeisDigitosInclusiveZerosAEsquerda(){
        assertEquals(400,statusOf(()->op.definir(a,pessoa.vinculoId(),ownerEmail,new Definicao("12345",0L))));
        assertEquals(400,statusOf(()->op.definir(a,pessoa.vinculoId(),ownerEmail,new Definicao("1234567",0L))));
        assertEquals(400,statusOf(()->op.definir(a,pessoa.vinculoId(),ownerEmail,new Definicao("abcdef",0L))));
        pin="001234";definir();assertNotNull(entrar());
    }
    @Test void pinCorretoIdentificaPessoaEVinculoSemSenhaAdministrativa(){definir();var p=entrar();assertNotNull(p);assertEquals(pessoa.usuarioId(),p.usuarioId());assertEquals(pessoa.vinculoId(),p.vinculoId());assertEquals(a,p.estabelecimentoId());assertNotNull(op.contexto(p,false));}
    @Test void pinIncorretoRegistraFalhaSemAutenticarNemAtribuirAutorFalso(){
        definir();falhar();assertEquals(1,jdbc.queryForObject("SELECT falhas FROM credenciais_operacionais WHERE vinculo_id=?",Integer.class,pessoa.vinculoId()));
        var event=jdbc.queryForMap("SELECT usuario_id,entidade_id FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='OPERACIONAL_PIN_INCORRETO'",a);
        assertNull(event.get("usuario_id"));assertEquals(pessoa.vinculoId(),event.get("entidade_id"));
    }
    @Test void tresErrosBloqueiamPersistemEntreNovasSessoes() throws Exception {
        definir();for(int i=0;i<3;i++)mvc.perform(post("/api/v1/operacional/entrar").with(csrf()).contentType("application/json").content(json.writeValueAsBytes(new Entrada(a.toString(),pessoa.vinculoId().toString(),errado())))).andExpect(status().isUnauthorized());
        assertTrue(op.estado(a,pessoa.vinculoId(),ownerEmail).bloqueado());assertEquals(0,op.estado(a,pessoa.vinculoId(),ownerEmail).tentativasRestantes());
        mvc.perform(post("/api/v1/operacional/entrar").session(new MockHttpSession()).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(new Entrada(a.toString(),pessoa.vinculoId().toString(),pin)))).andExpect(status().isUnauthorized());
        String event=jdbc.queryForObject("SELECT dados_evento::text FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='OPERACIONAL_TENTATIVAS_BLOQUEADO'",String.class,a);assertTrue(event.contains("notificacaoPendente"));
    }
    @Test void sucessoZeraFalhasConsecutivas(){definir();falhar();falhar();assertNotNull(entrar());falhar();assertEquals(2,op.estado(a,pessoa.vinculoId(),ownerEmail).tentativasRestantes());assertFalse(op.estado(a,pessoa.vinculoId(),ownerEmail).bloqueado());}
    @Test void desbloqueioAutorizadoNaoReativaDesbloqueioAnterior(){
        definir();var p=entrar();op.bloquear(a,pessoa.vinculoId(),ownerEmail,new Bloqueio(true,versao(a,pessoa.vinculoId())));assertNull(op.contexto(p,false));
        op.bloquear(a,pessoa.vinculoId(),ownerEmail,new Bloqueio(false,versao(a,pessoa.vinculoId())));assertNull(op.contexto(p,false));assertNotNull(entrar());
    }
    @Test void gerenteSemPermissaoNaoPodeDefinirOuDesbloquear(){
        assertEquals(403,statusOf(()->op.definir(a,pessoa.vinculoId(),managerEmail,new Definicao(pin,0L))));definir();
        assertEquals(403,statusOf(()->op.bloquear(a,pessoa.vinculoId(),managerEmail,new Bloqueio(false,versao(a,pessoa.vinculoId())))));
        assertFalse(op.capacidades(a,managerEmail).gerenciarPin());
    }
    @Test void gerenteComPermissaoAindaRespeitaHierarquiaESuasFuncoes(){
        autorizar("PIN_GERENCIAR","OPERACIONAL_DESBLOQUEAR");op.definir(a,pessoa.vinculoId(),managerEmail,new Definicao(pin,0L));
        assertEquals(403,statusOf(()->op.definir(a,managerLink,managerEmail,new Definicao(pin,versao(a,managerLink)))));
        UUID dono=jdbc.queryForObject("SELECT id FROM usuarios_estabelecimentos WHERE usuario_id=? AND estabelecimento_id=?",UUID.class,owner,a);
        assertEquals(403,statusOf(()->op.definir(a,dono,managerEmail,new Definicao(pin,0L))));
        var bar=funcionarios.cadastrar(a,ownerEmail,UUID.randomUUID(),new FuncionarioDtos.Cadastro("Bar","BAR",null,null));assertEquals(403,statusOf(()->op.definir(a,bar.vinculoId(),managerEmail,new Definicao(pin,0L))));
    }
    @Test void redefinirBloqueadoExigeTambemPoderDeDesbloquear(){
        definir();falhar();falhar();falhar();autorizar("PIN_GERENCIAR");
        assertEquals(403,statusOf(()->op.definir(a,pessoa.vinculoId(),managerEmail,new Definicao(pin,versao(a,pessoa.vinculoId())))));
        autorizar("PIN_GERENCIAR","OPERACIONAL_DESBLOQUEAR");op.definir(a,pessoa.vinculoId(),managerEmail,new Definicao(pin,versao(a,pessoa.vinculoId())));assertNotNull(entrar());
    }
    @Test void redefinirPinInvalidaSessoesEHashAnterior(){
        definir();var p=entrar();String anterior=pin;pin=errado();definir();assertNull(op.contexto(p,false));
        assertNull(op.entrar(new Entrada(a.toString(),pessoa.vinculoId().toString(),anterior),null));assertNotNull(entrar());
    }
    @Test void respostasEAuditoriaNaoExpoemPinOuHash() throws Exception {
        definir();var session=adminLogin();String body=mvc.perform(get("/api/v1/estabelecimentos/"+a+"/seguranca-operacional/funcionarios/"+pessoa.vinculoId()).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String events=jdbc.queryForObject("SELECT string_agg(dados_evento::text,'') FROM auditoria_eventos WHERE estabelecimento_id=?",String.class,a);
        assertFalse(body.contains("\""+pin+"\""));assertFalse(body.contains("pin_hash"));assertFalse(body.contains("$2"));assertFalse(events.contains("\""+pin+"\""));assertFalse(events.contains("pin_hash"));assertFalse(events.contains("$2"));
        assertFalse(new Definicao(pin,0L).toString().contains(pin));assertFalse(new Entrada(a.toString(),pessoa.vinculoId().toString(),pin).toString().contains(pin));
    }
    @Test void errosPublicosNaoEnumeramFuncionario() throws Exception {
        definir();String known=mvc.perform(post("/api/v1/operacional/entrar").with(csrf()).contentType("application/json").content(json.writeValueAsBytes(new Entrada(a.toString(),pessoa.vinculoId().toString(),errado())))).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknown=mvc.perform(post("/api/v1/operacional/entrar").with(csrf()).contentType("application/json").content(json.writeValueAsBytes(new Entrada(a.toString(),UUID.randomUUID().toString(),pin)))).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();assertEquals(known,unknown);
        mvc.perform(post("/api/v1/operacional/entrar").with(csrf()).contentType("application/json").content(json.writeValueAsBytes(new Entrada("inexistente","inexistente",pin)))).andExpect(status().isUnauthorized());
    }
    @Test void inatividadeRealBloqueiaNoBackendSemMudarConfiguracaoNormal() throws Exception {
        definir();assertEquals(300,op.politica(a,ownerEmail).segundos());jdbc.update("INSERT INTO politicas_operacionais(estabelecimento_id,inatividade_segundos) VALUES(?,1)",a);
        var p=entrar();Thread.sleep(1150);assertNull(op.contexto(p,false));assertNull(op.contexto(p,true));
        assertTrue(jdbc.queryForObject("SELECT bloqueada FROM sessoes_operacionais WHERE id=?",Boolean.class,p.sessaoId()));
        assertEquals(300,op.politica(b,ownerEmail).segundos());
    }
    @Test void consultasNaoRenovamAtividadeMasInteracaoValidaRenova() throws Exception {
        definir();var p=entrar();var before=op.contexto(p,false).expiraEm();Thread.sleep(30);assertEquals(before,op.contexto(p,false).expiraEm());assertTrue(op.contexto(p,true).expiraEm().isAfter(before));
    }
    @Test void sessaoVencidaNaoPodeSerReanimadaPorPrazoMaior(){
        definir();var p=entrar();jdbc.update("UPDATE sessoes_operacionais SET expira_em=clock_timestamp()-interval '1 second' WHERE id=?",p.sessaoId());op.politica(a,ownerEmail,new Politica(3600,0L));assertNull(op.contexto(p,false));
    }
    @Test void prazoReduzidoAfetaSessoesExistentesEExigeVersao(){
        definir();var p=entrar();var before=op.contexto(p,false).expiraEm();op.politica(a,ownerEmail,new Politica(30,0L));assertTrue(op.contexto(p,false).expiraEm().isBefore(before));
        assertEquals(409,statusOf(()->op.politica(a,ownerEmail,new Politica(300,0L))));assertEquals(403,statusOf(()->op.politica(a,managerEmail,new Politica(30,1L))));
    }
    @Test void reautenticacaoRotacionaSessaoOperacionalAnterior(){definir();var p=entrar();op.bloquearSessao(p);assertNull(op.contexto(p,false));var next=op.entrar(new Entrada(a.toString(),pessoa.vinculoId().toString(),pin),p);assertNotNull(next);assertNotEquals(p.sessaoId(),next.sessaoId());assertNull(op.contexto(p,false));assertNotNull(op.contexto(next,false));}
    @Test void trocaOperadorEncerraSessaoAnteriorEAuditaIdentidadeCorreta(){
        definir();var p=entrar();var outra=funcionarios.cadastrar(a,ownerEmail,UUID.randomUUID(),new FuncionarioDtos.Cadastro("Segundo operador","BAR",null,null));op.definir(a,outra.vinculoId(),ownerEmail,new Definicao(pin,0L));
        var next=op.entrar(new Entrada(a.toString(),outra.vinculoId().toString(),pin),p);assertEquals(outra.usuarioId(),next.usuarioId());assertNull(op.contexto(p,false));assertNotNull(op.contexto(next,false));
        assertEquals(pessoa.usuarioId(),jdbc.queryForObject("SELECT usuario_id FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='OPERACIONAL_TROCA_OPERADOR'",UUID.class,a));
    }
    @Test void pinPorVinculoNaoETransportadoNaAssociacao(){
        definir();var req=associacoes.solicitar(a,pessoa.vinculoId(),ownerEmail,new FuncionarioDtos.PedidoAssociacao(b));var outra=associacoes.aceitar(b,req.id(),ownerEmail,UUID.randomUUID(),new FuncionarioDtos.Aceite("ATENDENTE"));
        assertNull(op.entrar(new Entrada(b.toString(),outra.vinculoId().toString(),pin),null));assertNull(op.entrar(new Entrada(b.toString(),pessoa.vinculoId().toString(),pin),null));
        op.definir(b,outra.vinculoId(),ownerEmail,new Definicao(pin,0L));var second=op.entrar(new Entrada(b.toString(),outra.vinculoId().toString(),pin),null);
        op.bloquear(a,pessoa.vinculoId(),ownerEmail,new Bloqueio(true,versao(a,pessoa.vinculoId())));assertNotNull(op.contexto(second,false));
    }
    @Test void negacaoDaUnidadeRevogaSemEncerrarOutraUnidade(){
        definir();var p=entrar();permissoes.excecao(a,pessoa.vinculoId(),"ESTABELECIMENTO_VISUALIZAR",ownerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.NEGAR,versao(a,pessoa.vinculoId())));
        assertNull(op.contexto(p,false));assertNull(entrar());permissoes.excecao(a,pessoa.vinculoId(),"ESTABELECIMENTO_VISUALIZAR",ownerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.HERDAR,versao(a,pessoa.vinculoId())));
        assertNull(op.contexto(p,false));assertNotNull(entrar());
    }
    @Test void desativacaoEReativacaoRapidaNaoRessuscitamSessao(){
        definir();var p=entrar();funcionarios.situacao(a,pessoa.vinculoId(),ownerEmail,new FuncionarioDtos.Situacao(false,versao(a,pessoa.vinculoId())));
        funcionarios.situacao(a,pessoa.vinculoId(),ownerEmail,new FuncionarioDtos.Situacao(true,versao(a,pessoa.vinculoId())));assertNull(op.contexto(p,false));assertNotNull(entrar());
    }
    @Test void usuarioGlobalInativoEIdentidadeForjadaNaoAcessamContexto(){
        definir();var p=entrar();assertNull(op.contexto(new OperadorPrincipal(UUID.randomUUID(),p.vinculoId(),a,p.sessaoId()),false));
        jdbc.update("UPDATE usuarios SET ativo=false WHERE id=?",pessoa.usuarioId());assertNull(op.contexto(p,false));assertNull(entrar());
    }
    @Test void pinNaoElevaSessaoParaAdministracaoMesmoSeGerente() throws Exception {
        op.definir(a,managerLink,ownerEmail,new Definicao(pin,0L));var p=op.entrar(new Entrada(a.toString(),managerLink.toString(),pin),null);assertNotNull(p);
        var auth=UsernamePasswordAuthenticationToken.authenticated(p,null,List.of(new SimpleGrantedAuthority("ROLE_OPERADOR")));
        mvc.perform(get("/api/v1/auth/me").with(authentication(auth))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/estabelecimentos/"+a+"/funcionarios").with(authentication(auth))).andExpect(status().isForbidden());
        assertTrue(op.contexto(p,false).permissoes().stream().noneMatch(e->e.permissao().categoria().equals("ADMINISTRATIVA")));
    }
    @Test void operacaoHttpRecusadaAposBloqueio() throws Exception {
        definir();var session=pinLogin(pin,null);mvc.perform(get("/api/v1/operacional/contexto").session(session)).andExpect(status().isOk());
        op.bloquearSessao(principal(session));mvc.perform(get("/api/v1/operacional/contexto").session(session)).andExpect(status().isLocked());
        mvc.perform(post("/api/v1/operacional/atividade").session(session).with(csrf())).andExpect(status().isLocked());
    }
    @Test void csrfRotacaoHttpLogoutEReutilizacaoSaoProtegidos() throws Exception {
        definir();var anonymous=new MockHttpSession();String before=anonymous.getId();var session=pinLogin(pin,anonymous);assertNotEquals(before,session.getId());
        mvc.perform(post("/api/v1/operacional/atividade").session(session)).andExpect(status().isForbidden());
        var p=principal(session);mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());assertTrue(session.isInvalid());assertNull(op.contexto(p,false));
        mvc.perform(get("/api/v1/operacional/contexto")).andExpect(status().isUnauthorized());
    }
    @Test void sessaoAdministrativaNaoERebaixadaPorTentativaDePin() throws Exception {
        definir();var session=adminLogin();mvc.perform(post("/api/v1/operacional/entrar").session(session).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(new Entrada(a.toString(),pessoa.vinculoId().toString(),pin)))).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
    }
    @Test void auditoriaFalhandoReverteDefinicaoEFalhasDePin(){
        jdbc.execute("ALTER TABLE auditoria_eventos ADD CONSTRAINT pin_fail_"+a.toString().replace("-","")+" CHECK (estabelecimento_id <> '"+a+"'::uuid OR tipo_evento <> 'OPERACIONAL_PIN_DEFINIDO') NOT VALID");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->definir());assertFalse(op.estado(a,pessoa.vinculoId(),ownerEmail).configurado());
    }
    @Test void auditoriaFalhandoNaoConsomeTentativaOuBloqueia(){
        definir();jdbc.execute("ALTER TABLE auditoria_eventos ADD CONSTRAINT try_fail_"+a.toString().replace("-","")+" CHECK (estabelecimento_id <> '"+a+"'::uuid OR tipo_evento <> 'OPERACIONAL_PIN_INCORRETO') NOT VALID");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->falhar());assertEquals(3,op.estado(a,pessoa.vinculoId(),ownerEmail).tentativasRestantes());
    }
    @Test void entradasInvalidasNaoExpoemValorDePin() throws Exception {
        var session=adminLogin();mvc.perform(put("/api/v1/estabelecimentos/"+a+"/seguranca-operacional/funcionarios/"+pessoa.vinculoId()+"/pin").session(session).with(csrf()).contentType("application/json").content("{\"pin\":[1,2,3],\"versao\":0}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Formato de requisicao invalido"));
        assertEquals(400,statusOf(()->op.definir(a,pessoa.vinculoId(),ownerEmail,new Definicao(pin,null))));
    }
    @Test void eventosSaoRestritosEFuncionariosNaoGanhamSegurancaPorFuncao(){
        definir();falhar();assertFalse(op.eventos(a,ownerEmail).isEmpty());assertEquals(403,statusOf(()->op.eventos(a,managerEmail)));
        assertTrue(op.eventos(b,ownerEmail).isEmpty());assertFalse(op.capacidades(a,managerEmail).configurar());
    }
    @Test void revogarPermissaoDeGerenteBloqueiaRedefinicaoSeguinte(){
        autorizar("PIN_GERENCIAR");op.definir(a,pessoa.vinculoId(),managerEmail,new Definicao(pin,0L));
        permissoes.excecao(a,managerLink,"PIN_GERENCIAR",ownerEmail,new PermissoesDtos.Excecao(PermissoesDtos.Estado.NEGAR,versao(a,managerLink)));
        assertEquals(403,statusOf(()->op.definir(a,pessoa.vinculoId(),managerEmail,new Definicao(pin,versao(a,pessoa.vinculoId())))));
    }
    @Test void tresFalhasConcorrentesNaoPerdemContagem() throws Exception {
        definir();var pool=Executors.newFixedThreadPool(3);try{var futures=new ArrayList<Future<?>>();for(int i=0;i<3;i++)futures.add(pool.submit(()->falhar()));for(var f:futures)f.get(15,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertTrue(op.estado(a,pessoa.vinculoId(),ownerEmail).bloqueado());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento='OPERACIONAL_TENTATIVAS_BLOQUEADO'",Integer.class,a));
    }
    void corridaOrdenada(Runnable primeira,java.util.concurrent.Callable<Integer> segunda,int esperado) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var pronta=new CountDownLatch(1);var liberar=new CountDownLatch(1);var pid=new CompletableFuture<Integer>();
        try{
            var x=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{primeira.run();pronta.countDown();try{if(!liberar.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Barreira excedida");}catch(InterruptedException e){throw new IllegalStateException(e);}}));
            assertTrue(pronta.await(5,TimeUnit.SECONDS));var y=pool.submit(()->new TransactionTemplate(transactions).execute(tx->{pid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));try{return segunda.call();}catch(Exception e){throw new IllegalStateException(e);}}));
            int backend=pid.get(5,TimeUnit.SECONDS);boolean esperando=false;for(int i=0;i<100;i++){if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,backend))){esperando=true;break;}Thread.sleep(20);}assertTrue(esperando);liberar.countDown();x.get(10,TimeUnit.SECONDS);assertEquals(esperado,y.get(10,TimeUnit.SECONDS));
        }finally{liberar.countDown();pool.shutdownNow();}
    }
    @Test void bloqueioPrimeiroRecusaOperacaoQueAguardaLock() throws Exception {definir();var p=entrar();corridaOrdenada(()->op.bloquear(a,pessoa.vinculoId(),ownerEmail,new Bloqueio(true,versao(a,pessoa.vinculoId()))),()->op.contexto(p,false)==null?423:200,423);}
    @Test void operacaoPrimeiroConcluiAntesDeBloqueioSemRessuscitarSessao() throws Exception {definir();var p=entrar();corridaOrdenada(()->assertNotNull(op.contexto(p,false)),()->statusOf(()->op.bloquear(a,pessoa.vinculoId(),ownerEmail,new Bloqueio(true,versao(a,pessoa.vinculoId())))),200);assertNull(op.contexto(p,false));}
    @Test void entradaExigeCsrfERotacionaTokenAposPin() throws Exception {
        definir();String body=json.writeValueAsString(new Entrada(a.toString(),pessoa.vinculoId().toString(),pin));
        mvc.perform(post("/api/v1/operacional/entrar").contentType("application/json").content(body)).andExpect(status().isForbidden());
        var anonymous=new MockHttpSession();var before=json.readTree(mvc.perform(get("/api/v1/auth/csrf").session(anonymous)).andReturn().getResponse().getContentAsString());
        var result=mvc.perform(post("/api/v1/operacional/entrar").session(anonymous).header(before.get("headerName").asText(),before.get("token").asText()).contentType("application/json").content(body)).andExpect(status().isNoContent()).andReturn();
        var session=(MockHttpSession)result.getRequest().getSession(false);
        mvc.perform(post("/api/v1/operacional/atividade").session(session).header(before.get("headerName").asText(),before.get("token").asText())).andExpect(status().isForbidden());
        var after=json.readTree(mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/operacional/atividade").session(session).header(after.get("headerName").asText(),after.get("token").asText())).andExpect(status().isOk());
    }
    @Test void falhaDeAuditoriaNoLogoutAindaInvalidaSessaoHttp() throws Exception {
        definir();var session=pinLogin(pin,null);
        jdbc.execute("ALTER TABLE auditoria_eventos ADD CONSTRAINT logout_fail_"+a.toString().replace("-","")+" CHECK (estabelecimento_id <> '"+a+"'::uuid OR tipo_evento <> 'OPERACIONAL_SAIDA') NOT VALID");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf())));
        assertTrue(session.isInvalid());mvc.perform(get("/api/v1/operacional/contexto")).andExpect(status().isUnauthorized());
    }
    @Test void administracaoNaoAceitaVinculoDeOutraUnidadeNemGerenteSemVinculo(){
        definir();assertEquals(404,statusOf(()->op.estado(b,pessoa.vinculoId(),ownerEmail)));
        assertEquals(404,statusOf(()->op.definir(b,pessoa.vinculoId(),ownerEmail,new Definicao(pin,versao(a,pessoa.vinculoId())))));
        assertEquals(403,statusOf(()->op.capacidades(b,managerEmail)));
        assertTrue(op.eventos(b,ownerEmail).isEmpty());
    }
}