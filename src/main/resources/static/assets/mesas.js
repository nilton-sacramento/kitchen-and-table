// Administração de mesas: dados sempre revalidados no backend, sem cópias em Web Storage.
const node = (tag, text = "", cls = "") => {
  const e = document.createElement(tag);
  e.textContent = text;
  e.className = cls;
  return e;
};
export function createMesaScreen({ root, api, onExpired }) {
  let unit = null,
    generation = 0;
  const button = (text, action) => {
    const b = node("button", text, "secondary");
    b.type = "button";
    b.addEventListener("click", action);
    return b;
  };
  function reset() {
    unit = null;
    generation++;
    root.replaceChildren();
  }
  async function activate(id) {
    reset();
    unit = id;
    const current = generation;
    let busy = false;
    if (!id) return;
    root.append(node("p", "Consultando mesas…", "muted"));
    const path = "estabelecimentos/" + id + "/mesas";
    async function request(suffix = "", method = "GET", body) {
      let headers = {};
      if (method !== "GET") {
        const c = await api("auth/csrf");
        headers = {
          [c.headerName]: c.token,
          "Content-Type": "application/json",
        };
      }
      if (current !== generation) throw new Error("Contexto alterado");
      return api(path + suffix, {
        method,
        headers,
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
    }
    function fail(e) {
      if (current !== generation) return;
      if (e.status === 401) {
        reset();
        onExpired();
        return;
      }
      message.textContent =
        e.detail ||
        "Não foi possível confirmar a operação. Atualize a lista e tente novamente.";
      message.hidden = false;
    }
    const message = node("p", "", "error");
    message.role = "alert";
    message.hidden = true;
    const list = node("div", "", "attendance-grid");
    const status = node("p", "", "muted");
    status.role = "status";
    async function load() {
      try {
        const data = await request();
        if (current !== generation) return;
        list.replaceChildren();
        status.textContent = data.length
          ? data.length + " mesa(s) nesta unidade."
          : "Nenhuma mesa cadastrada nesta unidade.";
        for (const m of data) {
          const card = node("article", "", "attendance-card");
          card.append(
            node("h3", m.identificacao),
            node(
              "p",
              {
                LIVRE: "Livre",
                OCUPADA: "Ocupada",
                INDISPONIVEL: "Indisponível",
              }[m.situacao] || m.situacao,
              "attendance-state",
            ),
          );
          if (m.codigoComanda)
            card.append(node("p", "Comanda " + m.codigoComanda));
          const actions = node("div", "", "attendance-actions");
          actions.append(
            button("Editar identificação", () => edit(m)),
            button(m.ativo ? "Desativar" : "Ativar", () => change(m)),
            button("Histórico", () => history(m)),
          );
          card.append(actions);
          list.append(card);
        }
      } catch (e) {
        fail(e);
      }
    }
    const editor = node("div");
    function edit(m = null) {
      if (busy || current !== generation) return;
      editor.replaceChildren();
      const form = node("form", "", "attendance-form");
      const label = node("label", "Identificação da mesa");
      const input = node("input");
      input.id = "mesa-identificacao";
      input.maxLength = 40;
      input.required = true;
      input.value = m?.identificacao || "";
      label.htmlFor = input.id;
      const save = node(
        "button",
        m ? "Salvar identificação" : "Cadastrar mesa",
        "primary",
      );
      save.type = "submit";
      form.append(
        node("h3", m ? "Editar mesa" : "Nova mesa"),
        label,
        input,
        save,
        button("Cancelar", () => editor.replaceChildren()),
      );
      form.addEventListener("submit", async (e) => {
        e.preventDefault();
        if (busy || current !== generation) return;
        busy = true;
        save.disabled = true;
        message.hidden = true;
        try {
          await request(m ? "/" + m.id : "", m ? "PATCH" : "POST", {
            identificacao: input.value,
            ...(m ? { versao: m.versao } : {}),
          });
          if (current !== generation) return;
          editor.replaceChildren();
          await load();
        } catch (err) {
          fail(err);
        } finally {
          busy = false;
          save.disabled = false;
        }
      });
      editor.append(form);
      input.focus();
    }
    async function change(m) {
      if (busy || current !== generation) return;
      if (
        !confirm(
          (m.ativo ? "Desativar" : "Ativar") +
            " a mesa " +
            m.identificacao +
            "?",
        )
      )
        return;
      busy = true;
      try {
        await request("/" + m.id + "/situacao", "PATCH", {
          ativo: !m.ativo,
          versao: m.versao,
        });
        await load();
      } catch (e) {
        fail(e);
      } finally {
        busy = false;
      }
    }
    async function history(m) {
      editor.replaceChildren(node("p", "Consultando histórico…"));
      try {
        const data = await request("/" + m.id + "/historico");
        if (current !== generation) return;
        editor.replaceChildren(
          node("h3", "Histórico da mesa " + m.identificacao),
        );
        if (!data.length)
          editor.append(node("p", "Nenhum atendimento registrado."));
        for (const c of data)
          editor.append(
            node(
              "p",
              c.codigo +
                " · " +
                c.estado +
                " · " +
                new Date(c.abertoEm).toLocaleString("pt-BR"),
            ),
          );
        editor.append(
          button("Fechar histórico", () => editor.replaceChildren()),
        );
      } catch (e) {
        fail(e);
      }
    }
    root.replaceChildren(
      node("h2", "Mesas do estabelecimento"),
      node(
        "p",
        "Cadastre as mesas físicas. Para atender e abrir comandas, utilize o acesso operacional com PIN.",
        "muted",
      ),
      message,
      button("Nova mesa", () => edit()),
      button("Atualizar mesas", () => {
        editor.replaceChildren();
        void load();
      }),
      editor,
      status,
      list,
    );
    await load();
  }
  return { activate, reset, currentUnit: () => unit };
}
