package com.kitchentable.pedidos;

import static com.kitchentable.pedidos.PedidoDtos.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operacional/estabelecimentos/{unidade}")
public class PedidoController {
  private final PedidoRegistroService registro;
  private final ProducaoService producao;
  private final AlteracaoPedidoService alteracoes;

  public PedidoController(PedidoRegistroService r, ProducaoService p, AlteracaoPedidoService a) {
    registro = r;
    producao = p;
    alteracoes = a;
  }

  @GetMapping("/pedidos/ofertas")
  public List<Oferta> ofertas(
      @PathVariable UUID unidade,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID operador) {
    return registro.ofertas(unidade, a, operador);
  }

  @PostMapping("/comandas/{comanda}/pedidos")
  public Pedido registrar(
      @PathVariable UUID unidade,
      @PathVariable UUID comanda,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID operador,
      @RequestHeader("Idempotency-Key") UUID chave,
      @Valid @RequestBody Confirmacao d) {
    return registro.registrar(unidade, comanda, a, operador, chave, d);
  }

  @GetMapping("/comandas/{comanda}/pedidos")
  public List<Pedido> listar(
      @PathVariable UUID unidade,
      @PathVariable UUID comanda,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID operador) {
    return producao.listar(unidade, comanda, a, operador);
  }

  @GetMapping("/pedidos/{id}")
  public Pedido buscar(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID operador) {
    return producao.buscar(unidade, id, a, operador);
  }

  @GetMapping("/pedidos/{id}/historico")
  public List<Evento> historico(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID operador) {
    return producao.historico(unidade, id, a, operador);
  }

  @PostMapping("/pedidos/{id}/entregas")
  public Pedido entregar(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID operador,
      @Valid @RequestBody Entrega d) {
    return producao.entregar(unidade, id, a, operador, d);
  }

  @GetMapping("/fila/{setor}")
  public List<FilaItem> fila(
      @PathVariable UUID unidade,
      @PathVariable String setor,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID operador) {
    return producao.fila(unidade, setor, a, operador);
  }

  @PostMapping("/fila/{setor}/itens/{id}")
  public Item preparar(
      @PathVariable UUID unidade,
      @PathVariable String setor,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID operador,
      @Valid @RequestBody Atualizacao d) {
    return producao.preparar(unidade, setor, id, a, operador, d);
  }

  @GetMapping("/fila/{setor}/solicitacoes")
  public List<DecisaoFila> filaSolicitacoes(
      @PathVariable UUID unidade,
      @PathVariable String setor,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID operador) {
    return producao.filaSolicitacoes(unidade, setor, a, operador);
  }

  @PostMapping("/pedidos/{id}/solicitacoes")
  public Solicitacao solicitar(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID operador,
      @RequestHeader("Idempotency-Key") UUID chave,
      @Valid @RequestBody SolicitacaoEntrada d) {
    return alteracoes.solicitar(unidade, id, a, operador, chave, d);
  }

  @GetMapping("/pedidos/{id}/solicitacoes")
  public List<Solicitacao> solicitacoes(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID operador) {
    return producao.solicitacoes(unidade, id, a, operador);
  }

  @PostMapping("/fila/{setor}/solicitacoes/{id}/decisao")
  public ResultadoDecisao decidir(
      @PathVariable UUID unidade,
      @PathVariable String setor,
      @PathVariable UUID id,
      Authentication a,
      @RequestHeader("X-Operador-Vinculo") UUID operador,
      @Valid @RequestBody DecisaoEntrada d) {
    return alteracoes.decidir(unidade, id, setor, a, operador, d);
  }
}
