import { formatCents } from "./cardapio.js";
import { el, states } from "./pedido-ui.js";
import { createOrderIntent } from "./pedido-contratos.js";
export { orderTotal, createOrderIntent } from "./pedido-contratos.js";
import { renderPedidoEditor } from "./pedido-editor.js";
import { renderProducao } from "./producao.js";
export function createPedidosScreen({
  root,
  request,
  allowed,
  onError,
  isCurrent = () => true,
}) {
  let disposed = false,
    busy = false,
    generation = 0;
  const alive = () => !disposed && isCurrent();
  function button(text, fn, cls = "secondary") {
    const b = el("button", text, cls);
    b.type = "button";
    b.addEventListener("click", fn);
    return b;
  }
  function field(form, label, node) {
    const id = node.id || "pedido-" + crypto.randomUUID();
    node.id = id;
    const l = el("label", label);
    l.htmlFor = id;
    form.append(l, node);
    return node;
  }
  function report(e) {
    if (alive()) onError(e);
  }
  async function mutate(fn) {
    if (busy || !alive()) return;
    busy = true;
    root.setAttribute("aria-busy", "true");
    const controls = [
      ...root.querySelectorAll("button,input,select,textarea"),
    ].map((n) => [n, n.disabled]);
    controls.forEach(([n]) => (n.disabled = true));
    try {
      await fn();
    } catch (e) {
      report(e);
    } finally {
      busy = false;
      root.setAttribute("aria-busy", "false");
      controls.forEach(([n, v]) => (n.disabled = v));
    }
  }
  async function comanda(c) {
    const g = ++generation;
    root.replaceChildren(el("p", "Consultando pedidos…", "muted"));
    try {
      const list = await request("comandas/" + c.id + "/pedidos");
      if (!alive() || g !== generation) return;
      root.replaceChildren(
        el("h3", "Pedidos desta comanda"),
        button("Atualizar pedidos", () => comanda(c)),
      );
      if (
        c.estado === "ABERTA" &&
        allowed("PEDIDO_REGISTRAR") &&
        allowed("PEDIDO_ENVIAR")
      )
        root.append(button("Novo pedido", () => editor(c), "primary"));
      if (!list.length) root.append(el("p", "Nenhum pedido registrado."));
      for (const p of list) root.append(orderCard(c, p));
    } catch (e) {
      report(e);
    }
  }
  function orderCard(c, p) {
    const card = el("article", "", "attendance-card pedido-card");
    card.dataset.pedido = p.id;
    card.append(
      el("h4", p.codigo + " · " + states[p.estado]),
      el(
        "p",
        "Registrado por " +
          p.nomeAbertura +
          " · " +
          new Date(p.abertoEm).toLocaleString("pt-BR"),
      ),
      el("p", "Total registrado: " + formatCents(p.totalCentavos)),
    );
    if (p.tudoJunto)
      card.append(
        el(
          "strong",
          "TUDO JUNTO — liberar somente o conjunto completo",
          "order-together",
        ),
      );
    if (p.substituiId)
      card.append(
        el(
          "p",
          "Este pedido substitui um pedido anterior. O histórico foi preservado.",
          "muted",
        ),
      );
    const lines = el("ul");
    for (const l of p.linhas)
      lines.append(
        el(
          "li",
          l.quantidade +
            " × " +
            l.nome +
            " · " +
            formatCents(l.totalCentavos) +
            (l.observacao ? " · " + l.observacao : ""),
        ),
      );
    card.append(lines);
    const delivery = el("div", "", "pedido-items");
    const selected = new Map();
    for (const i of p.itens) {
      const row = el("div", "", "pedido-item");
      const label = el(
        "label",
        i.quantidade +
          " × " +
          i.nome +
          " · " +
          (i.setor === "BAR" ? "Bar" : "Cozinha") +
          " · " +
          states[i.estado],
      );
      if (i.estado === "PRONTO" && allowed("PEDIDO_ENTREGAR")) {
        const check = el("input");
        check.type = "checkbox";
        check.dataset.entrega = i.id;
        check.addEventListener("change", () => {
          if (check.checked) selected.set(i.id, { id: i.id, versao: i.versao });
          else selected.delete(i.id);
        });
        label.prepend(check);
      }
      row.append(label);
      delivery.append(row);
    }
    card.append(delivery);
    if (
      allowed("PEDIDO_ENTREGAR") &&
      p.itens.some((i) => i.estado === "PRONTO")
    )
      card.append(
        button(
          p.tudoJunto
            ? "Entregar conjunto completo"
            : "Entregar itens selecionados",
          () =>
            mutate(async () => {
              const items = p.tudoJunto
                ? p.itens
                    .filter(
                      (i) => !["CANCELADO", "ENTREGUE"].includes(i.estado),
                    )
                    .map((i) => ({ id: i.id, versao: i.versao }))
                : [...selected.values()];
              if (!items.length)
                throw Error("Selecione os itens prontos que foram entregues.");
              await request("pedidos/" + p.id + "/entregas", "POST", {
                itens: items,
              });
              await comanda(c);
            }),
          "primary",
        ),
      );
    if (
      c.estado === "ABERTA" &&
      allowed("PEDIDO_ALTERACAO_SOLICITAR") &&
      !p.itens.some((i) => ["ENTREGUE", "CANCELADO"].includes(i.estado))
    ) {
      card.append(button("Solicitar cancelamento", () => cancelForm(c, p)));
      if (allowed("PEDIDO_REGISTRAR") && allowed("PEDIDO_ENVIAR"))
        card.append(button("Solicitar alteração", () => editor(c, p)));
    }
    card.append(
      button("Consultar solicitações", () =>
        mutate(async () => {
          const tickets = await request("pedidos/" + p.id + "/solicitacoes");
          if (!alive()) return;
          let area = card.querySelector(".pedido-tickets");
          if (!area) {
            area = el("div", "", "pedido-tickets");
            card.append(area);
          }
          area.replaceChildren(el("h5", "Solicitações e decisões"));
          if (!tickets.length) area.append(el("p", "Nenhuma solicitação."));
          for (const t of tickets) {
            area.append(
              el(
                "p",
                (t.tipo === "ALTERACAO" ? "Alteração" : "Cancelamento") +
                  " · " +
                  states[t.estado] +
                  " · " +
                  t.motivo +
                  (t.resultadoMotivo ? " · " + t.resultadoMotivo : ""),
              ),
            );
            for (const d of t.decisoes)
              area.append(
                el(
                  "p",
                  (d.setor === "BAR" ? "Bar" : "Cozinha") +
                    ": " +
                    states[d.estado] +
                    (d.motivo ? " · " + d.motivo : ""),
                ),
              );
          }
        }),
      ),
      button("Histórico do pedido", () =>
        mutate(async () => {
          const events = await request("pedidos/" + p.id + "/historico");
          if (!alive()) return;
          let list = card.querySelector(".pedido-history");
          if (!list) {
            list = el("ol", "", "pedido-history");
            card.append(list);
          }
          list.replaceChildren();
          for (const e of events)
            list.append(
              el(
                "li",
                eventName(e.tipo) +
                  " · " +
                  (e.nomeExecutor || "Operador autorizado") +
                  " · " +
                  new Date(e.ocorridoEm).toLocaleString("pt-BR"),
              ),
            );
        }),
      ),
    );
    return card;
  }
  function eventName(type) {
    return (
      {
        PEDIDO_CRIADO: "Pedido criado",
        PEDIDO_ENVIADO: "Pedido enviado",
        PEDIDO_ITEM_ADICIONADO: "Item registrado",
        ITEM_ENCAMINHADO: "Item encaminhado ao setor",
        ITEM_EM_PREPARO: "Preparo iniciado",
        ITEM_PRONTO: "Item pronto",
        ITEM_ENTREGUE: "Entrega confirmada",
        PEDIDO_ALTERACAO_SOLICITADA: "Alteração solicitada",
        PEDIDO_ALTERACAO_SETOR_ACEITA: "Setor autorizou",
        PEDIDO_ALTERACAO_SETOR_RECUSADA: "Setor recusou",
        PEDIDO_ALTERACAO_EXECUTADA: "Alteração executada",
        PEDIDO_ALTERACAO_RECUSADA: "Alteração recusada",
        PEDIDO_CANCELADO_AUTORIZADO:
          "Pedido original cancelado com autorização",
      }[type] || "Atualização do pedido"
    );
  }
  function cancelForm(c, p) {
    const f = el("form", "", "attendance-form");
    root.replaceChildren(f);
    f.append(
      el("h3", "Solicitar cancelamento de " + p.codigo),
      el(
        "p",
        "O pedido será mantido até a decisão dos setores envolvidos. Isto não registra estorno financeiro.",
      ),
    );
    const reason = field(f, "Motivo da solicitação", el("textarea"));
    reason.required = true;
    reason.maxLength = 500;
    const submit = el("button", "Enviar solicitação", "primary");
    submit.type = "submit";
    f.append(
      submit,
      button("Voltar aos pedidos", () => comanda(c)),
    );
    let intent = null;
    f.addEventListener("submit", (e) => {
      e.preventDefault();
      void mutate(async () => {
        intent ??= createOrderIntent({
          tipo: "CANCELAMENTO",
          motivo: reason.value.trim(),
          proposta: null,
          versaoPedido: p.versao,
        });
        reason.readOnly = true;
        await request(
          "pedidos/" + p.id + "/solicitacoes",
          "POST",
          intent.body,
          { "Idempotency-Key": intent.key },
        );
        await comanda(c);
      });
    });
    reason.focus();
  }
  async function editor(c, original = null) {
    const g = ++generation;
    return renderPedidoEditor({
      c,
      original,
      root,
      request,
      alive: () => alive() && g === generation,
      button,
      field,
      report,
      mutate,
      comanda,
      editor,
    });
  }
  async function queue(sector) {
    const g = ++generation;
    return renderProducao({
      contextAlive: alive,
      sector,
      root,
      request,
      alive: () => alive() && g === generation,
      button,
      field,
      report,
      mutate,
      allowed,
      queue,
    });
  }
  return {
    comanda,
    queue,
    destroy() {
      disposed = true;
      generation++;
      root.replaceChildren();
    },
  };
}
