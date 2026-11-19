package com.kitchentable.cardapio;

import static com.kitchentable.cardapio.CardapioDtos.*;
import static com.kitchentable.cardapio.CardapioRepository.erro;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitchentable.funcionarios.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CopiaCardapioService {
  private final CardapioRepository repo;
  private final AcessoAtendimentoService acesso;
  private final AuditoriaAdministrativaService audit;
  private final ObjectMapper json;

  public CopiaCardapioService(
      CardapioRepository r,
      AcessoAtendimentoService a,
      AuditoriaAdministrativaService ad,
      ObjectMapper j) {
    repo = r;
    acesso = a;
    audit = ad;
    json = j;
  }

  private AcessoAtendimentoService.Ator autorizar(UUID origem, UUID destino, Authentication a) {
    if (origem.equals(destino)) throw erro(400, "Escolha outra unidade");
    acesso.bloquearUnidades(origem, destino);
    var ator = acesso.administrador(origem, a, true, "CARDAPIO_COPIAR");
    acesso.administrador(origem, a, false, "PRODUTO_CONSULTAR");
    for (String code :
        List.of(
            "CARDAPIO_COPIAR",
            "PRODUTO_GERENCIAR",
            "PRODUTO_PRECO_ALTERAR",
            "PRODUTO_DISPONIBILIDADE_ALTERAR")) acesso.administrador(destino, a, true, code);
    return ator;
  }

  private Catalogo dados(UUID u) {
    return new Catalogo(repo.categorias(u), repo.produtos(u));
  }

  private String hash(Catalogo c) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(c)));
    } catch (Exception e) {
      throw new IllegalStateException("Falha ao calcular previa");
    }
  }

  private List<String> conflitos(Catalogo source, Catalogo target, String prefix) {
    var names = new HashSet<String>();
    for (var c : target.categorias()) names.add("C:" + c.nome().toLowerCase(Locale.ROOT));
    for (var p : target.produtos()) names.add("P:" + p.nome().toLowerCase(Locale.ROOT));
    var conflicts = new ArrayList<String>();
    for (var c : source.categorias()) {
      String name = prefix + c.nome();
      if (name.length() > 80 || names.contains("C:" + name.toLowerCase(Locale.ROOT)))
        conflicts.add("Categoria: " + name);
    }
    for (var p : source.produtos()) {
      String name = prefix + p.nome();
      if (name.length() > 120 || names.contains("P:" + name.toLowerCase(Locale.ROOT)))
        conflicts.add("Produto: " + name);
    }
    return conflicts;
  }

  public Previa previa(UUID origem, Authentication a, CopiaEntrada d) {
    var ator = autorizar(origem, d.destino(), a);
    String prefix = CardapioService.texto(d.prefixo(), 40, false);
    prefix = prefix == null ? "" : prefix + " ";
    var source = dados(origem);
    var target = dados(d.destino());
    UUID id = UUID.randomUUID();
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " copias_cardapio(id,origem,destino,executor,prefixo,hash_origem,hash_destino)"
                + " VALUES(?,?,?,?,?,?,?)",
            id,
            origem,
            d.destino(),
            ator.usuario(),
            prefix,
            hash(source),
            hash(target));
    Instant expiry =
        repo.jdbc()
            .queryForObject(
                "SELECT expira_em FROM copias_cardapio WHERE id=?", java.sql.Timestamp.class, id)
            .toInstant();
    return new Previa(
        id,
        d.destino(),
        prefix,
        source.categorias().size(),
        source.produtos().size(),
        conflitos(source, target, prefix),
        expiry);
  }

  public ResultadoCopia confirmar(UUID origem, UUID id, Authentication a) {
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT destino FROM copias_cardapio WHERE origem=? AND id=?", origem, id);
    if (rows.isEmpty()) throw erro(404, "Previa nao encontrada nesta unidade");
    UUID dest = (UUID) rows.get(0).get("destino");
    var ator = autorizar(origem, dest, a);
    var prev =
        repo.jdbc()
            .queryForMap(
                "SELECT * FROM copias_cardapio WHERE origem=? AND id=? FOR UPDATE", origem, id);
    if (!ator.usuario().equals(prev.get("executor")))
      throw erro(403, "Previa pertence a outro administrador");
    if (prev.get("resultado") != null) {
      try {
        return json.readValue(prev.get("resultado").toString(), ResultadoCopia.class);
      } catch (Exception e) {
        throw new IllegalStateException("Resultado de copia invalido");
      }
    }
    if (!((java.sql.Timestamp) prev.get("expira_em")).toInstant().isAfter(Instant.now()))
      throw erro(409, "Previa expirada; gere outra");
    var source = dados(origem);
    var target = dados(dest);
    if (!hash(source).equals(prev.get("hash_origem"))
        || !hash(target).equals(prev.get("hash_destino")))
      throw erro(409, "Cardapio alterado; gere nova previa antes de confirmar");
    String prefix = (String) prev.get("prefixo");
    if (!conflitos(source, target, prefix).isEmpty())
      throw erro(
          409, "Conflitos de nomes; escolha prefixo e gere nova previa. Nada foi sobrescrito.");
    var categories = new HashMap<UUID, UUID>();
    for (var c : source.categorias()) {
      UUID next = UUID.randomUUID();
      categories.put(c.id(), next);
      repo.jdbc()
          .update(
              "INSERT INTO categorias_cardapio(id,estabelecimento_id,nome,ordem,ativo)"
                  + " VALUES(?,?,?,?,?)",
              next,
              dest,
              prefix + c.nome(),
              c.ordem(),
              c.ativo());
    }
    var products = new HashMap<UUID, UUID>();
    for (var p : source.produtos()) {
      UUID next = UUID.randomUUID();
      products.put(p.id(), next);
      repo.jdbc()
          .update(
              "INSERT INTO"
                  + " produtos_cardapio(id,estabelecimento_id,categoria_id,nome,descricao,tipo,preco_centavos,setor,ativo,disponivel,controla_quantidade,quantidade)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?,0)",
              next,
              dest,
              categories.get(p.categoriaId()),
              prefix + p.nome(),
              p.descricao(),
              p.tipo().name(),
              p.precoCentavos(),
              p.setor() == null ? null : p.setor().name(),
              p.ativo(),
              p.disponivel(),
              p.controlaQuantidade());
    }
    for (var p : source.produtos())
      if (p.tipo() == Tipo.COMBO)
        repo.componentes(
            dest,
            products.get(p.id()),
            p.componentes().stream()
                .map(c -> new Componente(products.get(c.produtoId()), c.quantidade()))
                .toList());
    var result = new ResultadoCopia(categories.size(), products.size());
    try {
      repo.jdbc()
          .update(
              "UPDATE copias_cardapio SET resultado=?::jsonb WHERE origem=? AND id=?",
              json.writeValueAsString(result),
              origem,
              id);
    } catch (Exception e) {
      throw new IllegalStateException("Falha ao registrar copia");
    }
    audit.registrarOperacao(
        origem,
        ator.usuario(),
        ator.vinculo(),
        ator.nome(),
        "CARDAPIO_COPIADO_ORIGEM",
        "COPIA_CARDAPIO",
        id,
        null,
        Map.of("destino", dest, "resultado", result));
    var receiver = acesso.administrador(dest, a, false, "CARDAPIO_COPIAR");
    audit.registrarOperacao(
        dest,
        receiver.usuario(),
        receiver.vinculo(),
        receiver.nome(),
        "CARDAPIO_COPIADO_DESTINO",
        "COPIA_CARDAPIO",
        id,
        null,
        Map.of("origem", origem, "resultado", result, "quantidadesIniciais", 0));
    return result;
  }
}
