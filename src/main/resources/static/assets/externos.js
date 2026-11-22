import { el, button } from "./financeiro-ui.js";
import { money, moneyInput, parseMoney } from "./financeiro.js";
import { renderPedidoEditor } from "./pedido-editor.js";
import { createPedidosScreen } from "./pedidos.js";
import { createContaScreen } from "./conta.js";
import {
  externalNames,
  externalIntent,
  executeExternalIntent,
} from "./externo-contratos.js";
export function createExternoScreen({
  root,
  request,
  allowed,
  scope,
  tipo,
  isCurrent,
  onError,
  onNavigate,
}) {
  let disposed = false,
    generation = 0,
    busy = false,
    child = null;
  const alive = () => !disposed && isCurrent();
  const read =
    tipo === "DELIVERY" ? "DELIVERY_CONSULTAR" : "VENDA_RAPIDA_CONSULTAR";
  const create =
    tipo === "DELIVERY" ? "DELIVERY_REGISTRAR" : "VENDA_RAPIDA_REGISTRAR";
  const title =
    tipo === "DELIVERY" ? "Delivery manual" : "Venda rápida de balcão";
  const message = el("p", "", "error");
  message.role = "alert";
  message.hidden = true;
  function reset() {
    child?.destroy();
    child = null;
    generation++;
    root.replaceChildren(el("h3", title), message);
    message.textContent = "";
    message.hidden = true;
    return generation;
  }
  function error(e) {
    if (!alive()) return;
    message.hidden = false;
    message.textContent =
      e instanceof TypeError
        ? "Conexão interrompida. Consulte o resultado da intenção antes de iniciar outra venda."
        : e.message;
    onError(e);
  }
  async function mutate(fn) {
    if (busy || !alive()) return;
    busy = true;
    root.setAttribute("aria-busy", "true");
    const nodes = [
      ...root.querySelectorAll("button,input,select,textarea"),
    ].map((n) => [n, n.disabled]);
    nodes.forEach(([n]) => (n.disabled = true));
    try {
      await fn();
    } catch (e) {
      error(e);
    } finally {
      busy = false;
      root.removeAttribute("aria-busy");
      nodes.forEach(
        ([n, d]) =>
          (n.disabled =
            d ||
            Boolean(
              n.closest("[data-intent-locked]") &&
                n.matches("input,select,textarea"),
            )),
      );
    }
  }
  function field(form, label, tag = "input", id, required = false) {
    const n = el(tag);
    n.id = "ext-" + id;
    n.required = required;
    const l = el("label", label);
    l.htmlFor = n.id;
    form.append(l, n);
    return n;
  }
  async function list() {
    const g = reset();
    if (!allowed(read)) {
      root.append(el("p", "Consulta não autorizada para este canal."));
      return;
    }
    root.append(button("Atualizar atendimentos", list));
    if (allowed(create))
      root.append(
        button(
          tipo === "DELIVERY" ? "Novo delivery" : "Nova venda rápida",
          editor,
          true,
        ),
      );
    root.append(
      el(
        "p",
        "Logística e pagamento são independentes. Atualize para consultar alterações; não há integração externa ou cobrança bancária.",
        "muted",
      ),
    );
    const status = el("p", "Consultando atendimentos…");
    root.append(status);
    try {
      const rows = await request("externos?tipo=" + tipo);
      if (!alive() || g !== generation) return;
      status.remove();
      if (!rows.length) root.append(el("p", "Nenhum atendimento neste canal."));
      const grid = el("div", "", "attendance-grid");
      root.append(grid);
      for (const r of rows) {
        const card = el("article", "", "attendance-card");
        card.append(
          el("h4", r.codigo + (r.referencia ? " · " + r.referencia : "")),
          el("p", externalNames[r.estado] + " · Preparo: " + r.preparo),
          el("p", "Origem: " + r.origem),
          button("Abrir atendimento", () => detail(r.comandaId)),
        );
        grid.append(card);
      }
    } catch (e) {
      error(e);
    }
  }
  async function editor() {
    const g = reset();
    root.append(
      el(
        "p",
        tipo === "VENDA_RAPIDA"
          ? "Pedido e pagamento serão confirmados juntos. Pagar não prepara nem entrega os produtos."
          : "Registre somente a identificação necessária. Não informe endereço, telefone ou dados bancários.",
        "muted",
      ),
    );
    const meta = el("div", "", "attendance-form");
    const ref = field(meta, "Nome ou código (opcional)", "input", "referencia");
    ref.maxLength = 120;
    let origin = null;
    if (tipo === "DELIVERY") {
      origin = field(meta, "Origem manual", "select", "origem", true);
      for (const [v, t] of [
        ["TELEFONE", "Telefone"],
        ["WHATSAPP", "WhatsApp"],
        ["PLATAFORMA", "Plataforma (registro manual)"],
        ["OUTRO", "Outro"],
      ]) {
        const o = el("option", t);
        o.value = v;
        origin.append(o);
      }
      meta.append(
        el(
          "p",
          "Repasse líquido, comissão ou dinheiro recebido fora da loja não são convertidos automaticamente em receita/caixa. Use apenas recebimentos efetivamente conferidos.",
          "muted",
        ),
      );
    }
    root.append(meta);
    const area = el("section");
    root.append(area);
    const fieldOrder = (f, label, n) => {
      const l = el("label", label);
      n.id ||= "ext-order-" + crypto.randomUUID();
      l.htmlFor = n.id;
      f.append(l, n);
      return n;
    };
    await renderPedidoEditor({
      c: { codigo: title },
      root: area,
      request,
      alive: () => alive() && g === generation,
      button,
      field: fieldOrder,
      report: error,
      mutate,
      comanda: list,
      editor,
      onReview: ({ body, cart, total }) =>
        review(body, cart, total, {
          referencia: ref.value.trim() || null,
          origem: origin?.value || "BALCAO",
        }),
    });
  }
  async function review(order, cart, total, metadata) {
    const g = reset(),
      panel = el("form", "", "attendance-form externo-review");
    root.append(panel);
    panel.append(
      el("h4", "Revise antes de confirmar"),
      el("p", metadata.referencia || "Sem identificação de cliente"),
    );
    for (const l of cart)
      panel.append(
        el(
          "p",
          l.quantidade +
            " × " +
            l.nome +
            " · " +
            money(BigInt(l.precoCentavos) * BigInt(l.quantidade)),
        ),
      );
    panel.append(el("strong", "Total: " + money(total)));
    let turn = null,
      method = null,
      tender = null,
      confirmed = null,
      physical = null;
    if (tipo === "VENDA_RAPIDA" && BigInt(total) > 0n) {
      if (!allowed("CAIXA_OPERAR") || !allowed("PAGAMENTO_REGISTRAR")) {
        panel.append(
          el(
            "p",
            "A venda rápida exige permissão de caixa e recebimento. Use comanda de balcão para pagamento posterior.",
          ),
        );
        panel.append(button("Voltar aos atendimentos", list));
        return;
      }
      try {
        turn = await request("caixa/atual");
        if (!alive() || g !== generation) return;
        if (!turn) {
          panel.append(
            el("p", "Abra o caixa antes de registrar a venda rápida."),
            button("Ir ao caixa", () => onNavigate("caixa")),
          );
          return;
        }
      } catch (e) {
        error(e);
        return;
      }
      method = field(panel, "Meio de pagamento", "select", "meio", true);
      for (const [v, t] of [
        ["DINHEIRO", "Dinheiro"],
        ["PIX", "Pix confirmado externamente"],
        ["DEBITO", "Cartão de débito"],
        ["CREDITO", "Cartão de crédito"],
      ]) {
        const o = el("option", t);
        o.value = v;
        method.append(o);
      }
      tender = field(
        panel,
        "Dinheiro entregue (somente dinheiro)",
        "input",
        "recebido",
      );
      tender.inputMode = "decimal";
      tender.value = moneyInput(total);
      confirmed = field(
        panel,
        "Confirme: Pix/cartão foi conferido externamente (não há cobrança bancária)",
        "input",
        "confirmado",
      );
      confirmed.type = "checkbox";
      physical = field(
        panel,
        "Confirme: o dinheiro entrou fisicamente neste caixa",
        "input",
        "dinheiro",
      );
      physical.type = "checkbox";
      physical.checked = false;
      const sync = () => {
        const cash = method.value === "DINHEIRO";
        tender.disabled = !cash;
        tender.required = cash;
        physical.disabled = !cash;
        physical.required = cash;
        confirmed.disabled = cash;
        confirmed.required = !cash;
      };
      method.addEventListener("change", sync);
      sync();
    } else if (tipo === "VENDA_RAPIDA")
      panel.append(
        el("p", "Total zero: nenhum pagamento fictício será criado."),
      );
    const submit = el(
      "button",
      tipo === "VENDA_RAPIDA"
        ? "Confirmar venda e pagamento"
        : "Confirmar delivery",
      "primary",
    );
    submit.type = "submit";
    panel.append(
      submit,
      button("Recomeçar com ofertas atuais", editor),
      el(
        "p",
        "Se a conexão falhar, use a mesma confirmação: primeiro consultaremos o resultado no servidor. Ao atualizar/sair, consulte a lista antes de criar outra intenção.",
        "muted",
      ),
    );
    let intent = null;
    panel.addEventListener("submit", (e) => {
      e.preventDefault();
      void mutate(async () => {
        if (!intent) {
          let pay = null;
          if (method) {
            const cash = method.value === "DINHEIRO";
            if (cash && !physical.checked)
              throw Error("Confirme a entrada física do dinheiro.");
            if (!cash && !confirmed.checked)
              throw Error("Confira o recebimento externo antes de confirmar.");
            const received = cash ? parseMoney(tender.value) : null;
            if (cash && BigInt(received) < BigInt(total))
              throw Error("Dinheiro entregue insuficiente.");
            pay = {
              turnoId: turn.id,
              meio: method.value,
              valorCentavos: String(total),
              recebidoCentavos: received,
              confirmadoExternamente: !cash && confirmed.checked,
              dinheiroRecebidoNoCaixa: cash && physical.checked,
            };
          }
          intent = externalIntent(scope, {
            tipo,
            ...metadata,
            pedido: order,
            pagamento: pay,
          });
          panel.dataset.intentLocked = "true";
        }
        const result = await executeExternalIntent({
          request,
          intent,
          currentScope: scope,
          isCurrent: () => alive() && g === generation,
        });
        if (alive() && g === generation) navigateDetail(result.comandaId);
      });
    });
  }
  function navigateDetail(id) {
    onNavigate(tipo === "DELIVERY" ? "delivery" : "venda-rapida", id);
  }
  async function detail(id) {
    const g = reset();
    root.append(el("p", "Consultando atendimento…"));
    try {
      const [r, history] = await Promise.all([
        request("externos/" + id),
        request("externos/" + id + "/historico"),
      ]);
      if (!alive() || g !== generation) return;
      if (r.tipo !== tipo) throw Error("Atendimento pertence a outro canal.");
      reset();
      const detailGeneration = generation;
      root.append(
        button("Voltar aos atendimentos", () =>
          onNavigate(tipo === "DELIVERY" ? "delivery" : "venda-rapida"),
        ),
        button("Atualizar atendimento", () => detail(id)),
        el("h4", r.codigo + (r.referencia ? " · " + r.referencia : "")),
        el("p", "Logística: " + externalNames[r.estado]),
        el("p", "Preparo: " + r.preparo),
        el("p", "Origem manual: " + r.origem),
        el(
          "p",
          "Pagamento deve ser conferido na conta. Entrega não significa pagamento, e pagamento não conclui entrega.",
          "muted",
        ),
      );
      const actionArea = el("section");
      root.append(actionArea);
      const canStatus = allowed(
        tipo === "DELIVERY" ? "DELIVERY_STATUS_ALTERAR" : "PEDIDO_ENTREGAR",
      );
      if (canStatus && ["REGISTRADO", "SAIU_PARA_ENTREGA"].includes(r.estado)) {
        const form = el("form", "", "attendance-form"),
          state = field(form, "Atualizar logística", "select", "estado", true);
        const opts =
          r.estado === "SAIU_PARA_ENTREGA"
            ? ["ENTREGUE"]
            : tipo === "DELIVERY"
              ? ["SAIU_PARA_ENTREGA", "ENTREGUE", "RETIRADO", "CANCELADO"]
              : ["RETIRADO", "CANCELADO"];
        for (const s of opts) {
          if (s === "CANCELADO" && !allowed("COMANDA_CANCELAR")) continue;
          const o = el("option", externalNames[s]);
          o.value = s;
          state.append(o);
        }
        const reason = field(
          form,
          "Motivo ou confirmação da operação",
          "textarea",
          "motivo",
          true,
        );
        reason.maxLength = 500;
        const b = el("button", "Confirmar estado logístico", "primary");
        b.type = "submit";
        form.append(
          b,
          el(
            "p",
            "Cancelamento exige pedidos cancelados pelos setores e lançamentos regularizados. Entregue/retirado exige produção pronta e permissão de entrega.",
          ),
        );
        actionArea.append(form);
        let intent = null;
        form.addEventListener("submit", (e) => {
          e.preventDefault();
          void mutate(async () => {
            const body = {
              estado: state.value,
              versao: r.versao,
              motivo: reason.value.trim(),
            };
            if (!intent)
              intent = externalIntent({ ...scope, comanda: id }, body);
            else if (JSON.stringify(body) !== JSON.stringify(intent.body))
              throw Error(
                "Consulte e atualize antes de mudar uma intenção já enviada.",
              );
            await request("externos/" + id + "/estado", "POST", intent.body, {
              "Idempotency-Key": intent.key,
            });
            if (alive()) await detail(id);
          });
        });
      }
      if (!alive() || detailGeneration !== generation) return;
      const disclosure = el("details");
      disclosure.append(el("summary", "Histórico do atendimento"));
      for (const e of history)
        disclosure.append(
          el(
            "p",
            e.tipo +
              " · " +
              e.executor +
              " · " +
              new Date(e.ocorridoEm).toLocaleString("pt-BR") +
              (e.motivo ? " · Motivo: " + e.motivo : ""),
          ),
        );
      root.append(disclosure);
      const area = el("section");
      root.append(area);
      const current = () => alive() && generation === detailGeneration;
      if (allowed("CONTA_CONSULTAR"))
        root.append(
          button("Conta, pagamentos e quitação", () => {
            child?.destroy();
            area.className = "financial-account";
            child = createContaScreen({
              root: area,
              request,
              allowed,
              scope: { ...scope, comanda: id },
              onError: error,
              isCurrent: current,
            });
          }),
        );
      if (allowed("PEDIDO_CONSULTAR"))
        root.append(
          button(
            "Itens, entrega e solicitações de alteração/cancelamento",
            async () => {
              child?.destroy();
              area.className = "";
              child = createPedidosScreen({
                root: area,
                request,
                allowed,
                onError: error,
                isCurrent: current,
                allowNewOrders: tipo !== "VENDA_RAPIDA",
              });
              await child.comanda({
                id,
                codigo: r.codigo,
                estado: r.estadoComanda,
              });
            },
          ),
        );
      if (allowed(create))
        root.append(
          button(
            tipo === "DELIVERY" ? "Novo delivery" : "Nova venda rápida",
            editor,
          ),
        );
    } catch (e) {
      error(e);
    }
  }
  return {
    list,
    detail,
    destroy() {
      disposed = true;
      generation++;
      child?.destroy();
      root.replaceChildren();
    },
  };
}
