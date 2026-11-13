package com.kitchentable.funcionarios;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static com.kitchentable.funcionarios.FuncionarioDtos.*;

@Repository
public class FuncionarioRepository {
    private final JdbcTemplate jdbc;
    public FuncionarioRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public JdbcTemplate jdbc() { return jdbc; }
    static ResponseStatusException erro(HttpStatus status, String message) {
        return new ResponseStatusException(status,message);
    }
    // Leituras usam snapshot consistente, sem row lock; mutacoes mantem FOR UPDATE.
    void validarUnidade(UUID id) {
        Boolean ativa=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM estabelecimentos WHERE id=? AND ativo=true)",Boolean.class,id);
        if(!Boolean.TRUE.equals(ativa)) throw erro(HttpStatus.NOT_FOUND,"Estabelecimento indisponível");
    }
    void bloquearUnidades(UUID... ids) {
        for (UUID id : new TreeSet<>(Arrays.asList(ids))) {
            var rows=jdbc.query("SELECT id FROM estabelecimentos WHERE id=? AND ativo=true FOR UPDATE",
                (rs,i)->rs.getObject(1,UUID.class), id);
            if(rows.isEmpty()) throw erro(HttpStatus.NOT_FOUND,"Estabelecimento indisponível");
        }
    }
    record Pessoa(UUID id, UUID vinculo, String funcao) {}
    Pessoa ator(UUID estabelecimento, String email) {
        var rows=jdbc.query("SELECT u.id,ue.id AS vinculo,f.codigo FROM usuarios u " +
            "JOIN usuarios_estabelecimentos ue ON ue.usuario_id=u.id JOIN funcoes f ON f.id=ue.funcao_id " +
            "WHERE lower(u.email)=lower(?) AND u.ativo=true AND ue.ativo=true AND ue.estabelecimento_id=? AND (f.codigo='PROPRIETARIO' OR NOT EXISTS(SELECT 1 FROM usuarios_permissoes up JOIN permissoes p ON p.id=up.permissao_id WHERE up.vinculo_id=ue.id AND p.codigo='ESTABELECIMENTO_VISUALIZAR' AND NOT up.permitido))",
            (rs,i)->new Pessoa(rs.getObject("id",UUID.class),rs.getObject("vinculo",UUID.class),rs.getString("codigo")),email,estabelecimento);
        if(rows.isEmpty()) throw erro(HttpStatus.FORBIDDEN,"Acesso administrativo não autorizado");
        return rows.get(0);
    }
    String emailAtivo(UUID usuario) {
        var rows=jdbc.query("SELECT email FROM usuarios WHERE id=? AND ativo=true AND email IS NOT NULL",
            (rs,i)->rs.getString(1),usuario);
        if(rows.isEmpty()) throw erro(HttpStatus.CONFLICT,"Autorização de origem revogada");
        return rows.get(0);
    }
    private static final String SELECT="SELECT ue.id AS vinculo_id,u.id AS usuario_id,COALESCE(ue.nome_exibicao,u.nome) AS nome,"+
        "ue.email_contato,ue.telefone,f.codigo,ue.ativo,ue.versao,EXISTS(SELECT 1 FROM credenciais_operacionais co WHERE co.vinculo_id=ue.id) AS acesso_operacional FROM usuarios_estabelecimentos ue " +
        "JOIN usuarios u ON u.id=ue.usuario_id JOIN funcoes f ON f.id=ue.funcao_id ";
    private Funcionario map(ResultSet rs,int n) throws SQLException {
        return new Funcionario(rs.getObject("vinculo_id",UUID.class),rs.getObject("usuario_id",UUID.class),
            rs.getString("nome"),rs.getString("email_contato"),rs.getString("telefone"),rs.getString("codigo"),
            rs.getBoolean("ativo"),rs.getLong("versao"),rs.getBoolean("acesso_operacional"));
    }
    Funcionario buscar(UUID estabelecimento, UUID vinculo) {
        var rows=jdbc.query(SELECT+"WHERE ue.estabelecimento_id=? AND ue.id=? AND u.ativo=true",this::map,estabelecimento,vinculo);
        if(rows.isEmpty()) throw erro(HttpStatus.NOT_FOUND,"Funcionário não encontrado");
        return rows.get(0);
    }
    List<Funcionario> listar(UUID estabelecimento) {
        return jdbc.query(SELECT+"WHERE ue.estabelecimento_id=? AND u.ativo=true AND f.codigo<>'PROPRIETARIO' ORDER BY nome,ue.id",this::map,estabelecimento);
    }
    Delegacao delegacao(UUID vinculo,long versao) {
        var rows=jdbc.query("SELECT * FROM delegacoes_administrativas WHERE vinculo_id=?",(rs,i)->
            new Delegacao(rs.getBoolean("consultar"),rs.getBoolean("cadastrar"),rs.getBoolean("editar"),
                rs.getBoolean("ativar"),rs.getBoolean("desativar"),rs.getBoolean("associar"),
                Arrays.asList((String[])rs.getArray("funcoes").getArray()),versao),vinculo);
        return rows.isEmpty()?new Delegacao(false,false,false,false,false,false,List.of(),versao):rows.get(0);
    }
    UUID criarIdentidade(String nome) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios(id,nome) VALUES(?,?)",id,nome);
        return id;
    }
    Funcionario vincular(UUID estabelecimento, UUID usuario, String nome,String funcao,String email,String telefone) {
        Integer count=jdbc.queryForObject("SELECT count(*) FROM usuarios_estabelecimentos WHERE usuario_id=? AND estabelecimento_id=?",Integer.class,usuario,estabelecimento);
        if(count!=null && count>0) throw erro(HttpStatus.CONFLICT,"A pessoa já possui vínculo nesta unidade; consulte o cadastro existente");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios_estabelecimentos(id,usuario_id,estabelecimento_id,funcao_id,nome_exibicao,email_contato,telefone) " +
            "VALUES(?,?,?,(SELECT id FROM funcoes WHERE codigo=?),?,?,?)",id,usuario,estabelecimento,funcao,nome,email,telefone);
        return buscar(estabelecimento,id);
    }
    void verificarVersao(Funcionario target,long versao) {
        if(target.versao()!=versao) throw erro(HttpStatus.CONFLICT,"Cadastro alterado por outra operação; atualize a lista");
    }
    void editar(UUID unidade,UUID vinculo,Edicao dto) {
        jdbc.update("UPDATE usuarios_estabelecimentos SET nome_exibicao=?,email_contato=?,telefone=?,versao=versao+1,atualizado_em=now() WHERE id=? AND estabelecimento_id=?",
            dto.nome().trim(),normalizar(dto.emailContato()),normalizar(dto.telefone()),vinculo,unidade);
    }
    static String normalizar(String value) { return value==null||value.isBlank()?null:value.trim(); }
    int situacao(UUID unidade,UUID vinculo,boolean ativo) {
        jdbc.update("UPDATE usuarios_estabelecimentos SET ativo=?,versao=versao+1,atualizado_em=now() WHERE id=? AND estabelecimento_id=?",ativo,vinculo,unidade);
        if(!ativo){
            jdbc.update("UPDATE credenciais_operacionais SET versao=versao+1 WHERE vinculo_id=?",vinculo);
            return jdbc.update("UPDATE sessoes_operacionais SET bloqueada=true,encerrada=true WHERE vinculo_id=? AND NOT encerrada",vinculo);
        }
        return 0;
    }
    void funcao(UUID unidade,UUID vinculo,String codigo) {
        jdbc.update("DELETE FROM vinculos_funcoes_adicionais WHERE vinculo_id=? AND funcao_id=(SELECT id FROM funcoes WHERE codigo=?)",vinculo,codigo);
        jdbc.update("DELETE FROM limites_gestao_alvos WHERE alvo_id=?",vinculo);
        jdbc.update("DELETE FROM limites_gestao_permissoes WHERE gerente_id=?",vinculo);
        jdbc.update("UPDATE usuarios_estabelecimentos SET funcao_id=(SELECT id FROM funcoes WHERE codigo=?),versao=versao+1,atualizado_em=now() WHERE id=? AND estabelecimento_id=?",codigo,vinculo,unidade);
        // Uma delegacao antiga nunca ressurge ao rebaixar e promover novamente.
        jdbc.update("DELETE FROM delegacoes_administrativas WHERE vinculo_id=?",vinculo);
    }
}
