package com.kitchentable.externos;

import static com.kitchentable.externos.ExternoDtos.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operacional/estabelecimentos/{unidade}/externos")
public class ExternoController {
  private final ExternoService service;

  public ExternoController(ExternoService s) {
    service = s;
  }

  @GetMapping
  public List<Atendimento> listar(
      @PathVariable UUID unidade,
      @RequestParam Tipo tipo,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID actor) {
    return service.listar(unidade, tipo, a, actor);
  }

  @GetMapping("/{id}")
  public Atendimento buscar(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID actor) {
    return service.buscar(unidade, id, a, actor);
  }

  @GetMapping("/{id}/historico")
  public List<Evento> historico(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID actor) {
    return service.historico(unidade, id, a, actor);
  }

  @GetMapping("/operacoes/{key}")
  public Criado operacao(
      @PathVariable UUID unidade,
      @PathVariable UUID key,
      @RequestParam Tipo tipo,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID actor) {
    return service.operacao(unidade, tipo, key, a, actor);
  }

  @PostMapping
  public Criado criar(
      @PathVariable UUID unidade,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID actor,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Criacao d) {
    return service.criar(unidade, a, actor, key, d);
  }

  @PostMapping("/{id}/estado")
  public Atendimento estado(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID actor,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Transicao d) {
    return service.transicionar(unidade, id, a, actor, key, d);
  }
}
