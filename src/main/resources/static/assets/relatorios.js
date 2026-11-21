import { money } from "./financeiro.js";
import {
  el,
  button,
  field,
  pairs,
  ledger,
  paymentNames,
} from "./financeiro-ui.js";
export function createRelatorioScreen({
  root,
  request,
  onError,
  isCurrent = () => true,
  employees = false,
  units = [],
  consolidate,
}) {
  let disposed = false,
    generation = 0;
  const live = (g) => !disposed && isCurrent() && g === generation;
  const g = ++generation;
  root.replaceChildren(
    el("h3", "Relatórios financeiros"),
    el(
      "p",
      "Recebimentos no período e vendas de comandas quitadas são apresentados separadamente. Reforços e abertura não são receita. Datas no fuso deste dispositivo.",
      "muted",
    ),
  );
  const form = el("form", "", "attendance-form"),
    now = new Date(),
    start = new Date(now);
  start.setHours(0, 0, 0, 0);
  const local = (d) =>
    new Date(d.getTime() - d.getTimezoneOffset() * 60000)
      .toISOString()
      .slice(0, 16);
  const from = field(form, {
      key: "report-from",
      label: "Início (inclusive)",
      type: "datetime-local",
      value: local(start),
    }),
    to = field(form, {
      key: "report-to",
      label: "Fim (exclusive)",
      type: "datetime-local",
      value: local(new Date(now.getTime() + 60000)),
    });
  let person = null;
  const submit = el("button", "Consultar relatório", "primary");
  submit.type = "submit";
  const output = el("section");
  form.append(submit);
  root.append(form, output);
  if (employees)
    request("relatorios-financeiros/funcionarios")
      .then((list) => {
        if (!live(g)) return;
        person = field(form, {
          key: "report-person",
          label: "Funcionário (opcional)",
          type: "select",
          required: false,
          options: [
            { value: "", label: "Todos os autorizados" },
            ...list.map((x) => ({
              value: x.vinculoId,
              label: x.nome + (x.ativo ? "" : " · vínculo inativo"),
            })),
          ],
        });
      })
      .catch((e) => {
        if (live(g)) onError(e);
      });
  let boxes = [];
  if (consolidate && units.length > 1) {
    const group = el("fieldset");
    group.append(el("legend", "Consolidar unidades próprias autorizadas"));
    for (const u of units) {
      const l = el("label", u.nome),
        i = el("input");
      i.type = "checkbox";
      i.value = u.id;
      l.prepend(i);
      group.append(l);
      boxes.push(i);
    }
    form.append(
      group,
      el(
        "p",
        "Selecione unidades para consolidar; sem seleção, consulta somente a unidade atual.",
      ),
    );
  }
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    if (submit.disabled) return;
    submit.disabled = true;
    output.replaceChildren(el("p", "Consultando período…"));
    try {
      const begin = new Date(from.value),
        end = new Date(to.value);
      if (!(begin < end)) throw Error("O início deve ser anterior ao fim.");
      const q = new URLSearchParams({
        inicio: begin.toISOString(),
        fim: end.toISOString(),
      });
      if (person?.value) q.set("funcionario", person.value);
      const selected = boxes.filter((x) => x.checked).map((x) => x.value);
      if (selected.length && person?.value)
        throw Error(
          "Consulte o funcionário dentro da unidade, sem consolidação.",
        );
      const r = selected.length
        ? await consolidate(selected, q)
        : await request("relatorios-financeiros?" + q);
      if (!live(g)) return;
      output.replaceChildren();
      if (r.unidades) {
        pairs(output, [
          ["Vendas liquidadas consolidadas", money(r.vendasLiquidadasCentavos)],
        ]);
        for (const u of r.unidades) draw(output, u);
      } else draw(output, r);
    } catch (err) {
      if (live(g)) {
        output.replaceChildren(el("p", err.detail || err.message, "error"));
        if ([401, 403, 423].includes(err.status)) onError(err);
      }
    } finally {
      if (live(g)) submit.disabled = false;
    }
  });
  function draw(parent, r) {
    const block = el("section", "", "financial-report");
    block.append(
      el("h3", r.estabelecimento),
      el("p", "Fuso da unidade: " + r.fusoHorario),
    );
    pairs(block, [
      ["Vendas de comandas quitadas", money(r.vendasLiquidadasCentavos)],
      ["Comandas quitadas", r.comandasLiquidadas],
      ["Descontos líquidos", money(r.descontosLiquidosCentavos)],
      ["Compensações de pagamento", money(r.estornosCentavos)],
      ["Correções internas de registro", money(r.correcoesRegistroCentavos)],
      ["Estornos de pagamento", money(r.estornosPagamentoCentavos)],
      ["Sangrias (não são despesa de venda)", money(r.sangriasCentavos)],
      ["Reforços (não são receita)", money(r.reforcosCentavos)],
      ["Devoluções externas pendentes", r.estornosExternosPendentes],
      [
        "Cancelamentos operacionais na unidade",
        r.cancelamentosOperacionaisNaUnidade,
      ],
      ["Substituições na unidade", r.substituicoesNaUnidade],
    ]);
    for (const m of r.meios) {
      block.append(el("h4", paymentNames[m.meio]));
      pairs(block, [
        ["Recebimentos", money(m.recebidoCentavos)],
        ["Compensações", money(m.estornadoCentavos)],
        ["Líquido do período", money(m.liquidoCentavos)],
        ["Recebimentos registrados", m.transacoes],
      ]);
    }
    for (const c of r.conferencias) {
      const card = el("article", "", "attendance-card");
      card.append(
        el(
          "h4",
          "Conferência · " + new Date(c.criadoEm).toLocaleString("pt-BR"),
        ),
      );
      pairs(card, [
        ["Esperado", money(c.esperadoCentavos)],
        ["Informado", money(c.informadoCentavos)],
        ["Diferença", money(c.diferencaCentavos)],
      ]);
      block.append(card);
    }
    if (r.detalhesFuncionariosPermitidos) {
      block.append(el("h4", "Lançamentos por responsável"));
      ledger(block, r.lancamentos);
    } else
      block.append(
        el("p", "Detalhes individuais exigem permissão específica."),
      );
    for (const f of r.relatoriosFechamento) {
      const card = el("article", "", "attendance-card");
      card.append(
        el("h4", "Fechamento preservado · ciclo " + f.ciclo),
        el("p", new Date(f.criadoEm).toLocaleString("pt-BR")),
      );
      if (f.dados.conferencia)
        pairs(card, [
          ["Contado", money(f.dados.conferencia.informadoCentavos)],
          ["Diferença", money(f.dados.conferencia.diferencaCentavos)],
        ]);
      block.append(card);
    }
    parent.append(block);
  }
  return {
    destroy() {
      disposed = true;
      generation++;
      root.replaceChildren();
    },
  };
}
