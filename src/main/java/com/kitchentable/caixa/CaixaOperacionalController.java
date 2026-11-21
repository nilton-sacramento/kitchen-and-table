package com.kitchentable.caixa;

import static com.kitchentable.caixa.CaixaDtos.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operacional/estabelecimentos/{unidade}/caixa")
public class CaixaOperacionalController {
  private final CaixaService caixa;
  private final FechamentoCaixaService fechamento;
  private final AutorizacaoFinanceiraService auth;

  public CaixaOperacionalController(
      CaixaService c, FechamentoCaixaService f, AutorizacaoFinanceiraService a) {
    caixa = c;
    fechamento = f;
    auth = a;
  }

  @GetMapping("/operacoes/{key}")
  public Map<String, Object> operacao(
      @PathVariable UUID unidade,
      @PathVariable UUID key,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op) {
    return caixa.consultarOperacao(unidade, key, a, op);
  }

  @GetMapping("/politica")
  public Politica politica(
      @PathVariable UUID unidade, Authentication a, @RequestHeader("X-Operador-Vinculo") UUID op) {
    return caixa.politicaOperacional(unidade, a, op);
  }

  @GetMapping("/atual")
  public org.springframework.http.ResponseEntity<Turno> atual(
      @PathVariable UUID unidade, Authentication a, @RequestHeader("X-Operador-Vinculo") UUID op) {
    var current = caixa.atual(unidade, a, op);
    return current == null
        ? org.springframework.http.ResponseEntity.noContent().build()
        : org.springframework.http.ResponseEntity.ok(current);
  }

  @GetMapping("/turnos")
  public List<Turno> listar(
      @PathVariable UUID unidade, Authentication a, @RequestHeader("X-Operador-Vinculo") UUID op) {
    return caixa.listar(unidade, a, op);
  }

  @PostMapping("/turnos")
  public Turno abrir(
      @PathVariable UUID unidade,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Abertura d) {
    return caixa.abrir(unidade, a, op, key, d);
  }

  @GetMapping("/turnos/{id}")
  public Resumo resumo(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op) {
    return caixa.resumo(unidade, id, a, op);
  }

  @GetMapping("/candidatos")
  public List<Operador> candidatos(
      @PathVariable UUID unidade, Authentication a, @RequestHeader("X-Operador-Vinculo") UUID op) {
    return caixa.candidatosOperador(unidade, a, op);
  }

  @PutMapping("/turnos/{id}/equipe")
  public Turno equipe(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op,
      @Valid @RequestBody Equipe d) {
    return caixa.equipeOperador(unidade, id, a, op, d);
  }

  @PostMapping("/turnos/{id}/movimentos")
  public com.kitchentable.pagamentos.PagamentoDtos.Lancamento movimentar(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Movimento d) {
    return caixa.movimentar(unidade, id, a, op, key, d);
  }

  @PostMapping("/turnos/{id}/conferencias")
  public Conferencia conferir(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Conferir d) {
    return fechamento.conferir(unidade, id, a, op, key, d);
  }

  @PostMapping("/turnos/{id}/fechamento")
  public Fechamento fechar(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Fechar d) {
    return fechamento.fechar(unidade, id, a, op, key, d);
  }

  @GetMapping("/turnos/{id}/relatorios")
  public List<Fechamento> historico(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op) {
    return fechamento.historico(unidade, id, a, op);
  }

  @GetMapping("/autorizacoes")
  public List<Autorizacao> autorizacoes(
      @PathVariable UUID unidade, Authentication a, @RequestHeader("X-Operador-Vinculo") UUID op) {
    return auth.listarOperador(unidade, a, op);
  }

  @PostMapping("/autorizacoes")
  public Autorizacao solicitar(
      @PathVariable UUID unidade,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody SolicitarAutorizacao d) {
    return auth.solicitar(unidade, a, op, key, d);
  }
}
