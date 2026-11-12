package com.kitchentable.funcionarios;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import static com.kitchentable.funcionarios.FuncionarioDtos.*;
import static com.kitchentable.funcionarios.FuncionarioRepository.erro;

@Service
public class AutorizacaoAdministrativaService {
    static final List<String> COMUNS=List.of("ATENDENTE","CAIXA","COZINHA","BAR");
    private final FuncionarioRepository repo;
    public AutorizacaoAdministrativaService(FuncionarioRepository repo) { this.repo=repo; }
    record Administrador(FuncionarioRepository.Pessoa pessoa, Delegacao delegacao, boolean proprietario) {}
    Administrador carregar(UUID unidade,String email) {
        var pessoa=repo.ator(unidade,email);
        boolean owner=pessoa.funcao().equals("PROPRIETARIO");
        if(!owner && !pessoa.funcao().equals("GERENTE")) throw erro(HttpStatus.FORBIDDEN,"Acesso administrativo não autorizado");
        return new Administrador(pessoa,repo.delegacao(pessoa.vinculo(),0),owner);
    }
    void exigir(Administrador ator,String acao) {
        if(ator.proprietario()) return;
        var d=ator.delegacao();
        boolean allowed=switch(acao) {
            case "consultar" -> d.consultar(); case "cadastrar" -> d.cadastrar();
            case "editar" -> d.editar(); case "ativar" -> d.ativar();
            case "desativar" -> d.desativar(); case "associar" -> d.associar(); default -> false;
        };
        if(!allowed) throw erro(HttpStatus.FORBIDDEN,"Operação não autorizada");
    }
    void funcaoPermitida(Administrador ator,String funcao) {
        if(!COMUNS.contains(funcao) && !funcao.equals("GERENTE")) throw erro(HttpStatus.BAD_REQUEST,"Função inválida para gestão de funcionários");
        if(!ator.proprietario() && (!COMUNS.contains(funcao) || !ator.delegacao().funcoes().contains(funcao)))
            throw erro(HttpStatus.FORBIDDEN,"Função fora dos limites administrativos");
    }
    void alvo(Administrador ator,Funcionario target) {
        if(target.funcao().equals("PROPRIETARIO")) throw erro(HttpStatus.FORBIDDEN,"O acesso do proprietário não é administrado neste módulo");
        funcaoPermitida(ator,target.funcao());
    }
    void somenteProprietario(Administrador ator) {
        if(!ator.proprietario()) throw erro(HttpStatus.FORBIDDEN,"Somente o proprietário pode autorizar gerentes");
    }
    Capacidades capacidades(Administrador ator) {
        if(ator.proprietario()) return new Capacidades(true,true,true,true,true,true,true,List.of("ATENDENTE","CAIXA","COZINHA","BAR","GERENTE"));
        var d=ator.delegacao();
        return new Capacidades(false,d.consultar(),d.cadastrar(),d.editar(),d.ativar(),d.desativar(),d.associar(),d.funcoes());
    }
}
