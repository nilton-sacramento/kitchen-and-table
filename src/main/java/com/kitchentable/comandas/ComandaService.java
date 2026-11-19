package com.kitchentable.comandas;

import static com.kitchentable.comandas.ComandaDtos.*;

import com.kitchentable.funcionarios.*;
import com.kitchentable.mesas.MesaRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class ComandaService {
  private final ComandaRepository repo;
  private final MesaRepository mesas;
  private final AcessoAtendimentoService acesso;
  private final AuditoriaAdministrativaService audit;

  public ComandaService(
      ComandaRepository r,
      MesaRepository m,
      AcessoAtendimentoService a,
      AuditoriaAdministrativaService audit) {
    repo = r;
    mesas = m;
    acesso = a;
    this.audit = audit;
  }

  public List<Comanda> listar(UUID u, Authentication a, UUID esperado, Estado estado) {
    acesso.operador(u, a, esperado, "COMANDA_CONSULTAR");
    return repo.listar(u, estado);
  }

  public Comanda buscar(UUID u, UUID id, Authentication a, UUID esperado) {
    acesso.operador(u, a, esperado, "COMANDA_CONSULTAR");
    return repo.buscar(u, id, false);
  }

  public List<Evento> historico(UUID u, UUID id, Authentication a, UUID esperado) {
    acesso.operador(u, a, esperado, "COMANDA_CONSULTAR");
    repo.buscar(u, id, false);
    return repo.historico(u, id);
  }

  public Comanda abrir(UUID u, Authentication a, UUID esperado, UUID chave, Abertura dto) {
    var ator = acesso.operador(u, a, esperado, "ATENDIMENTO_ABRIR", true);
    if (chave == null || dto.modalidade() == null)
      throw erro(HttpStatus.BAD_REQUEST, "Informe modalidade e chave de abertura");
    if ((dto.modalidade() == Modalidade.MESA) != (dto.mesaId() != null))
      throw erro(HttpStatus.BAD_REQUEST, "Comanda de mesa exige mesa; balcao nao usa mesa fisica");
    String cliente = texto(dto.cliente(), 120);
    String hash =
        hash(
            dto.modalidade()
                + "\n"
                + dto.mesaId()
                + "\n"
                + (cliente == null ? "0:" : "1:" + cliente));
    UUID anterior = repo.repeticao(u, chave, ator.vinculo(), hash);
    if (anterior != null) return repo.buscar(u, anterior, false);
    if (dto.mesaId() != null) {
      var mesa = mesas.buscar(u, dto.mesaId(), true);
      if (!mesa.ativo() || mesa.comandaAtiva() != null)
        throw erro(HttpStatus.CONFLICT, "Mesa indisponivel ou ja ocupada. Atualize a listagem.");
    }
    var c = repo.abrir(u, ator.vinculo(), dto, cliente);
    repo.memorizar(u, chave, ator.vinculo(), hash, c.id());
    evento(u, ator, "COMANDA_ABERTA", c.id(), null, c);
    return c;
  }

  public Comanda finalizar(
      UUID u, UUID id, Authentication a, UUID esperado, Transicao dto, boolean cancelar) {
    var ator =
        acesso.operador(u, a, esperado, cancelar ? "COMANDA_CANCELAR" : "COMANDA_ENCERRAR", true);
    var ref = repo.buscar(u, id, false);
    // Mesma ordem da abertura: mesa antes da comanda.
    if (ref.mesaId() != null) mesas.buscar(u, ref.mesaId(), true);
    var antes = repo.buscar(u, id, true);
    if (dto.versao() == null || dto.versao() != antes.versao() || !antes.estado().equals("ABERTA"))
      throw erro(
          HttpStatus.CONFLICT, "Comanda ja alterada ou finalizada. Atualize antes de continuar.");
    String motivo = texto(dto.justificativa(), 500);
    if (cancelar && motivo == null)
      throw erro(HttpStatus.BAD_REQUEST, "Informe a justificativa do cancelamento");
    // Latch de consumo e contrato fail-closed: habilitar pedidos/financeiro exige revisar a
    // integracao.
    if (antes.teveConsumo()
        || antes.possuiPendencias()
        || repo.possuiPedidos(u, id)
        || repo.integracaoConsumoHabilitada())
      throw erro(
          HttpStatus.CONFLICT,
          "Encerramento operacional indisponivel: ha consumo, pendencias ou integracao financeira a"
              + " validar.");
    var c =
        repo.finalizar(
            u, id, ator.vinculo(), cancelar ? Estado.CANCELADA : Estado.ENCERRADA, motivo);
    evento(
        u,
        ator,
        cancelar ? "COMANDA_CANCELADA" : "COMANDA_ENCERRADA_OPERACIONALMENTE",
        id,
        antes,
        c);
    return c;
  }

  private void evento(
      UUID u, AcessoAtendimentoService.Ator a, String tipo, UUID id, Object antes, Object depois) {
    audit.registrarOperacao(
        u, a.usuario(), a.vinculo(), a.nome(), tipo, "COMANDA", id, antes, depois);
  }

  private String texto(String s, int max) {
    if (s == null) return null;
    String n = s.strip().replaceAll("\\s+", " ");
    if (n.isEmpty()) return null;
    if (n.length() > max) throw erro(HttpStatus.BAD_REQUEST, "Texto acima do limite permitido");
    return n;
  }

  private String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private ResponseStatusException erro(HttpStatus s, String m) {
    return new ResponseStatusException(s, m);
  }
}
