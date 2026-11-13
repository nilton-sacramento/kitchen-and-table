package com.kitchentable.funcionarios;

import jakarta.validation.constraints.*;
import java.util.*;
public final class PermissoesDtos {
    private PermissoesDtos() {}
    public enum Estado { HERDAR, PERMITIR, NEGAR }
    public record Adicionais(@NotNull @Size(max=4) List<@NotBlank String> funcoes,@NotNull @PositiveOrZero Long versao) {}
    public record Excecao(@NotNull Estado estado,@NotNull @PositiveOrZero Long versao) {}
    public record LimiteCodigo(@NotBlank String codigo,boolean conceder,boolean revogar) {}
    public record Limites(boolean adicionais,@NotNull @Size(max=500) List<@NotNull UUID> alvos,
        @NotNull @Size(max=100) List<@jakarta.validation.Valid LimiteCodigo> permissoes,@NotNull @PositiveOrZero Long versao) {}
    public record Catalogo(String codigo,String nome,String descricao,String modulo,String categoria,boolean critica,boolean disponivel,boolean exclusivaProprietario) {}
    public record Efetiva(Catalogo permissao,List<String> herdadaDe,Estado estado,boolean configurada,boolean efetiva,String restricao) {}
    public record Painel(UUID vinculoId,String nome,String principal,List<String> adicionais,long versao,List<Efetiva> permissoes,
        boolean alterarAdicionais,boolean definirLimites,List<String> conceder,List<String> revogar) {}
    public record Capacidades(boolean proprietario,boolean alterarPrincipal,List<UUID> alvos) {}
}