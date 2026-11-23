import { formatCents } from "./cardapio.js";
import { el } from "./pedido-ui.js";
import { orderTotal, createOrderIntent } from "./pedido-contratos.js";
export async function renderPedidoEditor({
  c,
  original,
  onReview,
  root,
  request,
  alive,
  button,
  field,
  report,
  mutate,
  comanda,
  editor,
}) {
  root.replaceChildren(el("p", "Consultando ofertas atuais…", "muted"));
  try {
    const offers = await request("pedidos/ofertas");
    if (!alive()) return;
    const cart = [];
    root.replaceChildren();
    const form = el("form", "", "attendance-form pedido-editor");
    form.append(
      el(
        "h3",
        original
          ? "Propor novo pedido para substituir " + original.codigo
          : "Novo pedido · " + c.codigo,
      ),
      el(
        "p",
        "O estoque e os preços serão revalidados no envio. O rascunho fica somente nesta tela e é descartado ao sair, bloquear ou atualizar a página.",
        "muted",
      ),
    );
    const search = field(form, "Buscar produto ou combo", el("input"));
    search.type = "search";
    const select = field(
      form,
      "Produto disponível",
      Object.assign(el("select"), { id: "pedido-produto" }),
    );
    const displayOffers = () => {
      const previous = select.value;
      select.replaceChildren();
      for (const o of offers.filter(
        (o) =>
          o.produto.vendavel &&
          o.produto.nome
            .toLocaleLowerCase("pt-BR")
            .includes(search.value.toLocaleLowerCase("pt-BR")),
      )) {
        const opt = el(
          "option",
          o.produto.nome +
            " · " +
            formatCents(o.produto.precoCentavos) +
            (o.produto.tipo === "COMBO" ? " · Combo" : ""),
        );
        opt.value = o.produto.id;
        select.append(opt);
      }
      if ([...select.options].some((o) => o.value === previous))
        select.value = previous;
    };
    displayOffers();
    search.addEventListener("input", displayOffers);
    const qty = field(
      form,
      "Quantidade",
      Object.assign(el("input"), { id: "pedido-quantidade" }),
    );
    qty.type = "number";
    qty.min = "1";
    qty.max = "1000";
    qty.step = "1";
    qty.value = "1";
    const notes = field(
      form,
      "Observação de preparo (opcional)",
      Object.assign(el("textarea"), { id: "pedido-observacao" }),
    );
    notes.maxLength = 500;
    const summary = el("div", "", "pedido-cart");
    const together = el("input");
    together.type = "checkbox";
    together.id = "pedido-tudo-junto";
    const togetherLabel = el("label", "Cliente pediu tudo junto");
    togetherLabel.htmlFor = together.id;
    togetherLabel.prepend(together);
    const reason = original
      ? field(
          form,
          "Motivo da alteração",
          Object.assign(el("textarea"), { id: "pedido-motivo-alteracao" }),
        )
      : null;
    if (reason) {
      reason.required = true;
      reason.maxLength = 500;
    }
    const review = el("button", "Revisar pedido", "primary");
    review.type = "submit";
    function draw() {
      summary.replaceChildren(el("h4", "Itens selecionados"));
      for (const [index, l] of cart.entries()) {
        const row = el(
          "p",
          l.quantidade +
            " × " +
            l.nome +
            " · " +
            formatCents(BigInt(l.precoCentavos) * BigInt(l.quantidade)) +
            (l.observacao ? " · " + l.observacao : ""),
        );
        row.append(
          button("Remover " + l.nome, () => {
            cart.splice(index, 1);
            draw();
          }),
        );
        summary.append(row);
      }
      summary.append(el("strong", "Total: " + formatCents(orderTotal(cart))));
      review.disabled = !cart.length;
    }
    const add = button("Adicionar item", () => {
      const o = offers.find((o) => o.produto.id === select.value);
      if (!o) return report(Error("Nenhum produto disponível para adicionar."));
      const n = Number(qty.value);
      if (!Number.isInteger(n) || n < 1 || n > 1000)
        return report(Error("Informe quantidade inteira entre 1 e 1.000."));
      if (cart.length >= 30)
        return report(Error("Limite de 30 linhas por pedido."));
      cart.push({
        produtoId: o.produto.id,
        nome: o.produto.nome,
        quantidade: n,
        precoCentavos: o.produto.precoCentavos,
        assinaturaOferta: o.assinaturaOferta,
        observacao: notes.value.trim() || null,
      });
      notes.value = "";
      draw();
    });
    form.append(
      add,
      summary,
      togetherLabel,
      review,
      button("Voltar sem enviar", () => comanda(c)),
    );
    root.append(form);
    draw();
    select.focus();
    form.addEventListener("submit", (e) => {
      e.preventDefault();
      if (!cart.length) return;
      const data = {
        tudoJunto: together.checked,
        linhas: cart.map(({ nome, ...l }) => l),
      };
      const body = original
        ? {
            tipo: "ALTERACAO",
            motivo: reason.value.trim(),
            proposta: data,
            versaoPedido: original.versao,
          }
        : data;
      if (onReview) { void onReview({body,cart:structuredClone(cart),total:orderTotal(cart).toString()}); return; }
      const intent = createOrderIntent(body);
      const panel = el("div", "", "attendance-card pedido-review");
      panel.append(el("h3", "Confira antes de confirmar"));
      for (const l of cart)
        panel.append(
          el(
            "p",
            l.quantidade +
              " × " +
              l.nome +
              " · " +
              formatCents(BigInt(l.precoCentavos) * BigInt(l.quantidade)) +
              (l.observacao ? " · " + l.observacao : ""),
          ),
        );
      panel.append(
        el("strong", "Total: " + formatCents(orderTotal(cart))),
        el(
          "p",
          together.checked
            ? "Entrega: tudo junto"
            : "Entrega: parcial permitida",
        ),
      );
      if (original)
        panel.append(
          el("p", "Motivo: " + reason.value.trim()),
          el(
            "p",
            "A substituição depende dos setores; o pedido original será preservado no histórico.",
          ),
        );
      panel.append(
        button(
          original
            ? "Confirmar solicitação de alteração"
            : "Confirmar e enviar pedido",
          () =>
            mutate(async () => {
              await request(
                original
                  ? "pedidos/" + original.id + "/solicitacoes"
                  : "comandas/" + c.id + "/pedidos",
                "POST",
                intent.body,
                { "Idempotency-Key": intent.key },
              );
              await comanda(c);
            }),
          "primary",
        ),
        button("Recomeçar com ofertas atuais", () => editor(c, original)),
        button("Voltar aos pedidos", () => comanda(c)),
        el(
          "p",
          "Se a conexão falhar, repita esta confirmação: a chave evita duplicidade. Se preço ou composição mudarem, recomece e revise as ofertas atuais.",
          "muted",
        ),
      );
      root.replaceChildren(panel);
    });
  } catch (e) {
    report(e);
  }
}
