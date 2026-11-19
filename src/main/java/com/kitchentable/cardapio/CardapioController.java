package com.kitchentable.cardapio;

import static com.kitchentable.cardapio.CardapioDtos.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class CardapioController {
  private final CardapioService service;

  public CardapioController(CardapioService s) {
    service = s;
  }

  private static final String ADMIN = "/api/v1/estabelecimentos/{unidade}/cardapio",
      OP = "/api/v1/operacional/estabelecimentos/{unidade}/cardapio";

  @GetMapping({ADMIN, OP})
  public Catalogo consultar(
      @PathVariable UUID unidade,
      Authentication a,
      @RequestHeader(value = "X-Operador-Vinculo", required = false) UUID esperado) {
    return service.consultar(unidade, a, esperado);
  }

  @PostMapping(ADMIN + "/categorias")
  public Categoria criarCategoria(
      @PathVariable UUID unidade, Authentication a, @Valid @RequestBody CategoriaEntrada d) {
    return service.categoria(unidade, null, a, d);
  }

  @PutMapping(ADMIN + "/categorias/{id}")
  public Categoria editarCategoria(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @Valid @RequestBody CategoriaEntrada d) {
    return service.categoria(unidade, id, a, d);
  }

  @PostMapping(ADMIN + "/produtos")
  public Produto criarProduto(
      @PathVariable UUID unidade, Authentication a, @Valid @RequestBody ProdutoEntrada d) {
    return service.produto(unidade, null, a, d);
  }

  @PutMapping(ADMIN + "/produtos/{id}")
  public Produto editarProduto(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @Valid @RequestBody ProdutoEntrada d) {
    return service.produto(unidade, id, a, d);
  }

  @PostMapping(ADMIN + "/produtos/{id}/ajustes")
  public Produto ajustar(
      @PathVariable UUID unidade,
      @PathVariable UUID id,
      Authentication a,
      @Valid @RequestBody Ajuste d) {
    return service.ajustar(unidade, id, a, d);
  }

  @GetMapping(ADMIN + "/produtos/{id}/movimentos")
  public List<Movimento> movimentos(
      @PathVariable UUID unidade, @PathVariable UUID id, Authentication a) {
    return service.movimentos(unidade, id, a);
  }

  @GetMapping(ADMIN + "/produtos/{id}/historico")
  public List<Evento> historico(
      @PathVariable UUID unidade, @PathVariable UUID id, Authentication a) {
    return service.historico(unidade, id, a);
  }
}
