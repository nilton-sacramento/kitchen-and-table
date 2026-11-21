import {
  money,
  moneyInput,
  parseMoney,
  parsePercent,
  percent,
} from "./financeiro.js";
import {
  el,
  button,
  field,
  pairs,
  ledger,
  reviewFinancial,
  movementNames,
} from "./financeiro-ui.js";
import { createRelatorioScreen } from "./relatorios.js";
export function createFinanceAdminScreen({
  root,
  api,
  getEstablishments = () => [],
  onExpired,
  reports = false,
}) {
  let generation = 0,
    unit = null,
    child = null;
  const reset = () => {
    generation++;
    unit = null;
    child?.destroy();
    child = null;
    root.replaceChildren();
  };
  async function activate(u) {
    reset();
    unit = u;
    const g = generation,
      live = () => g === generation && unit === u;
    if (!u) return;
    const message = el("p", "", "error");
    message.role = "alert";
    message.hidden = true;
    root.append(message, el("p", "Consultando acesso financeiro…"));
    const request = async (path, method = "GET", body, headers = {}) => {
      const options = { method, headers: { ...headers } };
      if (method !== "GET") {
        const csrf = await api("auth/csrf");
        options.headers[csrf.headerName] = csrf.token;
        options.headers["Content-Type"] = "application/json";
        if (body) options.body = JSON.stringify(body);
      }
      return api("estabelecimentos/" + u + "/" + path, options);
    };
    const fail = (e) => {
      if (!live()) return;
      if (e.status === 401) {
        onExpired();
        return;
      }
      message.textContent =
        e.detail || e.message || "Não foi possível consultar esta unidade.";
      message.hidden = false;
    };
    try {
      const caps = await request("caixa/capacidades");
      if (!live()) return;
      root.replaceChildren(
        message,
        el("h2", reports ? "Relatórios financeiros" : "Administração do caixa"),
      );
      if (reports) {
        if (!caps.relatorios) {
          root.append(el("p", "Consulta financeira não autorizada."));
          return;
        }
        child = createRelatorioScreen({
          root: elArea(root),
          request,
          onError: fail,
          isCurrent: live,
          employees: caps.historicoIndividual,
          units: getEstablishments().filter((x) => x.funcao === "PROPRIETARIO"),
          consolidate: async (ids, q) =>
            api("financeiro/consolidado?" + q + "&unidades=" + ids.join(",")),
        });
        return;
      }
      root.append(
        el(
          "p",
          "Recebimentos e movimentos são realizados no acesso operacional com PIN e permissões individuais. Esta área administra os limites, a equipe, autorizações e histórico.",
        ),
        el("a", "Abrir acesso operacional"),
      );
      root.lastChild.href = "/operacional.html";
      if (caps.configurar) {
        const p = await request("caixa/politica");
        if (!live()) return;
        const f = el("form", "", "attendance-form");
        f.append(el("h3", "Política financeira da unidade"));
        const unlimited = field(f, {
            key: "unlimited",
            label:
              "Permitir quantidade ilimitada de operadores simultâneos (escolha explícita)",
            type: "checkbox",
            value: p.configurada && p.limiteOperadores === null,
            required: false,
          }),
          limit = field(f, {
            key: "operator-limit",
            label: "Limite de operadores simultâneos",
            type: "number",
            value: p.limiteOperadores || 1,
          });
        limit.min = "1";
        const discount = field(f, {
            key: "discount-limit",
            label: "Desconto acumulado por comanda sem aprovação extra (R$)",
            value: p.configurada
              ? moneyInput(p.descontoLimiteCentavos)
              : "0,00",
          }),
          bps = field(f, {
            key: "percent-limit",
            label: "Desconto acumulado máximo sem aprovação extra (%)",
            value: p.configurada ? moneyInput(p.descontoLimiteBps) : "0,00",
          }),
          sangria = field(f, {
            key: "withdraw-limit",
            label: "Sangria sem aprovação extra (R$)",
            value: p.configurada ? moneyInput(p.sangriaLimiteCentavos) : "0,00",
          }),
          difference = field(f, {
            key: "difference-limit",
            label: "Diferença de fechamento sem aprovação extra (R$)",
            value: p.configurada
              ? moneyInput(p.divergenciaLimiteCentavos)
              : "0,00",
          });
        f.append(
          el(
            "p",
            "Descontos devem respeitar os dois limites (valor e percentual). Zero exige aprovação para qualquer valor positivo. Configurar limites não concede permissões a funcionários.",
            "muted small",
          ),
        );
        const save = el("button", "Salvar política financeira", "primary");
        save.type = "submit";
        f.append(save);
        const sync = () => {
          limit.disabled = unlimited.checked;
          limit.required = !unlimited.checked;
        };
        unlimited.addEventListener("change", sync);
        sync();
        f.addEventListener("submit", async (e) => {
          e.preventDefault();
          save.disabled = true;
          try {
            const n = Number(limit.value);
            if (!unlimited.checked && (!Number.isInteger(n) || n < 1))
              throw Error("Informe um limite inteiro positivo.");
            await request("caixa/politica", "PUT", {
              limiteOperadores: unlimited.checked ? null : n,
              descontoLimiteCentavos: parseMoney(discount.value),
              descontoLimiteBps: parsePercent(bps.value),
              sangriaLimiteCentavos: parseMoney(sangria.value),
              divergenciaLimiteCentavos: parseMoney(difference.value),
              versao: p.versao,
            });
            if (live()) await activate(u);
          } catch (err) {
            fail(err);
          } finally {
            if (live()) save.disabled = false;
          }
        });
        root.append(f);
      }
      if (caps.autorizar) {
        const area = elArea(root);
        area.append(
          el("h3", "Autorizações específicas"),
          button("Atualizar solicitações", () => activate(u)),
        );
        const list = await request("caixa/autorizacoes");
        if (!live()) return;
        if (!list.length)
          area.append(el("p", "Nenhuma solicitação registrada."));
        for (const a of list) {
          const card = el("article", "", "attendance-card");
          card.dataset.autorizacao = a.id;
          card.append(
            el(
              "h4",
              {
                DESCONTO: "Desconto",
                DESCONTO_ESTORNO: "Reversão de desconto",
                ESTORNO: "Compensação de pagamento",
                SANGRIA: "Sangria",
                FECHAMENTO: "Fechamento com diferença",
              }[a.tipo] || a.tipo,
            ),
            el("p", "Solicitante: " + a.nomeSolicitante),
            el("p", "Situação: " + a.estado),
            el("p", "Motivo: " + a.motivo),
            el(
              "p",
              "Válida até " + new Date(a.expiraEm).toLocaleString("pt-BR"),
            ),
          );
          const d = a.operacao;
          let reviewable = true;
          try {
            if (d.valorCentavos !== null && d.valorCentavos !== undefined)
              pairs(card, [["Valor solicitado", money(d.valorCentavos)]]);
            if (a.tipo === "DESCONTO") {
              const c = await request("comandas/" + a.entidadeId + "/conta");
              if (c.revisao !== d.revisaoConta) reviewable = false;
              if (!reviewable)
                card.append(
                  el(
                    "p",
                    "Conta alterada: solicitação obsoleta, não aprovar.",
                    "financial-warning",
                  ),
                );
              if (d.percentualBps)
                pairs(card, [
                  ["Percentual", moneyInput(d.percentualBps) + "%"],
                  [
                    "Desconto calculado",
                    money(percent(c.consumoCentavos, d.percentualBps)),
                  ],
                ]);
            }
            if (["ESTORNO", "DESCONTO_ESTORNO"].includes(a.tipo)) {
              const r = await request("caixa/turnos/" + a.turnoId),
                original = r.lancamentos.find((l) => l.id === a.entidadeId);
              if (original)
                pairs(card, [
                  ["Valor original integral", money(original.valorCentavos)],
                  ["Operação original", movementNames[original.tipo]],
                  [
                    "Natureza",
                    d.natureza === "CORRECAO_LANCAMENTO"
                      ? "Correção interna, sem devolução bancária afirmada"
                      : d.natureza === "REVERSAO_DESCONTO"
                        ? "Reversão de desconto"
                        : "Estorno com devolução externa a confirmar",
                  ],
                ]);
              else {
                reviewable = false;
                card.append(
                  el("p", "Original indisponível. Não aprove sem conferir."),
                );
              }
            }
            if (a.tipo === "FECHAMENTO") {
              const c = await request("caixa/conferencias/" + d.conferenciaId);
              pairs(card, [
                ["Dinheiro esperado", money(c.esperadoCentavos)],
                ["Dinheiro contado", money(c.informadoCentavos)],
                ["Diferença", money(c.diferencaCentavos)],
              ]);
            }
          } catch (e) {
            reviewable = false;
            card.append(
              el(
                "p",
                "Não foi possível conferir os dados atuais. Atualize antes de decidir.",
                "error",
              ),
            );
          }
          if (
            ["PENDENTE", "APROVADA"].includes(a.estado) &&
            new Date(a.expiraEm) > new Date()
          ) {
            const f = el("form", "", "attendance-form");
            const reason = field(f, {
              key: "approval-" + a.id,
              label: "Motivo da decisão",
              type: "textarea",
            });
            for (const [action, label] of a.estado === "PENDENTE"
              ? [
                  ["APROVAR", "Aprovar somente esta operação"],
                  ["RECUSAR", "Recusar solicitação"],
                ]
              : [["REVOGAR", "Revogar autorização ainda não utilizada"]])
              f.append(
                button(label, async () => {
                  if (action === "APROVAR" && !reviewable) {
                    fail(
                      new Error("Confira os dados atuais antes de aprovar."),
                    );
                    return;
                  }
                  if (!f.reportValidity()) return;
                  const buttons = [...f.querySelectorAll("button")];
                  buttons.forEach((b) => (b.disabled = true));
                  try {
                    await request(
                      "caixa/autorizacoes/" + a.id + "/decisao",
                      "POST",
                      {
                        acao: action,
                        motivo: reason.value.trim(),
                        versao: a.versao,
                      },
                    );
                    if (live()) await activate(u);
                  } catch (e) {
                    fail(e);
                  } finally {
                    if (live()) buttons.forEach((b) => (b.disabled = false));
                  }
                }),
              );
            card.append(f);
          }
          area.append(card);
        }
      }
      if (caps.historico) {
        const turns = await request("caixa/turnos");
        if (!live()) return;
        const area = elArea(root);
        area.append(el("h3", "Turnos e histórico preservado"));
        if (!turns.length) area.append(el("p", "Nenhum turno registrado."));
        for (const t of turns) {
          const card = el("article", "", "attendance-card");
          card.append(
            el("h4", t.estado === "ABERTO" ? "Turno aberto" : "Turno fechado"),
            el(
              "p",
              new Date(t.abertoEm).toLocaleString("pt-BR") +
                " · ciclo " +
                t.ciclo +
                " · " +
                (t.responsavelNome || "Responsável"),
            ),
          );
          const details = elArea(card);
          card.append(
            button("Consultar turno", async () => {
              try {
                const r = await request("caixa/turnos/" + t.id),
                  reports = await request(
                    "caixa/turnos/" + t.id + "/relatorios",
                  );
                if (!live()) return;
                details.replaceChildren();
                pairs(details, [
                  ["Dinheiro esperado", money(r.dinheiroEsperadoCentavos)],
                  ["Descontos líquidos", money(r.descontosCentavos)],
                ]);
                ledger(details, r.lancamentos);
                for (const report of reports) {
                  details.append(
                    el("h4", "Relatório imutável · ciclo " + report.ciclo),
                  );
                  if (report.dados.conferencia)
                    pairs(details, [
                      [
                        "Contado",
                        money(report.dados.conferencia.informadoCentavos),
                      ],
                      [
                        "Diferença",
                        money(report.dados.conferencia.diferencaCentavos),
                      ],
                    ]);
                }
              } catch (e) {
                fail(e);
              }
            }),
          );
          if (t.estado === "FECHADO" && caps.reabrir)
            card.append(
              button("Reabrir turno com justificativa", () => {
                child?.destroy();
                child = reviewFinancial({
                  root: details,
                  request,
                  live,
                  onError: fail,
                  scope: { unidade: u, ator: "administrativo", turno: t.id },
                  title: "Reabrir caixa",
                  fields: [
                    {
                      key: "motivo",
                      label: "Justificativa obrigatória",
                      type: "textarea",
                    },
                  ],
                  body: (v) => ({ motivo: v.motivo.trim(), versao: t.versao }),
                  preview: (d) => [
                    "Reabrir o turno original, preservando os relatórios anteriores.",
                    "Motivo: " + d.motivo,
                  ],
                  path: "caixa/turnos/" + t.id + "/reabrir",
                  onSuccess: () => activate(u),
                  onBack: () => activate(u),
                });
              }),
            );
          if (t.estado === "ABERTO" && caps.operadores)
            card.append(
              button("Administrar equipe do turno", async () => {
                try {
                  const candidates = await request("caixa/candidatos");
                  if (!live()) return;
                  const f = el("form", "", "attendance-form");
                  const boxes = candidates.map((c) => {
                    const i = field(f, {
                      key: "team-" + c.vinculoId,
                      label: c.nome,
                      type: "checkbox",
                      value: t.operadores.some(
                        (o) => o.ativo && o.vinculoId === c.vinculoId,
                      ),
                      required: false,
                    });
                    i.dataset.vinculo = c.vinculoId;
                    return i;
                  });
                  const responsible = field(f, {
                      key: "responsible",
                      label: "Responsável principal",
                      type: "select",
                      value: t.responsavelId,
                      options: candidates.map((c) => ({
                        value: c.vinculoId,
                        label: c.nome,
                      })),
                    }),
                    reason = field(f, {
                      key: "team-reason",
                      label: "Motivo da alteração",
                      type: "textarea",
                    });
                  const save = el("button", "Salvar equipe", "primary");
                  save.type = "submit";
                  f.append(save);
                  details.replaceChildren(f);
                  f.addEventListener("submit", async (e) => {
                    e.preventDefault();
                    save.disabled = true;
                    try {
                      await request("caixa/turnos/" + t.id + "/equipe", "PUT", {
                        operadores: boxes
                          .filter((i) => i.checked)
                          .map((i) => i.dataset.vinculo),
                        responsavelId: responsible.value,
                        versao: t.versao,
                        motivo: reason.value.trim(),
                      });
                      if (live()) await activate(u);
                    } catch (e) {
                      fail(e);
                    } finally {
                      if (live()) save.disabled = false;
                    }
                  });
                } catch (e) {
                  fail(e);
                }
              }),
            );
          area.append(card);
        }
      }
    } catch (e) {
      fail(e);
    }
  }
  return { activate, reset };
}
function elArea(root) {
  const n = el("section", "", "financial-area");
  root.append(n);
  return n;
}
