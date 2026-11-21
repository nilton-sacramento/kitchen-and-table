package com.kitchentable.relatorios;

import com.kitchentable.caixa.CaixaDtos;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RelatorioFinanceiroController {
  private final RelatorioFinanceiroService service;

  public RelatorioFinanceiroController(RelatorioFinanceiroService s) {
    service = s;
  }

  @GetMapping("/estabelecimentos/{unidade}/relatorios-financeiros")
  public Map<String, Object> administrativo(
      @PathVariable UUID unidade,
      Authentication a,
      @RequestParam Instant inicio,
      @RequestParam Instant fim,
      @RequestParam(required = false) UUID funcionario) {
    return service.administrativo(unidade, a, inicio, fim, funcionario);
  }

  @GetMapping("/operacional/estabelecimentos/{unidade}/relatorios-financeiros")
  public Map<String, Object> operacional(
      @PathVariable UUID unidade,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op,
      @RequestParam Instant inicio,
      @RequestParam Instant fim,
      @RequestParam(required = false) UUID funcionario) {
    return service.operacional(unidade, a, op, inicio, fim, funcionario);
  }

  @GetMapping("/estabelecimentos/{unidade}/relatorios-financeiros/funcionarios")
  public List<CaixaDtos.Operador> funcionariosAdmin(@PathVariable UUID unidade, Authentication a) {
    return service.funcionarios(unidade, a, null);
  }

  @GetMapping("/operacional/estabelecimentos/{unidade}/relatorios-financeiros/funcionarios")
  public List<CaixaDtos.Operador> funcionarios(
      @PathVariable UUID unidade, Authentication a, @RequestHeader("X-Operador-Vinculo") UUID op) {
    return service.funcionarios(unidade, a, op);
  }

  @GetMapping("/financeiro/consolidado")
  public Map<String, Object> consolidado(
      Authentication a,
      @RequestParam List<UUID> unidades,
      @RequestParam Instant inicio,
      @RequestParam Instant fim) {
    return service.consolidado(unidades, a, inicio, fim);
  }
}
