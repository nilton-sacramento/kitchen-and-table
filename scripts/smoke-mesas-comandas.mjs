// Extensão do smoke oficial: Chrome + backend real + PostgreSQL 16 descartável.
export async function prepararMesas({
  fixture,
  targetLink,
  evaluate,
  wait,
  ownerEval,
  cdp,
  pass,
}) {
  const click = (text) =>
    evaluate(
      '([...document.querySelectorAll("#mesas-view button")].find(b=>b.textContent===' +
        JSON.stringify(text) +
        ")).click();true",
    );
  await evaluate("document.querySelector('[data-page=mesas]').click();true");
  await wait(
    "!document.getElementById('mesas-view').hidden && document.querySelector('#mesas-view .attendance-grid')",
    "administração real de mesas",
  );
  await click("Nova mesa");
  await evaluate(
    "document.getElementById('mesa-identificacao').value='01';document.querySelector('#mesas-view form').requestSubmit();true",
  );
  await wait(
    "document.querySelector('#mesas-view .attendance-card h3')?.textContent==='01'",
    "cadastro real de mesa",
  );
  await click("Editar identificação");
  await evaluate(
    "document.getElementById('mesa-identificacao').value='Mesa principal';document.querySelector('#mesas-view form').requestSubmit();true",
  );
  await wait(
    "document.querySelector('#mesas-view .attendance-card h3')?.textContent==='Mesa principal'",
    "edição real de mesa",
  );
  await click("Desativar");
  await wait(
    "document.querySelector('#mesas-view .attendance-state')?.textContent==='Indisponível'",
    "mesa desativada",
  );
  await click("Ativar");
  await wait(
    "document.querySelector('#mesas-view .attendance-state')?.textContent==='Livre'",
    "mesa ativada",
  );
  const hash = await evaluate("location.hash");
  await cdp('Page.reload');
  await wait(
    "!document.getElementById('mesas-view').hidden && document.querySelector('#mesas-view .attendance-card')",
    "F5 em mesas",
  );
  if ((await evaluate("location.hash")) !== hash)
    throw Error("F5 perdeu a unidade ou seção");
  await evaluate(
    'document.getElementById("establishment-select").value=' +
      JSON.stringify(fixture.destinoId) +
      ';document.getElementById("establishment-select").dispatchEvent(new Event("change"));true',
  );
  await wait(
    "!document.getElementById('app-view').hidden && document.querySelector('#mesas-view [role=status]')?.textContent.startsWith('Nenhuma')",
    "unidade B sem dados da unidade A",
  );
  await evaluate(
    'document.getElementById("establishment-select").value=' +
      JSON.stringify(fixture.estabelecimentoId) +
      ';document.getElementById("establishment-select").dispatchEvent(new Event("change"));true',
  );
  await wait(
    "!document.getElementById('app-view').hidden && document.querySelector('#mesas-view .attendance-card h3')?.textContent==='Mesa principal'",
    "retorno à unidade A",
  );
  // O cenario anterior revoga PRODUTO_CONSULTAR; o proprietario concede explicitamente na fixture antes do fluxo do cardapio.
  for (const [target,codes] of [[targetLink,['COMANDA_ENCERRAR','COMANDA_CANCELAR','PRODUTO_CONSULTAR']],[fixture.managerVinculo,['MESA_CONSULTAR','COMANDA_CONSULTAR','COMANDA_ENCERRAR']]]) {
    for(const code of codes){
      const result=await ownerEval('(async()=>{const base="/api/v1/estabelecimentos/'+fixture.estabelecimentoId+'/permissoes/funcionarios/'+target+'";const f=await(await fetch(base)).json();const csrf=await(await fetch("/api/v1/auth/csrf")).json();return(await fetch(base+"/individuais/'+code+'",{method:"PUT",headers:{[csrf.headerName]:csrf.token,"Content-Type":"application/json"},body:JSON.stringify({estado:"PERMITIR",versao:f.versao})})).status})()');
      if(result!==200)throw Error('Concessão explícita de teste recusada: HTTP '+result);
    }
  }
  pass(
    "2.4: mesas reais, edição, ativação, desativação, F5 e isolamento entre duas unidades",
  );
  await evaluate(
    "document.querySelector('[data-page=funcionarios]').click();true",
  );
  await wait(
    "!document.getElementById('employee-view').hidden && document.querySelector('.employee-table')",
    "voltar à equipe",
  );
}
export async function smokeMesasComandas({
  credential, login,
  fixture,
  targetLink,
  cdp,
  evaluate,
  wait,
  pass,
  pause,
  artifacts,
  fs,
  path,
}) {
  const click = (text) =>
    evaluate(
      '([...document.querySelectorAll(".attendance-area button")].find(b=>b.textContent===' +
        JSON.stringify(text) +
        ")).click();true",
    );
  await wait(
    "!!document.querySelector('.attendance-area .attendance-card')",
    "mesas operacionais carregadas",
  );
  await click("Abrir atendimento");
  await evaluate(
    "document.getElementById('comanda-cliente').value='Cliente fictício do smoke';document.querySelector('.attendance-area form').requestSubmit();document.querySelector('.attendance-area form')?.requestSubmit();true",
  );
  await wait(
    "document.querySelector('.attendance-area h3')?.textContent.includes('C-000001') && document.querySelector('.attendance-area ol')",
    "abertura idempotente pela interface",
  );
  await cdp("Page.reload");
  await wait(
    "!document.getElementById('op-protected').hidden && document.querySelector('.attendance-area h3')?.textContent.includes('C-000001')",
    "F5 mantém comanda e operador",
  );
  for (const [name, width, height] of [
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
    if (!(await evaluate("document.documentElement.scrollWidth<=innerWidth+2")))
      throw Error("Atendimento transborda em " + name);
    const image = await cdp("Page.captureScreenshot", {
      format: "png",
      captureBeyondViewport: true,
    });
    await fs.writeFile(
      path.join(artifacts, "atendimento-" + name + ".png"),
      Buffer.from(image.data, "base64"),
    );
  }
  async function switchTo(link,pin,name){
    await evaluate("[...document.querySelectorAll('#op-protected button')].find(b=>b.textContent==='Trocar operador').click();true");
    await wait("document.getElementById('op-protected').hidden && !document.getElementById('op-enter').disabled",'troca segura para atendimento compartilhado');
    await login(link,pin);await wait("!document.getElementById('op-protected').hidden && !!document.querySelector('.attendance-area ol')",'comanda acessível ao segundo operador');
    if(!await evaluate('document.getElementById("op-protected").textContent.includes('+JSON.stringify(name)+')'))throw Error('Executor esperado não foi identificado');
  }
  await switchTo(fixture.managerVinculo,credential.secondPin,'ZZZ Gerente de teste');
  await click("Encerrar sem consumo");
  await evaluate(
    "document.querySelector('.attendance-area form').requestSubmit();true",
  );
  await wait(
    "document.querySelector('.attendance-area .attendance-state')?.textContent==='Encerrada operacionalmente'",
    "encerramento sem pagamento",
  );
  if (
    !(await evaluate(
      "document.querySelector('.attendance-area ol').children.length===2",
    ))
  )
    throw Error("Histórico duplicado ou ausente");
  if(!await evaluate("document.querySelector('.attendance-area ol').textContent.includes('Funcionario permissoes') && document.querySelector('.attendance-area ol').textContent.includes('ZZZ Gerente de teste')"))throw Error('Histórico não preservou abertura e executor compartilhado');
  pass('2.4: atendimento compartilhado real entre dois operadores com PIN, abertura preservada e executor auditado');
  await switchTo(targetLink,credential.pin,'Funcionario permissoes');
  await click("Mesas");
  await wait(
    "document.querySelector('.attendance-area .attendance-state')?.textContent==='Livre'",
    "mesa liberada",
  );
  await click("Nova comanda de balcão");
  await evaluate(
    "document.querySelector('.attendance-area form').requestSubmit();true",
  );
  await wait(
    "document.querySelector('.attendance-area h3')?.textContent.includes('C-000002')",
    "balcão real",
  );
  await click("Cancelar comanda");
  await evaluate(
    "document.getElementById('comanda-motivo').value='Desistência no ambiente de teste';document.querySelector('.attendance-area form').requestSubmit();true",
  );
  await wait(
    "document.querySelector('.attendance-area .attendance-state')?.textContent==='Cancelada'",
    "cancelamento justificado",
  );
  const blocked = await evaluate(
    '(async()=>{const csrf=await(await fetch("/api/v1/auth/csrf")).json();return(await fetch("/api/v1/operacional/estabelecimentos/' +
      fixture.destinoId +
      '/comandas",{headers:{"X-Operador-Vinculo":"' +
      targetLink +
      '"}})).status})()',
  );
  if (blocked !== 403) throw Error("Outra unidade não foi bloqueada");
  if (!(await evaluate("!localStorage.length && !sessionStorage.length")))
    throw Error("Atendimento gravou dados no armazenamento do navegador");
  pass(
    "2.4: mesa e balcão reais, duplo envio, F5, três resoluções, fechamento sem pagamento, cancelamento, histórico e isolamento",
  );
  await click("Mesas");
  await wait(
    "!!document.querySelector('.attendance-area .attendance-card')",
    "retorno às mesas",
  );
}
