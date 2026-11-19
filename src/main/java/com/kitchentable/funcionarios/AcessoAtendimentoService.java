package com.kitchentable.funcionarios;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Fachada da autorizacao existente. Deve participar da transacao da operacao. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AcessoAtendimentoService {
  public record Ator(UUID usuario, UUID vinculo, String nome) {}

  private final FuncionarioRepository repo;
  private final PermissoesService permissoes;
  private final OperacionalService operacional;

  public AcessoAtendimentoService(
      FuncionarioRepository r, PermissoesService p, OperacionalService o) {
    repo = r;
    permissoes = p;
    operacional = o;
  }

  public Ator administrador(UUID unidade, Authentication a, boolean escrita) {
    if (a == null || !a.isAuthenticated()) throw erro(HttpStatus.UNAUTHORIZED, "Entre novamente");
    if (a.getAuthorities().stream().noneMatch(x -> x.getAuthority().equals("ROLE_USER")))
      throw erro(HttpStatus.FORBIDDEN, "Exige acesso administrativo");
    if (escrita) repo.bloquearUnidades(unidade);
    else repo.validarUnidade(unidade);
    var ator = repo.ator(unidade, a.getName());
    if (!Set.of("PROPRIETARIO", "GERENTE").contains(ator.funcao()))
      throw erro(HttpStatus.FORBIDDEN, "Administracao de mesas nao autorizada");
    permissoes.exigirExistente(ator.vinculo(), "MESA_GERENCIAR");
    var f = repo.buscar(unidade, ator.vinculo());
    return new Ator(ator.id(), ator.vinculo(), f.nome());
  }

  public Ator operador(UUID unidade, Authentication a, UUID esperado, String codigo) {
    return operador(unidade, a, esperado, codigo, false);
  }

  public Ator operador(
      UUID unidade, Authentication a, UUID esperado, String codigo, boolean atividade) {
    if (a == null || !a.isAuthenticated()) throw erro(HttpStatus.UNAUTHORIZED, "Entre novamente");
    if (!(a.getPrincipal() instanceof OperadorPrincipal p))
      throw erro(HttpStatus.FORBIDDEN, "Atendimento exige identificacao e PIN");
    if (esperado == null) throw erro(HttpStatus.BAD_REQUEST, "Informe o contexto do operador");
    // O header e so uma precondicao de tela. A identidade SEMPRE vem do principal.
    if (!unidade.equals(p.estabelecimentoId()) || !esperado.equals(p.vinculoId()))
      throw erro(HttpStatus.FORBIDDEN, "O contexto do operador mudou. Identifique-se novamente.");
    var c = operacional.contexto(p, atividade);
    if (c == null) throw erro(HttpStatus.LOCKED, "Acesso bloqueado. Informe seu PIN novamente.");
    if (c.permissoes().stream()
        .noneMatch(x -> x.permissao().codigo().equals(codigo) && x.efetiva()))
      throw erro(HttpStatus.FORBIDDEN, "Seu acesso nao permite esta operacao.");
    return new Ator(p.usuarioId(), p.vinculoId(), c.nome());
  }

  private ResponseStatusException erro(HttpStatus s, String m) {
    return new ResponseStatusException(s, m);
  }
}
