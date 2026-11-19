package com.kitchentable.pedidos;

import static com.kitchentable.cardapio.CardapioRepository.erro;
import static com.kitchentable.pedidos.PedidoDtos.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitchentable.cardapio.*;
import com.kitchentable.funcionarios.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PedidoRegistroService {
  record LinhaPreparada(
      CardapioDtos.Produto produto,
      int quantidade,
      String observacao,
      List<ComponenteSnapshot> componentes) {}

  record Preparado(
      boolean tudoJunto,
      List<LinhaPreparada> linhas,
      Map<UUID, Integer> consumo,
      Set<String> setores) {}

  private final PedidoRepository repo;
  private final CardapioRepository cardapio;
  private final AcessoAtendimentoService acesso;
  private final EstoqueCardapioService estoque;
  private final AuditoriaAdministrativaService audit;
  private final ObjectMapper json;

  public PedidoRegistroService(
      PedidoRepository r,
      CardapioRepository c,
      AcessoAtendimentoService a,
      EstoqueCardapioService e,
      AuditoriaAdministrativaService ad,
      ObjectMapper j) {
    repo = r;
    cardapio = c;
    acesso = a;
    estoque = e;
    audit = ad;
    json = j;
  }

  String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));
    } catch (Exception e) {
      throw new IllegalStateException("Falha ao identificar operacao");
    }
  }

  String assinatura(CardapioDtos.Produto p, Map<UUID, CardapioDtos.Produto> all) {
    var components = new ArrayList<Object>();
    for (var c : p.componentes()) {
      var simple = all.get(c.produtoId());
      if (simple == null) throw erro(409, "Componente ausente");
      components.add(
          Arrays.asList(
              simple.id(),
              simple.nome(),
              simple.descricao(),
              simple.categoriaId(),
              simple.setor(),
              simple.ativo(),
              simple.disponivel(),
              c.quantidade()));
    }
    return hash(
        Arrays.asList(
            p.id(),
            p.nome(),
            p.descricao(),
            p.categoriaId(),
            p.categoria(),
            p.tipo(),
            p.precoCentavos(),
            p.setor(),
            p.ativo(),
            p.disponivel(),
            p.controlaQuantidade(),
            components));
  }

  public List<Oferta> ofertas(UUID u, Authentication a, UUID esperado) {
    acesso.operador(u, a, esperado, "PEDIDO_REGISTRAR");
    acesso.operador(u, a, esperado, "PRODUTO_CONSULTAR");
    var all = new LinkedHashMap<UUID, CardapioDtos.Produto>();
    for (var p : cardapio.produtos(u)) all.put(p.id(), p);
    return all.values().stream().map(p -> new Oferta(p, assinatura(p, all))).toList();
  }

  @Transactional(
      propagation = org.springframework.transaction.annotation.Propagation.MANDATORY,
      noRollbackFor = org.springframework.web.server.ResponseStatusException.class)
  Preparado preparar(UUID u, Confirmacao d) {
    if (d == null || d.linhas() == null || d.linhas().isEmpty() || d.linhas().size() > 30)
      throw erro(400, "Informe de um a trinta itens");
    var all = new HashMap<UUID, CardapioDtos.Produto>();
    for (var p : cardapio.produtos(u)) all.put(p.id(), p);
    var categories = new HashMap<UUID, Boolean>();
    for (var c : cardapio.categorias(u)) categories.put(c.id(), c.ativo());
    var lines = new ArrayList<LinhaPreparada>();
    var consumption = new TreeMap<UUID, Integer>();
    var sectors = new TreeSet<String>();
    for (var line : d.linhas()) {
      if (line == null
          || line.produtoId() == null
          || line.quantidade() < 1
          || line.quantidade() > 1000) throw erro(400, "Quantidade invalida");
      var p = all.get(line.produtoId());
      if (p == null) throw erro(404, "Produto nao encontrado nesta unidade");
      if (p.precoCentavos() != line.precoCentavos()
          || !assinatura(p, all).equals(line.assinaturaOferta()))
        throw erro(409, "Preco ou composicao mudou. Atualize o cardapio e confirme novamente.");
      disponivel(p, categories);
      String notes = line.observacao() == null ? null : line.observacao().strip();
      if (notes != null && notes.length() > 500) throw erro(400, "Observacao acima do limite");
      if (notes != null && notes.isEmpty()) notes = null;
      var parts = new ArrayList<ComponenteSnapshot>();
      if (p.tipo() == CardapioDtos.Tipo.SIMPLES)
        parts.add(new ComponenteSnapshot(p.id(), p.nome(), p.setor(), 1));
      else {
        if (p.componentes().isEmpty()) throw erro(409, "Combo sem componentes");
        for (var c : p.componentes()) {
          var child = all.get(c.produtoId());
          if (child == null || child.tipo() != CardapioDtos.Tipo.SIMPLES)
            throw erro(409, "Componente invalido");
          disponivel(child, categories);
          parts.add(
              new ComponenteSnapshot(child.id(), child.nome(), child.setor(), c.quantidade()));
        }
      }
      for (var part : parts) {
        if (part.setor() == null) throw erro(409, "Setor de preparo ausente");
        int quantity;
        try {
          quantity = Math.multiplyExact(part.quantidade(), line.quantidade());
          consumption.merge(part.produtoId(), quantity, Math::addExact);
        } catch (ArithmeticException e) {
          throw erro(400, "Quantidade acima do limite");
        }
        sectors.add(part.setor().name());
      }
      lines.add(new LinhaPreparada(p, line.quantidade(), notes, List.copyOf(parts)));
    }
    return new Preparado(d.tudoJunto(), List.copyOf(lines), consumption, sectors);
  }

  private void disponivel(CardapioDtos.Produto p, Map<UUID, Boolean> cats) {
    if (!p.ativo() || !p.disponivel() || !Boolean.TRUE.equals(cats.get(p.categoriaId())))
      throw erro(409, "Produto ou categoria indisponivel");
  }

  public Pedido registrar(
      UUID u, UUID comanda, Authentication a, UUID esperado, UUID key, Confirmacao d) {
    var ator = acesso.operador(u, a, esperado, "PEDIDO_REGISTRAR", true);
    acesso.operador(u, a, esperado, "PEDIDO_ENVIAR");
    acesso.operador(u, a, esperado, "PRODUTO_CONSULTAR");
    if (key == null) throw erro(400, "Informe a chave do pedido");
    String hash = hash(Arrays.asList(comanda, d));
    UUID old = repo.repeticao(u, key, ator.vinculo(), hash);
    if (old != null) return repo.pedido(u, old);
    repo.bloquearComanda(u, comanda);
    var prepared = preparar(u, d);
    var result = criar(u, comanda, a, esperado, ator, prepared, null, "PEDIDO_ENVIAR");
    repo.memorizar(u, key, ator.vinculo(), hash, result.id());
    return result;
  }

  Pedido criar(
      UUID u,
      UUID comanda,
      Authentication a,
      UUID esperado,
      AcessoAtendimentoService.Ator ator,
      Preparado prepared,
      UUID substitui,
      String capacidade) {
    UUID id = UUID.randomUUID();
    estoque.consumir(u, a, esperado, id, prepared.consumo(), capacidade);
    long sequence =
        repo.jdbc()
            .queryForObject(
                "INSERT INTO sequencias_pedidos(estabelecimento_id,ultimo) VALUES(?,1) ON"
                    + " CONFLICT(estabelecimento_id) DO UPDATE SET"
                    + " ultimo=sequencias_pedidos.ultimo+1 RETURNING ultimo",
                Long.class,
                u);
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " pedidos(id,estabelecimento_id,comanda_id,codigo,aberto_por,tudo_junto,substitui_id)"
                + " VALUES(?,?,?,?,?,?,?)",
            id,
            u,
            comanda,
            "P-" + String.format(Locale.ROOT, "%06d", sequence),
            ator.vinculo(),
            prepared.tudoJunto(),
            substitui);
    for (var l : prepared.linhas()) {
      UUID lineId = UUID.randomUUID();
      repo.jdbc()
          .update(
              "INSERT INTO"
                  + " linhas_pedido(id,estabelecimento_id,pedido_id,produto_id,nome,tipo,quantidade,preco_unitario_centavos,observacao,composicao)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?::jsonb)",
              lineId,
              u,
              id,
              l.produto().id(),
              l.produto().nome(),
              l.produto().tipo().name(),
              l.quantidade(),
              l.produto().precoCentavos(),
              l.observacao(),
              repo.json(l.componentes()));
      evento(
          u,
          ator,
          "PEDIDO_ITEM_ADICIONADO",
          id,
          Map.of("linhaId", lineId, "produtoId", l.produto().id(), "quantidade", l.quantidade()));
      for (var part : l.componentes()) {
        UUID itemId = UUID.randomUUID();
        int quantity = Math.multiplyExact(part.quantidade(), l.quantidade());
        repo.jdbc()
            .update(
                "INSERT INTO"
                    + " itens_preparo(id,estabelecimento_id,pedido_id,linha_id,produto_id,nome,setor,quantidade,observacao,atualizado_por)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                itemId,
                u,
                id,
                lineId,
                part.produtoId(),
                part.nome(),
                part.setor().name(),
                quantity,
                l.observacao(),
                ator.vinculo());
        evento(
            u,
            ator,
            "ITEM_ENCAMINHADO",
            id,
            Map.of("itemId", itemId, "setor", part.setor(), "quantidade", quantity));
      }
    }
    repo.jdbc()
        .update(
            "UPDATE comandas SET teve_consumo=true,possui_pendencias=true,versao=versao+1 WHERE"
                + " estabelecimento_id=? AND id=?",
            u,
            comanda);
    var result = repo.pedido(u, id);
    evento(
        u,
        ator,
        "PEDIDO_CRIADO",
        id,
        Map.of("pedidoId", id, "comandaId", comanda, "totalCentavos", result.totalCentavos()));
    evento(
        u,
        ator,
        "PEDIDO_ENVIADO",
        id,
        Map.of("pedidoId", id, "itens", result.itens().size(), "tudoJunto", result.tudoJunto()));
    return result;
  }

  void evento(UUID u, AcessoAtendimentoService.Ator ator, String tipo, UUID id, Object data) {
    audit.registrarOperacao(
        u, ator.usuario(), ator.vinculo(), ator.nome(), tipo, "PEDIDO", id, null, data);
  }
}
