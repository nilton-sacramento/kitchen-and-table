package com.kitchentable.funcionarios;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import static com.kitchentable.funcionarios.FuncionarioDtos.*;
import static com.kitchentable.funcionarios.FuncionarioRepository.*;

@Service
@Transactional
public class FuncionarioService {
    private final FuncionarioRepository repo;
    private final AutorizacaoAdministrativaService auth;
    private final AuditoriaAdministrativaService audit;
    private final ObjectMapper json;
    public FuncionarioService(FuncionarioRepository repo,AutorizacaoAdministrativaService auth,AuditoriaAdministrativaService audit,ObjectMapper json) {
        this.repo=repo;this.auth=auth;this.audit=audit;this.json=json;
    }
    private AutorizacaoAdministrativaService.Administrador iniciar(UUID unidade,String email,String acao) {
        repo.bloquearUnidades(unidade);
        var ator=auth.carregar(unidade,email); auth.exigir(ator,acao); return ator;
    }
    private void evento(UUID unidade,AutorizacaoAdministrativaService.Administrador ator,String tipo,UUID alvo,Object antes,Object depois) {
        audit.registrar(unidade,ator.pessoa().id(),ator.pessoa().vinculo(),tipo,alvo,antes,depois);
    }
    public Capacidades capacidades(UUID unidade,String email) {
        repo.bloquearUnidades(unidade);return auth.capacidades(auth.carregar(unidade,email));
    }
    public List<Funcionario> listar(UUID unidade,String email) {
        var ator=iniciar(unidade,email,"consultar");
        return repo.listar(unidade).stream().filter(f->ator.proprietario() || (AutorizacaoAdministrativaService.COMUNS.contains(f.funcao()) && ator.delegacao().funcoes().contains(f.funcao()))).toList();
    }
    public Funcionario consultar(UUID unidade,UUID vinculo,String email) {
        var ator=iniciar(unidade,email,"consultar");var target=repo.buscar(unidade,vinculo);auth.alvo(ator,target);return target;
    }
    private String resumo(Object body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(body))); }
        catch(Exception e) { throw new IllegalStateException("Não foi possível identificar a requisição",e); }
    }
    UUID repeticao(UUID unidade,UUID key,UUID executor,String tipo,Object dto) {
        var rows=repo.jdbc().query("SELECT executor_id,tipo,resumo_requisicao,vinculo_id FROM operacoes_administrativas WHERE estabelecimento_id=? AND requisicao_id=?",(rs,i)->{
            if(!rs.getObject("executor_id",UUID.class).equals(executor) || !rs.getString("tipo").equals(tipo) || !rs.getString("resumo_requisicao").equals(resumo(dto)))
                throw erro(HttpStatus.CONFLICT,"Identificador de requisição já utilizado");
            return rs.getObject("vinculo_id",UUID.class);
        },unidade,key);
        return rows.isEmpty()?null:rows.get(0);
    }
    void lembrar(UUID unidade,UUID key,UUID executor,String tipo,Object dto,UUID vinculo) {
        repo.jdbc().update("INSERT INTO operacoes_administrativas(estabelecimento_id,requisicao_id,executor_id,tipo,resumo_requisicao,vinculo_id) VALUES(?,?,?,?,?,?)",unidade,key,executor,tipo,resumo(dto),vinculo);
    }
    public Funcionario cadastrar(UUID unidade,String email,UUID key,Cadastro dto) {
        var ator=iniciar(unidade,email,"cadastrar");auth.funcaoPermitida(ator,dto.funcao());
        UUID replay=repeticao(unidade,key,ator.pessoa().id(),"CADASTRO",dto);
        if(replay!=null) { var target=repo.buscar(unidade,replay);auth.alvo(ator,target);return target; }
        UUID usuario=repo.criarIdentidade(dto.nome().trim());
        var target=repo.vincular(unidade,usuario,dto.nome().trim(),dto.funcao(),normalizar(dto.emailContato()),normalizar(dto.telefone()));
        lembrar(unidade,key,ator.pessoa().id(),"CADASTRO",dto,target.vinculoId());
        evento(unidade,ator,"FUNCIONARIO_CADASTRADO",target.vinculoId(),null,target);return target;
    }
    public Funcionario editar(UUID unidade,UUID vinculo,String email,Edicao dto) {
        var ator=iniciar(unidade,email,"editar");var antes=repo.buscar(unidade,vinculo);auth.alvo(ator,antes);repo.verificarVersao(antes,dto.versao());
        repo.editar(unidade,vinculo,dto);var depois=repo.buscar(unidade,vinculo);
        evento(unidade,ator,"FUNCIONARIO_EDITADO",vinculo,antes,depois);return depois;
    }
    public Funcionario situacao(UUID unidade,UUID vinculo,String email,Situacao dto) {
        var ator=iniciar(unidade,email,dto.ativo()?"ativar":"desativar");var antes=repo.buscar(unidade,vinculo);auth.alvo(ator,antes);repo.verificarVersao(antes,dto.versao());
        repo.situacao(unidade,vinculo,dto.ativo());var depois=repo.buscar(unidade,vinculo);
        evento(unidade,ator,dto.ativo()?"FUNCIONARIO_ATIVADO":"FUNCIONARIO_DESATIVADO",vinculo,antes,depois);return depois;
    }
    public Funcionario funcao(UUID unidade,UUID vinculo,String email,Funcao dto) {
        var ator=iniciar(unidade,email,"editar");var antes=repo.buscar(unidade,vinculo);auth.alvo(ator,antes);auth.funcaoPermitida(ator,dto.funcao());repo.verificarVersao(antes,dto.versao());
        if(antes.funcao().equals(dto.funcao())) return antes;
        var delegacaoAntes=repo.delegacao(vinculo,antes.versao());
        repo.funcao(unidade,vinculo,dto.funcao());var depois=repo.buscar(unidade,vinculo);
        evento(unidade,ator,"FUNCAO_PRINCIPAL_ALTERADA",vinculo,Map.of("cadastro",antes,"delegacao",delegacaoAntes),depois);return depois;
    }
    public Delegacao consultarDelegacao(UUID unidade,UUID vinculo,String email) {
        var ator=iniciar(unidade,email,"consultar");auth.somenteProprietario(ator);var target=repo.buscar(unidade,vinculo);
        if(!target.funcao().equals("GERENTE")) throw erro(HttpStatus.BAD_REQUEST,"Delegação disponível somente para gerente");
        return repo.delegacao(vinculo,target.versao());
    }
    public Funcionario delegar(UUID unidade,UUID vinculo,String email,Delegacao dto) {
        var ator=iniciar(unidade,email,"editar");auth.somenteProprietario(ator);var target=repo.buscar(unidade,vinculo);
        if(!target.funcao().equals("GERENTE")) throw erro(HttpStatus.BAD_REQUEST,"Delegação disponível somente para gerente");
        repo.verificarVersao(target,dto.versao());
        if(!AutorizacaoAdministrativaService.COMUNS.containsAll(dto.funcoes())) throw erro(HttpStatus.BAD_REQUEST,"Funções fora do limite de gerente");
        var antes=repo.delegacao(vinculo,target.versao());
        repo.jdbc().update("INSERT INTO delegacoes_administrativas(vinculo_id,consultar,cadastrar,editar,ativar,desativar,associar,funcoes) VALUES(?,?,?,?,?,?,?,?) " +
            "ON CONFLICT(vinculo_id) DO UPDATE SET consultar=EXCLUDED.consultar,cadastrar=EXCLUDED.cadastrar,editar=EXCLUDED.editar,ativar=EXCLUDED.ativar,desativar=EXCLUDED.desativar,associar=EXCLUDED.associar,funcoes=EXCLUDED.funcoes",
            vinculo,dto.consultar(),dto.cadastrar(),dto.editar(),dto.ativar(),dto.desativar(),dto.associar(),array(dto.funcoes()));
        repo.jdbc().update("UPDATE usuarios_estabelecimentos SET versao=versao+1,atualizado_em=now() WHERE id=? AND estabelecimento_id=?",vinculo,unidade);
        evento(unidade,ator,"DELEGACAO_ADMINISTRATIVA_ALTERADA",vinculo,antes,repo.delegacao(vinculo,target.versao()+1));
        return repo.buscar(unidade,vinculo);
    }
    private Object array(List<String> values) {
        // SqlValue cria/libera o array na mesma conexao transacional usada no statement.
        return new org.springframework.jdbc.support.SqlValue() {
            private java.sql.Array array;
            public void setValue(java.sql.PreparedStatement ps,int index) throws java.sql.SQLException {
                array=ps.getConnection().createArrayOf("text",values.toArray(String[]::new));ps.setArray(index,array);
            }
            public void cleanup() { if(array!=null) try { array.free(); } catch(java.sql.SQLException ignored) { } }
        };
    }
}
