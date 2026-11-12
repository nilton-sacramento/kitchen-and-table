package com.kitchentable.funcionarios;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static com.kitchentable.funcionarios.FuncionarioDtos.*;
import static com.kitchentable.funcionarios.FuncionarioRepository.erro;

@Service
@Transactional
public class AssociacaoFuncionarioService {
    private final FuncionarioRepository repo;
    private final AutorizacaoAdministrativaService auth;
    private final AuditoriaAdministrativaService audit;
    private final FuncionarioService funcionarios;
    private final Duration validade;
    public AssociacaoFuncionarioService(FuncionarioRepository repo,AutorizacaoAdministrativaService auth,
        AuditoriaAdministrativaService audit,FuncionarioService funcionarios,
        @Value("${kt.associacoes.validade:PT15M}") Duration validade) {
        this.repo=repo;this.auth=auth;this.audit=audit;this.funcionarios=funcionarios;this.validade=validade;
        if(validade.isNegative()||validade.isZero()||validade.compareTo(Duration.ofHours(1))>0)
            throw new IllegalArgumentException("Validade de associação deve ser positiva e limitada a uma hora");
    }
    private record Pedido(UUID id,UUID usuario,UUID origem,UUID destino,UUID vinculoOrigem,UUID solicitante,Instant expira,Instant consumida) {}
    private Pedido pedido(UUID destino,UUID id) {
        var rows=repo.jdbc().query("SELECT * FROM associacoes_funcionarios WHERE destino_id=? AND id=?",(rs,i)->
            new Pedido(rs.getObject("id",UUID.class),rs.getObject("usuario_id",UUID.class),rs.getObject("origem_id",UUID.class),
                rs.getObject("destino_id",UUID.class),rs.getObject("vinculo_origem_id",UUID.class),rs.getObject("solicitante_id",UUID.class),
                rs.getTimestamp("expira_em").toInstant(),rs.getTimestamp("consumida_em")==null?null:rs.getTimestamp("consumida_em").toInstant()),destino,id);
        if(rows.isEmpty()) throw erro(HttpStatus.NOT_FOUND,"Solicitação não encontrada");
        return rows.get(0);
    }
    public Associacao solicitar(UUID origem,UUID vinculo,String email,PedidoAssociacao dto) {
        if(origem.equals(dto.destinoId())) throw erro(HttpStatus.BAD_REQUEST,"Selecione outra unidade");
        repo.bloquearUnidades(origem,dto.destinoId());var ator=auth.carregar(origem,email);auth.exigir(ator,"associar");
        var target=repo.buscar(origem,vinculo);auth.alvo(ator,target);
        if(!target.ativo()) throw erro(HttpStatus.CONFLICT,"Vínculo de origem inativo");
        var existing=repo.jdbc().query("SELECT id,expira_em FROM associacoes_funcionarios WHERE origem_id=? AND destino_id=? AND usuario_id=? AND solicitante_id=? AND consumida_em IS NULL AND expira_em>clock_timestamp() ORDER BY criado_em DESC LIMIT 1",
            (rs,i)->new Associacao(rs.getObject("id",UUID.class),origem,dto.destinoId(),target.nome(),rs.getTimestamp("expira_em").toInstant()),origem,dto.destinoId(),target.usuarioId(),ator.pessoa().id());
        if(!existing.isEmpty()) return existing.get(0);
        UUID id=UUID.randomUUID();Instant expira=Instant.now().plus(validade);
        repo.jdbc().update("INSERT INTO associacoes_funcionarios(id,usuario_id,origem_id,destino_id,vinculo_origem_id,solicitante_id,expira_em) VALUES(?,?,?,?,?,?,?)",
            id,target.usuarioId(),origem,dto.destinoId(),vinculo,ator.pessoa().id(),java.sql.Timestamp.from(expira));
        audit.registrar(origem,ator.pessoa().id(),ator.pessoa().vinculo(),"ASSOCIACAO_SOLICITADA",vinculo,null,Map.of("solicitacaoId",id,"destinoId",dto.destinoId(),"expiraEm",expira));
        return new Associacao(id,origem,dto.destinoId(),target.nome(),expira);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Associacao> listar(UUID destino,String email) {
        repo.validarUnidade(destino);var ator=auth.carregar(destino,email);auth.exigir(ator,"associar");
        var rows=repo.jdbc().query("SELECT a.id,a.origem_id,a.expira_em,COALESCE(ue.nome_exibicao,u.nome) nome,f.codigo FROM associacoes_funcionarios a " +
            "JOIN usuarios u ON u.id=a.usuario_id JOIN usuarios_estabelecimentos ue ON ue.id=a.vinculo_origem_id " +
            "JOIN funcoes f ON f.id=ue.funcao_id JOIN estabelecimentos e ON e.id=a.origem_id " +
            "WHERE a.destino_id=? AND a.consumida_em IS NULL AND a.expira_em>clock_timestamp() AND u.ativo AND ue.ativo AND e.ativo ORDER BY a.criado_em",
            (rs,i)->new AbstractMap.SimpleImmutableEntry<>(rs.getString("codigo"),new Associacao(rs.getObject("id",UUID.class),rs.getObject("origem_id",UUID.class),destino,rs.getString("nome"),rs.getTimestamp("expira_em").toInstant())),destino);
        return rows.stream().map(Map.Entry::getValue).toList();
    }
    public Funcionario aceitar(UUID destino,UUID id,String email,UUID key,Aceite dto) {
        // Validacao inicial evita consultar solicitacoes sem acesso ao destino.
        var inicial=auth.carregar(destino,email);auth.exigir(inicial,"associar");
        var inicialPedido=pedido(destino,id);
        repo.bloquearUnidades(inicialPedido.origem(),destino);
        var ator=auth.carregar(destino,email);auth.exigir(ator,"associar");auth.funcaoPermitida(ator,dto.funcao());
        Object fingerprint=List.of(id,dto);
        UUID replay=funcionarios.repeticao(destino,key,ator.pessoa().id(),"ASSOCIACAO",fingerprint);
        if(replay!=null) { var result=repo.buscar(destino,replay);auth.alvo(ator,result);return result; }
        var request=pedido(destino,id);
        Boolean valid=repo.jdbc().queryForObject("SELECT consumida_em IS NULL AND expira_em>clock_timestamp() FROM associacoes_funcionarios WHERE id=? AND destino_id=?",Boolean.class,id,destino);
        if(!Boolean.TRUE.equals(valid)) throw erro(HttpStatus.CONFLICT,"Solicitação expirada ou já utilizada");
        var origem=repo.buscar(request.origem(),request.vinculoOrigem());
        if(!origem.ativo() || !origem.usuarioId().equals(request.usuario())) throw erro(HttpStatus.CONFLICT,"Vínculo de origem indisponível");
        try {
            var emissor=auth.carregar(request.origem(),repo.emailAtivo(request.solicitante()));
            auth.exigir(emissor,"associar");auth.alvo(emissor,origem);
        } catch(ResponseStatusException e) { throw erro(HttpStatus.CONFLICT,"Autorização de origem revogada"); }
        var target=repo.vincular(destino,request.usuario(),origem.nome(),dto.funcao(),null,null);
        repo.jdbc().update("UPDATE associacoes_funcionarios SET consumida_em=clock_timestamp() WHERE id=? AND destino_id=?",id,destino);
        funcionarios.lembrar(destino,key,ator.pessoa().id(),"ASSOCIACAO",fingerprint,target.vinculoId());
        audit.registrar(destino,ator.pessoa().id(),ator.pessoa().vinculo(),"ASSOCIACAO_ACEITA",target.vinculoId(),null,Map.of("cadastro",target,"solicitacaoId",id,"origemId",request.origem()));
        return target;
    }
}
