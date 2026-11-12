package com.kitchentable.funcionarios;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.kitchentable.funcionarios.FuncionarioDtos.*;

@RestController
@RequestMapping("/api/v1/estabelecimentos/{estabelecimentoId}/funcionarios")
public class FuncionarioController {
    private final FuncionarioService service;
    private final AssociacaoFuncionarioService associacoes;
    public FuncionarioController(FuncionarioService service,AssociacaoFuncionarioService associacoes) { this.service=service;this.associacoes=associacoes; }
    @GetMapping("/capacidades")
    public Capacidades capacidades(@PathVariable UUID estabelecimentoId,Authentication auth) { return service.capacidades(estabelecimentoId,auth.getName()); }
    @GetMapping
    public List<Funcionario> listar(@PathVariable UUID estabelecimentoId,Authentication auth) { return service.listar(estabelecimentoId,auth.getName()); }
    @GetMapping("/{vinculoId}")
    public Funcionario consultar(@PathVariable UUID estabelecimentoId,@PathVariable UUID vinculoId,Authentication auth) { return service.consultar(estabelecimentoId,vinculoId,auth.getName()); }
    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Funcionario cadastrar(@PathVariable UUID estabelecimentoId,Authentication auth,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Cadastro dto) { return service.cadastrar(estabelecimentoId,auth.getName(),key,dto); }
    @PatchMapping("/{vinculoId}")
    public Funcionario editar(@PathVariable UUID estabelecimentoId,@PathVariable UUID vinculoId,Authentication auth,@Valid @RequestBody Edicao dto) { return service.editar(estabelecimentoId,vinculoId,auth.getName(),dto); }
    @PatchMapping("/{vinculoId}/situacao")
    public Funcionario situacao(@PathVariable UUID estabelecimentoId,@PathVariable UUID vinculoId,Authentication auth,@Valid @RequestBody Situacao dto) { return service.situacao(estabelecimentoId,vinculoId,auth.getName(),dto); }
    @PatchMapping("/{vinculoId}/funcao-principal")
    public Funcionario funcao(@PathVariable UUID estabelecimentoId,@PathVariable UUID vinculoId,Authentication auth,@Valid @RequestBody Funcao dto) { return service.funcao(estabelecimentoId,vinculoId,auth.getName(),dto); }
    @GetMapping("/{vinculoId}/autorizacao-administrativa")
    public Delegacao delegacao(@PathVariable UUID estabelecimentoId,@PathVariable UUID vinculoId,Authentication auth) { return service.consultarDelegacao(estabelecimentoId,vinculoId,auth.getName()); }
    @PutMapping("/{vinculoId}/autorizacao-administrativa")
    public Funcionario delegar(@PathVariable UUID estabelecimentoId,@PathVariable UUID vinculoId,Authentication auth,@Valid @RequestBody Delegacao dto) { return service.delegar(estabelecimentoId,vinculoId,auth.getName(),dto); }
    @GetMapping("/associacoes")
    public List<Associacao> associacoes(@PathVariable UUID estabelecimentoId,Authentication auth) { return associacoes.listar(estabelecimentoId,auth.getName()); }
    @PostMapping("/{vinculoId}/associacoes")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Associacao solicitar(@PathVariable UUID estabelecimentoId,@PathVariable UUID vinculoId,Authentication auth,@Valid @RequestBody PedidoAssociacao dto) { return associacoes.solicitar(estabelecimentoId,vinculoId,auth.getName(),dto); }
    @PostMapping("/associacoes/{solicitacaoId}/aceitar")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Funcionario aceitar(@PathVariable UUID estabelecimentoId,@PathVariable UUID solicitacaoId,Authentication auth,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Aceite dto) { return associacoes.aceitar(estabelecimentoId,solicitacaoId,auth.getName(),key,dto); }
}
