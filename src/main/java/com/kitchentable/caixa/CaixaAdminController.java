package com.kitchentable.caixa;

import static com.kitchentable.caixa.CaixaDtos.*;

import com.kitchentable.pagamentos.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/estabelecimentos/{unidade}/caixa")
public class CaixaAdminController {
  private final CaixaService caixa;
  private final FechamentoCaixaService fechamento;
  private final AutorizacaoFinanceiraService auth;

  public CaixaAdminController(
      CaixaService c, FechamentoCaixaService f, AutorizacaoFinanceiraService a) {
    caixa = c;
    fechamento = f;
    auth = a;
  }

  @GetMapping("/operacoes/{key}")
  public Map<String, Object> operacao(
      @PathVariable UUID unidade, @PathVariable UUID key, Authentication a) {
    return caixa.consultarOperacaoAdmin(unidade, key, a);
  }

  @GetMapping("/conferencias/{id}")
  public Conferencia conferencia(
      @PathVariable UUID unidade, @PathVariable UUID id, Authentication a) {
    return fechamento.conferenciaAdmin(unidade, id, a);
  }

  @GetMapping("/capacidades")
  public Map<String, Boolean> capacidades(@PathVariable UUID unidade, Authentication a) {
    return caixa.capacidadesAdmin(unidade, a);
  }

  @GetMapping("/politica")
  public Politica politica(@PathVariable UUID unidade, Authentication a) {
    return caixa.politica(unidade, a);
  }

  @PutMapping("/politica")
  public Politica configurar(
      @PathVariable UUID unidade, Authentication a, @Valid @RequestBody Configuracao d) {
    return caixa.configurar(unidade, a, d);
  }

  @GetMapping("/candidatos")
  public List<Operador> candidatos(@PathVariable UUID unidade, Authentication a) {
    return caixa.candidatosAdmin(unidade, a);
  }

  @GetMapping("/turnos")
  public List<Turno> listar(@PathVariable UUID unidade, Authentication a) {
    return caixa.listarAdmin(unidade, a);
  }

  @GetMapping("/turnos/{id}")
  public Resumo resumo(@PathVariable UUID unidade, @PathVariable UUID id, Authentication a) {
    return caixa.resumoAdmin(unidade, id, a);
  }

  @PutMapping("/turnos/{id}/equipe")
  public Turno equipe(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @Valid @RequestBody Equipe d) {
    return caixa.equipeAdmin(unidade, id, a, d);
  }

  @PostMapping("/turnos/{id}/reabrir")
  public Turno reabrir(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Reabrir d) {
    return fechamento.reabrir(unidade, id, a, key, d);
  }

  @GetMapping("/turnos/{id}/relatorios")
  public List<Fechamento> historico(
      @PathVariable UUID unidade, @PathVariable UUID id, Authentication a) {
    return fechamento.historicoAdmin(unidade, id, a);
  }

  @GetMapping("/autorizacoes")
  public List<Autorizacao> autorizacoes(@PathVariable UUID unidade, Authentication a) {
    return auth.listarAdmin(unidade, a);
  }

  @PostMapping("/autorizacoes/{id}/decisao")
  public Autorizacao decidir(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @Valid @RequestBody Autorizar d) {
    return auth.decidir(unidade, id, a, d);
  }
}
