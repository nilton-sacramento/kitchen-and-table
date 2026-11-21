package com.kitchentable.pagamentos;

import static com.kitchentable.pagamentos.PagamentoDtos.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PagamentoController {
  private final PagamentoService service;

  public PagamentoController(PagamentoService s) {
    service = s;
  }

  @GetMapping("/operacional/estabelecimentos/{unidade}/comandas/{comanda}/conta")
  public Conta conta(
      @PathVariable UUID unidade,
      @PathVariable UUID comanda,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op) {
    return service.consultar(unidade, comanda, a, op);
  }

  @GetMapping("/estabelecimentos/{unidade}/comandas/{comanda}/conta")
  public Conta contaAdmin(
      @PathVariable UUID unidade, @PathVariable UUID comanda, Authentication a) {
    return service.consultarAdmin(unidade, comanda, a);
  }

  @PostMapping(
      "/operacional/estabelecimentos/{unidade}/caixa/turnos/{turno}/comandas/{comanda}/pagamentos")
  public Lancamento receber(
      @PathVariable UUID unidade,
      @PathVariable UUID turno,
      @PathVariable UUID comanda,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Receber d) {
    return service.receber(unidade, turno, comanda, a, op, key, d);
  }

  @PostMapping(
      "/operacional/estabelecimentos/{unidade}/caixa/turnos/{turno}/comandas/{comanda}/descontos")
  public Lancamento desconto(
      @PathVariable UUID unidade,
      @PathVariable UUID turno,
      @PathVariable UUID comanda,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Desconto d) {
    return service.descontar(unidade, turno, comanda, a, op, key, d);
  }

  @PostMapping(
      "/operacional/estabelecimentos/{unidade}/caixa/turnos/{turno}/comandas/{comanda}/encerramento-financeiro")
  public Quitacao encerrar(
      @PathVariable UUID unidade,
      @PathVariable UUID turno,
      @PathVariable UUID comanda,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Encerrar d) {
    return service.encerrar(unidade, turno, comanda, a, op, key, d);
  }

  @PostMapping(
      "/operacional/estabelecimentos/{unidade}/caixa/turnos/{turno}/lancamentos/{id}/compensacao")
  public Lancamento compensar(
      @PathVariable UUID unidade,
      @PathVariable UUID turno,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody Compensar d) {
    return service.compensar(unidade, turno, id, a, op, key, d);
  }

  @PostMapping(
      "/operacional/estabelecimentos/{unidade}/caixa/turnos/{turno}/lancamentos/{id}/confirmacao-externa")
  public Lancamento confirmar(
      @PathVariable UUID unidade,
      @PathVariable UUID turno,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID op,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody ConfirmarExterno d) {
    return service.confirmarExterno(unidade, turno, id, a, op, key, d);
  }
}
