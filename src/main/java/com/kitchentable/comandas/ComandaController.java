package com.kitchentable.comandas;

import static com.kitchentable.comandas.ComandaDtos.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operacional/estabelecimentos/{unidade}/comandas")
public class ComandaController {
  private final ComandaService service;

  public ComandaController(ComandaService s) {
    service = s;
  }

  @GetMapping
  public List<Comanda> listar(
      @PathVariable UUID unidade,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID esperado,
      @RequestParam(required = false) Estado estado) {
    return service.listar(unidade, a, esperado, estado);
  }

  @GetMapping("/{id}")
  public Comanda detalhe(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID esperado) {
    return service.buscar(unidade, id, a, esperado);
  }

  @GetMapping("/{id}/historico")
  public List<Evento> historico(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID esperado) {
    return service.historico(unidade, id, a, esperado);
  }

  @PostMapping
  public Comanda abrir(
      @PathVariable UUID unidade,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID esperado,
      @RequestHeader("Idempotency-Key") UUID chave,
      @Valid @RequestBody Abertura dto) {
    return service.abrir(unidade, a, esperado, chave, dto);
  }

  @PostMapping("/{id}/encerrar")
  public Comanda encerrar(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID esperado,
      @Valid @RequestBody Transicao dto) {
    return service.finalizar(unidade, id, a, esperado, dto, false);
  }

  @PostMapping("/{id}/cancelar")
  public Comanda cancelar(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID esperado,
      @Valid @RequestBody Transicao dto) {
    return service.finalizar(unidade, id, a, esperado, dto, true);
  }
}
