package com.kitchentable.funcionarios;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.kitchentable.funcionarios.OperacionalDtos.*;
@RestController
@RequestMapping("/api/v1/estabelecimentos/{unidade}/seguranca-operacional")
public class SegurancaOperacionalController {
    private final OperacionalService service;
    public SegurancaOperacionalController(OperacionalService service){this.service=service;}
    @GetMapping("/capacidades") public Capacidades capacidades(@PathVariable UUID unidade,Authentication a){return service.capacidades(unidade,a.getName());}
    @GetMapping("/funcionarios/{id}") public Estado estado(@PathVariable UUID unidade,@PathVariable UUID id,Authentication a){return service.estado(unidade,id,a.getName());}
    @PutMapping("/funcionarios/{id}/pin") public Estado definir(@PathVariable UUID unidade,@PathVariable UUID id,Authentication a,@RequestBody Definicao dto){return service.definir(unidade,id,a.getName(),dto);}
    @PutMapping("/funcionarios/{id}/bloqueio") public Estado bloquear(@PathVariable UUID unidade,@PathVariable UUID id,Authentication a,@Valid @RequestBody Bloqueio dto){return service.bloquear(unidade,id,a.getName(),dto);}
    @GetMapping("/inatividade") public Configuracao politica(@PathVariable UUID unidade,Authentication a){return service.politica(unidade,a.getName());}
    @PutMapping("/inatividade") public Configuracao politica(@PathVariable UUID unidade,Authentication a,@Valid @RequestBody Politica dto){return service.politica(unidade,a.getName(),dto);}
    @GetMapping("/eventos") public List<Evento> eventos(@PathVariable UUID unidade,Authentication a){return service.eventos(unidade,a.getName());}
}