import {
  money,
  parseMoney,
  parsePercent,
  percent,
  cents,
} from "./financeiro.js";
import {
  el,
  button,
  pairs,
  ledger,
  reviewFinancial,
  paymentNames,
} from "./financeiro-ui.js";
export function createContaScreen({
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
  const base = "caixa/turnos/";
  async function load() {
    form?.destroy();
    const g = ++version;
    root.removeAttribute("aria-busy");
    root.replaceChildren(el("p", "Consultando conta real…"));
    try {
      const c = await request("comandas/" + scope.comanda + "/conta");
      const t =
        allowed("CAIXA_OPERAR") ||
        allowed("CAIXA_ABRIR") ||
        allowed("CAIXA_HISTORICO_CONSULTAR")
          ? await request("caixa/atual")
          : null;
      if (!live(g)) return;
      root.replaceChildren(el("h3", "Conta · " + c.codigo));
      pairs(root, [
        ["Consumo válido", money(c.consumoCentavos)],
        ["Descontos líquidos", money(c.descontosCentavos)],
        ["Pagamentos líquidos", money(c.pagoCentavos)],
        ["Saldo a pagar", money(c.saldoCentavos)],
        ["Situação", c.estado === "ABERTA" ? "Em atendimento" : "Encerrada"],
      ]);
      root.append(
        button("Atualizar conta", load),
        el(
          "p",
          "Preços históricos dos pedidos. Cancelados e originais substituídos não compõem o saldo.",
          "muted small",
        ),
      );
      const orders = el("details");
      orders.append(el("summary", "Pedidos que compõem o histórico"));
      for (const p of c.pedidos) {
        const card = el("article", "", "attendance-card");
        card.append(
          el("h4", "Pedido " + p.codigo + " · " + p.estado),
          el(
            "p",
            p.estado === "CANCELADO"
              ? "Cancelado ou substituído: não cobrado."
              : "Valor histórico: " + money(p.totalCentavos),
          ),
        );
        orders.append(card);
      }
      root.append(orders);
      if (c.solicitacoesPendentes)
        root.append(
          el(
            "p",
            "Há alterações de pedido aguardando decisão. Resolva-as antes de receber.",
            "financial-warning",
          ),
        );
      if (c.possuiItensNaoEntregues)
        root.append(
          el(
            "p",
            "Há itens ainda não entregues. A quitação final exige a conclusão das entregas.",
            "financial-warning",
          ),
        );
      const can =
        t?.estado === "ABERTO" &&
        t.operadores.some((o) => o.ativo && o.vinculoId === scope.ator) &&
        c.estado === "ABERTA";
      if (!t)
        root.append(
          el(
            "p",
            "Operações exigem um caixa aberto acessível, participação na equipe e as permissões financeiras correspondentes.",
          ),
        );
      const area = el("section");
      const sc = { ...scope, turno: t?.id };
      const review = (title, fields, body, preview, path, approval) => {
        form?.destroy();
        form = reviewFinancial({
          root: area,
          request,
          live: () => live(g),
          onError,
          scope: sc,
          title,
          fields,
          body,
          preview,
          path,
          approval,
          onSuccess: load,
          onBack: load,
        });
        area.scrollIntoView({ block: "nearest" });
      };
      if (can && allowed("PAGAMENTO_REGISTRAR") && cents(c.saldoCentavos) > 0n)
        root.append(
          button(
            "Receber pagamento",
            () =>
              review(
                "Receber pagamento",
                [
                  {
                    key: "meio",
                    label: "Meio de pagamento",
                    type: "select",
                    options: Object.entries(paymentNames).map(
                      ([value, label]) => ({ value, label }),
                    ),
                  },
                  {
                    key: "valor",
                    label: "Valor aplicado à conta (R$)",
                    value: "",
                  },
                  {
                    key: "recebido",
                    label:
                      "Dinheiro entregue pelo cliente (R$; somente dinheiro)",
                    required: false,
                  },
                  {
                    key: "externo",
                    label:
                      "Conferi o recebimento na conta Pix ou na maquininha; não é confirmação automática",
                    type: "checkbox",
                    required: false,
                  },
                ],
                (v) => {
                  const valor = parseMoney(v.valor, { zero: false });
                  if (cents(valor) > cents(c.saldoCentavos))
                    throw Error("O valor supera o saldo atual.");
                  const received =
                    v.meio === "DINHEIRO"
                      ? parseMoney(v.recebido, { zero: false })
                      : null;
                  if (received !== null && cents(received) < cents(valor))
                    throw Error("O dinheiro recebido é menor que o pagamento.");
                  if (v.meio !== "DINHEIRO" && !v.externo)
                    throw Error(
                      "Confira o recebimento externo antes de continuar.",
                    );
                  if (v.meio !== "DINHEIRO" && v.recebido.trim())
                    throw Error(
                      "Dinheiro entregue só se aplica ao meio Dinheiro.",
                    );
                  return {
                    meio: v.meio,
                    valorCentavos: valor,
                    recebidoCentavos: received,
                    confirmadoExternamente: v.externo,
                    revisaoConta: c.revisao,
                  };
                },
                (d) => [
                  paymentNames[d.meio] + " · " + money(d.valorCentavos),
                  ...(d.meio === "DINHEIRO"
                    ? [
                        "Dinheiro entregue: " + money(d.recebidoCentavos),
                        "Troco: " +
                          money(
                            cents(d.recebidoCentavos) - cents(d.valorCentavos),
                          ),
                      ]
                    : ["Recebimento externo conferido manualmente."]),
                ],
                base + t.id + "/comandas/" + c.comandaId + "/pagamentos",
              ),
            true,
          ),
        );
      if (
        can &&
        allowed("COMANDA_DESCONTO_APLICAR") &&
        cents(c.saldoCentavos) > 0n
      )
        root.append(
          button("Aplicar desconto", () =>
            review(
              "Aplicar desconto",
              [
                {
                  key: "tipo",
                  label: "Forma do desconto",
                  type: "select",
                  options: [
                    { value: "VALOR", label: "Valor em reais" },
                    { value: "PERCENTUAL", label: "Percentual do consumo" },
                  ],
                },
                { key: "valor", label: "Valor (R$) ou percentual (%)" },
                {
                  key: "motivo",
                  label: "Motivo obrigatório",
                  type: "textarea",
                },
              ],
              (v) => ({
                tipo: v.tipo,
                valorCentavos:
                  v.tipo === "VALOR"
                    ? parseMoney(v.valor, { zero: false })
                    : null,
                percentualBps:
                  v.tipo === "PERCENTUAL" ? parsePercent(v.valor) : null,
                motivo: v.motivo.trim(),
                revisaoConta: c.revisao,
              }),
              (d) => [
                "Desconto: " +
                  money(
                    d.tipo === "VALOR"
                      ? d.valorCentavos
                      : percent(c.consumoCentavos, d.percentualBps),
                  ),
                "Motivo: " + d.motivo,
                "Acima do limite, será necessária autorização específica do proprietário.",
              ],
              base + t.id + "/comandas/" + c.comandaId + "/descontos",
              { tipo: "DESCONTO", entidadeId: c.comandaId },
            ),
          ),
        );
      if (can && allowed("COMANDA_FINANCEIRO_ENCERRAR"))
        root.append(
          button("Quitar e encerrar comanda", () =>
            review(
              "Quitar e encerrar comanda",
              [],
              () => ({ revisaoConta: c.revisao }),
              () => [
                "Consumo: " + money(c.consumoCentavos),
                "Pago: " + money(c.pagoCentavos),
                "Saldo: " + money(c.saldoCentavos),
                "A comanda somente será encerrada se não houver saldo, entregas ou autorizações operacionais pendentes.",
              ],
              base +
                t.id +
                "/comandas/" +
                c.comandaId +
                "/encerramento-financeiro",
            ),
          ),
        );
      const compensate = (l) =>
        review(
          l.tipo === "DESCONTO" ? "Reverter desconto" : "Compensar pagamento",
          [
            ...(l.tipo === "DESCONTO"
              ? []
              : [
                  {
                    key: "natureza",
                    label: "O que aconteceu?",
                    type: "select",
                    options: [
                      {
                        value: "CORRECAO_LANCAMENTO",
                        label:
                          "Corrigir registro incorreto (sem afirmar devolução bancária)",
                      },
                      {
                        value: "ESTORNO_PAGAMENTO",
                        label:
                          "Estornar pagamento (devolução externa exige confirmação)",
                      },
                    ],
                  },
                ]),
            { key: "motivo", label: "Motivo obrigatório", type: "textarea" },
          ],
          (v) => ({
            natureza: l.tipo === "DESCONTO" ? "REVERSAO_DESCONTO" : v.natureza,
            motivo: v.motivo.trim(),
            revisaoConta: c.revisao,
          }),
          (d) => [
            "Compensação integral: " + money(l.valorCentavos),
            "Motivo: " + d.motivo,
            "O registro original será preservado. Exige autorização específica do proprietário.",
          ],
          base + t.id + "/lancamentos/" + l.id + "/compensacao",
          {
            tipo: l.tipo === "DESCONTO" ? "DESCONTO_ESTORNO" : "ESTORNO",
            entidadeId: l.id,
          },
        );
      ledger(root, c.lancamentos, {
        canCompensate: (l) =>
          can &&
          l.turnoId === t.id &&
          allowed(
            l.tipo === "DESCONTO"
              ? "COMANDA_DESCONTO_APLICAR"
              : "PAGAMENTO_ESTORNAR",
          ),
        compensate: can
          ? (l) => {
              if (
                allowed(
                  l.tipo === "DESCONTO"
                    ? "COMANDA_DESCONTO_APLICAR"
                    : "PAGAMENTO_ESTORNAR",
                ) &&
                l.turnoId === t.id
              )
                compensate(l);
              else
                area.replaceChildren(
                  el(
                    "p",
                    "É necessária permissão específica e o turno original aberto.",
                  ),
                );
            }
          : null,
        confirmExternal:
          can && allowed("PAGAMENTO_ESTORNAR")
            ? (l) =>
                review(
                  "Confirmar devolução externa",
                  [
                    {
                      key: "confirmado",
                      label:
                        "Conferi a devolução na conta ou maquininha, fora deste sistema",
                      type: "checkbox",
                    },
                    {
                      key: "motivo",
                      label: "Descrição da conferência (sem dados bancários)",
                      type: "textarea",
                    },
                  ],
                  (v) => ({
                    confirmadoExternamente: v.confirmado,
                    motivo: v.motivo.trim(),
                  }),
                  (d) => [
                    "Devolução externa conferida: " + money(l.valorCentavos),
                    "Este sistema não movimenta a conta bancária.",
                    d.motivo,
                  ],
                  base + t.id + "/lancamentos/" + l.id + "/confirmacao-externa",
                )
            : null,
      });
      root.append(area);
    } catch (e) {
      if (live(g)) onError(e);
    }
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
