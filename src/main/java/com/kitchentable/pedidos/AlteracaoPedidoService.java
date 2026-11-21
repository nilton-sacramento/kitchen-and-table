package com.kitchentable.pedidos;

import static com.kitchentable.cardapio.CardapioRepository.erro;
import static com.kitchentable.pedidos.PedidoDtos.*;

import com.kitchentable.cardapio.*;
import com.kitchentable.funcionarios.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class AlteracaoPedidoService {
  private final com.kitchentable.pagamentos.IntegridadeFinanceiraService financeiro;
  private final PedidoRepository repo;
  private final PedidoRegistroService registro;
  private final AcessoAtendimentoService acesso;
  private final EstoqueCardapioService estoque;
  private final CardapioRepository cardapio;
  private final AuditoriaAdministrativaService audit;

  public AlteracaoPedidoService(
      PedidoRepository r,
      PedidoRegistroService s,
      AcessoAtendimentoService a,
      EstoqueCardapioService e,
      CardapioRepository c,
      AuditoriaAdministrativaService ad,
      com.kitchentable.pagamentos.IntegridadeFinanceiraService financeiro) {
    repo = r;
    registro = s;
    acesso = a;
    estoque = e;
    cardapio = c;
    audit = ad;
    this.financeiro = financeiro;
  }

  public Solicitacao solicitar(
      UUID u, UUID pedido, Authentication a, UUID esperado, UUID key, SolicitacaoEntrada d) {
    var ator = acesso.operador(u, a, esperado, "PEDIDO_ALTERACAO_SOLICITAR", true);
    if (key == null
        || d.tipo() == null
        || d.motivo() == null
        || d.motivo().isBlank()
        || d.motivo().length() > 500)
      throw erro(400, "Informe tipo, motivo e chave da solicitacao");
    String hash = registro.hash(Arrays.asList(pedido, d));
    repo.jdbc()
        .queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
            (r, n) -> 1,
            u + ":solicitacao:" + key);
    var previous =
        repo.jdbc()
            .queryForList(
                "SELECT id,solicitado_por,requisicao_hash FROM solicitacoes_pedido WHERE"
                    + " estabelecimento_id=? AND chave=?",
                u,
                key);
    if (!previous.isEmpty()) {
      var p = previous.get(0);
      if (!ator.vinculo().equals(p.get("solicitado_por")) || !hash.equals(p.get("requisicao_hash")))
        throw erro(409, "Chave ja usada com outro conteudo ou operador");
      return repo.solicitacao(u, (UUID) p.get("id"));
    }
    var reference = repo.pedido(u, pedido);
    financeiro.compartilhar(u);
    repo.bloquearComanda(u, reference.comandaId());
    financeiro.exigirContaSemLancamentoEfetivo(u, reference.comandaId());
    repo.bloquearPedido(u, pedido);
    var p = repo.pedido(u, pedido);
    if (d.versaoPedido() == null || p.versao() != d.versaoPedido())
      throw erro(409, "Pedido alterado. Atualize antes de solicitar");
    if (p.itens().stream().anyMatch(i -> Set.of("CANCELADO", "ENTREGUE").contains(i.estado())))
      throw erro(
          409,
          "Pedido finalizado ou parcialmente entregue exige fluxo financeiro futuro; nao pode ser"
              + " cancelado aqui");
    var sectors = new TreeSet<String>();
    for (var item : p.itens()) sectors.add(item.setor());
    var proposed = new ArrayList<ItemProposto>();
    if (d.tipo() == TipoSolicitacao.ALTERACAO) {
      acesso.operador(u, a, esperado, "PEDIDO_REGISTRAR");
      acesso.operador(u, a, esperado, "PEDIDO_ENVIAR");
      acesso.operador(u, a, esperado, "PRODUTO_CONSULTAR");
      var prepared = registro.preparar(u, d.proposta());
      sectors.addAll(prepared.setores());
      for (var line : prepared.linhas())
        for (var part : line.componentes())
          proposed.add(
              new ItemProposto(
                  part.produtoId(),
                  part.nome(),
                  part.setor().name(),
                  Math.multiplyExact(part.quantidade(), line.quantidade()),
                  line.observacao()));
    } else if (d.proposta() != null) throw erro(400, "Cancelamento nao aceita itens substitutos");
    UUID id = UUID.randomUUID();
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " solicitacoes_pedido(id,estabelecimento_id,pedido_id,tipo,motivo,proposta,proposta_itens,solicitado_por,chave,requisicao_hash,credencial_versao)"
                + " VALUES(?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?)",
            id,
            u,
            pedido,
            d.tipo().name(),
            d.motivo().strip(),
            d.proposta() == null ? null : repo.json(d.proposta()),
            repo.json(proposed),
            ator.vinculo(),
            key,
            hash,
            acesso.versaoCredencial(u, ator.vinculo()));
    for (String sector : sectors)
      repo.jdbc()
          .update(
              "INSERT INTO decisoes_pedido(estabelecimento_id,solicitacao_id,setor) VALUES(?,?,?)",
              u,
              id,
              sector);
    evento(
        u,
        ator,
        "PEDIDO_ALTERACAO_SOLICITADA",
        id,
        pedido,
        Map.of(
            "tipo", d.tipo(), "motivo", d.motivo(), "setores", sectors, "propostaItens", proposed));
    return repo.solicitacao(u, id);
  }

  public ResultadoDecisao decidir(
      UUID u, UUID id, String sector, Authentication a, UUID esperado, DecisaoEntrada d) {
    ProducaoService.setor(sector);
    var ator = acesso.operador(u, a, esperado, sector + "_PREPARO_ATUALIZAR", true);
    var initial = repo.solicitacao(u, id);
    String capability =
        initial.tipo().equals("CANCELAMENTO") ? "PEDIDO_CANCELAR" : "PEDIDO_ENVIADO_ALTERAR";
    acesso.operador(u, a, esperado, capability);
    var reference = repo.pedido(u, initial.pedidoId());
    financeiro.compartilhar(u);
    repo.bloquearComanda(u, reference.comandaId());
    financeiro.exigirContaSemLancamentoEfetivo(u, reference.comandaId());
    repo.bloquearPedido(u, reference.id());
    repo.jdbc()
        .queryForList(
            "SELECT id FROM solicitacoes_pedido WHERE estabelecimento_id=? AND id=? FOR UPDATE",
            u,
            id);
    var request = repo.solicitacao(u, id);
    var decision =
        request.decisoes().stream()
            .filter(x -> x.setor().equals(sector))
            .findFirst()
            .orElseThrow(() -> erro(403, "Setor nao participa desta solicitacao"));
    if (!request.estado().equals("PENDENTE")) return result(request);
    if (!decision.estado().equals("PENDENTE"))
      throw erro(409, "Setor ja decidiu; aguarde os demais ou gere nova solicitacao");
    var p = repo.pedido(u, request.pedidoId());
    if (d.versao() == null
        || d.versao() != decision.versao()
        || d.versaoPedido() == null
        || d.versaoPedido() != p.versao())
      throw erro(
          409, "Pedido ou decisao alterados; atualize e revalide a possibilidade da mudanca");
    String reason = d.motivo() == null ? null : d.motivo().strip();
    if ((!d.aceitar() && (reason == null || reason.isEmpty()))
        || (reason != null && reason.length() > 500)) throw erro(400, "Informe o motivo da recusa");
    repo.jdbc()
        .update(
            "UPDATE decisoes_pedido SET"
                + " estado=?,executor=?,motivo=?,credencial_versao=?,versao=versao+1,decidido_em=clock_timestamp()"
                + " WHERE estabelecimento_id=? AND solicitacao_id=? AND setor=?",
            d.aceitar() ? "ACEITA" : "RECUSADA",
            ator.vinculo(),
            reason,
            acesso.versaoCredencial(u, ator.vinculo()),
            u,
            id,
            sector);
    evento(
        u,
        ator,
        d.aceitar() ? "PEDIDO_ALTERACAO_SETOR_ACEITA" : "PEDIDO_ALTERACAO_SETOR_RECUSADA",
        id,
        p.id(),
        Map.of("setor", sector, "motivo", reason == null ? "" : reason));
    if (!d.aceitar()) return recusar(u, request, ator, reason);
    request = repo.solicitacao(u, id);
    if (request.decisoes().stream().anyMatch(x -> !x.estado().equals("ACEITA")))
      return result(request);
    String[] requesterCodes =
        request.tipo().equals("ALTERACAO")
            ? new String[] {
              "PEDIDO_ALTERACAO_SOLICITAR", "PEDIDO_REGISTRAR", "PEDIDO_ENVIAR", "PRODUTO_CONSULTAR"
            }
            : new String[] {"PEDIDO_ALTERACAO_SOLICITAR"};
    long requesterVersion =
        repo.jdbc()
            .queryForObject(
                "SELECT credencial_versao FROM solicitacoes_pedido WHERE estabelecimento_id=? AND"
                    + " id=?",
                Long.class,
                u,
                id);
    if (!acesso.credencialVigente(u, request.solicitadoPor(), requesterVersion)
        || !acesso.vinculoPossui(u, request.solicitadoPor(), requesterCodes))
      return recusar(u, request, ator, "Autorizacao do solicitante foi revogada");
    for (var dec : request.decisoes()) {
      long version =
          repo.jdbc()
              .queryForObject(
                  "SELECT credencial_versao FROM decisoes_pedido WHERE estabelecimento_id=? AND"
                      + " solicitacao_id=? AND setor=?",
                  Long.class,
                  u,
                  id,
                  dec.setor());
      if (!acesso.credencialVigente(u, dec.executor(), version)
          || !acesso.vinculoPossui(
              u, dec.executor(), capability, dec.setor() + "_PREPARO_ATUALIZAR"))
        return recusar(u, request, ator, "Autorizacao de um setor foi revogada");
    }
    if (p.itens().stream().anyMatch(i -> Set.of("ENTREGUE", "CANCELADO").contains(i.estado())))
      return recusar(
          u, request, ator, "Pedido ja entregue ou finalizado; nenhuma alteracao foi aplicada");
    var returns = new TreeMap<UUID, Integer>();
    for (var item : p.itens())
      if (item.estado().equals("AGUARDANDO"))
        returns.merge(item.produtoId(), item.quantidade(), Math::addExact);
    PedidoRegistroService.Preparado replacement = null;
    if (request.tipo().equals("ALTERACAO")) {
      var raw =
          repo.jdbc()
              .queryForObject(
                  "SELECT proposta::text FROM solicitacoes_pedido WHERE estabelecimento_id=? AND"
                      + " id=?",
                  String.class,
                  u,
                  id);
      try {
        replacement = registro.preparar(u, repo.proposta(raw));
      } catch (ResponseStatusException e) {
        return recusar(
            u,
            request,
            ator,
            "Oferta alterada ou indisponivel; solicite nova revisao de preco e composicao");
      }
      var locks = new TreeSet<UUID>(returns.keySet());
      locks.addAll(replacement.consumo().keySet());
      for (UUID lock : locks) cardapio.produto(u, lock, true);
      for (UUID product : new TreeSet<>(replacement.consumo().keySet())) {
        var current = cardapio.produto(u, product, true);
        int credit = 0;
        var consumed =
            repo.jdbc()
                .queryForList(
                    "SELECT anterior-posterior AS quantidade FROM movimentos_cardapio WHERE"
                        + " estabelecimento_id=? AND produto_id=? AND operacao_id=? AND"
                        + " tipo='CONSUMO'",
                    u,
                    product,
                    p.id());
        if (!consumed.isEmpty()) credit = returns.getOrDefault(product, 0);
        if (current.controlaQuantidade()
            && (long) current.quantidade() + credit < replacement.consumo().get(product))
          return recusar(
              u,
              request,
              ator,
              "Quantidade insuficiente para a alteracao; pedido original preservado");
      }
    }
    estoque.devolver(u, a, esperado, p.id(), returns, capability);
    UUID next = null;
    if (replacement != null)
      next =
          registro
              .criar(
                  u,
                  p.comandaId(),
                  a,
                  esperado,
                  ator,
                  replacement,
                  p.id(),
                  "PEDIDO_ENVIADO_ALTERAR")
              .id();
    for (var item : p.itens())
      repo.jdbc()
          .update(
              "UPDATE itens_preparo SET"
                  + " estado='CANCELADO',versao=versao+1,atualizado_por=?,atualizado_em=clock_timestamp()"
                  + " WHERE estabelecimento_id=? AND id=?",
              ator.vinculo(),
              u,
              item.id());
    repo.atualizarVersao(u, p.id());
    repo.jdbc()
        .update(
            "UPDATE solicitacoes_pedido SET estado='EXECUTADA',novo_pedido_id=? WHERE"
                + " estabelecimento_id=? AND id=?",
            next,
            u,
            id);
    registro.evento(
        u,
        ator,
        "PEDIDO_CANCELADO_AUTORIZADO",
        p.id(),
        Map.of(
            "solicitacaoId",
            id,
            "estoqueDevolvidoAntesDoPreparo",
            returns,
            "substituto",
            next == null ? "" : next));
    evento(
        u,
        ator,
        "PEDIDO_ALTERACAO_EXECUTADA",
        id,
        p.id(),
        Map.of("novoPedidoId", next == null ? "" : next));
    return result(repo.solicitacao(u, id));
  }

  private ResultadoDecisao recusar(
      UUID u, Solicitacao request, AcessoAtendimentoService.Ator ator, String reason) {
    repo.jdbc()
        .update(
            "UPDATE solicitacoes_pedido SET estado='RECUSADA',resultado_motivo=? WHERE"
                + " estabelecimento_id=? AND id=?",
            reason,
            u,
            request.id());
    evento(
        u,
        ator,
        "PEDIDO_ALTERACAO_RECUSADA",
        request.id(),
        request.pedidoId(),
        Map.of("motivo", reason));
    return result(repo.solicitacao(u, request.id()));
  }

  private ResultadoDecisao result(Solicitacao s) {
    return new ResultadoDecisao(s.id(), s.estado(), s.resultadoMotivo(), s.novoPedidoId());
  }

  private void evento(
      UUID u, AcessoAtendimentoService.Ator actor, String type, UUID id, UUID pedido, Object data) {
    audit.registrarOperacao(
        u,
        actor.usuario(),
        actor.vinculo(),
        actor.nome(),
        type,
        "SOLICITACAO_PEDIDO",
        id,
        null,
        Map.of("pedidoId", pedido, "contexto", data));
  }
}
