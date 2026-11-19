import { el, states } from "./pedido-ui.js";
export async function renderProducao({
  sector,
  root,
  request,
  alive,
  contextAlive,
  button,
  field,
  report,
  mutate,
  allowed,
  queue,
}) {
  root.replaceChildren(el("p", "Consultando fila…", "muted"));
  try {
    const [items, tickets] = await Promise.all([
      request("fila/" + sector),
      request("fila/" + sector + "/solicitacoes"),
    ]);
    if (!alive()) return;
    root.replaceChildren(
      el("h3", "Fila " + (sector === "COZINHA" ? "da cozinha" : "do bar")),
      button("Atualizar fila", () => queue(sector)),
      el(
        "p",
        "Atualize para consultar novos itens e decisões. Cada ação revalida seu PIN, vínculo e permissões no servidor.",
        "muted",
      ),
    );
    if (!items.length)
      root.append(el("p", "Nenhum item aguardando neste setor."));
    const grid = el("div", "", "attendance-grid");
    root.append(grid);
    for (const i of items) {
      const card = el("article", "", "attendance-card production-item");
      card.dataset.item = i.id;
      card.append(
        el(
          "h4",
          i.pedido +
            " · " +
            i.comanda +
            (i.mesa ? " · Mesa " + i.mesa : " · Balcão"),
        ),
        el("strong", i.quantidade + " × " + i.nome),
        el("p", states[i.estado], "attendance-state"),
      );
      if (i.observacao) card.append(el("p", "Observação: " + i.observacao));
      if (i.tudoJunto)
        card.append(el("strong", "TUDO JUNTO", "order-together"));
      const frozen = i.solicitacoes.some((t) => t.estadoDecisao === "ACEITA");
      if (frozen)
        card.append(
          el("p", "Você autorizou uma alteração. Aguarde os demais setores."),
        );
      if (
        !frozen &&
        allowed(sector + "_PREPARO_ATUALIZAR") &&
        ["AGUARDANDO", "EM_PREPARO"].includes(i.estado)
      )
        card.append(
          button(
            i.estado === "AGUARDANDO" ? "Iniciar preparo" : "Marcar pronto",
            () =>
              mutate(async () => {
                await request("fila/" + sector + "/itens/" + i.id, "POST", {
                  estado: i.estado === "AGUARDANDO" ? "EM_PREPARO" : "PRONTO",
                  versao: i.versao,
                });
                await queue(sector);
              }),
            "primary",
          ),
        );
      grid.append(card);
    }
    root.append(el("h3", "Solicitações para este setor"));
    if (!tickets.length) root.append(el("p", "Nenhuma solicitação pendente."));
    for (const t of tickets) {
      const card = el("article", "", "attendance-card production-ticket");
      card.dataset.solicitacao = t.id;
      card.append(
        el(
          "h4",
          t.pedido +
            " · " +
            t.comanda +
            " · " +
            (t.tipo === "CANCELAMENTO" ? "Cancelar pedido" : "Alterar pedido"),
        ),
        el("p", "Motivo: " + t.motivo),
        el("p", states[t.estadoDecisao]),
      );
      for (const part of t.proposta)
        card.append(
          el(
            "p",
            "Proposta neste setor: " +
              part.quantidade +
              " × " +
              part.nome +
              (part.observacao ? " · " + part.observacao : ""),
          ),
        );
      const cap =
        t.tipo === "CANCELAMENTO"
          ? "PEDIDO_CANCELAR"
          : "PEDIDO_ENVIADO_ALTERAR";
      if (
        t.estadoDecisao === "PENDENTE" &&
        allowed(sector + "_PREPARO_ATUALIZAR") &&
        allowed(cap)
      ) {
        const input = field(
          card,
          "Motivo da recusa (obrigatório ao recusar)",
          el("textarea"),
        );
        input.maxLength = 500;
        for (const accept of [true, false])
          card.append(
            button(
              accept ? "Autorizar solicitação" : "Recusar solicitação",
              () =>
                mutate(async () => {
                  if (!accept && !input.value.trim())
                    throw Error("Informe o motivo da recusa.");
                  const result = await request(
                    "fila/" + sector + "/solicitacoes/" + t.id + "/decisao",
                    "POST",
                    {
                      aceitar: accept,
                      motivo: input.value.trim() || null,
                      versao: t.versaoDecisao,
                      versaoPedido: t.versaoPedido,
                    },
                  );
                  await queue(sector);
                  if (contextAlive()) {
                    const notice = el(
                      "p",
                      (states[result.estado] || result.estado) +
                        (result.motivo ? " · " + result.motivo : ""),
                      "order-notice",
                    );
                    notice.role = "status";
                    root.prepend(notice);
                  }
                }),
              accept ? "primary" : "secondary",
            ),
          );
      } else if (t.estadoDecisao === "PENDENTE")
        card.append(
          el(
            "p",
            "Decisão exige autorização específica para alteração ou cancelamento, além da permissão de preparo. Procure o responsável.",
          ),
        );
      root.append(card);
    }
  } catch (e) {
    report(e);
  }
}
