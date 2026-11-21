import { randomInt, randomUUID } from "node:crypto";
// Chrome + Spring Boot + PostgreSQL 16 reais; valores e credenciais exclusivamente fictícios.
export async function prepararCaixa({
  fixture,
  prepared,
  evaluate,
  wait,
  pass,
}) {
  const admin = prepared.admin,
    actors = {};
  const codes = [
    "CAIXA_ABRIR",
    "CAIXA_OPERAR",
    "CAIXA_OPERADORES_CONFIGURAR",
    "CONTA_CONSULTAR",
    "PAGAMENTO_REGISTRAR",
    "PAGAMENTO_ESTORNAR",
    "COMANDA_DESCONTO_APLICAR",
    "COMANDA_FINANCEIRO_ENCERRAR",
    "CAIXA_SANGRIA_REGISTRAR",
    "CAIXA_SUPRIMENTO_REGISTRAR",
    "CAIXA_CONFERIR",
    "CAIXA_FECHAR",
    "CAIXA_HISTORICO_CONSULTAR",
    "RELATORIO_FINANCEIRO_CONSULTAR",
    "FUNCIONARIO_HISTORICO_FINANCEIRO_CONSULTAR",
    "PEDIDO_CONSULTAR",
    "PEDIDO_ENTREGAR",
  ];
  for (const [key, unit] of [
    ["A", fixture.estabelecimentoId],
    ["A2", fixture.estabelecimentoId],
    ["B", fixture.destinoId],
  ]) {
    const f = await admin(
      "funcionarios",
      "POST",
      {
        nome: "Caixa financeiro smoke " + key,
        funcao: "CAIXA",
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
      await admin(
        "permissoes/funcionarios/" + f.vinculoId + "/individuais/" + code,
        "PUT",
        { estado: "PERMITIR", versao: p.versao },
        unit,
      );
    }
    const p = await admin(
      "permissoes/funcionarios/" + f.vinculoId,
      "GET",
      undefined,
      unit,
    );
    await admin(
      "permissoes/funcionarios/" + f.vinculoId + "/adicionais",
      "PUT",
      { funcoes: ["BAR"], versao: p.versao },
      unit,
    );
    actors[key] = { id: f.vinculoId, pin, unit };
  }
  await evaluate("document.querySelector('[data-page=caixa]').click();true");
  await wait(
    "!!document.getElementById('fin-discount-limit')",
    "política financeira real",
  );
  await evaluate(
    "document.getElementById('fin-operator-limit').value='2';document.getElementById('fin-discount-limit').value='1,00';document.getElementById('fin-percent-limit').value='5,00';document.getElementById('fin-withdraw-limit').value='100,00';document.getElementById('fin-difference-limit').value='0,00';document.querySelector('#caixa-view form').requestSubmit();true",
  );
  await wait(
    "document.getElementById('fin-discount-limit')?.value==='1,00'&&!document.querySelector('#caixa-view button.primary')?.disabled",
    "política financeira gravada",
  );
  const policyA = await admin("caixa/politica");
  if (!policyA.configurada || policyA.descontoLimiteCentavos !== "100")
    throw Error("Política financeira não persistiu");
  const b = await admin("caixa/politica", "GET", undefined, fixture.destinoId);
  await admin(
    "caixa/politica",
    "PUT",
    {
      limiteOperadores: 2,
      descontoLimiteCentavos: "100",
      descontoLimiteBps: 500,
      sangriaLimiteCentavos: "10000",
      divergenciaLimiteCentavos: "0",
      versao: b.versao,
    },
    fixture.destinoId,
  );
  pass(
    "2.7: política do proprietário pela interface e permissões financeiras individuais explícitas em duas unidades",
  );
  return { actors, admin };
}
export async function smokeCaixa({
  fixture,
  targetLink,
  credential,
  prepared,
  finance,
  login,
  evaluate,
  wait,
  ownerEval,
  cdp,
  pause,
  pass,
  artifacts,
  fs,
  path,
}) {
  const click = async (text, scope = ".attendance-area") => {
    await wait(
      "[...document.querySelectorAll(" +
        JSON.stringify(scope + " button") +
        ")].some(b=>b.textContent===" +
        JSON.stringify(text) +
        "&&!b.disabled)",
      "ação financeira disponível: " + text,
    );
    return evaluate(
      "[...document.querySelectorAll(" +
        JSON.stringify(scope + " button") +
        ")].find(b=>b.textContent===" +
        JSON.stringify(text) +
        ").click();true",
    );
  };
  async function op(path, method = "GET", body, actor = finance.actors.A) {
    return evaluate(
      '(async()=>{const c=await(await fetch("/api/v1/auth/csrf")).json();const r=await fetch(' +
        JSON.stringify(
          "/api/v1/operacional/estabelecimentos/" + actor.unit + "/" + path,
        ) +
        ",{method:" +
        JSON.stringify(method) +
        ',headers:{[c.headerName]:c.token,"X-Operador-Vinculo":' +
        JSON.stringify(actor.id || actor.vinculoId) +
        ',"Content-Type":"application/json","Idempotency-Key":' +
        JSON.stringify(randomUUID()) +
        "}" +
        (body === undefined
          ? ""
          : ",body:JSON.stringify(" + JSON.stringify(body) + ")") +
        "});return {status:r.status,body:r.status===204?null:await r.json()}})()",
    );
  }
  async function switchActor(actor) {
    await click("Trocar operador", "#op-protected");
    await wait(
      "document.getElementById('op-protected').hidden&&!document.getElementById('op-enter').disabled",
      "troca financeira",
    );
    await login(actor.id || actor.vinculoId, actor.pin, actor.unit);
    await wait(
      "!document.getElementById('op-protected').hidden&&!!document.querySelector('.attendance-area nav')",
      "PIN financeiro validado",
    );
  }
  async function navCash() {
    await click("Caixa");
    await wait(
      "[...document.querySelectorAll('.attendance-area h3')].some(n=>n.textContent==='Caixa da unidade')",
      "caixa real carregado",
    );
  }
  async function fill(values) {
    await wait(
      "!!document.querySelector('.financial-form')",
      "formulário financeiro pronto",
    );
    await evaluate(
      "(()=>{for(const [key,value]of Object.entries(" +
        JSON.stringify(values) +
        ')){const i=document.getElementById("fin-"+key);if(!i)throw Error("Campo financeiro ausente: "+key);if(i.type==="checkbox")i.checked=value;else i.value=value;}document.querySelector(".financial-form").requestSubmit();return true;})()',
    );
    await wait(
      "!!document.querySelector('.financial-review')",
      "revisão financeira explícita",
    );
  }
  async function confirm() {
    await evaluate(
      'document.querySelector(".financial-review button.primary").click();true',
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
        throw Error("Financeiro transborda " + label);
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
  async function ownerWait(expression) {
    for (let i = 0; i < 100; i++) {
      if (await ownerEval("Boolean(" + expression + ")").catch(() => false))
        return;
      await pause(150);
    }
    throw Error("Proprietário não exibiu aprovação financeira");
  }
  async function ownerCash() {
    const previous = await ownerEval("performance.timeOrigin");
    await ownerEval(
      "location.href=" +
        JSON.stringify(
          fixture.url +
            "/?financeiro=" +
            randomUUID() +
            "#pagina=caixa&unidade=" +
            fixture.estabelecimentoId,
        ) +
        ";true",
    );
    await ownerWait(
      "performance.timeOrigin!==" +
        previous +
        '&&document.readyState==="complete"&&!document.getElementById("app-view")?.hidden&&!!document.getElementById("fin-discount-limit")',
    );
  }
  async function approveOwner() {
    await ownerCash();
    await ownerWait(
      '[...document.querySelectorAll("#caixa-view [data-autorizacao]")].some(n=>n.textContent.includes("Situação: PENDENTE"))',
    );
    await ownerEval(
      '(()=>{const card=[...document.querySelectorAll("#caixa-view [data-autorizacao]")].find(n=>n.textContent.includes("Situação: PENDENTE"));card.querySelector("textarea").value="Conferido no smoke com banco descartavel";[...card.querySelectorAll("button")].find(b=>b.textContent==="Aprovar somente esta operação").click();return true;})()',
    );
    await ownerWait(
      '[...document.querySelectorAll("#caixa-view [data-autorizacao]")].some(n=>n.textContent.includes("Situação: APROVADA"))',
    );
  }

  let policy = await finance.admin("seguranca-operacional/inatividade");
  await finance.admin("seguranca-operacional/inatividade", "PUT", {
    segundos: 300,
    versao: policy.versao,
  });
  // Finalizar os itens reais deixados pelo smoke 2.6, sem limpar consumo nem pendências por SQL.
  await switchActor({
    ...prepared.actors.BAR,
    unit: fixture.estabelecimentoId,
  });
  const queue = await op("fila/BAR", "GET", undefined, {
    ...prepared.actors.BAR,
    unit: fixture.estabelecimentoId,
  });
  if (queue.status !== 200) throw Error("Fila real indisponível");
  for (const ticket of queue.body) {
    const item = ticket.item || ticket;
    if (item.estado === "AGUARDANDO") {
      const r = await op(
        "fila/BAR/itens/" + item.id,
        "POST",
        { estado: "EM_PREPARO", versao: item.versao },
        { ...prepared.actors.BAR, unit: fixture.estabelecimentoId },
      );
      if (r.status !== 200) throw Error("Preparo real rejeitado");
      const ready = await op(
        "fila/BAR/itens/" + item.id,
        "POST",
        { estado: "PRONTO", versao: r.body.versao },
        { ...prepared.actors.BAR, unit: fixture.estabelecimentoId },
      );
      if (ready.status !== 200) throw Error("Conclusão real rejeitada");
    }
  }
  await switchActor(finance.actors.A);
  let comandas = await op("comandas");
  if (comandas.status !== 200) throw Error("Caixa não consulta comandas");
  const consuming = [];
  for (const c of comandas.body.filter((c) => c.estado === "ABERTA")) {
    const list = await op("comandas/" + c.id + "/pedidos");
    if (list.status !== 200) throw Error("Pedidos reais não consultados");
    for (const p of list.body) {
      const ready = p.itens.filter((i) => i.estado === "PRONTO");
      if (ready.length) {
        const r = await op("pedidos/" + p.id + "/entregas", "POST", {
          itens: ready.map((i) => ({ id: i.id, versao: i.versao })),
        });
        if (r.status !== 200) throw Error("Entrega real rejeitada");
      }
    }
    if (list.body.length) consuming.push(c);
  }
  if (consuming.length !== 1) throw Error("Cenário financeiro A inesperado");
  const c = consuming[0];
  await navCash();
  await click("Abrir caixa");
  await fill({ valor: "100,00" });
  await confirm();
  await wait(
    "document.querySelector('.financial-totals')?.textContent.includes('Dinheiro esperado')",
    "abertura financeira real",
  );
  const turn = (await op("caixa/atual")).body;
  await cdp("Page.reload");
  await wait(
    '!document.getElementById("op-protected").hidden&&!!document.querySelector(".financial-totals")',
    "F5 mantém caixa e sessão PIN válida",
  );
  if ((await evaluate("location.hash")) !== "#atendimento=caixa")
    throw Error("F5 perdeu seção financeira");
  await shot("caixa-aberto");
  await click("Gerenciar operadores e responsável");
  await wait(
    '!!document.getElementById("cash-team-reason")',
    "gestão real da equipe",
  );
  await evaluate(
    '[...document.querySelectorAll(".attendance-area label")].find(n=>n.textContent==="Caixa financeiro smoke A2").querySelector("input").checked=true;document.getElementById("cash-team-responsible").value=' +
      JSON.stringify(finance.actors.A2.id) +
      ';document.getElementById("cash-team-reason").value="Transferencia explicita de responsavel";document.getElementById("cash-team-reason").form.requestSubmit();true',
  );
  await wait(
    'document.querySelector(".financial-totals")?.textContent.includes("Caixa financeiro smoke A2")',
    "responsável transferido sem fechar turno",
  );
  pass(
    "2.7: equipe simultânea e transferência do responsável sem conceder capacidades ou fechar o turno",
  );
  async function account() {
    await click("Comandas");
    await wait(
      "!!document.querySelector('.attendance-area .attendance-grid')",
      "comandas para conta",
    );
    await evaluate(
      '[...document.querySelectorAll(".attendance-card")].find(n=>n.textContent.includes(' +
        JSON.stringify(c.codigo) +
        ')).querySelector("button").click();true',
    );
    await wait(
      "[...document.querySelectorAll('.attendance-area button')].some(b=>b.textContent==='Consultar conta e pagamentos')",
      "acesso à conta",
    );
    await click("Consultar conta e pagamentos");
    await wait(
      "!!document.querySelector('.financial-account .financial-totals')",
      "conta real",
    );
  }
  await account();
  const before = (await op("comandas/" + c.id + "/conta")).body;
  if (before.consumoCentavos !== "4500")
    throw Error(
      "Cancelados ou substituídos cobrados indevidamente: total divergente",
    );
  await shot("conta-pedidos-reais");
  await click("Aplicar desconto", ".financial-account");
  await fill({
    tipo: "VALOR",
    valor: "2,00",
    motivo: "Ajuste autorizado no smoke",
  });
  await confirm();
  await wait(
    "!!document.querySelector('.financial-approval')",
    "desconto exige aprovação",
  );
  await click("Solicitar autorização ao proprietário", ".financial-approval");
  await wait(
    "[...document.querySelectorAll('.financial-approval button')].some(b=>b.textContent==='Consultar decisão')",
    "solicitação real persistida",
  );
  await approveOwner();
  await click("Consultar decisão", ".financial-approval");
  await wait(
    "!document.querySelector('.financial-review button.primary').disabled",
    "aprovação revalidada",
  );
  await confirm();
  await wait(
    "document.querySelector('.financial-account .financial-totals')?.textContent.includes('R$ 2,00')",
    "desconto autorizado lançado",
  );
  pass(
    "2.7: conta real exclui pedidos cancelados/substituídos; desconto acima do limite exige aprovação específica e nova confirmação",
  );
  await click("Receber pagamento", ".financial-account");
  await fill({
    meio: "DINHEIRO",
    valor: "10,00",
    recebido: "20,00",
    externo: false,
  });
  if (
    !(await evaluate(
      "document.querySelector('.financial-review').textContent.includes('Troco: R$ 10,00')",
    ))
  )
    throw Error("Troco incorreto");
  await evaluate(
    'window.ktFinanceNative=window.fetch;window.ktFinanceLost=false;window.ktFinancePosts=0;window.fetch=async(input,options)=>{const r=await window.ktFinanceNative(input,options);if(String(input).endsWith("/pagamentos")&&options?.method==="POST"){window.ktFinancePosts++;if(!window.ktFinanceLost&&r.ok){window.ktFinanceLost=true;throw TypeError("resposta real descartada pelo teste");}}return r;};true',
  );
  await confirm();
  await wait(
    "document.querySelector('.financial-message')?.textContent.includes('Resposta não recebida')",
    "resposta perdida após gravação real",
  );
  await confirm();
  await wait(
    "!!document.querySelector('.financial-account .financial-totals')&&!document.querySelector('.financial-review')",
    "resultado recuperado por consulta",
  );
  if ((await evaluate("window.ktFinancePosts")) !== 1)
    throw Error("Pagamento reenviado após sucesso real");
  await evaluate("window.fetch=window.ktFinanceNative;true");
  for (const [meio, valor] of [
    ["PIX", "10,00"],
    ["DEBITO", "10,00"],
    ["CREDITO", "13,00"],
  ]) {
    await click("Receber pagamento", ".financial-account");
    await fill({ meio, valor, recebido: "", externo: true });
    await confirm();
    await wait(
      "!!document.querySelector('.financial-account .financial-totals')&&!document.querySelector('.financial-review')",
      "recebimento real " + meio,
    );
  }
  // Estorno externo pela interface: autorização, pendência, conferência manual e novo recebimento.
  await evaluate(
    '[...document.querySelectorAll(".financial-account [data-lancamento]")].find(n=>n.textContent.includes("Pix conferido externamente")&&n.textContent.includes("Pagamento")).querySelector("button").click();true',
  );
  await fill({
    natureza: "ESTORNO_PAGAMENTO",
    motivo: "Devolucao ficticia de Pix para validar fluxo",
  });
  await confirm();
  await wait(
    '!!document.querySelector(".financial-approval")',
    "estorno exige proprietário",
  );
  await click("Solicitar autorização ao proprietário", ".financial-approval");
  await wait(
    '[...document.querySelectorAll(".financial-approval button")].some(b=>b.textContent==="Consultar decisão")',
    "solicitação de estorno persistida",
  );
  await approveOwner();
  await click("Consultar decisão", ".financial-approval");
  await wait(
    '!document.querySelector(".financial-review button.primary").disabled',
    "aprovação de estorno revalidada",
  );
  await confirm();
  await wait(
    'document.querySelector(".financial-account")?.textContent.includes("Devolução externa ainda não confirmada")',
    "pendência externa real",
  );
  await shot("estorno-externo-pendente");
  await click("Confirmar devolução externa", ".financial-account");
  await fill({
    confirmado: true,
    motivo: "Conferido fora do sistema, em teste ficticio",
  });
  await confirm();
  await wait(
    '!!document.querySelector(".financial-account .financial-totals")&&!document.querySelector(".financial-review")&&!document.querySelector(".financial-account").textContent.includes("Devolução externa ainda não confirmada")',
    "confirmação externa auditada",
  );
  await click("Receber pagamento", ".financial-account");
  await fill({ meio: "PIX", valor: "10,00", recebido: "", externo: true });
  await confirm();
  await wait(
    '!!document.querySelector(".financial-account .financial-totals")&&!document.querySelector(".financial-review")',
    "novo pagamento após estorno",
  );
  pass(
    "2.7: estorno interno autorizado não afirma devolução bancária; confirmação externa explícita e novo recebimento preservam original",
  );
  const paid = (await op("comandas/" + c.id + "/conta")).body;
  if (paid.saldoCentavos !== "0" || paid.pagoCentavos !== "4300")
    throw Error("Pagamentos combinados inconsistentes");
  await shot("conta-quitada");
  await click("Quitar e encerrar comanda", ".financial-account");
  await fill({});
  await confirm();
  await wait(
    "document.querySelector('.financial-account .financial-totals')?.textContent.includes('Encerrada')",
    "quitação real com histórico preservado",
  );
  pass(
    "2.7: dinheiro/troco, Pix, débito e crédito reais; resposta perdida recuperada sem segundo POST; quitação sem apagar histórico",
  );
  await navCash();
  for (const [label, valor] of [
    ["Registrar sangria", "5,00"],
    ["Registrar reforço", "3,00"],
  ]) {
    await click(label);
    await fill({ valor, motivo: "Movimento ficticio conferido" });
    await confirm();
    await wait(
      "!!document.querySelector('.financial-totals')&&!document.querySelector('.financial-review')",
      "movimento financeiro real",
    );
  }
  const summary = (await op("caixa/turnos/" + turn.id)).body;
  if (summary.dinheiroEsperadoCentavos !== "10800")
    throw Error("Reconciliação física incorreta");
  await click("Conferir dinheiro e preparar fechamento");
  await fill({ valor: "108,00", motivo: "" });
  await confirm();
  await wait(
    "[...document.querySelectorAll('.attendance-area button')].some(b=>b.textContent==='Revisar fechamento do caixa')",
    "conferência persistida",
  );
  await click("Revisar fechamento do caixa");
  await fill({});
  await confirm();
  await wait(
    "[...document.querySelectorAll('.attendance-area button')].some(b=>b.textContent==='Abrir caixa')",
    "turno efetivamente fechado",
  );
  await shot("caixa-fechado");
  await click("Relatórios financeiros");
  await wait("!!document.getElementById('fin-report-from')", "filtros reais");
  await evaluate(
    'document.getElementById("fin-report-from").value=new Date(Date.now()-3600000-new Date().getTimezoneOffset()*60000).toISOString().slice(0,16);document.querySelector(".attendance-area form").requestSubmit();true',
  );
  await wait("!!document.querySelector('.financial-report')", "relatório real");
  if (
    !(await evaluate(
      "document.querySelector('.financial-report').textContent.includes('R$ 43,00')",
    ))
  )
    throw Error("Venda liquidada não aparece no relatório");
  await shot("relatorios-financeiros");
  pass(
    "2.7: sangria/reforço físicos, conferência, fechamento e relatório de vendas liquidadas pela interface nas três resoluções",
  );
  await ownerCash();
  await ownerWait(
    '[...document.querySelectorAll("#caixa-view button")].some(b=>b.textContent==="Reabrir turno com justificativa")',
  );
  await ownerEval(
    '[...document.querySelectorAll("#caixa-view button")].find(b=>b.textContent==="Reabrir turno com justificativa").click();true',
  );
  await ownerWait('!!document.querySelector("#caixa-view .financial-form")');
  await ownerEval(
    'document.querySelector("#caixa-view .financial-form textarea").value="Revisao de fechamento ficticio";document.querySelector("#caixa-view .financial-form").requestSubmit();true',
  );
  await ownerWait('!!document.querySelector("#caixa-view .financial-review")');
  await ownerEval(
    'document.querySelector("#caixa-view .financial-review button.primary").click();true',
  );
  await ownerWait(
    '[...document.querySelectorAll("#caixa-view h4")].some(n=>n.textContent==="Turno aberto")',
  );
  await navCash();
  await wait(
    'document.querySelector(".financial-totals")?.textContent.includes("ciclo 2")',
    "reabertura controlada refletida",
  );
  await click("Conferir dinheiro e preparar fechamento");
  await fill({ valor: "108,00", motivo: "" });
  await confirm();
  await wait(
    '[...document.querySelectorAll(".attendance-area button")].some(b=>b.textContent==="Revisar fechamento do caixa")',
    "segunda conferência",
  );
  await click("Revisar fechamento do caixa");
  await fill({});
  await confirm();
  await wait(
    '[...document.querySelectorAll(".attendance-area button")].some(b=>b.textContent==="Abrir caixa")',
    "segundo fechamento real",
  );
  const history = await op("caixa/turnos/" + turn.id + "/relatorios");
  if (history.status !== 200 || history.body.length !== 2)
    throw Error("Relatório anterior perdido na reabertura");
  pass(
    "2.7: reabertura administrativa com motivo e novo fechamento preservam os dois relatórios imutáveis",
  );
  await switchActor(finance.actors.B);
  await navCash();
  if (
    !(await evaluate(
      "[...document.querySelectorAll('.attendance-area button')].some(b=>b.textContent==='Abrir caixa')",
    ))
  )
    throw Error("Turno A vazou para B");
  const cross = await op(
    "comandas/" + c.id + "/conta",
    "GET",
    undefined,
    finance.actors.B,
  );
  if (cross.status !== 404) throw Error("Conta A acessível em B");
  await click("Abrir caixa");
  await fill({ valor: "0,00" });
  await confirm();
  await wait(
    "!!document.querySelector('.financial-totals')",
    "caixa B independente",
  );
  pass(
    "2.7: segunda unidade com PIN independente, caixa separado e acesso cruzado à conta bloqueado",
  );
  // Retornar ao roteiro anterior, preservando seu perfil e seu prazo de inatividade.
  await switchActor({
    id: targetLink,
    pin: credential.pin,
    unit: fixture.estabelecimentoId,
  });
  policy = await finance.admin("seguranca-operacional/inatividade");
  await finance.admin("seguranca-operacional/inatividade", "PUT", {
    segundos: 30,
    versao: policy.versao,
  });
  await click("Mesas");
  await wait(
    "!!document.querySelector('.attendance-area .attendance-card')",
    "retorno ao roteiro de segurança",
  );
}
