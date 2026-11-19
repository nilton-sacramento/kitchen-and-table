package com.kitchentable.mesas;

import static com.kitchentable.mesas.MesaDtos.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class MesaController {
  private final MesaService service;

  public MesaController(MesaService s) {
    service = s;
  }

  private static final String ADMIN = "/api/v1/estabelecimentos/{unidade}/mesas",
      OP = "/api/v1/operacional/estabelecimentos/{unidade}/mesas";

  @GetMapping({ADMIN, OP})
  public List<Mesa> listar(
      @PathVariable UUID unidade,
      Authentication a,
      @RequestHeader(value = "X-Operador-Vinculo", required = false) UUID esperado) {
    return service.listar(unidade, a, esperado);
  }

  @GetMapping({ADMIN + "/{id}", OP + "/{id}"})
  public Mesa detalhe(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader(value = "X-Operador-Vinculo", required = false) UUID esperado) {
    return service.buscar(unidade, id, a, esperado);
  }

  @GetMapping(ADMIN + "/{id}/historico")
  public List<Uso> historico(@PathVariable UUID unidade, @PathVariable UUID id, Authentication a) {
    return service.historico(unidade, id, a);
  }

  @PostMapping(ADMIN)
  public Mesa criar(@PathVariable UUID unidade, Authentication a, @Valid @RequestBody Criacao dto) {
    return service.criar(unidade, a, dto);
  }

  @PatchMapping(ADMIN + "/{id}")
  public Mesa editar(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @Valid @RequestBody Edicao dto) {
    return service.editar(unidade, id, a, dto);
  }

  @PatchMapping(ADMIN + "/{id}/situacao")
  public Mesa situacao(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @Valid @RequestBody Situacao dto) {
    return service.situacao(unidade, id, a, dto);
  }
}
