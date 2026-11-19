package com.kitchentable.cardapio;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class CardapioDtos {
  private CardapioDtos() {}

  public enum Tipo {
    SIMPLES,
    COMBO
  }

  public enum Setor {
    COZINHA,
    BAR
  }

  public record Categoria(UUID id, String nome, int ordem, boolean ativo, long versao) {}

  public record CategoriaEntrada(
      @NotBlank @Size(max = 80) String nome, @Min(0) int ordem, boolean ativo, Long versao) {}

  public record Componente(@NotNull UUID produtoId, @Min(1) @Max(1000) int quantidade) {}

  public record Produto(
      UUID id,
      UUID categoriaId,
      String categoria,
      String nome,
      String descricao,
      Tipo tipo,
      long precoCentavos,
      Setor setor,
      boolean ativo,
      boolean disponivel,
      boolean controlaQuantidade,
      int quantidade,
      long versao,
      boolean vendavel,
      List<Componente> componentes) {}

  public record ProdutoEntrada(
      @NotNull UUID categoriaId,
      @NotBlank @Size(max = 120) String nome,
      @Size(max = 1000) String descricao,
      @NotNull Tipo tipo,
      @Min(0) @Max(9999999999L) long precoCentavos,
      Setor setor,
      boolean ativo,
      boolean disponivel,
      boolean controlaQuantidade,
      @Min(0) int quantidade,
      @Size(max = 100) List<@jakarta.validation.Valid Componente> componentes,
      Long versao) {}

  public record Ajuste(
      @Min(0) int quantidade,
      @NotBlank @Size(max = 500) String motivo,
      @NotNull @PositiveOrZero Long versao) {}

  public record Movimento(
      UUID id, String tipo, int anterior, int posterior, String motivo, Instant ocorridoEm) {}

  public record Catalogo(List<Categoria> categorias, List<Produto> produtos) {}

  public record CopiaEntrada(@NotNull UUID destino, @Size(max = 40) String prefixo) {}

  public record Previa(
      UUID id,
      UUID destino,
      String prefixo,
      int categorias,
      int produtos,
      List<String> conflitos,
      Instant expiraEm) {}

  public record ResultadoCopia(int categorias, int produtos) {}

  public record Evento(String tipo, UUID executor, Instant ocorridoEm) {}
}
