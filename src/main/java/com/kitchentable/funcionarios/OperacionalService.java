package com.kitchentable.funcionarios;

import java.util.*;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.HttpStatus;
import static com.kitchentable.funcionarios.OperacionalDtos.*;
import static com.kitchentable.funcionarios.FuncionarioRepository.erro;

@Service
@Transactional
public class OperacionalService {
    private final FuncionarioRepository repo;
    private final PermissoesService permissoes;
    private final AuditoriaAdministrativaService audit;
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(12);
    private final String dummy=encoder.encode(UUID.randomUUID().toString());
    private record Credencial(String hash,int falhas,boolean bloqueado,String motivo,long versao){}
    public OperacionalService(FuncionarioRepository repo,PermissoesService permissoes,AuditoriaAdministrativaService audit){this.repo=repo;this.permissoes=permissoes;this.audit=audit;}
    private boolean compartilhar(UUID... unidades){
        for(UUID u:new TreeSet<>(Arrays.asList(unidades))){if(repo.jdbc().query("SELECT id FROM estabelecimentos WHERE id=? FOR SHARE",(rs,i)->rs.getObject(1,UUID.class),u).isEmpty())return false;}return true;
    }
    private Credencial credencial(UUID unidade,UUID id,boolean exclusiva){
        var rows=repo.jdbc().query("SELECT pin_hash,falhas,bloqueado,motivo,versao FROM credenciais_operacionais WHERE estabelecimento_id=? AND vinculo_id=? FOR "+(exclusiva?"UPDATE":"SHARE"),(rs,i)->new Credencial(rs.getString(1),rs.getInt(2),rs.getBoolean(3),rs.getString(4),rs.getLong(5)),unidade,id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private List<PermissoesDtos.Efetiva> efetivas(UUID unidade,UUID id){return permissoes.operacionais(unidade,id);}
    private boolean acesso(UUID unidade,UUID id){
        return efetivas(unidade,id).stream().anyMatch(p->p.permissao().codigo().equals("ESTABELECIMENTO_VISUALIZAR")&&p.efetiva());
    }
    private FuncionarioRepository.Pessoa admin(UUID unidade,String email,String codigo){return admin(unidade,email,codigo,true);}
    private FuncionarioRepository.Pessoa admin(UUID unidade,String email,String codigo,boolean escrita){
        if(escrita)repo.bloquearUnidades(unidade);else if(!compartilhar(unidade))throw erro(HttpStatus.NOT_FOUND,"Estabelecimento indisponivel");
        repo.validarUnidade(unidade);var a=repo.ator(unidade,email);
        if(!a.funcao().equals("PROPRIETARIO")&&!a.funcao().equals("GERENTE"))throw erro(HttpStatus.FORBIDDEN,"Administracao de seguranca nao autorizada");
        permissoes.exigirExistente(a.vinculo(),codigo);return a;
    }
    private FuncionarioDtos.Funcionario alvo(UUID unidade,UUID id,FuncionarioRepository.Pessoa a){
        var f=repo.buscar(unidade,id);
        if(f.funcao().equals("PROPRIETARIO"))throw erro(HttpStatus.FORBIDDEN,"Proprietario utiliza a autenticacao administrativa");
        if(!a.funcao().equals("PROPRIETARIO")&&(a.vinculo().equals(id)||!AutorizacaoAdministrativaService.COMUNS.contains(f.funcao())||!repo.delegacao(a.vinculo(),0).funcoes().contains(f.funcao())))throw erro(HttpStatus.FORBIDDEN,"Funcionario fora dos limites de seguranca recebidos");
        return f;
    }
    private void evento(UUID unidade,UUID executor,UUID vinculoExecutor,String tipo,UUID alvo,Object antes,Object depois){audit.registrar(unidade,executor,vinculoExecutor,tipo,alvo,antes,depois);}
    private void eventoOperador(OperadorPrincipal p,String tipo,Object dados){evento(p.estabelecimentoId(),p.usuarioId(),p.vinculoId(),tipo,p.vinculoId(),null,dados);}
    private int segundos(UUID unidade){var values=repo.jdbc().queryForList("SELECT inatividade_segundos FROM politicas_operacionais WHERE estabelecimento_id=?",Integer.class,unidade);return values.isEmpty()?300:values.get(0);}
    public Capacidades capacidades(UUID unidade,String email){
        repo.validarUnidade(unidade);var a=repo.ator(unidade,email);boolean perfil=Set.of("PROPRIETARIO","GERENTE").contains(a.funcao());
        return new Capacidades(perfil&&permissoes.podeExistente(a.vinculo(),"PIN_GERENCIAR"),perfil&&permissoes.podeExistente(a.vinculo(),"OPERACIONAL_DESBLOQUEAR"),perfil&&permissoes.podeExistente(a.vinculo(),"INATIVIDADE_CONFIGURAR"),perfil&&permissoes.podeExistente(a.vinculo(),"SEGURANCA_EVENTOS_CONSULTAR"));
    }
    public Estado estado(UUID unidade,UUID id,String email){var ator=repo.ator(unidade,email);var a=admin(unidade,email,permissoes.podeExistente(ator.vinculo(),"PIN_GERENCIAR")?"PIN_GERENCIAR":"OPERACIONAL_DESBLOQUEAR",false);alvo(unidade,id,a);return estado(unidade,id);}
    private Estado estado(UUID unidade,UUID id){
        var f=repo.buscar(unidade,id);var c=credencial(unidade,id,false);
        return new Estado(id,f.nome(),c!=null,c!=null&&c.bloqueado(),c==null?null:c.motivo(),c==null?3:3-c.falhas(),f.versao());
    }
    public Estado definir(UUID unidade,UUID id,String email,Definicao dto){
        var a=admin(unidade,email,"PIN_GERENCIAR");var f=alvo(unidade,id,a);
        if(dto.pin()==null||!dto.pin().matches("[0-9]{6}")||dto.versao()==null||dto.versao()<0)throw erro(HttpStatus.BAD_REQUEST,"Informe PIN numerico com seis digitos e a versao do cadastro");
        repo.verificarVersao(f,dto.versao());var anterior=estado(unidade,id);var c=credencial(unidade,id,true);
        if(c!=null&&c.bloqueado())permissoes.exigirExistente(a.vinculo(),"OPERACIONAL_DESBLOQUEAR");
        String hash=encoder.encode(dto.pin());
        repo.jdbc().update("INSERT INTO credenciais_operacionais(vinculo_id,estabelecimento_id,pin_hash) VALUES(?,?,?) ON CONFLICT(vinculo_id) DO UPDATE SET pin_hash=EXCLUDED.pin_hash,falhas=0,bloqueado=false,motivo=NULL,versao=credenciais_operacionais.versao+1,atualizado_em=clock_timestamp()",id,unidade,hash);
        invalidar(id,true);versao(unidade,id);
        evento(unidade,a.id(),a.vinculo(),c==null?"OPERACIONAL_PIN_DEFINIDO":"OPERACIONAL_PIN_REDEFINIDO",id,anterior,estado(unidade,id));
        if(anterior.bloqueado())evento(unidade,a.id(),a.vinculo(),"OPERACIONAL_DESBLOQUEADO",id,Map.of("motivo","REDEFINICAO_AUTORIZADA"),Map.of("bloqueado",false));
        return estado(unidade,id);
    }
    public Estado bloquear(UUID unidade,UUID id,String email,Bloqueio dto){
        var a=admin(unidade,email,"OPERACIONAL_DESBLOQUEAR");var f=alvo(unidade,id,a);repo.verificarVersao(f,dto.versao());
        var c=credencial(unidade,id,true);if(c==null)throw erro(HttpStatus.CONFLICT,"Defina o PIN inicial antes de administrar o bloqueio");
        var anterior=estado(unidade,id);
        repo.jdbc().update("UPDATE credenciais_operacionais SET bloqueado=?,falhas=0,motivo=?,versao=versao+1,atualizado_em=clock_timestamp() WHERE vinculo_id=? AND estabelecimento_id=?",dto.bloqueado(),dto.bloqueado()?"ADMINISTRATIVO":null,id,unidade);
        invalidar(id,false);versao(unidade,id);evento(unidade,a.id(),a.vinculo(),dto.bloqueado()?"OPERACIONAL_BLOQUEADO_ADMIN":"OPERACIONAL_DESBLOQUEADO",id,anterior,estado(unidade,id));return estado(unidade,id);
    }
    private void invalidar(UUID id,boolean encerrar){repo.jdbc().update("UPDATE sessoes_operacionais SET bloqueada=true,encerrada=encerrada OR ? WHERE vinculo_id=?",encerrar,id);}
    private void versao(UUID unidade,UUID id){repo.jdbc().update("UPDATE usuarios_estabelecimentos SET versao=versao+1,atualizado_em=clock_timestamp() WHERE id=? AND estabelecimento_id=?",id,unidade);}
    public Configuracao politica(UUID unidade,String email){admin(unidade,email,"INATIVIDADE_CONFIGURAR",false);return politica(unidade);}
    private Configuracao politica(UUID unidade){var rows=repo.jdbc().query("SELECT inatividade_segundos,versao FROM politicas_operacionais WHERE estabelecimento_id=?",(rs,i)->new Configuracao(rs.getInt(1),rs.getLong(2)),unidade);return rows.isEmpty()?new Configuracao(300,0):rows.get(0);}
    public Configuracao politica(UUID unidade,String email,Politica dto){
        var a=admin(unidade,email,"INATIVIDADE_CONFIGURAR");var antes=politica(unidade);
        if(antes.versao()!=dto.versao())throw erro(HttpStatus.CONFLICT,"Configuracao alterada; atualize os dados");
        if(dto.segundos()<30||dto.segundos()>3600)throw erro(HttpStatus.BAD_REQUEST,"Inatividade deve ficar entre 30 e 3600 segundos");
        repo.jdbc().update("INSERT INTO politicas_operacionais(estabelecimento_id,inatividade_segundos,versao) VALUES(?,?,1) ON CONFLICT(estabelecimento_id) DO UPDATE SET inatividade_segundos=EXCLUDED.inatividade_segundos,versao=politicas_operacionais.versao+1",unidade,dto.segundos());
        // Nunca estender uma sessao vencida: novo prazo maior so vale apos atividade valida.
        repo.jdbc().update("UPDATE sessoes_operacionais SET expira_em=LEAST(expira_em,ultimo_uso+(? * interval '1 second')) WHERE estabelecimento_id=? AND NOT encerrada",dto.segundos(),unidade);
        evento(unidade,a.id(),a.vinculo(),"OPERACIONAL_INATIVIDADE_CONFIGURADA",null,antes,politica(unidade));return politica(unidade);
    }
    public List<Evento> eventos(UUID unidade,String email){
        var a=admin(unidade,email,"SEGURANCA_EVENTOS_CONSULTAR",false);
        return repo.jdbc().query("SELECT id,tipo_evento,entidade_id,usuario_id,ocorrido_em FROM auditoria_eventos WHERE estabelecimento_id=? AND tipo_evento LIKE 'OPERACIONAL_%' ORDER BY ocorrido_em DESC,id LIMIT 100",(rs,i)->new Evento(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getTimestamp(5).toInstant()),unidade).stream()
            .filter(e->a.funcao().equals("PROPRIETARIO")||(e.funcionario()!=null&&visivel(unidade,e.funcionario(),a))).toList();
    }
    private boolean visivel(UUID unidade,UUID id,FuncionarioRepository.Pessoa a){try{alvo(unidade,id,a);return true;}catch(org.springframework.web.server.ResponseStatusException e){return false;}}
    /** Resultado nulo e negacao uniforme; falhas/auditoria precisam confirmar antes do HTTP 401. */
    public OperadorPrincipal entrar(Entrada dto,OperadorPrincipal anterior){
        String candidate=dto.pin()!=null&&dto.pin().matches("[0-9]{6}")?dto.pin():"000000";
        UUID unidade,id;try{unidade=UUID.fromString(dto.estabelecimentoId());id=UUID.fromString(dto.identificacao());}catch(Exception e){encoder.matches(candidate,dummy);return null;}
        if(!compartilhar(anterior==null?new UUID[]{unidade}:new UUID[]{unidade,anterior.estabelecimentoId()})){encoder.matches(candidate,dummy);return null;}
        var c=credencial(unidade,id,true);var pessoas=repo.jdbc().query("SELECT u.id,f.codigo FROM usuarios_estabelecimentos ue JOIN usuarios u ON u.id=ue.usuario_id JOIN estabelecimentos e ON e.id=ue.estabelecimento_id JOIN funcoes f ON f.id=ue.funcao_id WHERE ue.id=? AND ue.estabelecimento_id=? AND ue.ativo AND u.ativo AND e.ativo AND f.codigo<>'PROPRIETARIO'",(rs,i)->Map.entry(rs.getObject(1,UUID.class),rs.getString(2)),id,unidade);
        if(c==null||pessoas.isEmpty()||!acesso(unidade,id)){encoder.matches(candidate,dummy);return null;}
        UUID usuario=pessoas.get(0).getKey();boolean correto=encoder.matches(candidate,c.bloqueado()?dummy:c.hash())&&dto.pin()!=null&&dto.pin().matches("[0-9]{6}");
        if(c.bloqueado()){evento(unidade,null,null,"OPERACIONAL_ACESSO_BLOQUEADO",id,null,Map.of("resultado","NEGADO"));return null;}
        if(!correto){
            int falhas=c.falhas()+1;boolean bloqueado=falhas>=3;
            repo.jdbc().update("UPDATE credenciais_operacionais SET falhas=?,bloqueado=?,motivo=?,versao=versao+CASE WHEN ? THEN 1 ELSE 0 END,atualizado_em=clock_timestamp() WHERE vinculo_id=?",falhas,bloqueado,bloqueado?"TENTATIVAS":null,bloqueado,id);
            evento(unidade,null,null,"OPERACIONAL_PIN_INCORRETO",id,null,Map.of("resultado","NEGADO"));
            if(bloqueado){invalidar(id,false);evento(unidade,null,null,"OPERACIONAL_TENTATIVAS_BLOQUEADO",id,null,Map.of("notificacaoPendente",true,"resultado","BLOQUEADO"));}return null;
        }
        repo.jdbc().update("UPDATE credenciais_operacionais SET falhas=0,atualizado_em=clock_timestamp() WHERE vinculo_id=?",id);
        if(anterior!=null)encerrarInterno(anterior,"OPERACIONAL_TROCA_OPERADOR");
        UUID sessao=UUID.randomUUID();
        repo.jdbc().update("INSERT INTO sessoes_operacionais(id,vinculo_id,estabelecimento_id,versao_credencial,expira_em) VALUES(?,?,?,?,clock_timestamp()+(? * interval '1 second'))",sessao,id,unidade,c.versao(),segundos(unidade));
        var p=new OperadorPrincipal(usuario,id,unidade,sessao);eventoOperador(p,"OPERACIONAL_ENTRADA",Map.of("resultado","DESBLOQUEADO"));return p;
    }
    public Contexto contexto(OperadorPrincipal p,boolean atividade){
        if(!compartilhar(p.estabelecimentoId()))return null;
        var c=credencial(p.estabelecimentoId(),p.vinculoId(),false);
        var rows=repo.jdbc().query("SELECT versao_credencial,bloqueada,encerrada,expira_em FROM sessoes_operacionais WHERE id=? AND vinculo_id=? AND estabelecimento_id=? AND EXISTS(SELECT 1 FROM usuarios_estabelecimentos ue WHERE ue.id=sessoes_operacionais.vinculo_id AND ue.usuario_id=?) FOR UPDATE",(rs,i)->Map.of("versao",rs.getLong(1),"bloqueada",rs.getBoolean(2),"encerrada",rs.getBoolean(3),"expira",rs.getTimestamp(4).toInstant()),p.sessaoId(),p.vinculoId(),p.estabelecimentoId(),p.usuarioId());
        if(rows.isEmpty())return null;var s=rows.get(0);
        if(Boolean.TRUE.equals(s.get("encerrada"))||Boolean.TRUE.equals(s.get("bloqueada")))return null;
        boolean vencida=!((Instant)s.get("expira")).isAfter(Instant.now());
        if(c==null||c.bloqueado()||c.versao()!=(Long)s.get("versao")||vencida||!acesso(p.estabelecimentoId(),p.vinculoId())){
            repo.jdbc().update("UPDATE sessoes_operacionais SET bloqueada=true WHERE id=?",p.sessaoId());
            eventoOperador(p,vencida?"OPERACIONAL_BLOQUEIO_INATIVIDADE":"OPERACIONAL_SESSAO_REVOGADA",Map.of("resultado","BLOQUEADO"));return null;
        }
        if(atividade)repo.jdbc().update("UPDATE sessoes_operacionais SET ultimo_uso=clock_timestamp(),expira_em=clock_timestamp()+(? * interval '1 second') WHERE id=?",segundos(p.estabelecimentoId()),p.sessaoId());
        var f=repo.buscar(p.estabelecimentoId(),p.vinculoId());var prazo=repo.jdbc().queryForObject("SELECT expira_em FROM sessoes_operacionais WHERE id=?",java.sql.Timestamp.class,p.sessaoId()).toInstant();
        String unidade=repo.jdbc().queryForObject("SELECT nome FROM estabelecimentos WHERE id=?",String.class,p.estabelecimentoId());
        return new Contexto(p.usuarioId(),p.vinculoId(),p.estabelecimentoId(),f.nome(),unidade,prazo,Math.max(0,Duration.between(Instant.now(),prazo).toMillis()),efetivas(p.estabelecimentoId(),p.vinculoId()).stream().filter(e->!e.permissao().categoria().equals("ADMINISTRATIVA")).toList());
    }
    public void bloquearSessao(OperadorPrincipal p){if(compartilhar(p.estabelecimentoId())){
        var rows=repo.jdbc().query("SELECT expira_em FROM sessoes_operacionais WHERE id=? AND vinculo_id=? AND NOT bloqueada AND NOT encerrada FOR UPDATE",(rs,i)->rs.getTimestamp(1).toInstant(),p.sessaoId(),p.vinculoId());
        if(!rows.isEmpty()){
            repo.jdbc().update("UPDATE sessoes_operacionais SET bloqueada=true WHERE id=?",p.sessaoId());
            eventoOperador(p,rows.get(0).isAfter(Instant.now())?"OPERACIONAL_BLOQUEIO_INTERFACE":"OPERACIONAL_BLOQUEIO_INATIVIDADE",Map.of("resultado","BLOQUEADO"));
        }
    }}
    public void encerrar(OperadorPrincipal p){if(compartilhar(p.estabelecimentoId()))encerrarInterno(p,"OPERACIONAL_SAIDA");}
    private void encerrarInterno(OperadorPrincipal p,String tipo){
        int changed=repo.jdbc().update("UPDATE sessoes_operacionais SET encerrada=true,bloqueada=true WHERE id=? AND vinculo_id=? AND NOT encerrada",p.sessaoId(),p.vinculoId());
        if(changed>0)eventoOperador(p,tipo,Map.of("resultado","ENCERRADA"));
    }
}