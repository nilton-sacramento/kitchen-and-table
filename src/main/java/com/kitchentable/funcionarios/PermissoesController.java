package com.kitchentable.funcionarios;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.kitchentable.funcionarios.PermissoesDtos.*;
@RestController
@RequestMapping("/api/v1/estabelecimentos/{unidade}/permissoes")
public class PermissoesController {
    private final PermissoesService service;
    public PermissoesController(PermissoesService service){this.service=service;}
    @GetMapping("/catalogo") public List<Catalogo> catalogo(@PathVariable UUID unidade,Authentication a){return service.catalogo(unidade,a.getName());}
    @GetMapping("/capacidades") public Capacidades capacidades(@PathVariable UUID unidade,Authentication a){return service.capacidades(unidade,a.getName());}
    @GetMapping("/minhas") public List<Efetiva> minhas(@PathVariable UUID unidade,Authentication a){return service.minhas(unidade,a.getName());}
    @GetMapping("/alvos") public List<FuncionarioDtos.Funcionario> alvos(@PathVariable UUID unidade,Authentication a){return service.alvos(unidade,a.getName());}
    @GetMapping("/funcionarios/{id}") public Painel painel(@PathVariable UUID unidade,@PathVariable UUID id,Authentication a){return service.painel(unidade,id,a.getName());}
    @PutMapping("/funcionarios/{id}/adicionais") public Painel adicionais(@PathVariable UUID unidade,@PathVariable UUID id,Authentication a,@Valid @RequestBody Adicionais dto){return service.adicionais(unidade,id,a.getName(),dto);}
    @PutMapping("/funcionarios/{id}/individuais/{codigo}") public Painel excecao(@PathVariable UUID unidade,@PathVariable UUID id,@PathVariable String codigo,Authentication a,@Valid @RequestBody Excecao dto){return service.excecao(unidade,id,codigo,a.getName(),dto);}
    @GetMapping("/funcionarios/{id}/limites") public Limites limites(@PathVariable UUID unidade,@PathVariable UUID id,Authentication a){return service.limites(unidade,id,a.getName());}
    @PutMapping("/funcionarios/{id}/limites") public Painel limites(@PathVariable UUID unidade,@PathVariable UUID id,Authentication a,@Valid @RequestBody Limites dto){return service.limites(unidade,id,a.getName(),dto);}
}