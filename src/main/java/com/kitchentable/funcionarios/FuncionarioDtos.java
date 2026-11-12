package com.kitchentable.funcionarios;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FuncionarioDtos {
    private FuncionarioDtos() {}
    public record Cadastro(@NotBlank @Size(max=150) String nome,
        @NotBlank String funcao, @Email @Size(max=255) String emailContato,
        @Size(max=32) String telefone) {}
    public record Edicao(@NotBlank @Size(max=150) String nome,
        @Email @Size(max=255) String emailContato, @Size(max=32) String telefone,
        @NotNull @PositiveOrZero Long versao) {}
    public record Situacao(@NotNull Boolean ativo, @NotNull @PositiveOrZero Long versao) {}
    public record Funcao(@NotBlank String funcao, @NotNull @PositiveOrZero Long versao) {}
    public record Delegacao(boolean consultar, boolean cadastrar, boolean editar,
        boolean ativar, boolean desativar, boolean associar,
        @NotNull @Size(max=4) List<@NotBlank String> funcoes,
        @NotNull @PositiveOrZero Long versao) {}
    public record PedidoAssociacao(@NotNull UUID destinoId) {}
    public record Aceite(@NotBlank String funcao) {}
    public record Funcionario(UUID vinculoId, UUID usuarioId, String nome,
        String emailContato, String telefone, String funcao, boolean ativo,
        long versao, boolean acessoOperacionalDisponivel) {}
    public record Associacao(UUID id, UUID origemId, UUID destinoId,
        String nome, Instant expiraEm) {}
    public record Capacidades(boolean proprietario, boolean consultar, boolean cadastrar,
        boolean editar, boolean ativar, boolean desativar, boolean associar,
        List<String> funcoes) {}
}
