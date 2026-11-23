package com.kitchentable.externos;

import static com.kitchentable.cardapio.CardapioRepository.erro;
import static com.kitchentable.externos.ExternoDtos.*;

import com.kitchentable.caixa.*;
import com.kitchentable.comandas.*;
import com.kitchentable.funcionarios.*;
import com.kitchentable.pagamentos.*;
import com.kitchentable.pedidos.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ExternoService {
  private final JdbcTemplate j;
  private final CaixaRepository caixas;
  private final AcessoAtendimentoService acesso;
  private final ComandaService comandas;
  private final PedidoRegistroService registro;
  private final PedidoRepository pedidos;
  private final ProducaoService producao;
  private final PagamentoService pagamentos;
  private final ContaRepository contas;
  private final OperacaoFinanceiraRepository ops;
  private final AuditoriaAdministrativaService audit;

  public ExternoService(
      JdbcTemplate j,
      CaixaRepository caixa,
      AcessoAtendimentoService a,
      ComandaService c,
      PedidoRegistroService r,
      PedidoRepository p,
      ProducaoService pr,
      PagamentoService pg,
      ContaRepository co,
      OperacaoFinanceiraRepository o,
      AuditoriaAdministrativaService au) {
    this.j = j;
    caixas = caixa;
    acesso = a;
    comandas = c;
    registro = r;
    pedidos = p;
    producao = pr;
    pagamentos = pg;
    contas = co;
    ops = o;
    audit = au;
  }

  private String consulta(Tipo t) {
    return t == Tipo.DELIVERY ? "DELIVERY_CONSULTAR" : "VENDA_RAPIDA_CONSULTAR";
  }

  private UUID sub(UUID key, String suffix) {
    return UUID.nameUUIDFromBytes((key + ":" + suffix).getBytes(StandardCharsets.UTF_8));
  }

  private Map<String, Object> row(UUID u, UUID id, boolean lock) {
    var rows =
        j.queryForList(
            "SELECT e.*,c.codigo,c.estado AS estado_comanda FROM atendimentos_externos e JOIN"
                + " comandas c ON c.id=e.comanda_id AND c.estabelecimento_id=e.estabelecimento_id"
                + " WHERE e.estabelecimento_id=? AND e.comanda_id=?"
                + (lock ? " FOR UPDATE OF e" : ""),
            u,
            id);
    if (rows.isEmpty()) throw erro(404, "Atendimento nao encontrado nesta unidade");
    return rows.get(0);
  }

  private Atendimento dto(UUID u, Map<String, Object> r) {
    UUID id = (UUID) r.get("comanda_id");
    var list = pedidos.listar(u, id);
    var states =
        list.stream()
            .flatMap(p -> p.itens().stream())
            .map(PedidoDtos.Item::estado)
            .filter(s -> !s.equals("CANCELADO"))
            .toList();
    String prep =
        states.isEmpty()
            ? "CANCELADO"
            : states.stream().allMatch(s -> s.equals("ENTREGUE"))
                ? "ENTREGUE"
                : states.stream().allMatch(s -> Set.of("PRONTO", "ENTREGUE").contains(s))
                    ? "PRONTO"
                    : states.stream().anyMatch(s -> !s.equals("AGUARDANDO"))
                        ? "EM_PREPARO"
                        : "REGISTRADO";
    return new Atendimento(
        id,
        (String) r.get("codigo"),
        (String) r.get("tipo"),
        (String) r.get("origem"),
        (String) r.get("referencia"),
        (String) r.get("estado"),
        ((Number) r.get("versao")).longValue(),
        (UUID) r.get("criado_por"),
        ((java.sql.Timestamp) r.get("criado_em")).toInstant(),
        (String) r.get("estado_comanda"),
        prep,
        list);
  }

  public List<Atendimento> listar(UUID u, Tipo tipo, Authentication a, UUID esperado) {
    acesso.operador(u, a, esperado, consulta(tipo));
    return j
        .queryForList(
            "SELECT comanda_id FROM atendimentos_externos WHERE estabelecimento_id=? AND tipo=?"
                + " ORDER BY criado_em DESC,comanda_id",
            u,
            tipo.name())
        .stream()
        .map(r -> dto(u, row(u, (UUID) r.get("comanda_id"), false)))
        .toList();
  }

  public Atendimento buscar(UUID u, UUID id, Authentication a, UUID esperado) {
    acesso.operador(
        u,
        a,
        esperado,
        acesso.vinculoPossui(u, esperado, "DELIVERY_CONSULTAR")
            ? "DELIVERY_CONSULTAR"
            : "VENDA_RAPIDA_CONSULTAR");
    var r = row(u, id, false);
    acesso.operador(u, a, esperado, consulta(Tipo.valueOf((String) r.get("tipo"))));
    return dto(u, r);
  }

  public List<Evento> historico(UUID u, UUID id, Authentication a, UUID esperado) {
    buscar(u, id, a, esperado);
    return j.query(
        "SELECT tipo_evento,dados_evento->>'executorNome' AS"
            + " nome,ocorrido_em,dados_evento->'depois'->>'motivo' AS motivo FROM auditoria_eventos"
            + " WHERE estabelecimento_id=? AND entidade_tipo='ATENDIMENTO_EXTERNO' AND"
            + " entidade_id=? ORDER BY ocorrido_em,id",
        (r, n) ->
            new Evento(
                r.getString(1), r.getString(2), r.getTimestamp(3).toInstant(), r.getString(4)),
        u,
        id);
  }

  public Criado operacao(UUID u, Tipo tipo, UUID key, Authentication a, UUID esperado) {
    var actor = acesso.operador(u, a, esperado, consulta(tipo));
    acesso.operador(
        u, a, esperado, tipo == Tipo.DELIVERY ? "DELIVERY_REGISTRAR" : "VENDA_RAPIDA_REGISTRAR");
    var rows =
        j.queryForList(
            "SELECT resultado::text AS resultado FROM operacoes_financeiras WHERE"
                + " estabelecimento_id=? AND chave=? AND executor=? AND tipo='ATENDIMENTO_EXTERNO'",
            u,
            key,
            actor.vinculo());
    if (rows.isEmpty()) throw erro(404, "Registro nao encontrado para esta intencao e operador");
    var result = ops.ler((String) rows.get(0).get("resultado"), Criado.class);
    var r = row(u, result.comandaId(), false);
    if (!tipo.name().equals(r.get("tipo"))) throw erro(404, "Registro nao encontrado neste canal");
    return result;
  }

  public Criado criar(UUID u, Authentication a, UUID esperado, UUID key, Criacao d) {
    if (d == null || d.tipo() == null || d.origem() == null || key == null)
      throw erro(400, "Informe canal, origem e chave da operacao");
    var actor =
        acesso.operador(
            u,
            a,
            esperado,
            d.tipo() == Tipo.DELIVERY ? "DELIVERY_REGISTRAR" : "VENDA_RAPIDA_REGISTRAR",
            true);
    acesso.operador(u, a, esperado, consulta(d.tipo()));
    if ((d.tipo() == Tipo.VENDA_RAPIDA) != (d.origem() == Origem.BALCAO))
      throw erro(400, "Origem incompatível com o canal");
    String ref = d.referencia() == null ? null : d.referencia().strip();
    if (ref != null && ref.isEmpty()) ref = null;
    if (ref != null && ref.length() > 120) throw erro(400, "Referencia acima do limite");
    // Revalida capacidades antes do replay, inclusive as dependencias financeiras da venda paga.
    for (String code :
        List.of("ATENDIMENTO_ABRIR", "PEDIDO_REGISTRAR", "PEDIDO_ENVIAR", "PRODUTO_CONSULTAR"))
      acesso.operador(u, a, esperado, code);
    if (d.pagamento() != null)
      for (String code : List.of("CAIXA_OPERAR", "PAGAMENTO_REGISTRAR"))
        acesso.operador(u, a, esperado, code);
    if (d.pagamento() != null) caixas.membro(u, d.pagamento().turnoId(), actor.vinculo());
    ops.serializar(u);
    var old = ops.repetir(u, key, actor.vinculo(), "ATENDIMENTO_EXTERNO", d, Criado.class);
    if (old != null) return old;
    var c =
        comandas.abrir(
            u,
            a,
            esperado,
            sub(key, "comanda"),
            new ComandaDtos.Abertura(ComandaDtos.Modalidade.BALCAO, null, ref));
    j.update(
        "INSERT INTO"
            + " atendimentos_externos(comanda_id,estabelecimento_id,tipo,origem,referencia,criado_por,atualizado_por)"
            + " VALUES(?,?,?,?,?,?,?)",
        c.id(),
        u,
        d.tipo().name(),
        d.origem().name(),
        ref,
        actor.vinculo(),
        actor.vinculo());
    var p = registro.registrar(u, c.id(), a, esperado, sub(key, "pedido"), d.pedido());
    var account = contas.conta(u, c.id());
    UUID payment = null;
    if (d.tipo() == Tipo.VENDA_RAPIDA && account.saldoCentavos() > 0 && d.pagamento() == null)
      throw erro(400, "Venda rapida exige pagamento integral no momento da confirmacao");
    if (d.pagamento() != null) {
      var pay = d.pagamento();
      if (pay.meio() == PagamentoDtos.Meio.DINHEIRO && !pay.dinheiroRecebidoNoCaixa())
        throw erro(
            409,
            "Dinheiro externo sem entrada fisica nao pode ser registrado como caixa; concilie"
                + " antes");
      if (pay.meio() != PagamentoDtos.Meio.DINHEIRO && pay.dinheiroRecebidoNoCaixa())
        throw erro(400, "Pix e cartoes nao movimentam dinheiro fisico");
      if (d.tipo() == Tipo.VENDA_RAPIDA && pay.valorCentavos() != account.saldoCentavos())
        throw erro(409, "Venda rapida exige valor exato do total revisado");
      payment =
          pagamentos
              .receber(
                  u,
                  pay.turnoId(),
                  c.id(),
                  a,
                  esperado,
                  sub(key, "pagamento"),
                  new PagamentoDtos.Receber(
                      pay.meio(),
                      pay.valorCentavos(),
                      pay.recebidoCentavos(),
                      pay.confirmadoExternamente(),
                      account.revisao()))
              .id();
    }
    var result = new Criado(c.id(), p.id(), payment);
    audit.registrarOperacao(
        u,
        actor.usuario(),
        actor.vinculo(),
        actor.nome(),
        d.tipo() == Tipo.DELIVERY ? "DELIVERY_REGISTRADO" : "VENDA_RAPIDA_REGISTRADA",
        "ATENDIMENTO_EXTERNO",
        c.id(),
        null,
        Map.of("tipo", d.tipo(), "origem", d.origem(), "pedidoId", p.id()));
    ops.salvar(u, key, actor.vinculo(), "ATENDIMENTO_EXTERNO", d, result);
    return result;
  }

  public Atendimento transicionar(
      UUID u, UUID id, Authentication a, UUID esperado, UUID key, Transicao d) {
    acesso.operador(
        u,
        a,
        esperado,
        acesso.vinculoPossui(u, esperado, "DELIVERY_CONSULTAR")
            ? "DELIVERY_CONSULTAR"
            : "VENDA_RAPIDA_CONSULTAR");
    var reference = row(u, id, false);
    Tipo tipo = Tipo.valueOf((String) reference.get("tipo"));
    var actor =
        acesso.operador(
            u,
            a,
            esperado,
            tipo == Tipo.DELIVERY ? "DELIVERY_STATUS_ALTERAR" : "PEDIDO_ENTREGAR",
            true);
    acesso.operador(u, a, esperado, consulta(tipo));
    if (d == null
        || d.estado() == null
        || d.versao() == null
        || d.motivo() == null
        || d.motivo().isBlank()
        || d.motivo().length() > 500) throw erro(400, "Informe estado, versao e motivo");
    if (d.estado() == Estado.ENTREGUE || d.estado() == Estado.RETIRADO)
      acesso.operador(u, a, esperado, "PEDIDO_ENTREGAR");
    if (d.estado() == Estado.CANCELADO) acesso.operador(u, a, esperado, "COMANDA_CANCELAR");
    ops.serializar(u);
    Object intent = Arrays.asList(id, d);
    var old = ops.repetir(u, key, actor.vinculo(), "LOGISTICA_EXTERNA", intent, Atendimento.class);
    if (old != null) return old;
    contas.bloquear(u, id, true);
    var r = row(u, id, true);
    String before = (String) r.get("estado");
    if (((Number) r.get("versao")).longValue() != d.versao())
      throw erro(409, "Atendimento alterado. Atualize antes de confirmar");
    boolean valid =
        before.equals("REGISTRADO")
                && Set.of(
                        Estado.SAIU_PARA_ENTREGA,
                        Estado.ENTREGUE,
                        Estado.RETIRADO,
                        Estado.CANCELADO)
                    .contains(d.estado())
            || before.equals("SAIU_PARA_ENTREGA") && d.estado() == Estado.ENTREGUE;
    if (!valid
        || tipo == Tipo.VENDA_RAPIDA
            && Set.of(Estado.SAIU_PARA_ENTREGA, Estado.ENTREGUE).contains(d.estado()))
      throw erro(409, "Transicao logistica invalida");
    var account = contas.conta(u, id);
    var items =
        account.pedidos().stream()
            .flatMap(p -> p.itens().stream())
            .filter(i -> !i.estado().equals("CANCELADO"))
            .toList();
    if (d.estado() == Estado.CANCELADO) {
      if (!items.isEmpty()
          || account.pagoCentavos() != 0
          || account.descontosCentavos() != 0
          || account.solicitacoesPendentes() > 0)
        throw erro(
            409,
            "Cancele os pedidos e regularize pagamentos/descontos antes do cancelamento logistico");
    } else {
      if (items.isEmpty()
          || account.solicitacoesPendentes() > 0
          || items.stream().anyMatch(i -> !Set.of("PRONTO", "ENTREGUE").contains(i.estado())))
        throw erro(
            409, "Aguarde preparo completo e resolva solicitacoes antes de despachar ou concluir");
      if (d.estado() == Estado.ENTREGUE || d.estado() == Estado.RETIRADO) {
        acesso.operador(u, a, esperado, "PEDIDO_ENTREGAR");
        for (var p : account.pedidos()) {
          var ready =
              p.itens().stream()
                  .filter(i -> i.estado().equals("PRONTO"))
                  .map(i -> new PedidoDtos.Referencia(i.id(), i.versao()))
                  .toList();
          if (!ready.isEmpty())
            producao.entregar(u, p.id(), a, esperado, new PedidoDtos.Entrega(ready));
        }
      }
    }
    j.update(
        "UPDATE atendimentos_externos SET"
            + " estado=?,versao=versao+1,atualizado_por=?,atualizado_em=clock_timestamp() WHERE"
            + " estabelecimento_id=? AND comanda_id=?",
        d.estado().name(),
        actor.vinculo(),
        u,
        id);
    var result = dto(u, row(u, id, false));
    audit.registrarOperacao(
        u,
        actor.usuario(),
        actor.vinculo(),
        actor.nome(),
        "LOGISTICA_EXTERNA_ALTERADA",
        "ATENDIMENTO_EXTERNO",
        id,
        Map.of("estado", before),
        Map.of("estado", d.estado(), "motivo", d.motivo().strip()));
    ops.salvar(u, key, actor.vinculo(), "LOGISTICA_EXTERNA", intent, result);
    return result;
  }
}
