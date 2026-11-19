package com.kitchentable.cardapio;

import static com.kitchentable.cardapio.CardapioDtos.*;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/estabelecimentos/{unidade}/cardapio/copias")
public class CopiaCardapioController {
  private final CopiaCardapioService service;

  public CopiaCardapioController(CopiaCardapioService s) {
    service = s;
  }

  @PostMapping("/previa")
  public Previa previa(
      @PathVariable UUID unidade, Authentication a, @Valid @RequestBody CopiaEntrada d) {
    return service.previa(unidade, a, d);
  }

  @PostMapping("/{id}/confirmar")
  public ResultadoCopia confirmar(
      @PathVariable UUID unidade, @PathVariable UUID id, Authentication a) {
    return service.confirmar(unidade, id, a);
  }
}
