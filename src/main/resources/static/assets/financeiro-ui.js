import {
  money,
  financialIntent,
  executeFinancialIntent,
} from "./financeiro.js";
export const el = (tag, text = "", cls = "") => {
  const n = document.createElement(tag);
  n.textContent = text;
  n.className = cls;
  return n;
};
export const paymentNames = {
  DINHEIRO: "Dinheiro",
  PIX: "Pix conferido externamente",
  DEBITO: "Cartão de débito",
  CREDITO: "Cartão de crédito",
};
export const movementNames = {
  ABERTURA: "Valor inicial",
  PAGAMENTO: "Pagamento",
  ESTORNO: "Compensação de pagamento",
  DESCONTO: "Desconto",
  DESCONTO_ESTORNO: "Desconto revertido",
  SANGRIA: "Sangria",
  REFORCO: "Reforço",
};
export function button(label, action, primary = false) {
  const b = el("button", label, primary ? "primary" : "secondary");
  b.type = "button";
  b.addEventListener("click", action);
  return b;
}
export function field(
  form,
  {
    key,
    label,
    type = "text",
    value = "",
    required = true,
    options = [],
    maxLength = 500,
  },
) {
  const n = el(
    type === "textarea" ? "textarea" : type === "select" ? "select" : "input",
  );
  n.id = "fin-" + key;
  if (type !== "select" && type !== "textarea") n.type = type;
  if (type === "text")
    n.inputMode =
      key.toLowerCase().includes("valor") ||
      key.toLowerCase().includes("dinheiro") ||
      key.toLowerCase().includes("limite")
        ? "decimal"
        : "text";
  if (type === "select")
    for (const option of options) {
      const op = el("option", option.label);
      op.value = option.value;
      n.append(op);
    }
  if (type === "checkbox") n.checked = Boolean(value);
  else n.value = value ?? "";
  n.required = required;
  n.autocomplete = "off";
  if (type !== "select") n.maxLength = maxLength;
  const l = el("label", label);
  l.htmlFor = n.id;
  if (type === "checkbox") {
    l.prepend(n);
    form.append(l);
  } else form.append(l, n);
  return n;
}
export function pairs(parent, rows) {
  const dl = el("dl", "", "financial-totals");
  for (const [label, value] of rows) {
    const d = el("div");
    d.append(el("dt", label), el("dd", String(value)));
    dl.append(d);
  }
  parent.append(dl);
}
export function ledger(
  parent,
  list,
  { compensate, confirmExternal, canCompensate = () => true } = {},
) {
  const grid = el("div", "", "attendance-grid financial-ledger");
  if (!list.length)
    grid.append(el("p", "Nenhum lançamento disponível para esta consulta."));
  for (const l of list) {
    const card = el("article", "", "attendance-card");
    card.dataset.lancamento = l.id;
    card.append(
      el("h4", movementNames[l.tipo] || l.tipo),
      el("strong", money(l.valorCentavos)),
      el(
        "p",
        (paymentNames[l.meio] || "Ajuste da conta") +
          " · " +
          new Date(l.ocorridoEm).toLocaleString("pt-BR"),
      ),
      el("p", "Registrado por " + l.nomeExecutor),
    );
    if (l.tipo === "PAGAMENTO" && l.meio === "DINHEIRO")
      card.append(
        el(
          "p",
          "Recebido: " +
            money(l.recebidoCentavos) +
            " · Troco: " +
            money(l.trocoCentavos),
        ),
      );
    if (l.natureza)
      card.append(
        el(
          "p",
          l.natureza === "CORRECAO_LANCAMENTO"
            ? "Correção interna de registro; nenhuma devolução bancária foi afirmada."
            : l.natureza === "REVERSAO_DESCONTO"
              ? "Compensação do desconto original."
              : "Estorno interno do pagamento.",
        ),
      );
    if (l.motivo) card.append(el("p", "Motivo: " + l.motivo));
    if (l.confirmacaoExternaPendente) {
      card.append(
        el(
          "strong",
          "Devolução externa ainda não confirmada",
          "financial-warning",
        ),
      );
      if (confirmExternal)
        card.append(
          button("Confirmar devolução externa", () => confirmExternal(l)),
        );
    }
    if (
      compensate &&
      canCompensate(l) &&
      ["PAGAMENTO", "DESCONTO"].includes(l.tipo) &&
      !list.some((r) => r.originalId === l.id)
    )
      card.append(
        button(
          l.tipo === "DESCONTO"
            ? "Reverter desconto"
            : "Corrigir ou estornar lançamento",
          () => compensate(l),
        ),
      );
    grid.append(card);
  }
  parent.append(grid);
}
export function reviewFinancial({
  root,
  request,
  live,
  onError,
  scope,
  title,
  fields,
  body,
  preview,
  path,
  method = "POST",
  idempotent = true,
  onSuccess,
  onBack,
  approval,
}) {
  const form = el("form", "", "attendance-form financial-form");
  const status = el("p", "", "financial-message");
  status.role = "status";
  const error = el("p", "", "error");
  error.role = "alert";
  error.hidden = true;
  const controls = {};
  form.append(el("h3", title));
  for (const f of fields) controls[f.key] = field(form, f);
  const submit = el("button", "Revisar e continuar", "primary");
  submit.type = "submit";
  form.append(
    submit,
    button("Voltar sem confirmar", onBack),
    el(
      "p",
      "Valores registrados no sistema. Não informe número de cartão, CVV, PIN ou senha bancária.",
      "muted small",
    ),
  );
  root.replaceChildren(form, error, status);
  let intent = null,
    busy = false,
    uncertain = false,
    generation = 0;
  const fail = (e) => {
    if (!live()) return;
    if (
      [401, 423].includes(e.status) ||
      (e.status === 403 && !e.requerAutorizacao)
    ) {
      onError(e);
      return;
    }
    error.textContent =
      e.detail || e.message || "Não foi possível confirmar a operação.";
    error.hidden = false;
  };
  function drawReview(data) {
    intent = financialIntent(scope, data);
    const panel = el("article", "", "attendance-card financial-review");
    panel.append(el("h3", "Confira antes de confirmar"));
    for (const line of preview(data)) panel.append(el("p", line));
    const confirm = button(
      "Confirmar " + title.toLocaleLowerCase("pt-BR"),
      () => send(),
      true,
    );
    const back = button("Voltar e consultar dados atuais", onBack);
    panel.append(
      confirm,
      back,
      el(
        "p",
        "Se a conexão falhar, mantenha esta tela: a próxima tentativa consultará o registro no servidor antes de reenviar. Atualizar, navegar, bloquear ou sair descarta esta confirmação; consulte o histórico antes de iniciar outra.",
        "muted small",
      ),
    );
    root.replaceChildren(panel, error, status);
    const lock = (flag) => {
      confirm.disabled = flag;
      back.disabled = flag;
      root.setAttribute("aria-busy", String(flag));
    };
    async function send() {
      if (busy || !live()) return;
      busy = true;
      const g = ++generation;
      lock(true);
      error.hidden = true;
      try {
        const result = idempotent
          ? await executeFinancialIntent({
              request,
              path,
              intent,
              currentScope: scope,
            })
          : await request(path, method, intent.body);
        if (!live() || g !== generation) return;
        uncertain = false;
        await onSuccess(result);
      } catch (e) {
        if (!live() || g !== generation) return;
        uncertain = !e.status;
        if (uncertain) {
          status.textContent =
            "Resposta não recebida. O registro pode ter sido concluído. Consulte e tente novamente nesta confirmação, sem criar outra operação.";
          confirm.textContent = "Consultar resultado e tentar novamente";
        } else {
          fail(e);
          if (e.requerAutorizacao && approval) showApproval();
        }
      } finally {
        busy = false;
        if (live()) lock(false);
      }
    }
    function showApproval() {
      if (panel.querySelector(".financial-approval")) return;
      const area = el("section", "", "financial-approval");
      area.append(
        el("h4", "Autorização do proprietário"),
        el(
          "p",
          "Solicite a revisão deste valor. A operação não será executada antes da aprovação e de uma nova confirmação sua.",
        ),
      );
      const payload = {
        tipo: approval.tipo,
        turnoId: scope.turno,
        entidadeId: approval.entidadeId,
        operacao: intent.body,
        motivo: intent.body.motivo || approval.motivo || title,
      };
      const approvalIntent = financialIntent(scope, payload);
      const ask = button("Solicitar autorização ao proprietário", async () => {
        if (busy || !live()) return;
        busy = true;
        ask.disabled = true;
        try {
          let result;
          try {
            result = await executeFinancialIntent({
              request,
              path: "caixa/autorizacoes",
              intent: approvalIntent,
              currentScope: scope,
            });
          } catch (e) {
            if (!e.status) {
              area.append(
                el(
                  "p",
                  "A solicitação pode ter sido registrada. Consulte suas autorizações antes de criar outra.",
                ),
              );
              throw e;
            }
            throw e;
          }
          if (!live()) return;
          area.replaceChildren(
            el("p", "Solicitação enviada. Aguarde a decisão do proprietário."),
          );
          confirm.disabled = true;
          const poll = button("Consultar decisão", async () => {
            if (busy || !live()) return;
            busy = true;
            poll.disabled = true;
            try {
              const list = await request("caixa/autorizacoes");
              if (!live()) return;
              const found = list.find((a) => a.id === result.id);
              if (!found)
                throw Error("Solicitação não disponível neste contexto.");
              if (new Date(found.expiraEm).getTime() <= Date.now())
                throw Error(
                  "A autorização expirou. Volte aos dados atuais e solicite nova revisão.",
                );
              if (found.estado === "APROVADA") {
                intent = financialIntent(scope, {
                  ...intent.body,
                  autorizacaoId: found.id,
                });
                status.textContent =
                  "Autorização aprovada. Confira novamente os dados e confirme a operação.";
                confirm.disabled = false;
                poll.disabled = true;
              } else if (
                ["RECUSADA", "REVOGADA", "CONSUMIDA"].includes(found.estado)
              )
                throw Error(
                  "Autorização " +
                    found.estado.toLocaleLowerCase("pt-BR") +
                    ". Consulte os dados atuais antes de continuar.",
                );
              else
                status.textContent =
                  "O proprietário ainda não decidiu esta solicitação.";
            } catch (e) {
              fail(e);
            } finally {
              busy = false;
              if (live() && confirm.disabled) poll.disabled = false;
            }
          });
          area.append(poll);
          status.textContent =
            "Solicitação registrada; nenhum lançamento financeiro foi efetuado.";
        } catch (e) {
          fail(e);
        } finally {
          busy = false;
          if (live()) ask.disabled = false;
        }
      });
      area.append(ask);
      panel.append(area);
    }
  }
  form.addEventListener("submit", (e) => {
    e.preventDefault();
    if (busy || !live()) return;
    error.hidden = true;
    try {
      const values = Object.fromEntries(
        Object.entries(controls).map(([k, n]) => [
          k,
          n.type === "checkbox" ? n.checked : n.value,
        ]),
      );
      drawReview(body(values));
    } catch (e) {
      fail(e);
    }
  });
  return {
    destroy() {
      generation++;
      intent = null;
      root.replaceChildren();
    },
  };
}
