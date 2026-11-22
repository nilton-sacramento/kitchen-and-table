import { createExternoScreen } from './externos.js';
import { createCaixaScreen } from './caixa-operacional.js';
import { createContaScreen } from './conta.js';
import { createRelatorioScreen } from './relatorios.js';
import { createPedidosScreen } from "./pedidos.js";
import { renderOperationalMenu } from "./cardapio.js";
// Tela operacional. O header é precondição contra abas antigas, nunca autenticação.
const node = (tag, text = "", cls = "") => {
  const e = document.createElement(tag);
  e.textContent = text;
  e.className = cls;
  return e;
};
const states = {
  ABERTA: "Aberta",
  ENCERRADA: "Encerrada operacionalmente",
  CANCELADA: "Cancelada",
  LIVRE: "Livre",
  OCUPADA: "Ocupada",
  INDISPONIVEL: "Indisponível",
};
export function createOpeningIntent(body, key = crypto.randomUUID()) {
  return Object.freeze({
    key,
    body: Object.freeze({
      modalidade: body.modalidade,
      mesaId: body.mesaId || null,
      cliente: body.cliente?.trim() || null,
    }),
  });
}
export function readAttendanceRoute(hash) {
  const p = new URLSearchParams(hash.replace(/^#/, ""));
  const id = p.get("comanda");
  return {
    page: ["comandas", "cardapio", "cozinha", "bar", "caixa", "relatorios", "delivery", "venda-rapida"].includes(
      p.get("atendimento"),
    )
      ? p.get("atendimento")
      : "mesas",
    id: /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      id || "",
    )
      ? id
      : null,
  };
}
export function createAtendimentoScreen({ root, context, api, onBlocked }) {
  let disposed = false,
    version = 0,
    busy = false,
    pending = null,
    pedidos = null,
    financeiro = null;
  const unit = context.estabelecimentoId,
    actor = context.vinculoId;
  const allowed = (code) =>
    context.permissoes.some((p) => p.efetiva && p.permissao.codigo === code);
  const message = node("p", "", "error");
  message.role = "alert";
  message.hidden = true;
  const content = node("div"),
    navigation = node("nav", "", "attendance-actions");
  navigation.setAttribute("aria-label", "Atendimento");
  root.append(
    node("h2", "Atendimento"),
    node(
      "p",
      "Mesas, pedidos e contas da unidade. Recebimentos exigem permissões financeiras individuais.",
      "muted",
    ),
    navigation,
    message,
    content,
  );
  const button = (text, action, cls = "secondary") => {
    const b = node("button", text, cls);
    b.type = "button";
    b.addEventListener("click", action);
    return b;
  };
  function fail(e) {
    if (disposed) return;
    if ([401,423].includes(e.status)||(e.status===403&&!e.requerAutorizacao)) {
      destroy();
      onBlocked();
      return;
    }
    message.textContent =
      (e instanceof TypeError
        ? "Não foi possível carregar esta tela. Atualize a página ou procure o responsável."
        : e.message) ||
      "Não foi possível confirmar a operação. Confira a conexão e atualize a lista antes de abrir outro atendimento.";
    message.hidden = false;
  }
  async function request(path, method = "GET", body, headers = {}) {
    if (disposed) throw new Error("Tela encerrada");
    return api(
      "operacional/estabelecimentos/" + unit + "/" + path,
      method,
      body,
      { "X-Operador-Vinculo": actor, ...headers },
    );
  }
  function route(page, id = null, push = true) {
    if (disposed) return;
    if (busy) return;
    const hash = "#atendimento=" + page + (id ? "&comanda=" + id : "");
    if (location.hash !== hash)
      history[push ? "pushState" : "replaceState"](null, "", hash);
    void render(page, id);
  }
  async function render(page, id) {
    financeiro?.destroy();
    financeiro=null;
    pedidos?.destroy();
    pedidos = null;
    const current = ++version;
    content.replaceChildren(node("p", "Consultando atendimento…", "muted"));
    message.hidden = true;
    try {
      if (page === 'delivery' || page === 'venda-rapida') {
        const area=node('section');content.replaceChildren(area);
        financeiro=createExternoScreen({root:area,request,allowed,scope:{unidade:unit,ator:actor},tipo:page==='delivery'?'DELIVERY':'VENDA_RAPIDA',isCurrent:()=>!disposed&&current===version,onError:fail,onNavigate:route});
        if(id) await financeiro.detail(id); else await financeiro.list();
        return;
      }
      if (page === 'caixa' || page === 'relatorios') {
        const area=node('section');content.replaceChildren(area);
        if(page==='caixa') {
          if(!['CAIXA_OPERAR','CAIXA_ABRIR','CAIXA_HISTORICO_CONSULTAR'].some(allowed)){area.append(node('p','Consulta financeira não autorizada.'));return;}
          financeiro=createCaixaScreen({root:area,request,allowed,scope:{unidade:unit,ator:actor},onError:fail,isCurrent:()=>!disposed&&current===version});
        } else {
          if(!allowed('RELATORIO_FINANCEIRO_CONSULTAR')){area.append(node('p','Relatório não autorizado.'));return;}
          financeiro=createRelatorioScreen({root:area,request,onError:fail,isCurrent:()=>!disposed&&current===version,employees:allowed('FUNCIONARIO_HISTORICO_FINANCEIRO_CONSULTAR')});
        }
        return;
      }
      if (["cozinha", "bar"].includes(page)) {
        const sector = page.toUpperCase();
        if (!allowed(sector + "_FILA_CONSULTAR")) {
          content.replaceChildren(node("p", "Consulta não autorizada."));
          return;
        }
        const area = node("section");
        content.replaceChildren(area);
        pedidos = createPedidosScreen({
          root: area,
          request,
          allowed,
          onError: fail,
          isCurrent: () => !disposed && current === version,
        });
        await pedidos.queue(sector);
        return;
      }
      if (page === "cardapio") {
        if (!allowed("PRODUTO_CONSULTAR")) {
          content.replaceChildren(node("p", "Consulta não autorizada."));
          return;
        }
        const menu = await request("cardapio");
        if (!disposed && current === version)
          renderOperationalMenu(content, menu);
        return;
      }
      if (id) {
        if (!allowed("COMANDA_CONSULTAR")) {
          content.replaceChildren(
            node(
              "p",
              "Seu acesso permite as filas do setor. Escolha Cozinha ou Bar no menu autorizado.",
            ),
          );
          return;
        }
        await detail(id, current);
        return;
      }
      const code = page === "mesas" ? "MESA_CONSULTAR" : "COMANDA_CONSULTAR";
      if (!allowed(code)) {
        content.replaceChildren(
          node(
            "p",
            "Você não possui permissão para esta consulta. Procure o responsável.",
          ),
        );
        return;
      }
      const data = await request(page);
      if (disposed || current !== version) return;
      const grid = node("div", "", "attendance-grid");
      content.replaceChildren(
        node("h3", page === "mesas" ? "Mesas" : "Comandas"),
        button("Atualizar lista", () => route(page)),
        grid,
      );
      if (!data.length)
        grid.append(
          node(
            "p",
            page === "mesas"
              ? "Nenhuma mesa cadastrada."
              : "Nenhuma comanda registrada.",
          ),
        );
      for (const item of data) {
        const card = node("article", "", "attendance-card");
        card.append(
          node("h3", page === "mesas" ? item.identificacao : item.codigo),
          node(
            "p",
            item.estado==='ENCERRADA'&&item.teveConsumo?'Encerrada com quitação':states[item.situacao || item.estado] || item.estado,
            "attendance-state",
          ),
        );
        if (page === "mesas") {
          if (item.codigoComanda) card.append(node("p", item.codigoComanda));
          if (item.comandaAtiva && allowed("COMANDA_CONSULTAR"))
            card.append(
              button("Ver comanda", () => route("comandas", item.comandaAtiva)),
            );
          else if (item.situacao === "LIVRE" && allowed("ATENDIMENTO_ABRIR"))
            card.append(button("Abrir atendimento", () => opening(item)));
        } else {
          card.append(
            node(
              "p",
              (item.modalidade === "MESA" ? "Mesa " + item.mesa : "Balcão") +
                (item.cliente ? " · " + item.cliente : ""),
            ),
            button("Ver detalhes", () => route("comandas", item.id)),
          );
        }
        grid.append(card);
      }
    } catch (e) {
      if (current === version) fail(e);
    }
  }
  function opening(mesa = null) {
    if (disposed || busy) return;
    version++;
    const body = {
      modalidade: mesa ? "MESA" : "BALCAO",
      mesaId: mesa?.id || null,
      cliente: null,
    };
    pending = null;
    const form = node("form", "", "attendance-form");
    const label = node("label", "Identificação do cliente (opcional)");
    const input = node("input");
    input.id = "comanda-cliente";
    input.maxLength = 120;
    input.autocomplete = "off";
    label.htmlFor = input.id;
    const submit = node("button", "Confirmar abertura", "primary");
    submit.type = "submit";
    const back = button("Voltar sem abrir", () => {
      pending = null;
      route(mesa ? "mesas" : "comandas");
    });
    form.append(
      node(
        "h3",
        mesa ? "Abrir mesa " + mesa.identificacao : "Nova comanda de balcão",
      ),
      label,
      input,
      node(
        "p",
        "Em caso de perda de conexão, repita a confirmação nesta tela. A mesma solicitação não cria duas comandas.",
        "muted small",
      ),
      submit,
      back,
    );
    content.replaceChildren(form);
    input.focus();
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      if (busy || disposed) return;
      busy = true;
      submit.disabled = true;
      back.disabled = true;
      input.disabled = true;
      message.hidden = true;
      pending ??= createOpeningIntent({ ...body, cliente: input.value });
      try {
        const c = await request("comandas", "POST", pending.body, {
          "Idempotency-Key": pending.key,
        });
        if (disposed) return;
        pending = null;
        busy = false;
        route("comandas", c.id);
      } catch (err) {
        fail(err);
      } finally {
        busy = false;
        submit.disabled = false;
        back.disabled = false; /* Conteúdo congelado: retentativa usa a mesma chave e o mesmo corpo. */
      }
    });
  }
  async function detail(id, current) {
    const c = await request("comandas/" + id);
    const events = await request("comandas/" + id + "/historico");
    if (disposed || current !== version) return;
    content.replaceChildren();
    const card = node("article", "", "attendance-card");
    card.append(
      node(
        "h3",
        c.codigo +
          " · " +
          (c.modalidade === "MESA" ? "Mesa " + c.mesa : "Balcão"),
      ),
      node("p", c.estado==='ENCERRADA'&&c.teveConsumo?'Encerrada com quitação':states[c.estado], "attendance-state"),
    );
    if (c.cliente) card.append(node("p", "Cliente: " + c.cliente));
    card.append(
      node(
        "p",
        "Aberta por " +
          c.nomeAbertura +
          " em " +
          new Date(c.abertoEm).toLocaleString("pt-BR"),
      ),
    );
    if (c.justificativa) card.append(node("p", "Motivo: " + c.justificativa));
    if (c.estado === "ABERTA" && !c.teveConsumo && !c.possuiPendencias) {
      card.append(
        node(
          "p",
          "Só é possível finalizar sem consumo nem pendências. Esta ação não representa quitação ou fechamento financeiro.",
          "muted",
        ),
      );
      if (allowed("COMANDA_ENCERRAR"))
        card.append(button("Encerrar sem consumo", () => finish(c, false)));
      if (allowed("COMANDA_CANCELAR"))
        card.append(button("Cancelar comanda", () => finish(c, true)));
    }
    const history = node("ol");
    for (const event of events) {
      const text =
        event.tipo === "COMANDA_ABERTA"
          ? "Abertura"
          : event.tipo === "COMANDA_CANCELADA"
            ? "Cancelamento"
            : "Encerramento operacional";
      history.append(
        node(
          "li",
          text +
            " · " +
            (event.nomeExecutor || "Operador autorizado") +
            " · " +
            new Date(event.ocorridoEm).toLocaleString("pt-BR") +
            (event.justificativa ? " · " + event.justificativa : ""),
        ),
      );
    }
    content.append(
      button("Voltar às comandas", () => route("comandas")),
      card,
      node("h3", "Histórico do atendimento"),
      history,
    );
    if (allowed('CONTA_CONSULTAR')) {
      const area=node('section','','financial-account');content.insertBefore(area,card.nextSibling);
      area.append(button('Consultar conta e pagamentos',()=>{financeiro?.destroy();financeiro=createContaScreen({root:area,request,allowed,scope:{unidade:unit,ator:actor,comanda:c.id},onError:fail,isCurrent:()=>!disposed&&current===version});}));
    }
    if (allowed("PEDIDO_CONSULTAR")) {
      const area = node("section", "", "pedidos-area");
      content.insertBefore(area, content.querySelector('.financial-account')?.nextSibling || card.nextSibling);
      pedidos = createPedidosScreen({
        root: area,
        request,
        allowed,
        onError: fail,
        isCurrent: () => !disposed && current === version,
      });
      await pedidos.comanda(c);
    }
  }
  function finish(c, cancel) {
    if (busy || disposed) return;
    const form = node("form", "", "attendance-form");
    const label = node(
      "label",
      cancel ? "Justificativa do cancelamento" : "Observação (opcional)",
    );
    const input = node("textarea");
    input.id = "comanda-motivo";
    input.maxLength = 500;
    input.required = cancel;
    label.htmlFor = input.id;
    const submit = node(
      "button",
      cancel ? "Confirmar cancelamento" : "Confirmar encerramento sem consumo",
      "primary",
    );
    submit.type = "submit";
    form.append(
      node("h3", cancel ? "Cancelar " + c.codigo : "Encerrar " + c.codigo),
      label,
      input,
      submit,
      button("Voltar", () => route("comandas", c.id)),
    );
    content.replaceChildren(form);
    input.focus();
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      if (busy || disposed) return;
      busy = true;
      submit.disabled = true;
      try {
        await request(
          "comandas/" + c.id + "/" + (cancel ? "cancelar" : "encerrar"),
          "POST",
          { versao: c.versao, justificativa: input.value.trim() || null },
        );
        if (disposed) return;
        busy = false;
        route("comandas", c.id);
      } catch (err) {
        fail(err);
      } finally {
        busy = false;
        submit.disabled = false;
      }
    });
  }
  navigation.append(
    button("Mesas", () => route("mesas")),
    button("Comandas", () => route("comandas")),
  );
  if (allowed("ATENDIMENTO_ABRIR"))
    navigation.append(button("Nova comanda de balcão", () => opening()));
  const navigate = () => {
    const r = readAttendanceRoute(location.hash);
    route(r.page, r.id, false);
  };
  window.addEventListener("popstate", navigate);
  window.addEventListener("hashchange", navigate);
  function destroy() {
    financeiro?.destroy();
    pedidos?.destroy();
    pedidos = null;
    disposed = true;
    version++;
    pending = null;
    root.replaceChildren();
    window.removeEventListener("popstate", navigate);
    window.removeEventListener("hashchange", navigate);
  }
  if (allowed("PRODUTO_CONSULTAR"))
    navigation.append(button("Cardápio", () => route("cardapio")));
  for (const [code, label, page] of [
    ["COZINHA_FILA_CONSULTAR", "Cozinha", "cozinha"],
    ["BAR_FILA_CONSULTAR", "Bar", "bar"],
  ])
    if (allowed(code)) navigation.append(button(label, () => route(page)));
  if(['CAIXA_OPERAR','CAIXA_ABRIR','CAIXA_HISTORICO_CONSULTAR'].some(allowed))navigation.append(button('Caixa',()=>route('caixa')));
  if(allowed('RELATORIO_FINANCEIRO_CONSULTAR'))navigation.append(button('Relatórios financeiros',()=>route('relatorios')));
  if(allowed('DELIVERY_CONSULTAR'))navigation.append(button('Delivery',()=>route('delivery')));
  if(allowed('VENDA_RAPIDA_CONSULTAR'))navigation.append(button('Venda rápida',()=>route('venda-rapida')));
  const r = readAttendanceRoute(location.hash);
  route(r.page, r.id, false);
  return { destroy };
}
