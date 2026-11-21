package com.kitchentable.caixa;

import static com.kitchentable.cardapio.CardapioRepository.erro;

import com.fasterxml.jackson.databind.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperacaoFinanceiraRepository {
  private final JdbcTemplate j;
  private final ObjectMapper mapper;

  public OperacaoFinanceiraRepository(JdbcTemplate j, ObjectMapper m) {
    this.j = j;
    mapper = m;
  }

  public String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Falha ao preservar operacao financeira");
    }
  }

  public JsonNode node(Object value) {
    return mapper.valueToTree(value);
  }

  public <T> T ler(String data, Class<T> type) {
    try {
      return mapper.readValue(data, type);
    } catch (Exception e) {
      throw new IllegalStateException("Registro financeiro invalido");
    }
  }

  public String hash(Object data) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(json(data).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Falha ao identificar operacao financeira");
    }
  }

  public void serializar(UUID u) {
    j.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", (r, n) -> 1, u + ":financeiro");
  }

  public <T> T repetir(UUID u, UUID key, UUID executor, String tipo, Object data, Class<T> type) {
    if (key == null) throw erro(400, "Informe a chave de idempotencia");
    j.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        (r, n) -> 1,
        u + ":financeiro:" + key);
    var rows =
        j.queryForList(
            "SELECT executor,tipo,requisicao_hash,resultado::text AS resultado FROM"
                + " operacoes_financeiras WHERE estabelecimento_id=? AND chave=?",
            u,
            key);
    if (rows.isEmpty()) return null;
    var r = rows.get(0);
    if (!executor.equals(r.get("executor"))
        || !tipo.equals(r.get("tipo"))
        || !hash(data).equals(r.get("requisicao_hash")))
      throw erro(409, "Chave ja utilizada por outro operador, acao ou conteudo");
    return ler((String) r.get("resultado"), type);
  }

  public void salvar(UUID u, UUID key, UUID executor, String tipo, Object data, Object result) {
    j.update(
        "INSERT INTO"
            + " operacoes_financeiras(estabelecimento_id,chave,executor,tipo,requisicao_hash,resultado)"
            + " VALUES(?,?,?,?,?,?::jsonb)",
        u,
        key,
        executor,
        tipo,
        hash(data),
        json(result));
  }
}
