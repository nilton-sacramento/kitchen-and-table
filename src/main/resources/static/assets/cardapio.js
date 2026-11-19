const el = (tag, text = "", cls = "") => {
  const e = document.createElement(tag);
  e.textContent = text;
  e.className = cls;
  return e;
};
export function parseCents(value) {
  const s = String(value).trim().replace(",", ".");
  if (!/^\d{1,8}(?:\.\d{1,2})?$/.test(s))
    throw Error("Informe preço positivo ou zero, com até duas casas decimais.");
  const [a, b = ""] = s.split(".");
  const cents = BigInt(a) * 100n + BigInt(b.padEnd(2, "0"));
  if (cents > 9999999999n) throw Error("Preço acima do limite.");
  return Number(cents);
}
export function formatCents(cents) {
  const n = BigInt(cents);
  return (
    "R$ " + (n / 100n).toString() + "," + (n % 100n).toString().padStart(2, "0")
  );
}
export function renderOperationalMenu(root, data) {
  const search = el("input");
  search.type = "search";
  search.placeholder = "Buscar produto ou combo";
  search.setAttribute("aria-label", "Buscar no cardápio");
  const grid = el("div", "", "attendance-grid");
  function render() {
    grid.replaceChildren();
    for (const p of data.produtos.filter((p) =>
      p.nome
        .toLocaleLowerCase("pt-BR")
        .includes(search.value.toLocaleLowerCase("pt-BR")),
    )) {
      const card = el("article", "", "attendance-card");
      card.append(
        el("h3", p.nome),
        el("p", formatCents(p.precoCentavos)),
        el(
          "p",
          p.categoria +
            " · " +
            (p.tipo === "COMBO"
              ? "Combo"
              : p.setor === "BAR"
                ? "Bar"
                : "Cozinha"),
        ),
        el("p", p.vendavel ? "Disponível" : "Indisponível", "attendance-state"),
      );
      if (p.descricao) card.append(el("p", p.descricao));
      if (p.tipo === "COMBO")
        for (const c of p.componentes) {
          const item = data.produtos.find((x) => x.id === c.produtoId);
          card.append(
            el("p", c.quantidade + " × " + (item?.nome || "Componente")),
          );
        }
      grid.append(card);
    }
    if (!grid.children.length)
      grid.append(el("p", "Nenhum produto encontrado."));
  }
  search.addEventListener("input", render);
  root.replaceChildren(el("h3", "Cardápio da unidade"), search, grid);
  render();
}
export function createCardapioScreen({
  root,
  api,
  getEstablishments,
  onExpired,
}) {
  let unit = null,
    generation = 0;
  const button = (text, fn) => {
    const b = el("button", text, "secondary");
    b.type = "button";
    b.addEventListener("click", fn);
    return b;
  };
  function reset() {
    generation++;
    unit = null;
    root.replaceChildren();
  }
  async function activate(id) {
    reset();
    unit = id;
    const current = generation;
    let busy = false,
      data = null,
      permissions = [];
    if (!id) return;
    root.replaceChildren(el("p", "Consultando cardápio…"));
    const path = "estabelecimentos/" + id + "/cardapio";
    const message = el("p", "", "error");
    message.role = "alert";
    message.hidden = true;
    const editor = el("section"),
      list = el("div", "", "attendance-grid"),
      status = el("p");
    status.role = "status";
    const search = el("input");
    search.type = "search";
    search.placeholder = "Buscar por nome";
    search.setAttribute("aria-label", "Buscar produtos");
    const filter = el("select");
    filter.setAttribute("aria-label", "Filtrar disponibilidade");
    for (const [v, t] of [
      ["todos", "Todos"],
      ["disponiveis", "Disponíveis"],
      ["indisponiveis", "Indisponíveis"],
      ["combos", "Combos"],
    ]) {
      const o = el("option", t);
      o.value = v;
      filter.append(o);
    }
    const allowed = (c) =>
      permissions.some((p) => p.permissao.codigo === c && p.efetiva);
    function fail(e) {
      if (current !== generation) return;
      if (e.status === 401) {
        reset();
        onExpired();
        return;
      }
      message.hidden = false;
      message.textContent =
        e.detail ||
        e.message ||
        "Não foi possível confirmar. Atualize a lista antes de tentar novamente.";
    }
    async function request(suffix = "", method = "GET", body) {
      let headers = {};
      if (method !== "GET") {
        const c = await api("auth/csrf");
        headers = {
          [c.headerName]: c.token,
          "Content-Type": "application/json",
        };
      }
      if (current !== generation) throw Error("Contexto alterado");
      return api(path + suffix, {
        method,
        headers,
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
    }
    function label(form, text, input) {
      const l = el("label", text);
      input.id = "cardapio-" + crypto.randomUUID();
      l.htmlFor = input.id;
      form.append(l, input);
      return input;
    }
    const input = (type = "text", value = "") => {
      const i = el("input");
      i.type = type;
      i.value = value;
      return i;
    };
    function select(values, value) {
      const s = el("select");
      for (const [v, t] of values) {
        const o = el("option", t);
        o.value = v;
        s.append(o);
      }
      s.value = value;
      return s;
    }
    const toggle = (form, text, value) => {
      const i = input("checkbox");
      i.checked = value;
      label(form, text, i);
      return i;
    };
    function form(title, handler) {
      if (busy) return null;
      editor.replaceChildren();
      const f = el("form", "", "attendance-form");
      const save = el("button", "Salvar", "primary");
      save.type = "submit";
      f.append(el("h3", title));
      f.addEventListener("submit", async (e) => {
        e.preventDefault();
        if (busy || current !== generation) return;
        busy = true;
        save.disabled = true;
        f.setAttribute("aria-busy", "true");
        message.hidden = true;
        try {
          await handler(f);
          if (current === generation) {
            editor.replaceChildren();
            await load();
          }
        } catch (err) {
          fail(err);
        } finally {
          busy = false;
          save.disabled = false;
          f.removeAttribute("aria-busy");
        }
      });
      editor.append(f);
      return {
        f,
        finish() {
          f.append(
            save,
            button("Cancelar", () => {
              if (!busy) editor.replaceChildren();
            }),
          );
          f.querySelector("input,select")?.focus();
        },
      };
    }
    function category(c = null) {
      const view = form(c ? "Editar categoria" : "Nova categoria", async () =>
        request("/categorias" + (c ? "/" + c.id : ""), c ? "PUT" : "POST", {
          nome: name.value,
          ordem: Number(order.value),
          ativo: active.checked,
          ...(c ? { versao: c.versao } : {}),
        }),
      );
      if (!view) return;
      const name = label(
        view.f,
        "Nome da categoria",
        input("text", c?.nome || ""),
      );
      name.required = true;
      name.maxLength = 80;
      name.dataset.field = "categoria-nome";
      const order = label(view.f, "Ordem", input("number", c?.ordem || 0));
      order.min = 0;
      const active = toggle(view.f, "Categoria ativa", c?.ativo ?? true);
      view.finish();
    }
    function product(p = null) {
      if (!data.categorias.length) {
        message.textContent = "Cadastre uma categoria antes do produto.";
        message.hidden = false;
        return;
      }
      const view = form(
        p ? "Editar produto" : "Novo produto ou combo",
        async () => {
          const isCombo = type.value === "COMBO";
          const components = [
            ...componentBox.querySelectorAll("input[data-product]"),
          ]
            .filter((x) => x.checked)
            .map((x) => ({
              produtoId: x.dataset.product,
              quantidade: Number(
                componentBox.querySelector(
                  '[data-quantity="' + x.dataset.product + '"]',
                ).value,
              ),
            }));
          return request(
            "/produtos" + (p ? "/" + p.id : ""),
            p ? "PUT" : "POST",
            {
              nome: name.value,
              descricao: description.value || null,
              categoriaId: cat.value,
              tipo: type.value,
              precoCentavos: parseCents(price.value),
              setor: isCombo ? null : sector.value,
              ativo: active.checked,
              disponivel: available.checked,
              controlaQuantidade: isCombo ? false : stock.checked,
              quantidade: p?.quantidade || 0,
              componentes: isCombo ? components : [],
              ...(p ? { versao: p.versao } : {}),
            },
          );
        },
      );
      if (!view) return;
      const name = label(view.f, "Nome", input("text", p?.nome || ""));
      name.required = true;
      name.maxLength = 120;
      name.dataset.field = "produto-nome";
      const description = label(
        view.f,
        "Descrição (opcional)",
        input("text", p?.descricao || ""),
      );
      description.maxLength = 1000;
      const cat = label(
        view.f,
        "Categoria",
        select(
          data.categorias.map((c) => [
            c.id,
            c.nome + (c.ativo ? "" : " (inativa)"),
          ]),
          p?.categoriaId || data.categorias[0].id,
        ),
      );
      const type = label(
        view.f,
        "Tipo",
        select(
          [
            ["SIMPLES", "Produto simples"],
            ["COMBO", "Combo"],
          ],
          p?.tipo || "SIMPLES",
        ),
      );
      type.dataset.field = "produto-tipo";
      type.disabled = !!p;
      const price = label(
        view.f,
        "Preço de venda (R$)",
        input("text", p ? formatCents(p.precoCentavos).slice(3) : "0,00"),
      );
      price.inputMode = "decimal";
      price.required = true;
      price.dataset.field = "produto-preco";
      price.disabled = !!p && !allowed("PRODUTO_PRECO_ALTERAR");
      const sector = label(
        view.f,
        "Setor responsável",
        select(
          [
            ["COZINHA", "Cozinha"],
            ["BAR", "Bar"],
          ],
          p?.setor || "COZINHA",
        ),
      );
      sector.dataset.field = "produto-setor";
      const active = toggle(view.f, "Ativo", p?.ativo ?? true),
        available = toggle(
          view.f,
          "Disponível para novos pedidos",
          p?.disponivel ?? true,
        ),
        stock = toggle(
          view.f,
          "Controlar quantidade (inicial: zero)",
          p?.controlaQuantidade ?? false,
        );
      stock.dataset.field = "produto-estoque";
      if (p) stock.disabled = !allowed("ESTOQUE_AJUSTAR");
      const componentBox = el("fieldset");
      componentBox.append(el("legend", "Componentes do combo"));
      for (const simple of data.produtos.filter((x) => x.tipo === "SIMPLES")) {
        const row = el("div", "", "attendance-actions");
        const c = p?.componentes.find((c) => c.produtoId === simple.id);
        const choose = input("checkbox");
        choose.dataset.product = simple.id;
        choose.checked = !!c;
        label(row, simple.nome, choose);
        const qty = input("number", c?.quantidade || 1);
        qty.min = 1;
        qty.max = 1000;
        qty.dataset.quantity = simple.id;
        qty.setAttribute("aria-label", "Quantidade de " + simple.nome);
        row.append(qty);
        componentBox.append(row);
      }
      view.f.append(componentBox);
      const refresh = () => {
        const combo = type.value === "COMBO";
        componentBox.hidden = !combo;
        sector.disabled = combo;
        stock.disabled = combo || (!!p && !allowed("ESTOQUE_AJUSTAR"));
      };
      type.addEventListener("change", refresh);
      refresh();
      view.finish();
    }
    function adjust(p) {
      const view = form("Ajustar quantidade de " + p.nome, async () =>
        request("/produtos/" + p.id + "/ajustes", "POST", {
          quantidade: Number(qty.value),
          motivo: reason.value,
          versao: p.versao,
        }),
      );
      if (!view) return;
      const qty = label(
        view.f,
        "Quantidade contada",
        input("number", p.quantidade),
      );
      qty.min = 0;
      qty.required = true;
      qty.dataset.field = "estoque-quantidade";
      const reason = label(view.f, "Motivo do ajuste", input());
      reason.required = true;
      reason.maxLength = 500;
      reason.dataset.field = "estoque-motivo";
      view.finish();
    }
    async function history(p) {
      editor.replaceChildren(el("p", "Consultando histórico…"));
      try {
        const events = await request("/produtos/" + p.id + "/historico");
        if (current !== generation) return;
        editor.replaceChildren(el("h3", "Histórico de " + p.nome));
        for (const e of events)
          editor.append(
            el(
              "p",
              e.tipo.replaceAll("_", " ") +
                " · " +
                new Date(e.ocorridoEm).toLocaleString("pt-BR"),
            ),
          );
        if(allowed("ESTOQUE_CONSULTAR")){
          const movements=await request('/produtos/'+p.id+'/movimentos');
          if(current!==generation)return;
          editor.append(el('h4','Histórico de quantidades'));
          if(!movements.length)editor.append(el('p','Nenhum ajuste registrado.'));
          for(const m of movements)editor.append(el('p',m.anterior+' → '+m.posterior+' · '+m.motivo+' · '+new Date(m.ocorridoEm).toLocaleString('pt-BR')));
        }
        editor.append(
          button("Fechar histórico", () => editor.replaceChildren()),
        );
      } catch (e) {
        fail(e);
      }
    }
    function copy() {
      editor.replaceChildren();
      const f = el("form", "", "attendance-form");
      f.append(
        el("h3", "Copiar cardápio para outra unidade"),
        el(
          "p",
          "Novos identificadores, preços independentes e quantidade inicial zero. Nenhum item existente é sobrescrito.",
        ),
      );
      const dest = label(
        f,
        "Unidade de destino",
        select(
          getEstablishments()
            .filter((e) => e.id !== id)
            .map((e) => [e.id, e.nome]),
          "",
        ),
      );
      const prefix = label(
        f,
        "Prefixo para resolver conflitos (opcional)",
        input(),
      );
      prefix.maxLength = 40;
      const previewButton = el("button", "Gerar prévia", "primary");
      previewButton.type = "submit";
      const detail = el("div");
      let preview = null;
      f.append(previewButton, detail);
      f.addEventListener("submit", async (e) => {
        e.preventDefault();
        if (busy) return;
        busy = true;
        previewButton.disabled = true;
        try {
          preview = await request("/copias/previa", "POST", {
            destino: dest.value,
            prefixo: prefix.value || null,
          });
          if (current !== generation) return;
          detail.replaceChildren(
            el(
              "p",
              preview.categorias +
                " categoria(s), " +
                preview.produtos +
                " produto(s)/combo(s).",
            ),
          );
          for (const c of preview.conflitos) detail.append(el("p", c, "error"));
          if (!preview.conflitos.length)
            detail.append(
              button("Confirmar cópia", async () => {
                if (busy || !preview) return;
                busy = true;
                try {
                  await request("/copias/" + preview.id + "/confirmar", "POST");
                  if (current === generation) {
                    editor.replaceChildren(
                      el(
                        "p",
                        "Cópia confirmada. O estoque da origem permaneceu intacto.",
                      ),
                    );
                    await load();
                  }
                } catch (err) {
                  fail(err);
                } finally {
                  busy = false;
                }
              }),
            );
        } catch (err) {
          fail(err);
        } finally {
          busy = false;
          previewButton.disabled = false;
        }
      });
      editor.append(f);
    }
    function render() {
      if (!data) return;
      list.replaceChildren();
      status.textContent =
        data.produtos.length + " produto(s)/combo(s) nesta unidade.";
      for (const p of data.produtos.filter(
        (p) =>
          p.nome
            .toLocaleLowerCase("pt-BR")
            .includes(search.value.toLocaleLowerCase("pt-BR")) &&
          (filter.value === "todos" ||
            (filter.value === "combos" && p.tipo === "COMBO") ||
            (filter.value === "disponiveis" && p.vendavel) ||
            (filter.value === "indisponiveis" && !p.vendavel)),
      )) {
        const card = el("article", "", "attendance-card");
        card.append(
          el("h3", p.nome),
          el("p", formatCents(p.precoCentavos)),
          el(
            "p",
            p.categoria + " · " + (p.tipo === "COMBO" ? "Combo" : p.setor),
          ),
          el(
            "p",
            p.vendavel ? "Disponível" : "Indisponível",
            "attendance-state",
          ),
        );
        if (p.controlaQuantidade)
          card.append(el("p", "Quantidade: " + p.quantidade));
        if (allowed("PRODUTO_GERENCIAR"))
          card.append(
            button("Editar produto", () => product(p)),
            button("Histórico", () => history(p)),
          );
        if (
          allowed("ESTOQUE_AJUSTAR") &&
          p.controlaQuantidade &&
          p.tipo === "SIMPLES"
        )
          card.append(button("Ajustar quantidade", () => adjust(p)));
        list.append(card);
      }
      categoryList.replaceChildren();
      for (const c of data.categorias) {
        const line = el("div", "", "attendance-actions");
        line.append(
          el(
            "span",
            c.ordem + " · " + c.nome + " · " + (c.ativo ? "Ativa" : "Inativa"),
          ),
        );
        if (allowed("PRODUTO_GERENCIAR"))
          line.append(button("Editar categoria", () => category(c)));
        categoryList.append(line);
      }
    }
    const categoryList = el("div");
    async function load() {
      try {
        const [catalog, caps] = await Promise.all([
          request(),
          api("estabelecimentos/" + id + "/permissoes/minhas"),
        ]);
        if (current !== generation) return;
        data = catalog;
        permissions = caps;
        render();
        toolbar.replaceChildren(
          button("Atualizar cardápio", () => {
            editor.replaceChildren();
            void load();
          }),
        );
        if (allowed("PRODUTO_GERENCIAR"))
          toolbar.append(button("Nova categoria", () => category()));
        if (
          allowed("PRODUTO_GERENCIAR") &&
          allowed("PRODUTO_PRECO_ALTERAR") &&
          allowed("PRODUTO_DISPONIBILIDADE_ALTERAR")
        )
          toolbar.append(button("Novo produto ou combo", () => product()));
        if (allowed("CARDAPIO_COPIAR"))
          toolbar.append(button("Copiar cardápio", copy));
      } catch (e) {
        fail(e);
      }
    }
    const toolbar = el("div", "", "attendance-actions");
    search.addEventListener("input", render);
    filter.addEventListener("change", render);
    root.replaceChildren(
      el("h2", "Cardápio, produtos e combos"),
      message,
      toolbar,
      editor,
      el("h3", "Categorias"),
      categoryList,
      search,
      filter,
      status,
      list,
    );
    await load();
  }
  return { activate, reset };
}
