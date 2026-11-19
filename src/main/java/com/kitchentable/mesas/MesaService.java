package com.kitchentable.mesas;

import static com.kitchentable.mesas.MesaDtos.*;

import com.kitchentable.funcionarios.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class MesaService {
  private final MesaRepository repo;
  private final AcessoAtendimentoService acesso;
  private final AuditoriaAdministrativaService audit;

  public MesaService(
      MesaRepository r, AcessoAtendimentoService a, AuditoriaAdministrativaService audit) {
    repo = r;
    acesso = a;
    this.audit = audit;
  }

  private void consultar(UUID u, Authentication a, UUID esperado) {
    if (a != null && a.getPrincipal() instanceof OperadorPrincipal)
      acesso.operador(u, a, esperado, "MESA_CONSULTAR");
    else acesso.administrador(u, a, false);
  }

  public List<Mesa> listar(UUID u, Authentication a, UUID esperado) {
    consultar(u, a, esperado);
    return repo.listar(u);
  }

  public Mesa buscar(UUID u, UUID id, Authentication a, UUID esperado) {
    consultar(u, a, esperado);
    return repo.buscar(u, id, false);
  }

  public List<Uso> historico(UUID u, UUID id, Authentication a) {
    acesso.administrador(u, a, false);
    repo.buscar(u, id, false);
    return repo.historico(u, id);
  }

  public Mesa criar(UUID u, Authentication a, Criacao dto) {
    var ator = acesso.administrador(u, a, true);
    var mesa = repo.criar(u, nome(dto.identificacao()));
    evento(u, ator, "MESA_CRIADA", mesa.id(), null, mesa);
    return mesa;
  }

  public Mesa editar(UUID u, UUID id, Authentication a, Edicao dto) {
    var ator = acesso.administrador(u, a, true);
    var antes = repo.buscar(u, id, true);
    versao(antes, dto.versao());
    String nome = nome(dto.identificacao());
    if (nome.equals(antes.identificacao())) return antes;
    var depois = repo.editar(u, id, nome);
    evento(u, ator, "MESA_ALTERADA", id, antes, depois);
    return depois;
  }

  public Mesa situacao(UUID u, UUID id, Authentication a, Situacao dto) {
    var ator = acesso.administrador(u, a, true);
    var antes = repo.buscar(u, id, true);
    versao(antes, dto.versao());
    if (dto.ativo() == null) throw erro(HttpStatus.BAD_REQUEST, "Informe a situacao");
    if (!dto.ativo() && antes.comandaAtiva() != null)
      throw erro(
          HttpStatus.CONFLICT, "Mesa ocupada: finalize a comanda autorizada antes de desativar.");
    if (dto.ativo() == antes.ativo()) return antes;
    var depois = repo.situacao(u, id, dto.ativo());
    evento(u, ator, dto.ativo() ? "MESA_ATIVADA" : "MESA_DESATIVADA", id, antes, depois);
    return depois;
  }

  private void evento(
      UUID u, AcessoAtendimentoService.Ator a, String tipo, UUID id, Object antes, Object depois) {
    audit.registrarOperacao(u, a.usuario(), a.vinculo(), a.nome(), tipo, "MESA", id, antes, depois);
  }

  private String nome(String value) {
    if (value == null) throw erro(HttpStatus.BAD_REQUEST, "Informe a identificacao da mesa");
    String n = value.strip().replaceAll("\\s+", " ");
    if (n.isBlank() || n.length() > 40)
      throw erro(HttpStatus.BAD_REQUEST, "Identificacao deve ter de 1 a 40 caracteres");
    return n;
  }

  private void versao(Mesa m, Long v) {
    if (v == null || m.versao() != v)
      throw erro(HttpStatus.CONFLICT, "Mesa alterada por outra pessoa. Atualize antes de salvar.");
  }

  private ResponseStatusException erro(HttpStatus s, String m) {
    return new ResponseStatusException(s, m);
  }
}
