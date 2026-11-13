// Smoke local com Chrome instalado e fixture Java/PostgreSQL descartavel. Sem dependencias npm.
import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import readline from 'node:readline';
const pause = (ms) => new Promise((r) => setTimeout(r, ms));
const tools = path.resolve('.tools');
const artifacts = path.join(tools, 'browser-artifacts');
await fs.mkdir(artifacts, { recursive: true });
const report = await fs.readFile('target/surefire-reports/TEST-com.kitchentable.funcionarios.FuncionariosPostgresTest.xml', 'utf8');
const rawClasspath = report.match(/<property name="java.class.path" value="([^"]+)"/u)?.[1];
if (!rawClasspath) throw new Error('Execute Maven verify antes do smoke.');
const classpath = rawClasspath.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
const credential = { email: `browser-${crypto.randomUUID()}@example.com`, password: crypto.randomUUID(), managerEmail: `manager-${crypto.randomUUID()}@example.com`, managerPassword: crypto.randomUUID() };
const java = spawn('java', ['-cp', classpath, 'com.kitchentable.funcionarios.FuncionariosBrowserFixture'], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
java.stderr.on('data', () => {}); // Nunca encaminhar ambiente/credenciais ou logs brutos.
const lines = readline.createInterface({ input: java.stdout });
let readyTimer;
const ready = new Promise((resolve, reject) => {
  readyTimer = setTimeout(() => reject(new Error('Fixture descartável não iniciou no prazo.')), 120000);
  lines.on('line', (line) => { if (line.startsWith('KT_BROWSER_READY ')) { clearTimeout(readyTimer); resolve(JSON.parse(line.slice('KT_BROWSER_READY '.length))); } });
  java.once('exit', () => reject(new Error('Fixture descartável encerrou antes da validação.')));
});
java.stdin.write(`${JSON.stringify(credential)}\n`);
let chrome, socket;
const profile = path.join(tools, `chrome-test-${crypto.randomUUID()}`);
let sequence = 0;
const pending = new Map();
function cdp(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP sem resposta: ${method}`)); }, 15000);
    pending.set(id, { resolve, reject, timer }); socket.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const result = await cdp('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (result.exceptionDetails) throw new Error('Falha no passo do navegador (conteúdo omitido).');
  return result.result.value;
}
async function wait(expression, description) {
  for (let i = 0; i < 100; i++) { if (await evaluate(expression) && await evaluate("document.getElementById('employee-view')?.getAttribute('aria-busy')!=='true'")) return; await pause(150); }
  const diagnostic = await evaluate("({appHidden:document.getElementById('app-view')?.hidden,loginError:document.getElementById('login-error')?.textContent,employeeMessage:document.querySelector('[data-message]')?.textContent,busy:document.getElementById('employee-view')?.getAttribute('aria-busy'),ready:document.readyState})").catch(() => ({}));
  console.log('DIAGNOSTIC:', JSON.stringify(diagnostic));
  throw new Error(`Tempo esgotado: ${description}`);
}
async function press(key, code, windowsVirtualKeyCode) {
  await cdp('Input.dispatchKeyEvent', { type: 'keyDown', key, code, windowsVirtualKeyCode, ...(key === 'Enter' ? { text: '\r', unmodifiedText: '\r' } : {}) });
  await cdp('Input.dispatchKeyEvent', { type: 'keyUp', key, code, windowsVirtualKeyCode });
}
function pass(name) { console.log(`PASS: ${name}`); }
try {
  const fixture = await ready;
  if (!fixture.url.startsWith('http://127.0.0.1:')) throw new Error('Fixture fora do loopback.');
  const executable = process.env.KT_CHROME_EXECUTABLE || (process.platform === 'win32' ? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe' : 'google-chrome');
  await fs.mkdir(profile, { recursive: true });
  chrome = spawn(executable, ['--headless=new', ...(process.env.CI ? ['--no-sandbox'] : []), '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-background-networking', '--disable-sync', '--disable-extensions', '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
  chrome.stderr.on('data', () => {});
  let port;
  for (let i = 0; i < 100; i++) { try { port = Number((await fs.readFile(path.join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); if (port) break; } catch {} await pause(100); }
  if (!port) throw new Error('Chrome de teste não iniciou.');
  const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(pages.find((p) => p.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = () => reject(new Error('Falha na conexão com Chrome.')); });
  socket.onmessage = (event) => {
    const data = JSON.parse(event.data);
    if (data.method === 'Runtime.exceptionThrown') {
      const error = data.params.exceptionDetails.exception;
      const safe = String(error?.description || data.params.exceptionDetails.text).split('\n')[0].replaceAll(credential.email, '[fixture]').replaceAll(credential.password, '[fixture]').replaceAll(credential.managerEmail, '[fixture]').replaceAll(credential.managerPassword, '[fixture]');
      console.log('BROWSER_ERROR:', safe);
    }
    const item = pending.get(data.id); if (!item) return;
    clearTimeout(item.timer); pending.delete(data.id);
    if (data.error) item.reject(new Error('Comando do navegador rejeitado.')); else item.resolve(data.result);
  };
  await cdp('Page.enable'); await cdp('Runtime.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: 'window.confirm=()=>true;' });
  await cdp('Page.navigate', { url: fixture.url });
  await wait("document.getElementById('login-submit') && !document.getElementById('login-submit').disabled", 'formulário de login');
  await evaluate(`document.getElementById('email').value=${JSON.stringify(credential.email)};document.getElementById('password').value=${JSON.stringify(credential.password)};document.getElementById('login-form').requestSubmit();true`);
  await wait("!document.getElementById('app-view').hidden && document.getElementById('establishment-select').value", 'login real');
  pass('login real por sessão no banco descartável');
  await evaluate("document.querySelector('[data-page=funcionarios]').click();true");
  await wait("!document.getElementById('employee-view').hidden && !!document.querySelector('.employee-table')", 'tela de funcionários');
  await evaluate("[...document.querySelectorAll('#employee-view button')].find(b=>b.textContent==='Nova pessoa').click();true");
  const name = 'Pessoa de teste <script>window.ktXss=1</script>';
  await evaluate(`document.getElementById('employee-nome').value=${JSON.stringify(name)};document.querySelector('.employee-form').requestSubmit();true`);
  await wait(`document.querySelector('.employee-table tbody')?.textContent.includes(${JSON.stringify(name)})`, 'cadastro sem contato ou senha');
  if (!(await evaluate("!window.ktXss && !document.querySelector('.employee-table script')"))) throw new Error('Renderização insegura.');
  pass('cadastro real sem e-mail/senha e renderização de nome como texto');
  await evaluate("var b=[...document.querySelectorAll('.employee-table tbody button')].find(b=>b.textContent==='Editar');b.focus();b.click();document.getElementById('employee-nome').value='Pessoa editada';document.querySelector('.employee-form').requestSubmit();true");
  await wait("document.querySelector('.employee-table tbody')?.textContent.includes('Pessoa editada')", 'edição real');
  if (!(await evaluate("document.activeElement?.textContent==='Editar'"))) throw new Error('Foco perdido após salvar edição.');
  pass('edição com controle de versão e foco restaurado');
  await evaluate("window.confirm=()=>true;[...document.querySelectorAll('.employee-table tbody button')].find(b=>b.textContent==='Desativar').click();true");
  await wait("document.querySelector('.employee-table tbody tr')?.children[3]?.textContent==='Inativo'", 'desativação');
  await evaluate("[...document.querySelectorAll('.employee-table tbody button')].find(b=>b.textContent==='Ativar').click();true");
  await wait("document.querySelector('.employee-table tbody tr')?.children[3]?.textContent==='Ativo'", 'reativação');
  pass('ativação e desativação integradas');
  await evaluate("[...document.querySelectorAll('.employee-table tbody button')].find(b=>b.textContent==='Função').click();document.getElementById('employee-funcao').value='GERENTE';document.querySelector('.employee-form').requestSubmit();true");
  await wait("document.querySelector('.employee-table tbody tr')?.children[1]?.textContent==='Gerente'", 'promoção pelo proprietário');
  await evaluate("[...document.querySelectorAll('.employee-table tbody button')].find(b=>b.textContent==='Limites do gerente').click();true");
  await wait("document.querySelectorAll('.employee-form input[type=checkbox]').length===10", 'limites administrativos');
  await evaluate("const checks=document.querySelectorAll('.employee-form input[type=checkbox]');checks[0].checked=true;checks[1].checked=true;checks[6].checked=true;document.querySelector('.employee-form').requestSubmit();true");
  await wait("document.querySelector('[data-message]')?.textContent==='Limites do gerente atualizados.'", 'delegação pelo proprietário');
  pass('função e delegação mínima gravadas pela API');
  const sourceIdentity = await evaluate("document.querySelector('.employee-identity').textContent");
  await evaluate("[...document.querySelectorAll('.employee-table tbody button')].find(b=>b.textContent==='Associar a outra unidade').click();document.querySelector('.employee-form').requestSubmit();true");
  await wait("document.querySelector('[data-message]')?.textContent.startsWith('Solicitação enviada.')", 'solicitação de associação');
  await evaluate(`document.getElementById('establishment-select').value=${JSON.stringify(fixture.destinoId)};document.getElementById('establishment-select').dispatchEvent(new Event('change'));true`);
  await wait("[...document.querySelectorAll('#employee-view button')].some(b=>b.textContent==='Aceitar vínculo')", 'associação no destino');
  await evaluate("[...document.querySelectorAll('#employee-view button')].find(b=>b.textContent==='Aceitar vínculo').click();true");
  await wait("document.querySelector('.employee-table tbody')?.textContent.includes('Pessoa editada')", 'aceite da associação');
  if ((await evaluate("document.querySelector('.employee-identity').textContent")) !== sourceIdentity) throw new Error('Associação criou outra identidade.');
  if ((await evaluate("document.querySelector('.employee-table tbody tr').children[1].textContent")) !== 'Atendente') throw new Error('Função da origem copiada indevidamente.');
  pass('associação real com identidade preservada e função independente no destino');
  await evaluate(`document.getElementById('establishment-select').value=${JSON.stringify(fixture.estabelecimentoId)};document.getElementById('establishment-select').dispatchEvent(new Event('change'));true`);
  await wait("document.querySelector('.employee-table tbody tr')?.children[1]?.textContent==='Gerente'", 'retorno à origem');
  await cdp('Page.reload');
  await wait("!document.getElementById('app-view').hidden && !!document.getElementById('establishment-select').value", 'F5 com sessão');
  await evaluate("document.querySelector('[data-page=funcionarios]').click();true");
  await wait("document.querySelector('.employee-table tbody')?.textContent.includes('Pessoa editada')", 'persistência após F5');
  pass('sessão e dados preservados após F5');
  for (const [label, width, height] of [['desktop',1366,900],['tablet',768,1024],['mobile',360,800]]) {
    await cdp('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: width < 500 });
    await pause(150);
    if (!(await evaluate('document.documentElement.scrollWidth <= window.innerWidth + 2'))) throw new Error(`Rolagem horizontal externa em ${label}.`);
    if (width === 360) {
      if (!(await evaluate("getComputedStyle(document.querySelector('.employee-table')).display==='block' && document.querySelector('.employee-table-wrap').scrollWidth<=window.innerWidth && !document.querySelector('.employee-identifiers').open"))) throw new Error('Cartao mobile ou identificadores incorretos.');
      if (!(await evaluate("[...document.querySelectorAll('.employee-table tbody button')].every(b=>{const r=b.getBoundingClientRect();return r.left>=0 && r.right<=innerWidth && r.height>=44 && !!b.getAttribute('aria-label')})"))) throw new Error('Acoes mobile inacessiveis ou pequenas.');
      await evaluate("document.querySelector('.employee-identifiers summary').focus();true");
      await press('Enter', 'Enter', 13);
      if (!(await evaluate("document.querySelector('.employee-identifiers').open"))) throw new Error('Identificadores nao abrem pelo teclado.');
      await press('Enter', 'Enter', 13);
      await evaluate("[...document.querySelectorAll('.employee-table tbody button')].find(b=>b.textContent==='Editar').focus();true");
      await press('Enter', 'Enter', 13);
      if (!(await evaluate("document.activeElement?.id==='employee-nome'"))) throw new Error('Formulario sem foco inicial.');
      await press('Escape', 'Escape', 27);
      if (!(await evaluate("document.querySelector('[data-editor]').hidden && document.activeElement?.textContent==='Editar'"))) throw new Error('Escape nao devolveu foco.');
      await press('Tab', 'Tab', 9);
      if (!(await evaluate("document.activeElement?.textContent==='Função'"))) throw new Error('Ordem de Tab incorreta.');
      await evaluate("document.getElementById('menu-toggle').focus();true");
      await press('Enter', 'Enter', 13);
      if (!(await evaluate("document.getElementById('menu-toggle').getAttribute('aria-expanded')==='true'"))) throw new Error('Menu mobile nao abriu pelo teclado.');
      await press('Escape', 'Escape', 27);
      if (!(await evaluate("document.getElementById('menu-toggle').getAttribute('aria-expanded')==='false' && document.activeElement.id==='menu-toggle'"))) throw new Error('Menu mobile nao fechou com foco correto.');
      await evaluate("document.activeElement.blur();window.scrollTo({top:0,left:0,behavior:'instant'});true");
      pass('mobile: cartoes, acoes de 44px, identificadores e teclado Enter/Tab/Escape');
    } else if (!(await evaluate("getComputedStyle(document.querySelector('.employee-table')).display==='table'"))) throw new Error('Layout de tabela desktop/tablet alterado.');
    await evaluate("window.scrollTo({top:0,left:0,behavior:'instant'});true");
    await pause(150);
    const metrics = await cdp('Page.getLayoutMetrics');
    const image = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true, clip: { x: 0, y: 0, width, height: Math.max(height, Math.ceil(metrics.cssContentSize.height)), scale: 1 } });
    await fs.writeFile(path.join(artifacts, `${label}.png`), Buffer.from(image.data, 'base64'));
    pass(`layout sem transbordamento externo: ${label}`);
  }
  // Entrega 2.2: todas as alteracoes abaixo usam APIs reais no banco descartavel.
  await evaluate("[...document.querySelectorAll('#employee-view button')].find(b=>b.textContent==='Nova pessoa').click();document.getElementById('employee-nome').value='Funcionario permissoes';document.querySelector('.employee-form').requestSubmit();true");
  await wait("document.querySelector('.employee-table tbody')?.textContent.includes('Funcionario permissoes')", 'funcionario para permissoes');
  const targetLink = await evaluate("[...document.querySelectorAll('.employee-table tbody tr')].find(r=>r.textContent.includes('Funcionario permissoes')).querySelector('.employee-link-id').textContent.replace('Vínculo: ','')");
  const permBase = `/api/v1/estabelecimentos/${fixture.estabelecimentoId}/permissoes`;
  async function permissionPanel() {
    await evaluate("var trigger=[...document.querySelectorAll('.employee-table tbody tr')].find(r=>r.textContent.includes('Funcionario permissoes')).querySelector('button[data-focus$=\":Funções e permissões\"]');trigger.focus();trigger.click();true");
    await wait("!!document.querySelector('.permission-card')", 'painel real de permissoes');
  }
  await permissionPanel();
  await evaluate("const field=[...document.querySelectorAll('.permission-form fieldset label')].find(l=>l.textContent==='BAR').querySelector('input');field.checked=true;[...document.querySelectorAll('.permission-form button')].find(b=>b.textContent==='Salvar funções adicionais').click();true");
  await wait("document.querySelector('[data-message]')?.textContent.startsWith('Funções adicionais atualizadas.')", 'funcoes adicionais');
  await permissionPanel();
  if (!(await evaluate("document.querySelector('[data-code=PRODUTO_CONSULTAR]').textContent.includes('BAR')"))) throw new Error('Heranca de adicional nao exibida.');
  async function exception(state) {
    await evaluate(`var card=document.querySelector('[data-code=PRODUTO_CONSULTAR]');card.querySelector('select').value=${JSON.stringify(state)};card.querySelector('button').click();true`);
    await wait("document.querySelector('[data-message]')?.textContent.startsWith('Permissão atualizada.') && !document.getElementById('employee-view').hasAttribute('aria-busy') && !document.querySelector('.permission-form')", 'excecao real');
    await permissionPanel();
    if (!(await evaluate(`document.querySelector('[data-code=PRODUTO_CONSULTAR] select').value===${JSON.stringify(state)}`))) throw new Error('Excecao nao persistida.');
  }
  await exception('NEGAR');await exception('PERMITIR');await exception('HERDAR');
  pass('permissoes: adicionais, uniao herdada, negar, permitir e herdar persistidos');
  for (const [label,width,height] of [['desktop',1366,900],['tablet',768,1024],['mobile',360,800]]) {
    await cdp('Emulation.setDeviceMetricsOverride', { width,height,deviceScaleFactor:1,mobile:width<500 });
    await evaluate("var search=document.querySelector('.permission-form input[type=search]');search.value='PRODUTO_CONSULTAR';search.dispatchEvent(new Event('input'));window.scrollTo({top:0,behavior:'instant'});true");await pause(150);
    if (!(await evaluate('document.documentElement.scrollWidth<=innerWidth+2'))) throw new Error(`Permissoes transbordam em ${label}.`);
    const metrics=await cdp('Page.getLayoutMetrics');const shot=await cdp('Page.captureScreenshot',{format:'png',captureBeyondViewport:true,clip:{x:0,y:0,width,height:Math.max(height,Math.ceil(metrics.cssContentSize.height)),scale:1}});
    await fs.writeFile(path.join(artifacts, `permissoes-${label}.png`),Buffer.from(shot.data,'base64'));
  }
  await evaluate("document.querySelector('.permission-form input[type=search]').focus();true");await press('Escape','Escape',27);
  if (!(await evaluate("document.querySelector('[data-editor]').hidden && document.activeElement?.textContent==='Funções e permissões'"))) throw new Error('Foco de permissoes perdido ao fechar.');
  pass('permissoes: desktop/tablet/360px e teclado');
  // Validar o formulario real de limites como proprietario, concedendo e retirando antes do login de gerente.
  async function openLimits() {
    await evaluate("[...document.querySelectorAll('.employee-table tbody tr')].find(r=>r.textContent.includes('ZZZ Gerente de teste')).querySelector('button[data-focus$=\":Delegar gestão de permissões\"]').click();true");
    await wait("!!document.querySelector('.permission-form fieldset')", 'formulario de delegacao');
  }
  await openLimits();
  await evaluate("[...document.querySelectorAll('.permission-form label')].find(l=>l.textContent.startsWith('Funcionario permissoes')).querySelector('input').checked=true;document.querySelector('[data-code=PRODUTO_CONSULTAR] input').checked=true;document.querySelector('.permission-form').requestSubmit();true");
  await wait("document.querySelector('[data-message]')?.textContent==='Limites de gestão de permissões atualizados.'", 'limites salvos pela interface');
  await openLimits();
  if (!(await evaluate("document.querySelector('[data-code=PRODUTO_CONSULTAR] input').checked"))) throw new Error('Limites nao persistiram.');
  await evaluate("document.querySelectorAll('.permission-form input[type=checkbox]').forEach(i=>i.checked=false);document.querySelector('.permission-form').requestSubmit();true");
  await wait("document.querySelector('[data-message]')?.textContent==='Limites de gestão de permissões atualizados.' && !document.querySelector('.permission-form')", 'limites retirados pela interface');
  pass('permissoes: formulario real de limites concede e revoga autoridade');
  // Trocar para o gerente no contexto atual, sem gravar credenciais.
  await evaluate(`(async()=>{const csrf=await(await fetch('/api/v1/auth/csrf')).json();await fetch('/api/v1/auth/logout',{method:'POST',headers:{[csrf.headerName]:csrf.token}});const fresh=await(await fetch('/api/v1/auth/csrf')).json();const body=new URLSearchParams({email:${JSON.stringify(credential.managerEmail)},password:${JSON.stringify(credential.managerPassword)}});return(await fetch('/api/v1/auth/login',{method:'POST',headers:{[fresh.headerName]:fresh.token},body})).status===204})()`);
  await cdp('Page.reload');await wait("!document.getElementById('app-view').hidden",'sessao do gerente');await evaluate("document.querySelector('[data-page=funcionarios]').click();true");
  await wait("!!document.querySelector('.employee-table')",'equipe do gerente');
  if (!(await evaluate("![...document.querySelectorAll('.employee-table button')].some(b=>b.textContent==='Funções e permissões')"))) throw new Error('Gerente sem limites recebeu controles.');
  const denied = await evaluate(`(async()=>{const csrf=await(await fetch('/api/v1/auth/csrf')).json();return(await fetch(${JSON.stringify(permBase+'/funcionarios/'+targetLink+'/adicionais')},{method:'PUT',headers:{[csrf.headerName]:csrf.token,'Content-Type':'application/json'},body:JSON.stringify({funcoes:['COZINHA'],versao:4})})).status})()`);
  if(denied!==403)throw new Error('API nao bloqueou gerente sem limites.');
  // Segunda sessao autenticada de proprietario, isolada em outro contexto de navegador.
  async function browserCommand(method, params = {}) {
    const version=await(await fetch(`http://127.0.0.1:${port}/json/version`)).json();const ws=new WebSocket(version.webSocketDebuggerUrl);
    await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=()=>reject(new Error('CDP browser indisponivel'));});
    try {return await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(new Error(`CDP browser sem resposta: ${method}`)),15000);
      ws.onmessage=e=>{const d=JSON.parse(e.data);if(d.id===1){clearTimeout(timer);d.error?reject(new Error(`CDP browser rejeitou ${method}`)):resolve(d.result)}};
      ws.send(JSON.stringify({id:1,method,params}));
    });}finally{ws.close();}
  }
  const context=await browserCommand('Target.createBrowserContext',{disposeOnDetach:false});const target=await browserCommand('Target.createTarget',{url:fixture.url,browserContextId:context.browserContextId});
  const all=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json();const ownerSocket=new WebSocket(all.find(p=>p.id===target.targetId).webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{ownerSocket.onopen=resolve;ownerSocket.onerror=reject;});let ownerSeq=0;const ownerPending=new Map();
  ownerSocket.onmessage=e=>{const d=JSON.parse(e.data);const r=ownerPending.get(d.id);if(r){ownerPending.delete(d.id);d.error?r.reject(new Error('CDP proprietario rejeitado')):r.resolve(d.result)}};
  async function ownerEval(expression){const id=++ownerSeq;const result=await new Promise((resolve,reject)=>{ownerPending.set(id,{resolve,reject});ownerSocket.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression,awaitPromise:true,returnByValue:true}}));});if(result.exceptionDetails)throw new Error('Sessao isolada do proprietario falhou');return result.result.value;}
  for(let i=0;i<100;i++){if(await ownerEval("document.readyState==='complete'"))break;await pause(100);}
  const ownerLogin=await ownerEval(`(async()=>{const c=await(await fetch('/api/v1/auth/csrf')).json();return(await fetch('/api/v1/auth/login',{method:'POST',headers:{[c.headerName]:c.token},body:new URLSearchParams({email:${JSON.stringify(credential.email)},password:${JSON.stringify(credential.password)}})})).status})()`);
  if(ownerLogin!==204)throw new Error('Login isolado do proprietario rejeitado');
  async function ownerApi(suffix, method='GET', body){return ownerEval(`(async()=>{const c=await(await fetch('/api/v1/auth/csrf')).json();const r=await fetch(${JSON.stringify(permBase)}+${JSON.stringify(suffix)},{method:${JSON.stringify(method)},headers:{[c.headerName]:c.token,'Content-Type':'application/json'},${body===undefined?'':`body:JSON.stringify(${JSON.stringify(body)}),`} });return {status:r.status,body:await r.json()}})()`);}
  const managerPanel=await ownerApi(`/funcionarios/${fixture.managerVinculo}`);const granted=await ownerApi(`/funcionarios/${fixture.managerVinculo}/limites`,'PUT',{adicionais:true,alvos:[targetLink],permissoes:[{codigo:'PRODUTO_CONSULTAR',conceder:true,revogar:true}],versao:managerPanel.body.versao});if(granted.status!==200)throw new Error('Delegacao explicita rejeitada');
  await evaluate("[...document.querySelectorAll('#employee-view button')].find(b=>b.textContent==='Atualizar').click();true");
  await wait("[...document.querySelectorAll('.employee-table button')].some(b=>b.textContent==='Funções e permissões')",'limites recebidos em sessao aberta');await permissionPanel();
  // Conflito real: proprietario muda a versao enquanto gerente mantem editor aberto.
  const employee=await ownerApi(`/funcionarios/${targetLink}`);await ownerApi(`/funcionarios/${targetLink}/individuais/PRODUTO_CONSULTAR`,'PUT',{estado:'NEGAR',versao:employee.body.versao});
  await evaluate("var card=document.querySelector('[data-code=PRODUTO_CONSULTAR]');card.querySelector('select').value='PERMITIR';card.querySelector('button').click();true");
  await wait("document.querySelector('[data-message]')?.classList.contains('error')",'conflito de versao visivel');pass('permissoes: conflito real entre sessoes tratado');
  const limitsNow=await ownerApi(`/funcionarios/${fixture.managerVinculo}`);await ownerApi(`/funcionarios/${fixture.managerVinculo}/limites`,'PUT',{adicionais:false,alvos:[],permissoes:[],versao:limitsNow.body.versao});
  await evaluate("var card=document.querySelector('[data-code=PRODUTO_CONSULTAR]');card.querySelector('button').click();true");
  await wait("document.querySelector('[data-message]')?.classList.contains('error') && !document.querySelector('.permission-card')",'revogacao com editor aberto');
  if (!(await evaluate("![...document.querySelectorAll('.employee-table button')].some(b=>b.textContent==='Funções e permissões')")))throw new Error('Controles revogados permaneceram');
  ownerSocket.close();await browserCommand('Target.disposeBrowserContext',{browserContextId:context.browserContextId});
  pass('permissoes: backend bloqueia gerente, concessao e revogacao em sessao aberta');
  // Voltar ao proprietario para repetir o logout/senha incorreta originais.
  await evaluate(`(async()=>{const c=await(await fetch('/api/v1/auth/csrf')).json();await fetch('/api/v1/auth/logout',{method:'POST',headers:{[c.headerName]:c.token}});const fresh=await(await fetch('/api/v1/auth/csrf')).json();return(await fetch('/api/v1/auth/login',{method:'POST',headers:{[fresh.headerName]:fresh.token},body:new URLSearchParams({email:${JSON.stringify(credential.email)},password:${JSON.stringify(credential.password)}})})).status})()`);
  await cdp('Page.reload');await wait("!document.getElementById('app-view').hidden",'retorno ao proprietario');
  await evaluate("document.getElementById('logout').click();true");
  await wait("document.getElementById('app-view').hidden && !document.getElementById('login-view').hidden", 'logout');
  await cdp('Page.reload');
  await wait("document.getElementById('login-submit') && !document.getElementById('login-submit').disabled", 'página após logout');
  if (!(await evaluate("(async()=>document.getElementById('app-view').hidden && (await fetch('/api/v1/auth/me')).status===401)()"))) throw new Error('Acesso indevido após logout.');
  pass('logout e proteção real após atualização');
  await evaluate(`document.getElementById('email').value=${JSON.stringify(credential.email)};document.getElementById('password').value='credencial-incorreta-de-teste';document.getElementById('login-form').requestSubmit();true`);
  await wait("!document.getElementById('login-error').hidden && document.getElementById('app-view').hidden", 'senha incorreta');
  pass('senha incorreta rejeitada na interface');
  console.log('SMOKE_BROWSER_SUCCESS: banco e navegador exclusivos de teste; nenhum dado operacional acessado.');
} finally {
  clearTimeout(readyTimer);
  try { if (socket?.readyState === WebSocket.OPEN) await cdp('Browser.close'); } catch {}
  socket?.close();
  if (chrome && chrome.exitCode === null) { await pause(500); if (chrome.exitCode === null) chrome.kill(); }
  if (java.exitCode === null) {
    java.stdin.write('\n'); java.stdin.end();
    await Promise.race([new Promise((resolve) => java.once('exit', resolve)), pause(20000)]);
    if (java.exitCode === null) java.kill();
  }
  lines.close();
  // Excluir apenas o perfil aleatorio criado por este processo dentro de .tools.
  if (path.resolve(profile).startsWith(`${tools}${path.sep}chrome-test-`)) await fs.rm(profile, { recursive: true, force: true }).catch(() => {});
}
