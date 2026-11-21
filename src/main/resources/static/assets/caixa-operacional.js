import { money, parseMoney } from "./financeiro.js";
import { el, button, pairs, ledger, reviewFinancial } from "./financeiro-ui.js";
export function createCaixaScreen({
  root,
  request,
  allowed,
  scope,
  onError,
  isCurrent,
}) {
  let disposed = false,
    version = 0,
    form = null;
  const live = (g) => !disposed && isCurrent() && g === version;
  async function load() {
    form?.destroy();
    const g = ++version;
    root.removeAttribute("aria-busy");
    root.replaceChildren(el("p", "Consultando caixa…"));
    try {
      const policy = await request("caixa/politica"),
        current = await request("caixa/atual");
      if (!live(g)) return;
      root.replaceChildren(
        el("h3", "Caixa da unidade"),
        el(
          "p",
          "Abertura e reforços não são receita. Pix e cartões exigem conferência externa; o sistema não movimenta bancos.",
          "muted",
        ),
      );
      if (!policy.configurada) {
        root.append(
          el(
            "p",
            "O proprietário precisa configurar os limites do caixa antes da primeira abertura.",
          ),
        );
        return;
      }
      const area = el("section");
      root.append(area);
      const review = (
        title,
        fields,
        body,
        preview,
        path,
        approval,
        success = load,
      ) => {
        form?.destroy();
        form = reviewFinancial({
          root: area,
          request,
          live: () => live(g),
          onError,
          scope: { ...scope, turno: current?.id },
          title,
          fields,
          body,
          preview,
          path,
          approval,
          onSuccess: success,
          onBack: load,
        });
        area.scrollIntoView({ block: "nearest" });
      };
      if (!current) {
        root.append(el("p", "Não há turno aberto."));
        if (allowed("CAIXA_ABRIR"))
          root.append(
            button(
              "Abrir caixa",
              () =>
                review(
                  "Abrir caixa",
                  [
                    {
                      key: "valor",
                      label: "Dinheiro inicial disponível (R$)",
                      value: "0,00",
                    },
                  ],
                  (v) => ({
                    responsavelId: scope.ator,
                    valorInicialCentavos: parseMoney(v.valor),
                  }),
                  (d) => [
                    "Responsável: operador autenticado",
                    "Dinheiro inicial: " + money(d.valorInicialCentavos),
                    "Este valor não será registrado como venda.",
                  ],
                  "caixa/turnos",
                ),
              true,
            ),
          );
      } else {
        const r = await request("caixa/turnos/" + current.id);
        if (!live(g)) return;
        pairs(root, [
          ["Situação", "Aberto · ciclo " + current.ciclo],
          ["Responsável", current.responsavelNome],
          ["Abertura", new Date(current.abertoEm).toLocaleString("pt-BR")],
          ["Dinheiro inicial", money(current.valorInicialCentavos)],
          ["Dinheiro esperado", money(r.dinheiroEsperadoCentavos)],
          ["Troco entregue", money(r.trocoCentavos)],
          ["Sangrias", money(r.sangriasCentavos)],
          ["Reforços", money(r.reforcosCentavos)],
          ["Descontos líquidos", money(r.descontosCentavos)],
          ["Vendas de comandas quitadas", money(r.vendasLiquidadasCentavos)],
          ["Cancelamentos no turno", r.cancelamentosOperacionais],
          ["Estornos em dinheiro", money(r.estornosDinheiroCentavos)],
        ]);
        const methods = el("div", "", "attendance-grid");
        for (const m of r.meios) {
          const card = el("article", "", "attendance-card");
          card.append(
            el(
              "h4",
              {
                DINHEIRO: "Dinheiro",
                PIX: "Pix",
                DEBITO: "Débito",
                CREDITO: "Crédito",
              }[m.meio],
            ),
          );
          pairs(card, [
            ["Recebimentos", money(m.recebidoCentavos)],
            ["Compensações", money(m.estornadoCentavos)],
            ["Líquido", money(m.liquidoCentavos)],
            ["Recebimentos registrados", m.transacoes],
          ]);
          methods.append(card);
        }
        root.append(
          methods,
          el(
            "p",
            "Equipe atual: " +
              current.operadores
                .filter((o) => o.ativo)
                .map((o) => o.nome)
                .join(", "),
          ),
          button("Atualizar caixa", load),
        );
        const member = current.operadores.some(
          (o) => o.ativo && o.vinculoId === scope.ator,
        );
        if (!member)
          root.append(
            el(
              "p",
              "Você não integra a equipe deste turno. O responsável deve autorizar seu vínculo antes de operar.",
              "financial-warning",
            ),
          );
        for (const [type, label, cap] of [
          ["SANGRIA", "Registrar sangria", "CAIXA_SANGRIA_REGISTRAR"],
          ["REFORCO", "Registrar reforço", "CAIXA_SUPRIMENTO_REGISTRAR"],
        ])
          if (member && allowed(cap))
            root.append(
              button(label, () =>
                review(
                  label,
                  [
                    { key: "valor", label: "Valor em dinheiro (R$)" },
                    {
                      key: "motivo",
                      label: "Motivo obrigatório",
                      type: "textarea",
                    },
                  ],
                  (v) => ({
                    tipo: type,
                    valorCentavos: parseMoney(v.valor, { zero: false }),
                    motivo: v.motivo.trim(),
                    versaoTurno: current.versao,
                  }),
                  (d) => [
                    (type === "SANGRIA" ? "Retirada: " : "Entrada: ") +
                      money(d.valorCentavos),
                    "Motivo: " + d.motivo,
                    "Não representa venda.",
                  ],
                  "caixa/turnos/" + current.id + "/movimentos",
                  type === "SANGRIA"
                    ? { tipo: "SANGRIA", entidadeId: current.id }
                    : null,
                ),
              ),
            );
        if (member && allowed("CAIXA_CONFERIR"))
          root.append(
            button("Conferir dinheiro e preparar fechamento", () =>
              review(
                "Conferir caixa",
                [
                  { key: "valor", label: "Dinheiro contado fisicamente (R$)" },
                  {
                    key: "motivo",
                    label: "Motivo da diferença, quando houver",
                    type: "textarea",
                    required: false,
                  },
                ],
                (v) => ({
                  informadoCentavos: parseMoney(v.valor),
                  motivo: v.motivo.trim() || null,
                  versaoTurno: current.versao,
                }),
                (d) => [
                  "Esperado: " + money(r.dinheiroEsperadoCentavos),
                  "Contado: " + money(d.informadoCentavos),
                  "Diferença: " +
                    money(
                      BigInt(d.informadoCentavos) -
                        BigInt(r.dinheiroEsperadoCentavos),
                    ),
                ],
                "caixa/turnos/" + current.id + "/conferencias",
                null,
                async (conference) => {
                  if (!live(g)) return;
                  area.removeAttribute("aria-busy");
                  area.replaceChildren(el("h3", "Conferência registrada"));
                  pairs(area, [
                    ["Esperado", money(conference.esperadoCentavos)],
                    ["Contado", money(conference.informadoCentavos)],
                    ["Diferença", money(conference.diferencaCentavos)],
                  ]);
                  if (allowed("CAIXA_FECHAR"))
                    area.append(
                      button("Revisar fechamento do caixa", () =>
                        review(
                          "Fechar caixa",
                          [],
                          () => ({
                            conferenciaId: conference.id,
                            versaoTurno: conference.versaoTurno,
                          }),
                          () => [
                            "Dinheiro contado: " +
                              money(conference.informadoCentavos),
                            "Diferença: " + money(conference.diferencaCentavos),
                            "O servidor verificará comandas com consumo, entregas, estornos e a validade desta conferência.",
                          ],
                          "caixa/turnos/" + current.id + "/fechamento",
                          { tipo: "FECHAMENTO", entidadeId: current.id },
                        ),
                      ),
                    );
                  else
                    area.append(
                      el(
                        "p",
                        "Solicite o fechamento a um operador com permissão específica.",
                      ),
                    );
                  area.append(button("Voltar ao caixa", load));
                },
              ),
            ),
          );
        if (allowed("CAIXA_OPERADORES_CONFIGURAR"))
          root.append(
            button("Gerenciar operadores e responsável", async () => {
              try {
                const candidates = await request("caixa/candidatos");
                if (!live(g)) return;
                const f = el("form", "", "attendance-form");
                f.append(el("h3", "Equipe do turno"));
                const boxes = [];
                for (const c of candidates) {
                  const label = el("label", c.nome),
                    input = el("input");
                  input.type = "checkbox";
                  input.checked = current.operadores.some(
                    (o) => o.ativo && o.vinculoId === c.vinculoId,
                  );
                  input.value = c.vinculoId;
                  label.prepend(input);
                  f.append(label);
                  boxes.push(input);
                }
                const label = el("label", "Responsável principal"),
                  select = el("select");
                select.id = "cash-team-responsible";
                label.htmlFor = select.id;
                for (const c of candidates) {
                  const o = el("option", c.nome);
                  o.value = c.vinculoId;
                  select.append(o);
                }
                select.value = current.responsavelId;
                const reason = el("textarea");
                reason.id = "cash-team-reason";
                reason.required = true;
                reason.maxLength = 500;
                const rl = el("label", "Motivo da alteração");
                rl.htmlFor = reason.id;
                const save = el("button", "Salvar equipe", "primary");
                save.type = "submit";
                f.append(
                  label,
                  select,
                  rl,
                  reason,
                  save,
                  button("Voltar", load),
                  el(
                    "p",
                    "Adicionar alguém à equipe não concede permissões. Os limites e o PIN são revalidados no servidor.",
                  ),
                );
                area.replaceChildren(f);
                f.addEventListener("submit", async (e) => {
                  e.preventDefault();
                  save.disabled = true;
                  try {
                    await request(
                      "caixa/turnos/" + current.id + "/equipe",
                      "PUT",
                      {
                        operadores: boxes
                          .filter((x) => x.checked)
                          .map((x) => x.value),
                        responsavelId: select.value,
                        versao: current.versao,
                        motivo: reason.value.trim(),
                      },
                    );
                    if (live(g)) await load();
                  } catch (err) {
                    if (live(g)) onError(err);
                  } finally {
                    save.disabled = false;
                  }
                });
              } catch (e) {
                if (live(g)) onError(e);
              }
            }),
          );
        ledger(root, r.lancamentos);
      }
      if (allowed("CAIXA_HISTORICO_CONSULTAR")) {
        const turns = await request("caixa/turnos");
        if (!live(g)) return;
        const hist = el("details");
        hist.append(el("summary", "Turnos e relatórios de fechamento"));
        for (const t of turns) {
          const item = el("article", "", "attendance-card");
          item.append(
            el("h4", t.estado === "ABERTO" ? "Turno aberto" : "Turno fechado"),
            el(
              "p",
              new Date(t.abertoEm).toLocaleString("pt-BR") +
                " · " +
                (t.responsavelNome || "Responsável"),
            ),
            button("Ver relatórios deste turno", async () => {
              try {
                const reports = await request(
                  "caixa/turnos/" + t.id + "/relatorios",
                );
                if (live(g)) drawClosing(item, reports);
              } catch (e) {
                if (live(g)) onError(e);
              }
            }),
          );
          hist.append(item);
        }
        root.append(hist);
      }
      root.append(area);
    } catch (e) {
      if (live(g)) onError(e);
    }
  }
  function drawClosing(parent, reports) {
    parent.querySelector(".closing-results")?.remove();
    const out = el("section", "", "closing-results");
    if (!reports.length) out.append(el("p", "Nenhum fechamento registrado."));
    for (const report of reports) {
      const card = el("article", "", "attendance-card");
      card.append(
        el("h4", "Fechamento · ciclo " + report.ciclo),
        el("p", new Date(report.criadoEm).toLocaleString("pt-BR")),
      );
      const d = report.dados;
      for (const [key, label] of [
        ["resumo", "Resumo"],
        ["conferencia", "Conferência"],
      ])
        if (d[key]) {
          const s = d[key];
          for (const [k, v] of Object.entries(s))
            if (k.endsWith("Centavos") && v !== null)
              pairs(card, [
                [
                  k
                    .replace(/Centavos$/, "")
                    .replace(/[A-Z]/g, (c) => " " + c.toLowerCase()),
                  money(v),
                ],
              ]);
        }
      out.append(card);
    }
    parent.append(out);
  }
  void load();
  return {
    refresh: load,
    destroy() {
      disposed = true;
      version++;
      form?.destroy();
      root.replaceChildren();
    },
  };
}
