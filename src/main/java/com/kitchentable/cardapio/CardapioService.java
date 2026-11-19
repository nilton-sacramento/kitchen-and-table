package com.kitchentable.cardapio;

import static com.kitchentable.cardapio.CardapioDtos.*;
import static com.kitchentable.cardapio.CardapioRepository.erro;

import com.kitchentable.funcionarios.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CardapioService {
  private final CardapioRepository repo;
  private final AcessoAtendimentoService acesso;
  private final AuditoriaAdministrativaService audit;

  public CardapioService(
      CardapioRepository r, AcessoAtendimentoService a, AuditoriaAdministrativaService audit) {
    repo = r;
    acesso = a;
    this.audit = audit;
  }

  public Catalogo consultar(UUID u, Authentication a, UUID esperado) {
    if (a.getPrincipal() instanceof OperadorPrincipal)
      acesso.operador(u, a, esperado, "PRODUTO_CONSULTAR");
    else acesso.administrador(u, a, false, "PRODUTO_CONSULTAR");
    return new Catalogo(repo.categorias(u), repo.produtos(u));
  }

  public Categoria categoria(UUID u, UUID id, Authentication a, CategoriaEntrada d) {
    var ator = acesso.administrador(u, a, true, "PRODUTO_GERENCIAR");
    Categoria old = id == null ? null : repo.categoria(u, id);
    if (old != null) versao(old.versao(), d.versao());
    String name = texto(d.nome(), 80, true);
    if (d.ordem() < 0) throw erro(400, "Ordem invalida");
    UUID target = id == null ? UUID.randomUUID() : id;
    if (id == null)
      repo.jdbc()
          .update(
              "INSERT INTO categorias_cardapio(id,estabelecimento_id,nome,ordem,ativo)"
                  + " VALUES(?,?,?,?,?)",
              target,
              u,
              name,
              d.ordem(),
              d.ativo());
    else
      repo.jdbc()
          .update(
              "UPDATE categorias_cardapio SET nome=?,ordem=?,ativo=?,versao=versao+1 WHERE id=? AND"
                  + " estabelecimento_id=?",
              name,
              d.ordem(),
              d.ativo(),
              target,
              u);
    var next = repo.categoria(u, target);
    evento(u, ator, "CATEGORIA_SALVA", "CATEGORIA", target, old, next);
    return next;
  }

  public Produto produto(UUID u, UUID id, Authentication a, ProdutoEntrada d) {
    var ator = acesso.administrador(u, a, true, "PRODUTO_GERENCIAR");
    var old = id == null ? null : repo.produto(u, id, true);
    if (old != null) versao(old.versao(), d.versao());
    repo.categoria(u, d.categoriaId());
    String name = texto(d.nome(), 120, true), desc = texto(d.descricao(), 1000, false);
    if (d.tipo() == null
        || d.precoCentavos() < 0
        || d.precoCentavos() > 9999999999L
        || d.quantidade() < 0) throw erro(400, "Preco, tipo ou quantidade invalidos");
    var components = d.componentes() == null ? List.<Componente>of() : List.copyOf(d.componentes());
    if (old != null && old.tipo() != d.tipo())
      throw erro(409, "Tipo de produto e imutavel; cadastre outro item");
    if (d.tipo() == Tipo.SIMPLES) {
      if (d.setor() == null || !components.isEmpty())
        throw erro(400, "Produto simples exige setor e nao aceita componentes");
    } else {
      if (d.setor() != null
          || d.controlaQuantidade()
          || d.quantidade() != 0
          || components.isEmpty()
          || components.size() > 100)
        throw erro(400, "Combo exige componentes simples e nao possui estoque proprio");
      Set<UUID> ids = new HashSet<>();
      for (var c : components) {
        var p = repo.produto(u, c.produtoId(), false);
        if (p.tipo() != Tipo.SIMPLES
            || c.quantidade() < 1
            || c.quantidade() > 1000
            || !ids.add(c.produtoId()))
          throw erro(400, "Componente invalido, repetido ou recursivo");
      }
    }
    if (old == null || old.precoCentavos() != d.precoCentavos())
      acesso.administrador(u, a, true, "PRODUTO_PRECO_ALTERAR");
    if (old == null || old.ativo() != d.ativo() || old.disponivel() != d.disponivel())
      acesso.administrador(u, a, true, "PRODUTO_DISPONIBILIDADE_ALTERAR");
    if (old == null && d.quantidade() != 0)
      throw erro(400, "Cadastre com quantidade zero; use ajuste auditado para informar estoque");
    if (old != null && old.quantidade() != d.quantidade())
      throw erro(400, "Altere quantidades somente pelo ajuste auditado");
    if (old != null && old.controlaQuantidade() != d.controlaQuantidade())
      acesso.administrador(u, a, true, "ESTOQUE_AJUSTAR");
    UUID target = id == null ? UUID.randomUUID() : id;
    if (id == null)
      repo.jdbc()
          .update(
              "INSERT INTO"
                  + " produtos_cardapio(id,estabelecimento_id,categoria_id,nome,descricao,tipo,preco_centavos,setor,ativo,disponivel,controla_quantidade,quantidade)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?,0)",
              target,
              u,
              d.categoriaId(),
              name,
              desc,
              d.tipo().name(),
              d.precoCentavos(),
              d.setor() == null ? null : d.setor().name(),
              d.ativo(),
              d.disponivel(),
              d.controlaQuantidade());
    else
      repo.jdbc()
          .update(
              "UPDATE produtos_cardapio SET"
                  + " categoria_id=?,nome=?,descricao=?,preco_centavos=?,setor=?,ativo=?,disponivel=?,controla_quantidade=?,versao=versao+1,atualizado_em=clock_timestamp()"
                  + " WHERE id=? AND estabelecimento_id=?",
              d.categoriaId(),
              name,
              desc,
              d.precoCentavos(),
              d.setor() == null ? null : d.setor().name(),
              d.ativo(),
              d.disponivel(),
              d.controlaQuantidade(),
              target,
              u);
    repo.componentes(u, target, components);
    var next = repo.produto(u, target, false);
    evento(
        u, ator, "PRODUTO_SALVO", d.tipo() == Tipo.COMBO ? "COMBO" : "PRODUTO", target, old, next);
    return next;
  }

  public Produto ajustar(UUID u, UUID id, Authentication a, Ajuste d) {
    var ator = acesso.administrador(u, a, true, "ESTOQUE_AJUSTAR");
    var old = repo.produto(u, id, true);
    versao(old.versao(), d.versao());
    String reason = texto(d.motivo(), 500, true);
    if (old.tipo() != Tipo.SIMPLES || !old.controlaQuantidade() || d.quantidade() < 0)
      throw erro(400, "Ajuste exige produto simples com quantidade controlada");
    repo.jdbc()
        .update(
            "UPDATE produtos_cardapio SET"
                + " quantidade=?,versao=versao+1,atualizado_em=clock_timestamp() WHERE"
                + " estabelecimento_id=? AND id=?",
            d.quantidade(),
            u,
            id);
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " movimentos_cardapio(estabelecimento_id,produto_id,operacao_id,tipo,anterior,posterior,executor,motivo)"
                + " VALUES(?,?,?,'AJUSTE',?,?,?,?)",
            u,
            id,
            UUID.randomUUID(),
            old.quantidade(),
            d.quantidade(),
            ator.vinculo(),
            reason);
    var next = repo.produto(u, id, false);
    evento(
        u, ator, "ESTOQUE_AJUSTADO", "ESTOQUE", id, old, Map.of("produto", next, "motivo", reason));
    return next;
  }

  public List<Movimento> movimentos(UUID u, UUID id, Authentication a) {
    acesso.administrador(u, a, false, "ESTOQUE_CONSULTAR");
    repo.produto(u, id, false);
    return repo.movimentos(u, id);
  }

  public List<Evento> historico(UUID u, UUID id, Authentication a) {
    acesso.administrador(u, a, false, "PRODUTO_GERENCIAR");
    repo.produto(u, id, false);
    return repo.jdbc()
        .query(
            "SELECT tipo_evento,usuario_id,ocorrido_em FROM auditoria_eventos WHERE"
                + " estabelecimento_id=? AND entidade_id=? AND entidade_tipo IN"
                + " ('PRODUTO','COMBO','ESTOQUE') ORDER BY ocorrido_em,id",
            (r, n) ->
                new Evento(
                    r.getString(1), r.getObject(2, UUID.class), r.getTimestamp(3).toInstant()),
            u,
            id);
  }

  private void evento(
      UUID u,
      AcessoAtendimentoService.Ator a,
      String tipo,
      String entidade,
      UUID id,
      Object old,
      Object next) {
    audit.registrarOperacao(u, a.usuario(), a.vinculo(), a.nome(), tipo, entidade, id, old, next);
  }

  static void versao(long expected, Long got) {
    if (got == null || expected != got)
      throw erro(409, "Dados alterados por outra pessoa; atualize antes de salvar");
  }

  static String texto(String s, int max, boolean required) {
    String n = s == null ? null : s.strip().replaceAll("\\s+", " ");
    if (n != null && n.isEmpty()) n = null;
    if ((required && n == null) || (n != null && n.length() > max))
      throw erro(400, "Confira os campos e limites de tamanho");
    return n;
  }
}
