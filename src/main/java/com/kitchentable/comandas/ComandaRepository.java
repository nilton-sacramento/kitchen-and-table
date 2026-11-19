package com.kitchentable.comandas;

import static com.kitchentable.comandas.ComandaDtos.*;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ComandaRepository {
  private final JdbcTemplate jdbc;

  public ComandaRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final String SELECT =
      "SELECT c.*,m.identificacao AS mesa,COALESCE(ue.nome_exibicao,u.nome) AS nome_abertura FROM"
          + " comandas c LEFT JOIN mesas m ON m.id=c.mesa_id AND"
          + " m.estabelecimento_id=c.estabelecimento_id JOIN usuarios_estabelecimentos ue ON"
          + " ue.id=c.aberto_por JOIN usuarios u ON u.id=ue.usuario_id ";

  private Comanda map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Comanda(
        r.getObject("id", UUID.class),
        r.getString("codigo"),
        r.getString("modalidade"),
        r.getObject("mesa_id", UUID.class),
        r.getString("mesa"),
        r.getString("cliente"),
        r.getString("estado"),
        r.getObject("aberto_por", UUID.class),
        r.getString("nome_abertura"),
        r.getTimestamp("aberto_em").toInstant(),
        r.getObject("finalizado_por", UUID.class),
        r.getTimestamp("finalizado_em") == null
            ? null
            : r.getTimestamp("finalizado_em").toInstant(),
        r.getString("justificativa"),
        r.getBoolean("teve_consumo"),
        r.getBoolean("possui_pendencias"),
        r.getLong("versao"));
  }

  public List<Comanda> listar(UUID unidade, Estado estado) {
    return jdbc.query(
        SELECT
            + "WHERE c.estabelecimento_id=? "
            + (estado == null ? "" : "AND c.estado=? ")
            + "ORDER BY c.aberto_em DESC,c.id",
        this::map,
        estado == null ? new Object[] {unidade} : new Object[] {unidade, estado.name()});
  }

  public Comanda buscar(UUID unidade, UUID id, boolean bloquear) {
    if (bloquear) {
      var ids =
          jdbc.query(
              "SELECT id FROM comandas WHERE estabelecimento_id=? AND id=? FOR UPDATE",
              (r, n) -> r.getObject(1, UUID.class),
              unidade,
              id);
      if (ids.isEmpty()) throw ausente();
    }
    var list =
        jdbc.query(SELECT + "WHERE c.estabelecimento_id=? AND c.id=?", this::map, unidade, id);
    if (list.isEmpty()) throw ausente();
    return list.get(0);
  }

  public UUID repeticao(UUID unidade, UUID chave, UUID executor, String hash) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", (r, n) -> 1, unidade + ":" + chave);
    var rows =
        jdbc.queryForList(
            "SELECT executor_vinculo,requisicao_hash,comanda_id FROM operacoes_abertura_comanda"
                + " WHERE estabelecimento_id=? AND chave=?",
            unidade,
            chave);
    if (rows.isEmpty()) return null;
    var r = rows.get(0);
    if (!executor.equals(r.get("executor_vinculo")) || !hash.equals(r.get("requisicao_hash")))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Chave de abertura ja usada com outro conteudo ou executor");
    return (UUID) r.get("comanda_id");
  }

  public Comanda abrir(UUID unidade, UUID executor, Abertura dto, String cliente) {
    long numero =
        jdbc.queryForObject(
            "INSERT INTO sequencias_comandas(estabelecimento_id,ultimo) VALUES(?,1) ON"
                + " CONFLICT(estabelecimento_id) DO UPDATE SET ultimo=sequencias_comandas.ultimo+1"
                + " RETURNING ultimo",
            Long.class,
            unidade);
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO comandas(id,estabelecimento_id,codigo,modalidade,mesa_id,cliente,aberto_por)"
            + " VALUES(?,?,?,?,?,?,?)",
        id,
        unidade,
        "C-" + String.format(java.util.Locale.ROOT, "%06d", numero),
        dto.modalidade().name(),
        dto.mesaId(),
        cliente,
        executor);
    return buscar(unidade, id, false);
  }

  public void memorizar(UUID unidade, UUID chave, UUID executor, String hash, UUID id) {
    jdbc.update(
        "INSERT INTO"
            + " operacoes_abertura_comanda(estabelecimento_id,chave,executor_vinculo,requisicao_hash,comanda_id)"
            + " VALUES(?,?,?,?,?)",
        unidade,
        chave,
        executor,
        hash,
        id);
  }

  public Comanda finalizar(UUID unidade, UUID id, UUID executor, Estado estado, String motivo) {
    jdbc.update(
        "UPDATE comandas SET"
            + " estado=?,finalizado_por=?,finalizado_em=clock_timestamp(),justificativa=?,versao=versao+1"
            + " WHERE estabelecimento_id=? AND id=?",
        estado.name(),
        executor,
        motivo,
        unidade,
        id);
    return buscar(unidade, id, false);
  }

  public boolean integracaoConsumoHabilitada() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM permissoes WHERE disponivel AND codigo IN"
                + " ('PEDIDO_REGISTRAR','PEDIDO_ENVIAR','PAGAMENTO_REGISTRAR','CAIXA_OPERAR'))",
            Boolean.class));
  }

  public List<Evento> historico(UUID unidade, UUID id) {
    return jdbc.query(
        "SELECT id,tipo_evento,usuario_id,dados_evento->>'executorNome' AS"
            + " nome,dados_evento->'depois'->>'justificativa' AS motivo,ocorrido_em FROM"
            + " auditoria_eventos WHERE estabelecimento_id=? AND entidade_tipo='COMANDA' AND"
            + " entidade_id=? ORDER BY ocorrido_em,id",
        (r, n) ->
            new Evento(
                r.getObject(1, UUID.class),
                r.getString(2),
                r.getObject(3, UUID.class),
                r.getString("nome"),
                r.getTimestamp("ocorrido_em").toInstant(),
                r.getString("motivo")),
        unidade,
        id);
  }

  private ResponseStatusException ausente() {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "Comanda nao encontrada nesta unidade");
  }
}
