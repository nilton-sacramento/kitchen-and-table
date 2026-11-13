package com.kitchentable.funcionarios;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.http.HttpStatus;
import static com.kitchentable.funcionarios.FuncionarioRepository.erro;
import static com.kitchentable.funcionarios.PermissoesDtos.*;

@Service
@Transactional
public class PermissoesService {
    private final FuncionarioRepository repo;
    private final AuditoriaAdministrativaService audit;
    private static final Map<String,String> ACOES=Map.of("FUNCIONARIO_CONSULTAR","consultar","FUNCIONARIO_CADASTRAR","cadastrar",
        "FUNCIONARIO_EDITAR","editar","FUNCIONARIO_ATIVAR","ativar","FUNCIONARIO_DESATIVAR","desativar","FUNCIONARIO_ASSOCIAR","associar",
        "FUNCIONARIO_FUNCAO_PRINCIPAL_ALTERAR","editar");
    public PermissoesService(FuncionarioRepository repo,AuditoriaAdministrativaService audit){this.repo=repo;this.audit=audit;}
    private record Ator(UUID id,UUID vinculo,String funcao){boolean dono(){return funcao.equals("PROPRIETARIO");}}
    private Ator ator(UUID unidade,String email,boolean escrita){
        if(escrita) repo.bloquearUnidades(unidade);else repo.validarUnidade(unidade);
        var a=repo.ator(unidade,email);return new Ator(a.id(),a.vinculo(),a.funcao());
    }
    private FuncionarioDtos.Funcionario alvo(UUID unidade,UUID id){
        var f=repo.buscar(unidade,id);
        if(f.funcao().equals("PROPRIETARIO"))throw erro(HttpStatus.FORBIDDEN,"O acesso do proprietario e protegido");return f;
    }
    private boolean alvoDelegado(Ator a,UUID alvo){
        return a.funcao().equals("GERENTE")&&!a.vinculo().equals(alvo)&&Boolean.TRUE.equals(repo.jdbc().queryForObject(
            "SELECT EXISTS(SELECT 1 FROM limites_gestao_alvos WHERE gerente_id=? AND alvo_id=?)",Boolean.class,a.vinculo(),alvo));
    }
    private void autorizar(Ator a,FuncionarioDtos.Funcionario f){
        if(!a.dono()&&(!AutorizacaoAdministrativaService.COMUNS.contains(f.funcao())||!alvoDelegado(a,f.vinculoId())))
            throw erro(HttpStatus.FORBIDDEN,"Gestao de permissoes nao autorizada para este funcionario");
    }
    private List<Catalogo> catalogo(){
        return repo.jdbc().query("SELECT * FROM permissoes ORDER BY categoria,modulo,codigo",(rs,i)->new Catalogo(rs.getString("codigo"),rs.getString("nome"),rs.getString("descricao"),rs.getString("modulo"),rs.getString("categoria"),rs.getBoolean("critica"),rs.getBoolean("disponivel"),rs.getBoolean("exclusiva_proprietario")));
    }
    private List<String> adicionais(UUID id){return repo.jdbc().queryForList("SELECT f.codigo FROM vinculos_funcoes_adicionais a JOIN funcoes f ON f.id=a.funcao_id WHERE a.vinculo_id=? ORDER BY f.codigo",String.class,id);}
    private List<String> limites(UUID gerente,boolean conceder){
        return repo.jdbc().queryForList("SELECT p.codigo FROM limites_gestao_codigos l JOIN permissoes p ON p.id=l.permissao_id WHERE l.gerente_id=? AND "+(conceder?"l.conceder":"l.revogar"),String.class,gerente);
    }
    private boolean adicionaisDelegados(Ator a,UUID alvo){
        return alvoDelegado(a,alvo)&&Boolean.TRUE.equals(repo.jdbc().queryForObject("SELECT adicionais FROM limites_gestao_permissoes WHERE gerente_id=?",Boolean.class,a.vinculo()));
    }
    // Avaliador unico. Chamadores protegidos usam transacao e validam o escopo antes de chamar.
    private List<Efetiva> calcular(UUID vinculo,String principal){
        Map<String,List<String>> herdadas=new HashMap<>();
        repo.jdbc().query("SELECT p.codigo,f.codigo AS funcao FROM funcoes_permissoes fp JOIN permissoes p ON p.id=fp.permissao_id JOIN funcoes f ON f.id=fp.funcao_id WHERE f.codigo=? OR f.id IN (SELECT funcao_id FROM vinculos_funcoes_adicionais WHERE vinculo_id=?)",rs->{
            herdadas.computeIfAbsent(rs.getString("codigo"),k->new ArrayList<>()).add(rs.getString("funcao"));
        },principal,vinculo);
        Map<String,Boolean> excecoes=new HashMap<>();
        repo.jdbc().query("SELECT p.codigo,up.permitido FROM usuarios_permissoes up JOIN permissoes p ON p.id=up.permissao_id WHERE up.vinculo_id=?",rs->{excecoes.put(rs.getString("codigo"),rs.getBoolean("permitido"));},vinculo);
        var d=repo.delegacao(vinculo,0);boolean gerente=principal.equals("GERENTE"),dono=principal.equals("PROPRIETARIO");
        boolean novaDelegacao=gerente&&Boolean.TRUE.equals(repo.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM limites_gestao_alvos WHERE gerente_id=?)",Boolean.class,vinculo));
        boolean novasAdicionais=novaDelegacao&&Boolean.TRUE.equals(repo.jdbc().queryForObject("SELECT adicionais FROM limites_gestao_permissoes WHERE gerente_id=?",Boolean.class,vinculo));
        boolean ativo=Boolean.TRUE.equals(repo.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM usuarios_estabelecimentos ue JOIN usuarios u ON u.id=ue.usuario_id JOIN estabelecimentos e ON e.id=ue.estabelecimento_id WHERE ue.id=? AND ue.ativo AND u.ativo AND e.ativo)",Boolean.class,vinculo));
        boolean unidadeNegada=Boolean.FALSE.equals(excecoes.get("ESTABELECIMENTO_VISUALIZAR"));
        List<Efetiva> resultado=new ArrayList<>();
        for(var p:catalogo()){
            var origens=new ArrayList<>(herdadas.getOrDefault(p.codigo(),List.of()));Collections.sort(origens);
            boolean base=!origens.isEmpty();String restricao="";
            String acao=ACOES.get(p.codigo());
            boolean delegada=acao!=null&&gerente&&switch(acao){case "consultar"->d.consultar();case "cadastrar"->d.cadastrar();case "editar"->d.editar();case "ativar"->d.ativar();case "desativar"->d.desativar();case "associar"->d.associar();default->false;};
            if(delegada){base=true;origens.add("DELEGACAO_2_1");}
            if(novaDelegacao&&Set.of("PERMISSOES_CONSULTAR","PERMISSOES_INDIVIDUAIS_GERENCIAR").contains(p.codigo())){base=true;origens.add("LIMITE_EXPLICITO_POR_ALVO");}
            boolean configurada=excecoes.getOrDefault(p.codigo(),base);
            Estado estado=!excecoes.containsKey(p.codigo())?Estado.HERDAR:excecoes.get(p.codigo())?Estado.PERMITIR:Estado.NEGAR;
            boolean permitida=configurada;
            if(acao!=null&&!dono&&!delegada){permitida=false;restricao="Exige funcao principal GERENTE e delegacao administrativa explicita";}
            if(novasAdicionais&&p.codigo().equals("FUNCIONARIO_FUNCOES_ADICIONAIS_ALTERAR")){base=true;configurada=true;permitida=true;origens.add("LIMITE_EXPLICITO_POR_ALVO");}
            if(p.exclusivaProprietario()&&!dono){
                permitida=(novaDelegacao&&Set.of("PERMISSOES_CONSULTAR","PERMISSOES_INDIVIDUAIS_GERENCIAR").contains(p.codigo()))||(novasAdicionais&&p.codigo().equals("FUNCIONARIO_FUNCOES_ADICIONAIS_ALTERAR"));
                restricao=permitida?"Limitada aos alvos e codigos delegados":"Exclusiva do proprietario ou fluxo de delegacao protegido";
                if(estado==Estado.NEGAR)permitida=false;
            }
            if(dono){permitida=true;origens.add("AUTORIDADE_PROPRIETARIO");}
            if(!ativo||(!dono&&unidadeNegada)){permitida=false;restricao="Vinculo inativo ou acesso a unidade revogado";}
            if(!p.disponivel()){permitida=false;restricao="Contrato futuro ou legado inativo; nenhuma operacao habilitada";}
            resultado.add(new Efetiva(p,List.copyOf(origens),estado,configurada,permitida,restricao));
        }return resultado;
    }
    boolean podeExistente(UUID vinculo,String codigo){
        var rows=repo.jdbc().query("SELECT f.codigo FROM usuarios_estabelecimentos ue JOIN usuarios u ON u.id=ue.usuario_id JOIN estabelecimentos e ON e.id=ue.estabelecimento_id JOIN funcoes f ON f.id=ue.funcao_id WHERE ue.id=? AND ue.ativo AND u.ativo AND e.ativo",(rs,i)->rs.getString(1),vinculo);
        return !rows.isEmpty()&&calcular(vinculo,rows.get(0)).stream().anyMatch(p->p.permissao().codigo().equals(codigo)&&p.efetiva());
    }
    void exigirExistente(UUID vinculo,String codigo){if(!podeExistente(vinculo,codigo))throw erro(HttpStatus.FORBIDDEN,"Permissao revogada ou nao autorizada");}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Catalogo> catalogo(UUID unidade,String email){ator(unidade,email,false);return catalogo();}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Capacidades capacidades(UUID unidade,String email){var a=ator(unidade,email,false);return new Capacidades(a.dono(),podeExistente(a.vinculo(),"FUNCIONARIO_FUNCAO_PRINCIPAL_ALTERAR"),a.dono()?List.of():repo.jdbc().queryForList("SELECT alvo_id FROM limites_gestao_alvos WHERE gerente_id=? ORDER BY alvo_id",UUID.class,a.vinculo()));}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<FuncionarioDtos.Funcionario> alvos(UUID unidade,String email){
        var a=ator(unidade,email,false);return repo.listar(unidade).stream().filter(f->a.dono()||(AutorizacaoAdministrativaService.COMUNS.contains(f.funcao())&&alvoDelegado(a,f.vinculoId()))).toList();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Painel painel(UUID unidade,UUID id,String email){var a=ator(unidade,email,false);var f=alvo(unidade,id);autorizar(a,f);return painel(a,f);}
    private Painel painel(Ator a,FuncionarioDtos.Funcionario f){
        var codes=catalogo().stream().filter(p->!p.exclusivaProprietario()).map(Catalogo::codigo).toList();
        return new Painel(f.vinculoId(),f.nome(),f.funcao(),adicionais(f.vinculoId()),f.versao(),calcular(f.vinculoId(),f.funcao()),
            a.dono()||adicionaisDelegados(a,f.vinculoId()),a.dono()&&f.funcao().equals("GERENTE"),a.dono()?codes:limites(a.vinculo(),true),a.dono()?codes:limites(a.vinculo(),false));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Efetiva> minhas(UUID unidade,String email){var a=ator(unidade,email,false);return calcular(a.vinculo(),a.funcao());}
    public Painel adicionais(UUID unidade,UUID id,String email,Adicionais dto){
        var a=ator(unidade,email,true);var f=alvo(unidade,id);autorizar(a,f);repo.verificarVersao(f,dto.versao());
        if(!a.dono()&&!adicionaisDelegados(a,id))throw erro(HttpStatus.FORBIDDEN,"Funcoes adicionais fora da delegacao");
        var novas=new LinkedHashSet<>(dto.funcoes());
        if(novas.size()!=dto.funcoes().size()||!AutorizacaoAdministrativaService.COMUNS.containsAll(novas)||novas.contains(f.funcao()))throw erro(HttpStatus.BAD_REQUEST,"Funcoes adicionais devem ser operacionais, unicas e diferentes da principal");
        var antes=adicionais(id);repo.jdbc().update("DELETE FROM vinculos_funcoes_adicionais WHERE vinculo_id=?",id);
        for(String codigo:novas)repo.jdbc().update("INSERT INTO vinculos_funcoes_adicionais(vinculo_id,estabelecimento_id,funcao_id) SELECT ?,?,id FROM funcoes WHERE codigo=?",id,unidade,codigo);
        concluir(unidade,a,id,"FUNCOES_ADICIONAIS_ALTERADAS",antes,List.copyOf(novas));return painel(a,repo.buscar(unidade,id));
    }
    public Painel excecao(UUID unidade,UUID id,String codigo,String email,Excecao dto){
        var a=ator(unidade,email,true);var f=alvo(unidade,id);autorizar(a,f);repo.verificarVersao(f,dto.versao());
        var p=catalogo().stream().filter(c->c.codigo().equals(codigo)).findFirst().orElseThrow(()->erro(HttpStatus.BAD_REQUEST,"Permissao desconhecida"));
        if(p.exclusivaProprietario())throw erro(HttpStatus.FORBIDDEN,"Autoridade administrativa e alterada somente nos fluxos protegidos");
        var anteriores=repo.jdbc().queryForList("SELECT permitido FROM usuarios_permissoes WHERE vinculo_id=? AND permissao_id=(SELECT id FROM permissoes WHERE codigo=?)",Boolean.class,id,codigo);
        Estado antes=anteriores.isEmpty()?Estado.HERDAR:anteriores.get(0)?Estado.PERMITIR:Estado.NEGAR;
        if(!a.dono()){
            // HERDAR pode conceder heranca antes negada: exige ambos os poderes, sem atalho de revogacao.
            boolean grant=dto.estado()==Estado.PERMITIR||dto.estado()==Estado.HERDAR;
            boolean revoke=dto.estado()==Estado.NEGAR||dto.estado()==Estado.HERDAR;
            if((grant&&!limites(a.vinculo(),true).contains(codigo))||(revoke&&!limites(a.vinculo(),false).contains(codigo)))throw erro(HttpStatus.FORBIDDEN,"Codigo ou transicao fora dos limites delegados");
        }
        if(dto.estado()==Estado.HERDAR)repo.jdbc().update("DELETE FROM usuarios_permissoes WHERE vinculo_id=? AND permissao_id=(SELECT id FROM permissoes WHERE codigo=?)",id,codigo);
        else repo.jdbc().update("INSERT INTO usuarios_permissoes(vinculo_id,permissao_id,permitido) SELECT ?,id,? FROM permissoes WHERE codigo=? ON CONFLICT(vinculo_id,permissao_id) DO UPDATE SET permitido=EXCLUDED.permitido",id,dto.estado()==Estado.PERMITIR,codigo);
        concluir(unidade,a,id,"PERMISSAO_INDIVIDUAL_ALTERADA",Map.of("codigo",codigo,"estado",antes),Map.of("codigo",codigo,"estado",dto.estado()));return painel(a,repo.buscar(unidade,id));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Limites limites(UUID unidade,UUID id,String email){var a=ator(unidade,email,false);if(!a.dono())throw erro(HttpStatus.FORBIDDEN,"Somente proprietario define limites");var f=alvo(unidade,id);return lerLimites(f);}
    private Limites lerLimites(FuncionarioDtos.Funcionario f){
        boolean adicionais=Boolean.TRUE.equals(repo.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM limites_gestao_permissoes WHERE gerente_id=? AND adicionais)",Boolean.class,f.vinculoId()));
        var alvos=repo.jdbc().queryForList("SELECT alvo_id FROM limites_gestao_alvos WHERE gerente_id=? ORDER BY alvo_id",UUID.class,f.vinculoId());
        var codes=repo.jdbc().query("SELECT p.codigo,l.conceder,l.revogar FROM limites_gestao_codigos l JOIN permissoes p ON p.id=l.permissao_id WHERE gerente_id=? ORDER BY p.codigo",(rs,i)->new LimiteCodigo(rs.getString(1),rs.getBoolean(2),rs.getBoolean(3)),f.vinculoId());
        return new Limites(adicionais,alvos,codes,f.versao());
    }
    public Painel limites(UUID unidade,UUID id,String email,Limites dto){
        var a=ator(unidade,email,true);if(!a.dono())throw erro(HttpStatus.FORBIDDEN,"Somente proprietario define limites");var f=alvo(unidade,id);
        if(!f.funcao().equals("GERENTE"))throw erro(HttpStatus.BAD_REQUEST,"Limites disponiveis somente para gerente");repo.verificarVersao(f,dto.versao());
        if(new HashSet<>(dto.alvos()).size()!=dto.alvos().size()||dto.permissoes().stream().map(LimiteCodigo::codigo).distinct().count()!=dto.permissoes().size())throw erro(HttpStatus.BAD_REQUEST,"Limites duplicados");
        for(UUID alvo:dto.alvos()){var t=alvo(unidade,alvo);if(!AutorizacaoAdministrativaService.COMUNS.contains(t.funcao())||alvo.equals(id))throw erro(HttpStatus.FORBIDDEN,"Somente funcionarios comuns podem ser alvos");}
        var disponiveis=catalogo();for(var l:dto.permissoes())if((!l.conceder()&&!l.revogar())||disponiveis.stream().noneMatch(p->p.codigo().equals(l.codigo())&&!p.exclusivaProprietario()))throw erro(HttpStatus.BAD_REQUEST,"Limite invalido ou exclusivo do proprietario");
        var antes=lerLimites(f);repo.jdbc().update("DELETE FROM limites_gestao_permissoes WHERE gerente_id=?",id);
        repo.jdbc().update("INSERT INTO limites_gestao_permissoes(gerente_id,estabelecimento_id,adicionais,autorizador_id,versao_autorizacao) VALUES(?,?,?,?,?)",id,unidade,dto.adicionais(),a.id(),f.versao()+1);
        for(UUID target:dto.alvos())repo.jdbc().update("INSERT INTO limites_gestao_alvos(gerente_id,estabelecimento_id,alvo_id) VALUES(?,?,?)",id,unidade,target);
        for(var l:dto.permissoes())repo.jdbc().update("INSERT INTO limites_gestao_codigos(gerente_id,permissao_id,conceder,revogar) SELECT ?,id,?,? FROM permissoes WHERE codigo=?",id,l.conceder(),l.revogar(),l.codigo());
        concluir(unidade,a,id,"LIMITES_PERMISSOES_ALTERADOS",antes,dto);return painel(a,repo.buscar(unidade,id));
    }
    Map<String,Object> snapshot(UUID id){
        return Map.of("gestoresAnteriores",repo.jdbc().queryForList("SELECT gerente_id FROM limites_gestao_alvos WHERE alvo_id=?",UUID.class,id),"adicionais",adicionais(id),"limites",repo.jdbc().queryForList("SELECT adicionais,autorizador_id,autorizado_em,versao_autorizacao FROM limites_gestao_permissoes WHERE gerente_id=?",id),
            "alvos",repo.jdbc().queryForList("SELECT alvo_id FROM limites_gestao_alvos WHERE gerente_id=?",UUID.class,id),
            "codigos",repo.jdbc().queryForList("SELECT p.codigo,l.conceder,l.revogar FROM limites_gestao_codigos l JOIN permissoes p ON p.id=l.permissao_id WHERE gerente_id=?",id));
    }
    private void concluir(UUID unidade,Ator a,UUID id,String evento,Object antes,Object depois){
        repo.jdbc().update("UPDATE usuarios_estabelecimentos SET versao=versao+1,atualizado_em=now() WHERE id=? AND estabelecimento_id=?",id,unidade);
        Map<String,Object> registro=new LinkedHashMap<>();registro.put("alteracao",depois);registro.put("resultado","APLICADA");
        if(a.dono())registro.put("autorizador",a.id());
        else {
            var autorizacao=repo.jdbc().queryForMap("SELECT autorizador_id,versao_autorizacao FROM limites_gestao_permissoes WHERE gerente_id=?",a.vinculo());
            registro.put("autorizador",autorizacao.get("autorizador_id"));registro.put("versaoAutorizacao",autorizacao.get("versao_autorizacao"));registro.put("delegacaoGerente",a.vinculo());
        }
        audit.registrar(unidade,a.id(),a.vinculo(),evento,id,antes,registro);
    }
}