import assert from "node:assert/strict";
import { randomInt, randomUUID } from "node:crypto";
// Extensão do smoke oficial: Spring Boot/PostgreSQL16 reais, sem dados operacionais.
export async function smokeExternos({
  fixture,
  credential,
  cdp,
  evaluate,
  wait,
  pause,
  pass,
  ownerEval,
  artifacts,
  fs,
  path,
}) {
  const A = fixture.estabelecimentoId,
    B = fixture.destinoId;
  async function admin(suffix, method = "GET", body, unit = A) {
    const r = await ownerEval(
      '(async()=>{const c=await(await fetch("/api/v1/auth/csrf")).json();const r=await fetch(' +
        JSON.stringify("/api/v1/estabelecimentos/" + unit + "/" + suffix) +
        ",{method:" +
        JSON.stringify(method) +
        ',headers:{[c.headerName]:c.token,"Content-Type":"application/json","Idempotency-Key":' +
        JSON.stringify(randomUUID()) +
        "}" +
        (body === undefined
          ? ""
          : ",body:JSON.stringify(" + JSON.stringify(body) + ")") +
        "});return{status:r.status,body:r.status===204?null:await r.json()};})()",
    );
    if (r.status >= 400)
      throw Error("Preparação2.8 HTTP" + r.status + " em " + suffix);
    return r.body;
  }
  async function person(name, role, codes, unit = A) {
    const f = await admin(
      "funcionarios",
      "POST",
      {
        nome: "Externo smoke " + name,
        funcao: role,
        emailContato: null,
        telefone: null,
      },
      unit,
    );
    const pin = String(randomInt(1000000)).padStart(6, "0");
    await admin(
      "seguranca-operacional/funcionarios/" + f.vinculoId + "/pin",
      "PUT",
      { pin, versao: f.versao },
      unit,
    );
    for (const code of codes) {
      const p = await admin(
        "permissoes/funcionarios/" + f.vinculoId,
        "GET",
        undefined,
        unit,
      );
      if (p.permissoes.some((p) => p.permissao.codigo === code && p.efetiva))
        continue;
      await admin(
        "permissoes/funcionarios/" + f.vinculoId + "/individuais/" + code,
        "PUT",
        { estado: "PERMITIR", versao: p.versao },
        unit,
      );
    }
    return { id: f.vinculoId, pin, unit };
  }
  const codes = [
    "DELIVERY_CONSULTAR",
    "DELIVERY_REGISTRAR",
    "DELIVERY_STATUS_ALTERAR",
    "VENDA_RAPIDA_CONSULTAR",
    "VENDA_RAPIDA_REGISTRAR",
    "ATENDIMENTO_ABRIR",
    "COMANDA_CONSULTAR",
    "PRODUTO_CONSULTAR",
    "PEDIDO_REGISTRAR",
    "PEDIDO_ENVIAR",
    "PEDIDO_CONSULTAR",
    "PEDIDO_ENTREGAR",
    "PEDIDO_ALTERACAO_SOLICITAR",
    "COMANDA_CANCELAR",
    "CAIXA_OPERAR",
    "CAIXA_ABRIR",
    "CONTA_CONSULTAR",
    "PAGAMENTO_REGISTRAR",
    "COMANDA_FINANCEIRO_ENCERRAR",
    "RELATORIO_FINANCEIRO_CONSULTAR",
  ];
  const cash = await person("Caixa", "CAIXA", codes),
    kitchen = await person("Cozinha", "COZINHA", [
      "PEDIDO_CANCELAR",
      "PEDIDO_ENVIADO_ALTERAR",
    ]),
    bar = await person("Bar", "BAR", [
      "PEDIDO_CANCELAR",
      "PEDIDO_ENVIADO_ALTERAR",
    ]),
    other = await person("Unidade B", "ATENDENTE", ["DELIVERY_CONSULTAR"], B);
  const category = await admin("cardapio/categorias", "POST", {
    nome: "Cardápio externos2.8",
    ordem: 30,
    ativo: true,
  });
  async function product(name, price, sector, parts = []) {
    let p = await admin("cardapio/produtos", "POST", {
      categoriaId: category.id,
      nome: name,
      descricao: null,
      tipo: parts.length ? "COMBO" : "SIMPLES",
      precoCentavos: price,
      setor: parts.length ? null : sector,
      ativo: true,
      disponivel: true,
      controlaQuantidade: !parts.length,
      quantidade: 0,
      componentes: parts,
      versao: null,
    });
    if (!parts.length)
      p = await admin("cardapio/produtos/" + p.id + "/ajustes", "POST", {
        quantidade: 20,
        motivo: "Estoque sintético2.8",
        versao: p.versao,
      });
    return p;
  }
  const food = await product("Prato externo80", 8000, "COZINHA"),
    drink = await product("Bebida externa20", 2000, "BAR"),
    combo = await product("Combo externo100", 10000, null, [
      { produtoId: food.id, quantidade: 1 },
      { produtoId: drink.id, quantidade: 1 },
    ]);
  class Client {
    constructor(p) {
      this.p = p;
      this.cookies = new Map();
    }
    async req(suffix, method = "GET", body) {
      const h = {
        "X-Operador-Vinculo": this.p.id,
        "Idempotency-Key": randomUUID(),
      };
      if (method !== "GET") {
        const c = await this.req("auth/csrf");
        h[c.body.headerName] = c.body.token;
        h["Content-Type"] = "application/json";
      }
      h.Cookie = [...this.cookies].map(([k, v]) => k + "=" + v).join("; ");
      const r = await fetch(fixture.url + "/api/v1/" + suffix, {
        method,
        headers: h,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      for (const cookie of r.headers.getSetCookie()) {
        const [k, ...v] = cookie.split(";")[0].split("=");
        this.cookies.set(k, v.join("="));
      }
      const t = await r.text();
      return { status: r.status, body: t ? JSON.parse(t) : null };
    }
    async enter() {
      assert.equal(
        (
          await this.req("operacional/entrar", "POST", {
            estabelecimentoId: this.p.unit,
            identificacao: this.p.id,
            pin: this.p.pin,
          })
        ).status,
        204,
      );
      return this;
    }
    async op(suffix, method = "GET", body) {
      const r = await this.req(
        "operacional/estabelecimentos/" + this.p.unit + "/" + suffix,
        method,
        body,
      );
      if (r.status >= 400)
        throw Error("API2.8 HTTP" + r.status + " em " + suffix);
      return r.body;
    }
  }
  const idle = await admin("seguranca-operacional/inatividade");
  await admin("seguranca-operacional/inatividade", "PUT", {
    segundos: 300,
    versao: idle.versao,
  });
  const cook = await new Client(kitchen).enter(),
    drinks = await new Client(bar).enter(),
    api = await new Client(cash).enter(),
    bApi = await new Client(other).enter();
  async function browserLogin(p) {
    await evaluate(
      '(async()=>{const c=await(await fetch("/api/v1/auth/csrf")).json();await fetch("/api/v1/auth/logout",{method:"POST",headers:{[c.headerName]:c.token}});return true;})()',
    );
    await cdp("Page.navigate", { url: fixture.url + "/operacional.html" });
    await wait(
      'document.getElementById("op-enter")&&!document.getElementById("op-enter").disabled',
      "entrada2.8",
    );
    await evaluate(
      'document.getElementById("op-unit").value=' +
        JSON.stringify(p.unit) +
        ';document.getElementById("op-person").value=' +
        JSON.stringify(p.id) +
        ';document.getElementById("op-pin").value=' +
        JSON.stringify(p.pin) +
        ';document.getElementById("op-form").requestSubmit();true',
    );
    await wait('!document.getElementById("op-protected").hidden', "PIN2.8");
  }
  const click = async (text, scope = ".attendance-area") => {
    await wait(
      "[...document.querySelectorAll(" +
        JSON.stringify(scope + " button") +
        ")].some(b=>b.textContent===" +
        JSON.stringify(text) +
        "&&!b.disabled)",
      "ação2.8 " + text,
    );
    await evaluate(
      "[...document.querySelectorAll(" +
        JSON.stringify(scope + " button") +
        ")].find(b=>b.textContent===" +
        JSON.stringify(text) +
        ").click();true",
    );
  };
  async function shot(name) {
    assert.equal(await evaluate('[...document.querySelectorAll(".attendance-area .error")].some(n=>n.getClientRects().length>0&&!n.textContent.trim())'),false,"Alerta vazio não deve aparecer");
    for (const [label, width, height] of [
      ["desktop", 1366, 900],
      ["tablet", 768, 1024],
      ["mobile", 360, 800],
    ]) {
      await cdp("Emulation.setDeviceMetricsOverride", {
        width,
        height,
        deviceScaleFactor: 1,
        mobile: width === 360,
      });
      await pause(100);
      assert.equal(
        await evaluate("document.documentElement.scrollWidth<=innerWidth+2"),
        true,
        "Largura2.8 " + name + label,
      );
      const pic = await cdp("Page.captureScreenshot", {
        format: "png",
        captureBeyondViewport: true,
      });
      await fs.writeFile(
        path.join(artifacts, "externo-" + name + "-" + label + ".png"),
        Buffer.from(pic.data, "base64"),
      );
    }
  }
  const turn =
    (await api.op("caixa/atual")) ||
    (await api.op("caixa/turnos", "POST", {
      responsavelId: cash.id,
      valorInicialCentavos: "10000",
    }));
  // A abertura operacional existente usa /caixa/turnos; nenhum estado de produção é inventado.
  await browserLogin(cash);
  await click("Delivery");
  await click("Novo delivery");
  await wait('!!document.getElementById("pedido-produto")', "editor delivery");
  await evaluate(
    'document.getElementById("ext-referencia").value="Delivery manual fictício";document.getElementById("ext-origem").value="WHATSAPP";document.getElementById("pedido-produto").value=' +
      JSON.stringify(combo.id) +
      ";true",
  );
  await click("Adicionar item");
  await evaluate(
    'document.querySelector(".pedido-editor button[type=submit]").focus();true',
  );
  await cdp("Input.dispatchKeyEvent", {
    type: "keyDown",
    key: "Enter",
    code: "Enter",
    windowsVirtualKeyCode: 13,
    text: "\r",
  });
  await cdp("Input.dispatchKeyEvent", {
    type: "keyUp",
    key: "Enter",
    code: "Enter",
    windowsVirtualKeyCode: 13,
  });
  await wait('!!document.querySelector(".externo-review")', "revisão delivery");
  await click("Confirmar delivery");
  await wait(
    'document.querySelector(".attendance-area").textContent.includes("Logística: Registrado")',
    "delivery registrado",
  );
  const delivery = (await api.op("externos?tipo=DELIVERY")).find(
    (d) => d.referencia === "Delivery manual fictício",
  );
  assert.ok(delivery);
  assert.equal(delivery.pedidos[0].totalCentavos, 10000);
  assert.equal(delivery.pedidos[0].itens.length, 2);
  await shot("delivery-registrado");
  pass(
    "2.8: delivery manual real, combo, setores, estoque e referência mínima; três resoluções",
  );
  async function ready(d) {
    for (const p of (await api.op("externos/" + d.comandaId)).pedidos) {
      for (const i of p.itens) {
        if (i.estado !== "AGUARDANDO") continue;
        const sector = i.setor === "BAR" ? drinks : cook;
        const started = await sector.op(
          "fila/" + i.setor + "/itens/" + i.id,
          "POST",
          { estado: "EM_PREPARO", versao: i.versao },
        );
        await sector.op("fila/" + i.setor + "/itens/" + i.id, "POST", {
          estado: "PRONTO",
          versao: started.versao,
        });
      }
    }
  }
  await evaluate(
    'window.extHistoryFetch=window.fetch;window.fetch=async(u,o)=>{const r=await window.extHistoryFetch(u,o);if(String(u).includes("/externos/")&&String(u).endsWith("/historico"))await new Promise(resolve=>setTimeout(resolve,300));return r;};true',
  );
  await ready(delivery);
  await click("Atualizar atendimento");
  await wait(
    'document.querySelector(".attendance-area").textContent.includes("Preparo: PRONTO")',
    "preparo delivery",
  );
  await evaluate(
    'document.getElementById("ext-estado").value="SAIU_PARA_ENTREGA";document.getElementById("ext-motivo").value="Despacho fictício confirmado";true',
  );
  await click("Confirmar estado logístico");
  await wait(
    'document.querySelector(".attendance-area").textContent.includes("Logística: Saiu para entrega")',
    "despacho real",
  );
  await evaluate(
    'document.getElementById("ext-motivo").value="Entrega fictícia confirmada";true',
  );
  await click("Confirmar estado logístico");
  await wait(
    'document.querySelector(".attendance-area").textContent.includes("Logística: Entregue")',
    "entrega real",
  );
  await evaluate("window.fetch=window.extHistoryFetch;true");
  assert.equal(
    (await api.op("comandas/" + delivery.comandaId + "/conta")).saldoCentavos,
    "10000",
  );
  pass(
    "2.8: preparo cozinha/bar, despacho e entrega pela interface sem quitação fictícia",
  );
  async function pay(id, meio, value, recebido = null) {
    const conta = await api.op("comandas/" + id + "/conta");
    return api.op(
      "caixa/turnos/" + turn.id + "/comandas/" + id + "/pagamentos",
      "POST",
      {
        meio,
        valorCentavos: String(value),
        recebidoCentavos: recebido,
        confirmadoExternamente: meio !== "DINHEIRO",
        revisaoConta: conta.revisao,
      },
    );
  }
  await pay(delivery.comandaId, "PIX", 3500);
  await pay(delivery.comandaId, "DEBITO", 6500);
  await click("Conta, pagamentos e quitação");
  await wait(
    '!!document.querySelector(".financial-account")',
    "conta delivery",
  );
  assert.equal(
    (await api.op("comandas/" + delivery.comandaId + "/conta")).saldoCentavos,
    "0",
  );
  await click("Quitar e encerrar comanda", ".financial-account");
  await wait(
    '!!document.querySelector(".financial-form")',
    "quitação delivery",
  );
  await evaluate(
    'document.querySelector(".financial-form").requestSubmit();true',
  );
  await wait(
    '!!document.querySelector(".financial-review")',
    "revisão quitação",
  );
  await evaluate(
    'document.querySelector(".financial-review button.primary").click();true',
  );
  await wait(
    'document.querySelector(".financial-account")?.textContent.includes("Encerrada")',
    "delivery quitado",
  );
  pass(
    "2.8: pagamento combinado Pix/débito e quitação usam a conta2.7 sem duplicar receita",
  );
  await click("Venda rápida");
  await click("Nova venda rápida");
  await wait(
    '!!document.getElementById("pedido-produto")',
    "editor venda rápida",
  );
  await evaluate(
    'document.getElementById("pedido-produto").value=' +
      JSON.stringify(food.id) +
      ";true",
  );
  await click("Adicionar item");
  await click("Revisar pedido");
  await wait('!!document.getElementById("ext-recebido")', "pagamento imediato");
  await evaluate(
    'document.getElementById("ext-recebido").value="100,00";document.getElementById("ext-dinheiro").checked=true;window.extOriginalFetch=window.fetch;window.extPosts=0;window.extOmitiu=false;window.fetch=async(u,o)=>{const r=await window.extOriginalFetch(u,o);if(String(u).endsWith("/externos")&&o?.method==="POST"){window.extPosts++;if(r.ok&&!window.extOmitiu){window.extOmitiu=true;throw TypeError("Resposta omitida depois de confirmação real");}}return r;};true',
  );
  await click("Confirmar venda e pagamento");
  await wait(
    'document.querySelector(".externo-review")?.textContent.includes("Total:")&&!document.querySelector(".attendance-area [aria-busy=true]")',
    "resultado incerto",
  );
  await pause(500);
  await click("Confirmar venda e pagamento");
  await wait(
    'document.querySelector(".attendance-area").textContent.includes("Logística: Registrado")',
    "venda recuperada",
  );
  assert.equal(await evaluate("window.extPosts"), 1);
  await evaluate("window.fetch=window.extOriginalFetch;true");
  const sale = (await api.op("externos?tipo=VENDA_RAPIDA"))[0],
    account = await api.op("comandas/" + sale.comandaId + "/conta");
  assert.equal(account.pagoCentavos, "8000");
  assert.equal(account.lancamentos[0].trocoCentavos, "2000");
  assert.equal(sale.pedidos[0].itens[0].estado, "AGUARDANDO");
  await shot("venda-paga-nao-preparada");
  pass(
    "2.8: venda rápida dinheiro80/recebido100/troco20 atômica; resposta perdida sem segundo POST e sem preparo automático",
  );
  await cdp("Page.reload");
  await wait(
    'document.querySelector(".attendance-area").textContent.includes("Logística: Registrado")',
    "F5 canal venda",
  );
  assert.equal(
    await evaluate('location.hash.includes("atendimento=venda-rapida")'),
    true,
  );
  await ready(sale);
  await click("Atualizar atendimento");
  await wait(
    'document.querySelector(".attendance-area").textContent.includes("Preparo: PRONTO")',
    "preparo venda",
  );
  await evaluate(
    'document.getElementById("ext-motivo").value="Retirada fictícia pelo cliente";true',
  );
  await click("Confirmar estado logístico");
  await wait(
    'document.querySelector(".attendance-area").textContent.includes("Logística: Retirado")',
    "retirada venda",
  );
  await shot("venda-retirada");
  pass(
    "2.8: F5 preserva canal/atendimento, produção real e retirada explícita; três resoluções",
  );

  await click("Conta, pagamentos e quitação");
  await wait(
    '!!document.querySelector(".financial-account .financial-totals")',
    "comprovante venda rápida",
  );
  assert.equal(
    await evaluate(
      'document.querySelector(".financial-account").textContent.includes("Troco: R$ 20,00")',
    ),
    true,
  );
  await shot("venda-troco-comprovante");
  await click("Quitar e encerrar comanda", ".financial-account");
  await wait(
    '!!document.querySelector(".financial-form")',
    "quitação venda rápida",
  );
  await evaluate(
    'document.querySelector(".financial-form").requestSubmit();true',
  );
  await wait(
    '!!document.querySelector(".financial-review")',
    "revisão venda rápida",
  );
  await evaluate(
    'document.querySelector(".financial-review button.primary").click();true',
  );
  await wait(
    'document.querySelector(".financial-account")?.textContent.includes("Encerrada")',
    "venda rápida quitada",
  );
  const report = await api.op(
    "relatorios-financeiros?inicio=" +
      encodeURIComponent(new Date(Date.now() - 3600000).toISOString()) +
      "&fim=" +
      encodeURIComponent(new Date(Date.now() + 60000).toISOString()),
  );
  assert.equal(
    report.canais
      .filter((c) => ["DELIVERY", "VENDA_RAPIDA"].includes(c.canal))
      .reduce((n, c) => n + BigInt(c.vendasLiquidadasCentavos), 0n),
    18000n,
  );
  await click("Delivery");
  await click("Novo delivery");
  await wait(
    '!!document.getElementById("pedido-produto")',
    "editor cancelamento",
  );
  await evaluate(
    'document.getElementById("ext-referencia").value="Delivery a cancelar";document.getElementById("pedido-produto").value=' +
      JSON.stringify(food.id) +
      ";true",
  );
  await click("Adicionar item");
  await click("Revisar pedido");
  await click("Confirmar delivery");
  await wait(
    '!document.querySelector(".externo-review")&&document.querySelector(".attendance-area").textContent.includes("Logística: Registrado")&&document.querySelector(".attendance-area").textContent.includes("Delivery a cancelar")',
    "novo delivery cancelável",
  );
  const cancelable = (await api.op("externos?tipo=DELIVERY")).find(
    (d) => d.referencia === "Delivery a cancelar",
  );
  assert.ok(cancelable);
  const toCancel = cancelable.pedidos[0];
  const request = await api.op(
    "pedidos/" + toCancel.id + "/solicitacoes",
    "POST",
    {
      tipo: "CANCELAMENTO",
      motivo: "Cancelamento fictício autorizado",
      proposta: null,
      versaoPedido: toCancel.versao,
    },
  );
  const decision = request.decisoes.find((d) => d.setor === "COZINHA");
  await cook.op(
    "fila/COZINHA/solicitacoes/" + request.id + "/decisao",
    "POST",
    {
      aceitar: true,
      motivo: null,
      versao: decision.versao,
      versaoPedido: request.versaoPedido,
    },
  );
  await click("Atualizar atendimento");
  await wait(
    '!!document.getElementById("ext-estado")',
    "cancelamento logístico",
  );
  await evaluate(
    'document.getElementById("ext-estado").value="CANCELADO";document.getElementById("ext-motivo").value="Pedido cancelado pelo setor; sem recebimento";true',
  );
  await click("Confirmar estado logístico");
  await wait(
    'document.querySelector(".attendance-area").textContent.includes("Logística: Cancelado")',
    "delivery cancelado",
  );
  await shot("delivery-cancelado");
  pass(
    "2.8: cancelamento setorial autorizado, baixa revertida apenas enquanto aguardando, histórico e logística cancelada reais",
  );
  const cross = await bApi.req(
    "operacional/estabelecimentos/" + A + "/externos/" + delivery.comandaId,
  );
  assert.equal(cross.status, 403);
  assert.equal((await bApi.op("externos?tipo=DELIVERY")).length, 0);
  pass("2.8: sessão de outra unidade não consulta delivery/venda da origem");
  const history = await api.op("externos/" + delivery.comandaId + "/historico");
  assert.equal(history.length, 3);
  const stock = (await admin("cardapio")).produtos;
  assert.equal(stock.find((p) => p.id === food.id).quantidade, 18);
  assert.equal(stock.find((p) => p.id === drink.id).quantidade, 19);
  pass(
    "2.8: histórico individual, combo cobrado uma vez e consumo de estoque sem duplicação",
  );
  await evaluate(
    '(async()=>{const c=await(await fetch("/api/v1/auth/csrf")).json();await fetch("/api/v1/auth/logout",{method:"POST",headers:{[c.headerName]:c.token}});const fresh=await(await fetch("/api/v1/auth/csrf")).json();return(await fetch("/api/v1/auth/login",{method:"POST",headers:{[fresh.headerName]:fresh.token},body:new URLSearchParams({email:' +
      JSON.stringify(credential.email) +
      ",password:" +
      JSON.stringify(credential.password) +
      "})})).status;})()",
  );
  await cdp("Page.navigate", { url: fixture.url });
  await wait(
    '!document.getElementById("app-view").hidden',
    "retorno administrativo2.8",
  );
}
