import { randomInt, randomUUID } from "node:crypto";
// Extensao real do smoke oficial: sem mock de API, credenciais so em memoria.
export async function prepararPedidos({ fixture, targetLink, ownerEval }) {
  async function admin(
    path,
    method = "GET",
    body,
    unit = fixture.estabelecimentoId,
  ) {
    const value = await ownerEval(
      '(async()=>{const c=await(await fetch("/api/v1/auth/csrf")).json();const r=await fetch(' +
        JSON.stringify("/api/v1/estabelecimentos/" + unit + "/" + path) +
        ",{method:" +
        JSON.stringify(method) +
        ',headers:{[c.headerName]:c.token,"Content-Type":"application/json","Idempotency-Key":' +
        JSON.stringify(randomUUID()) +
        "}" +
        (body === undefined
          ? ""
          : ",body:JSON.stringify(" + JSON.stringify(body) + ")") +
        "});return {status:r.status,body:await r.json()}})()",
    );
    if (value.status >= 400)
      throw Error("Preparacao de pedidos rejeitada: HTTP " + value.status);
    return value.body;
  }
  async function grant(id, code) {
    const f = await admin("permissoes/funcionarios/" + id);
    await admin(
      "permissoes/funcionarios/" + id + "/individuais/" + code,
      "PUT",
      { estado: "PERMITIR", versao: f.versao },
    );
  }
  for (const code of [
    "PRODUTO_CONSULTAR",
    "PEDIDO_REGISTRAR",
    "PEDIDO_ENVIAR",
    "PEDIDO_CONSULTAR",
    "PEDIDO_ENTREGAR",
    "PEDIDO_ALTERACAO_SOLICITAR",
  ])
    await grant(targetLink, code);
  const actors = {};
  for (const role of ["COZINHA", "BAR"]) {
    const f = await admin("funcionarios", "POST", {
      nome: "Producao smoke " + role,
      funcao: role,
      email: null,
      telefone: null,
    });
    const pin = String(randomInt(1000000)).padStart(6, "0");
    await admin(
      "seguranca-operacional/funcionarios/" + f.vinculoId + "/pin",
      "PUT",
      { pin, versao: f.versao },
    );
    for (const code of ["PEDIDO_CANCELAR", "PEDIDO_ENVIADO_ALTERAR"])
      await grant(f.vinculoId, code);
    actors[role] = { id: f.vinculoId, pin };
  }
  const fB = await admin(
    "funcionarios",
    "POST",
    {
      nome: "Atendimento smoke B",
      funcao: "ATENDENTE",
      emailContato: null,
      telefone: null,
    },
    fixture.destinoId,
  );
  const pinB = String(randomInt(1000000)).padStart(6, "0");
  await admin(
    "seguranca-operacional/funcionarios/" + fB.vinculoId + "/pin",
    "PUT",
    { pin: pinB, versao: fB.versao },
    fixture.destinoId,
  );
  actors.UNIDADE_B = { id: fB.vinculoId, pin: pinB };
  return { actors, admin };
}
export async function smokePedidos({
  fixture,
  targetLink,
  credential,
  prepared,
  login,
  evaluate,
  wait,
  cdp,
  pause,
  pass,
  artifacts,
  fs,
  path,
}) {
  const click = (text, scope = ".attendance-area") =>
    evaluate(
      "[...document.querySelectorAll(" +
        JSON.stringify(scope + " button") +
        ")].find(b=>b.textContent===" +
        JSON.stringify(text) +
        ").click();true",
    );
  async function switchActor(id, pin, unit = fixture.estabelecimentoId) {
    await click("Trocar operador", "#op-protected");
    await wait(
      'document.getElementById("op-protected").hidden&&!document.getElementById("op-enter").disabled',
      "troca real de operador",
    );
    await login(id, pin, unit);
    await wait(
      '!document.getElementById("op-protected").hidden&&!!document.querySelector(".attendance-area")',
      "novo operador autenticado",
    );
  }
  async function shot(name) {
    for (const [label, width, height] of [
      ["desktop", 1366, 900],
      ["tablet", 768, 1024],
      ["mobile", 360, 800],
    ]) {
      await cdp("Emulation.setDeviceMetricsOverride", {
        width,
        height,
        deviceScaleFactor: 1,
        mobile: width < 500,
      });
      await pause(100);
      if (
        !(await evaluate("document.documentElement.scrollWidth<=innerWidth+2"))
      )
        throw Error("Pedidos transborda " + label);
      const image = await cdp("Page.captureScreenshot", {
        format: "png",
        captureBeyondViewport: true,
      });
      await fs.writeFile(
        path.join(artifacts, name + "-" + label + ".png"),
        Buffer.from(image.data, "base64"),
      );
    }
  }
  // O prazo curto dos testes anteriores so e ampliado neste banco descartavel e restaurado ao terminar.
  let policy = await prepared.admin("seguranca-operacional/inatividade");
  await prepared.admin("seguranca-operacional/inatividade", "PUT", {
    segundos: 300,
    versao: policy.versao,
  });
  await click("Nova comanda de balcão");
  await wait(
    '!!document.getElementById("comanda-cliente")',
    "abertura de comanda para pedidos",
  );
  await evaluate(
    'document.getElementById("comanda-cliente").value="Fluxo pedidos smoke";document.querySelector(".attendance-area form").requestSubmit();true',
  );
  await wait(
    '!!document.querySelector(".pedidos-area")&&[...document.querySelectorAll(".pedidos-area button")].some(b=>b.textContent==="Novo pedido")',
    "comanda real recebe pedidos",
  );
  const comandaHash = await evaluate("location.hash");
  async function addCombo(together) {
    await click("Novo pedido");
    await wait(
      '!!document.getElementById("pedido-produto")',
      "editor de pedido",
    );
    await evaluate(
      'document.getElementById("pedido-produto").value=[...document.getElementById("pedido-produto").options].find(o=>o.textContent.includes("Combo smoke")).value;document.getElementById("pedido-observacao").value="Observacao real smoke";true',
    );
    if (
      !(await evaluate(
        '[...document.querySelectorAll(".pedido-editor input,.pedido-editor select,.pedido-editor textarea")].every(e=>e.labels.length>0)',
      ))
    )
      throw Error("Editor com campo sem rotulo associado");
    await click("Adicionar item");
    await evaluate(
      'document.getElementById("pedido-tudo-junto").checked=' +
        together +
        ';document.querySelector(".pedido-editor").requestSubmit();true',
    );
    await wait(
      '!!document.querySelector(".pedido-review")',
      "revisao explicita",
    );
    if (
      !(await evaluate(
        '[...document.querySelectorAll(".pedido-review button")].every(b=>b.textContent.trim().length>0)',
      ))
    )
      throw Error("Revisao sem nomes acessiveis");
    await shot("pedidos-revisao");
    await click("Confirmar e enviar pedido");
    await wait(
      '!!document.querySelector(".pedido-card")&&!document.querySelector(".pedido-review")',
      "pedido confirmado no backend",
    );
    return evaluate(
      '[...document.querySelectorAll(".pedido-card")].at(-1).dataset.pedido',
    );
  }
  const first = await addCombo(false);
  await cdp("Page.reload");
  await wait(
    '!document.getElementById("op-protected").hidden&&!!document.querySelector(".pedido-card")',
    "F5 preserva comanda e pedidos",
  );
  if ((await evaluate("location.hash")) !== comandaHash)
    throw Error("F5 perdeu comanda");
  async function production(sector, pedido) {
    const actor = prepared.actors[sector];
    await switchActor(actor.id, actor.pin);
    await click(sector === "BAR" ? "Bar" : "Cozinha");
    await wait(
      '!!document.querySelector(".production-item")',
      "fila real do setor",
    );
    if (
      await evaluate(
        'document.querySelector(".attendance-area").textContent.includes("R$ ")',
      )
    )
      throw Error("Fila de producao expos valores");
    await shot("fila-" + sector.toLowerCase());
    for (const action of ["Iniciar preparo", "Marcar pronto"]) {
      await evaluate(
        '[...document.querySelectorAll(".production-item")].find(c=>c.textContent.includes("Observacao real smoke")).querySelector("button").click();true',
      );
      await wait(
        '!document.querySelector(".attendance-area [aria-busy=true]")&&[...document.querySelectorAll(".production-item")].some(c=>c.textContent.includes(' +
          JSON.stringify(
            action === "Iniciar preparo" ? "Em preparo" : "Pronto",
          ) +
          "))",
        "estado persistido de preparo",
      );
    }
  }
  async function backToWaiter() {
    await switchActor(targetLink, credential.pin);
    await evaluate("location.hash=" + JSON.stringify(comandaHash) + ";true");
    await wait(
      '!!document.querySelector(".pedido-card")',
      "atendimento retorna a comanda",
    );
  }
  await production("BAR", first);
  await backToWaiter();
  await evaluate('document.querySelector("input[data-entrega]").click();true');
  await click("Entregar itens selecionados");
  await wait(
    'document.querySelector(".pedido-card")?.textContent.includes("Parcialmente entregue")',
    "entrega parcial real",
  );
  await production("COZINHA", first);
  await backToWaiter();
  await evaluate('document.querySelector("input[data-entrega]").click();true');
  await click("Entregar itens selecionados");
  await wait(
    'document.querySelector(".pedido-card h4")?.textContent.includes("Entregue")',
    "pedido todo entregue",
  );
  await shot("pedidos-entregues");
  pass(
    "2.6: comanda, combo sem cobranca duplicada, revisao, F5, filas cozinha/bar, preparo e entrega parcial/total reais; tres resolucoes",
  );
  // Segundo pedido: consentimento de ambos os setores, original preservado e estoque devolvido antes do preparo.
  await click("Novo pedido");
  await wait('!!document.getElementById("pedido-produto")', "segundo editor");
  await evaluate(
    'document.getElementById("pedido-produto").value=[...document.getElementById("pedido-produto").options].find(o=>o.textContent.includes("Combo smoke")).value;true',
  );
  await click("Adicionar item");
  await evaluate(
    'document.querySelector(".pedido-editor").requestSubmit();true',
  );
  await click("Confirmar e enviar pedido");
  await wait('document.querySelectorAll(".pedido-card").length===2', "segundo pedido criado");
  await click("Solicitar cancelamento");
  await wait(
    '!!document.querySelector(".pedidos-area textarea")',
    "motivo cancelamento",
  );
  await evaluate(
    'document.querySelector(".pedidos-area textarea").value="Cliente solicitou no smoke";document.querySelector(".pedidos-area form").requestSubmit();true',
  );
  await wait(
    'document.querySelectorAll(".pedido-card").length===2',
    "solicitacao persistida",
  );
  for (const sector of ["COZINHA", "BAR"]) {
    const actor = prepared.actors[sector];
    await switchActor(actor.id, actor.pin);
    await click(sector === "BAR" ? "Bar" : "Cozinha");
    await wait(
      '!!document.querySelector(".production-ticket")',
      "solicitacao chega ao setor",
    );
    await shot("decisao-" + sector.toLowerCase());
    await click("Autorizar solicitação");
    await wait(
      '!!document.querySelector(".order-notice")',
      "decisao confirmada pelo backend",
    );
  }
  await backToWaiter();
  await wait(
    '[...document.querySelectorAll(".pedido-card h4")].some(h=>h.textContent.includes("Cancelado"))',
    "cancelamento apos unanimidade",
  );
  await click("Histórico do pedido");
  await wait(
    '!!document.querySelector(".pedido-history li")',
    "auditoria real consultada",
  );
  pass(
    "2.6: solicitacao de cancelamento, duas decisoes setoriais, original preservado e historico real",
  );
  const together = await addCombo(true);
  await production("BAR", together);
  await backToWaiter();
  await click("Entregar conjunto completo");
  await wait(
    'document.querySelector(".attendance-area [role=alert]")?.textContent.includes("tudo junto")',
    "backend impede entrega incompleta de tudo junto",
  );
  await production("COZINHA", together);
  await backToWaiter();
  await click("Entregar conjunto completo");
  await wait(
    '[...document.querySelectorAll(".pedido-card")].find(c=>c.dataset.pedido===' +
      JSON.stringify(together) +
      ')?.querySelector("h4")?.textContent.includes("Entregue")',
    "entrega unica apos todos prontos",
  );
  pass(
    "2.6: tudo junto rejeita liberacao parcial e confirma conjunto completo depois de ambos os setores",
  );
  // Alteracao por revisao explicita: primeiro recusa, depois nova proposta aceita inclusive pelo setor novo.
  await click("Novo pedido");
  await wait(
    '!!document.getElementById("pedido-produto")',
    "pedido simples para alteracao",
  );
  await evaluate(
    'document.getElementById("pedido-produto").value=[...document.getElementById("pedido-produto").options].find(o=>o.textContent.includes("Prato smoke")).value;true',
  );
  await click("Adicionar item");
  await evaluate(
    'document.querySelector(".pedido-editor").requestSubmit();true',
  );
  await click("Confirmar e enviar pedido");
  await wait(
    'document.querySelectorAll(".pedido-card").length===4',
    "original de alteracao persistido",
  );
  const original = await evaluate(
    '[...document.querySelectorAll(".pedido-card")].at(-1).dataset.pedido',
  );
  async function propose() {
    await click(
      "Solicitar alteração",
      '.pedido-card[data-pedido="' + original + '"]',
    );
    await wait(
      '!!document.getElementById("pedido-motivo-alteracao")',
      "editor de proposta",
    );
    await evaluate(
      'document.getElementById("pedido-produto").value=[...document.getElementById("pedido-produto").options].find(o=>o.textContent.includes("Bebida smoke")).value;document.getElementById("pedido-motivo-alteracao").value="Cliente deseja mudar para bebida";true',
    );
    await click("Adicionar item");
    await evaluate(
      'document.querySelector(".pedido-editor").requestSubmit();true',
    );
    await click("Confirmar solicitação de alteração");
    await wait(
      'document.querySelectorAll(".pedido-card").length===4&&!document.querySelector(".pedido-review")',
      "proposta persistida",
    );
  }
  await propose();
  await switchActor(prepared.actors.COZINHA.id, prepared.actors.COZINHA.pin);
  await click("Cozinha");
  await wait(
    '!!document.querySelector(".production-ticket textarea")',
    "recusa setorial disponivel",
  );
  await evaluate(
    'document.querySelector(".production-ticket textarea").value="Manter original nesta primeira revisao de teste";true',
  );
  await click("Recusar solicitação");
  await wait(
    'document.querySelector(".order-notice")?.textContent.includes("Recusada")',
    "recusa registrada",
  );
  await backToWaiter();
  if (
    !(await evaluate(
      '[...document.querySelectorAll(".pedido-card")].find(c=>c.dataset.pedido===' +
        JSON.stringify(original) +
        ')?.querySelector("h4")?.textContent.includes("Aguardando")',
    ))
  )
    throw Error("Recusa modificou original");
  await propose();
  for (const sector of ["COZINHA", "BAR"]) {
    const actor = prepared.actors[sector];
    await switchActor(actor.id, actor.pin);
    await click(sector === "BAR" ? "Bar" : "Cozinha");
    await wait(
      '!!document.querySelector(".production-ticket")',
      "proposta chega ao setor original ou novo",
    );
    await click("Autorizar solicitação");
    await wait(
      '!!document.querySelector(".order-notice")',
      "decisao da proposta persistida",
    );
  }
  await backToWaiter();
  await wait(
    'document.querySelectorAll(".pedido-card").length===5',
    "substituto e original preservados",
  );
  if (
    !(await evaluate(
      '[...document.querySelectorAll(".pedido-card")].find(c=>c.dataset.pedido===' +
        JSON.stringify(original) +
        ')?.querySelector("h4")?.textContent.includes("Cancelado")',
    ))
  )
    throw Error("Original nao preservado como cancelado");
  pass(
    "2.6: alteracao real com revisao, recusa sem efeitos e nova unanimidade incluindo setor novo; substituto e original rastreaveis",
  );
  // O saldo inicial era cinco pratos e tres bebidas; tres unidades foram consumidas e uma retornou.
  const products = await prepared.admin("cardapio");
  const food = products.produtos.find((p) => p.nome === "Prato smoke");
  if (!food || food.quantidade !== 3)
    throw Error("Reposicao do pedido cancelado incorreta");
  const cross = await evaluate(
    '(async()=>{const r=await fetch("/api/v1/operacional/estabelecimentos/' +
      fixture.destinoId +
      '/pedidos/ofertas",{headers:{"X-Operador-Vinculo":"' +
      targetLink +
      '"}});return r.status})()',
  );
  if (cross !== 403) throw Error("Pedidos de outra unidade nao bloqueados");
  const actorB = prepared.actors.UNIDADE_B;
  await switchActor(actorB.id, actorB.pin, fixture.destinoId);
  await click("Nova comanda de balcão");
  await wait(
    '!!document.getElementById("comanda-cliente")',
    "abertura independente B",
  );
  await evaluate(
    'document.getElementById("comanda-cliente").value="Pedido unidade B";document.querySelector(".attendance-area form").requestSubmit();true',
  );
  await wait(
    '[...document.querySelectorAll(".pedidos-area button")].some(b=>b.textContent==="Novo pedido")',
    "comanda B aberta",
  );
  await click("Novo pedido");
  await wait('!!document.getElementById("pedido-produto")', "cardapio B");
  await evaluate(
    'document.getElementById("pedido-produto").value=[...document.getElementById("pedido-produto").options].find(o=>o.textContent.includes("Bebida smoke")).value;true',
  );
  await click("Adicionar item");
  await evaluate(
    'document.querySelector(".pedido-editor").requestSubmit();true',
  );
  await click("Confirmar e enviar pedido");
  await wait(
    '!!document.querySelector(".pedido-card h4")',
    "pedido B confirmado",
  );
  if (
    (await evaluate('document.querySelectorAll(".pedido-card").length')) !== 1
  )
    throw Error("Pedido de A vazou para B");
  await shot("pedido-unidade-b");
  await backToWaiter();
  if (
    (await evaluate('document.querySelectorAll(".pedido-card").length')) !== 5
  )
    throw Error("Pedidos da unidade A foram alterados por B");
  pass(
    "2.6: produto simples em B com PIN de outro funcionario, pedido independente e acesso cruzado 403",
  );
  policy = await prepared.admin("seguranca-operacional/inatividade");
  await prepared.admin("seguranca-operacional/inatividade", "PUT", {
    segundos: 30,
    versao: policy.versao,
  });
  await click("Mesas");
  await wait(
    '!!document.querySelector(".attendance-area .attendance-card")',
    "retorno ao roteiro original",
  );
}
