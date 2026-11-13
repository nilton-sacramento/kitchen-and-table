// Extensao do smoke principal: API real, Chrome e PostgreSQL 16 descartavel.
export async function smokeOperational({fixture,targetLink,credential,cdp,evaluate,wait,press,pause,pass,ownerEval,artifacts,fs,path}) {
  const base=`/api/v1/estabelecimentos/${fixture.estabelecimentoId}/seguranca-operacional`;
  async function security(suffix,method='GET',body){
    return ownerEval(`(async()=>{const c=await(await fetch('/api/v1/auth/csrf')).json();const r=await fetch(${JSON.stringify(base+suffix)},{method:${JSON.stringify(method)},headers:{[c.headerName]:c.token,'Content-Type':'application/json'},${body===undefined?'':`body:JSON.stringify(${JSON.stringify(body)}),`}});return {status:r.status,body:await r.json()}})()`);
  }
  await evaluate("document.querySelector('[data-page=funcionarios]').click();true");
  await wait("!!document.querySelector('.employee-table')",'equipe para PIN');
  async function openSecurity(name){
    await evaluate(`var row=[...document.querySelectorAll('.employee-table tbody tr')].find(r=>r.textContent.includes(${JSON.stringify(name)}));var trigger=row.querySelector('button[data-focus$=":Segurança operacional"]');trigger.focus();trigger.click();true`);
    await wait("!!document.getElementById('security-pin')",'editor de PIN');
  }
  for(const [name,pin] of [['Funcionario permissoes',credential.pin],['ZZZ Gerente de teste',credential.secondPin]]){
    await openSecurity(name);
    await evaluate(`document.getElementById('security-pin').value=${JSON.stringify(pin)};document.getElementById('security-pin-confirm').value=${JSON.stringify(pin)};document.querySelector('.employee-form').requestSubmit();true`);
    await wait("document.querySelector('[data-message]')?.textContent.startsWith('PIN atualizado.') && !document.getElementById('security-pin')",'definicao real de PIN');
  }
  await evaluate("[...document.querySelectorAll('#employee-view button')].find(b=>b.textContent==='Inatividade operacional').click();true");
  await wait("!!document.getElementById('security-idle')",'configuracao de inatividade');
  if(await evaluate("document.getElementById('security-idle').value")!=='300')throw new Error('Prazo padrao inesperado');
  await evaluate("document.getElementById('security-idle').value='30';document.querySelector('.employee-form').requestSubmit();true");
  await wait("document.querySelector('[data-message]')?.textContent==='Prazo de inatividade atualizado.'",'prazo no banco descartavel');
  await openSecurity('Funcionario permissoes');
  for(const [label,width,height] of [['desktop',1366,900],['tablet',768,1024],['mobile',360,800]]){
    await cdp('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:width<500});await pause(100);
    if(!await evaluate('document.documentElement.scrollWidth<=innerWidth+2'))throw new Error(`Editor PIN transborda em ${label}`);
    const shot=await cdp('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});await fs.writeFile(path.join(artifacts,`seguranca-${label}.png`),Buffer.from(shot.data,'base64'));
  }
  await evaluate("document.getElementById('security-pin').focus();true");await press('Escape','Escape',27);
  if(!await evaluate("document.querySelector('[data-editor]').hidden && document.activeElement.textContent==='Segurança operacional'"))throw new Error('Foco do editor PIN perdido');
  await evaluate("[...document.querySelectorAll('#employee-view button')].find(b=>b.textContent==='Eventos de segurança').click();true");
  await wait("document.querySelector('.employee-form')?.textContent.includes('PIN DEFINIDO')",'eventos reais de seguranca');
  pass('PIN: definicao e politica reais, eventos, teclado e editor desktop/tablet/360px');
  await evaluate("document.getElementById('logout').click();true");await wait("!document.getElementById('login-view').hidden",'sair da administracao antes do PIN');
  await cdp('Page.navigate',{url:fixture.url+'/operacional.html'});
  await wait("document.getElementById('op-enter') && !document.getElementById('op-enter').disabled",'entrada operacional');
  async function login(link,pin){
    await evaluate(`document.getElementById('op-unit').value=${JSON.stringify(fixture.estabelecimentoId)};document.getElementById('op-person').value=${JSON.stringify(link)};document.getElementById('op-pin').value=${JSON.stringify(pin)};document.getElementById('op-form').requestSubmit();true`);
  }
  async function unlocked(name){await wait(`!document.getElementById('op-protected').hidden && document.getElementById('op-protected').textContent.includes(${JSON.stringify(name)})`,'operador validado');if(!await evaluate("document.getElementById('op-pin').value===''") )throw new Error('PIN permaneceu no campo');}
  async function locked(){await wait("document.getElementById('op-protected').hidden && document.getElementById('op-protected').textContent==='' && !document.getElementById('op-locked').hidden",'dados operacionais ocultos');}
  for(const [label,width,height] of [['desktop',1366,900],['tablet',768,1024],['mobile',360,800]]){
    await cdp('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:width<500});await pause(100);
    if(!await evaluate("document.documentElement.scrollWidth<=innerWidth+2 && document.getElementById('op-pin').type==='password' && document.getElementById('op-pin').inputMode==='numeric'"))throw new Error(`Entrada PIN inadequada em ${label}`);
    const shot=await cdp('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});await fs.writeFile(path.join(artifacts,`operacional-bloqueado-${label}.png`),Buffer.from(shot.data,'base64'));
  }
  await login(targetLink,credential.pin);await unlocked('Funcionario permissoes');
  for(const [label,width,height] of [['desktop',1366,900],['tablet',768,1024],['mobile',360,800]]){
    await cdp('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:width<500});await pause(100);
    if(!await evaluate('document.documentElement.scrollWidth<=innerWidth+2'))throw new Error(`Operacional transborda em ${label}`);
    const shot=await cdp('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});await fs.writeFile(path.join(artifacts,`operacional-${label}.png`),Buffer.from(shot.data,'base64'));
  }
  await cdp('Page.reload');await unlocked('Funcionario permissoes');
  if(!await evaluate("(async()=>{const m=await import('/assets/operacional.js');const v=m.createOperationalDrafts();v.bind('A:pessoa');v.set('rascunho',{texto:'teste descartavel'});v.lock();let negado=false;try{v.get('rascunho')}catch{negado=true}v.bind('A:pessoa');const preservado=v.get('rascunho').texto==='teste descartavel';v.bind('B:outra');return negado&&preservado&&v.get('rascunho')===undefined&&localStorage.length===0&&sessionStorage.length===0})()"))throw new Error('Isolamento dos rascunhos ou armazenamento incorreto');
  pass('PIN: login real, F5, tres resolucoes e rascunho em memoria isolado');
  // Nao gerar eventos de atividade: GET de verificacao nao deve renovar o prazo.
  await pause(31500);await locked();
  if(await evaluate("(async()=>(await fetch('/api/v1/operacional/contexto')).status)()")!==423)throw new Error('Backend aceitou sessao inativa');
  await cdp('Page.reload');await wait("document.getElementById('op-enter') && !document.getElementById('op-enter').disabled",'F5 bloqueado');await locked();
  pass('PIN: inatividade real em 30s bloqueia UI e backend; F5 nao reabre');
  await login(targetLink,credential.pin);await unlocked('Funcionario permissoes');
  let state=await security(`/funcionarios/${targetLink}`);let result=await security(`/funcionarios/${targetLink}/bloqueio`,'PUT',{bloqueado:true,versao:state.body.versao});if(result.status!==200)throw new Error('Bloqueio remoto rejeitado');
  await locked();state=await security(`/funcionarios/${targetLink}`);result=await security(`/funcionarios/${targetLink}/bloqueio`,'PUT',{bloqueado:false,versao:state.body.versao});if(result.status!==200)throw new Error('Desbloqueio remoto rejeitado');
  if(await evaluate("(async()=>(await fetch('/api/v1/operacional/contexto')).status)()")!==423)throw new Error('Desbloqueio administrativo reviveu sessao');
  const wrong=credential.pin==='999999'?'888888':'999999';
  for(let i=0;i<3;i++){await login(targetLink,wrong);await wait("!document.getElementById('op-error').hidden && !document.getElementById('op-enter').disabled",'PIN incorreto');}
  state=await security(`/funcionarios/${targetLink}`);if(!state.body.bloqueado||state.body.tentativasRestantes!==0)throw new Error('Tres falhas nao bloquearam persistentemente');
  await login(targetLink,credential.pin);await wait("!document.getElementById('op-error').hidden && !document.getElementById('op-enter').disabled",'PIN correto enquanto bloqueado');await locked();
  await security(`/funcionarios/${targetLink}/bloqueio`,'PUT',{bloqueado:false,versao:state.body.versao});
  await login(targetLink,credential.pin);await unlocked('Funcionario permissoes');
  pass('PIN: bloqueio remoto, tres erros persistentes e desbloqueio autorizado com nova identificacao');
  await evaluate("[...document.querySelectorAll('#op-protected button')].find(b=>b.textContent==='Trocar operador').click();true");await locked();
  await wait("!document.getElementById('op-enter').disabled",'troca de operador pronta');await login(fixture.managerVinculo,credential.secondPin);await unlocked('ZZZ Gerente de teste');
  if(await evaluate("(async()=>(await fetch('/api/v1/estabelecimentos')).status)()")!==403)throw new Error('PIN de gerente elevou autenticacao administrativa');
  const events=await security('/eventos');if(!events.body.some(e=>e.tipo==='OPERACIONAL_TROCA_OPERADOR')||!events.body.some(e=>e.tipo==='OPERACIONAL_TENTATIVAS_BLOQUEADO'))throw new Error('Auditoria operacional incompleta');
  await evaluate("document.getElementById('op-exit').click();true");await wait("document.getElementById('op-enter') && !document.getElementById('op-enter').disabled",'logout operacional');await locked();
  await cdp('Page.reload');await wait("document.getElementById('op-enter') && !document.getElementById('op-enter').disabled",'F5 apos logout operacional');
  if(await evaluate("(async()=>(await fetch('/api/v1/operacional/contexto')).status)()")!==401)throw new Error('Logout operacional nao encerrou autenticacao');
  pass('PIN: troca auditada, gerente sem elevacao e logout real');
  // Retornar a sessao administrativa para os testes originais de logout/senha.
  await evaluate(`(async()=>{const c=await(await fetch('/api/v1/auth/csrf')).json();return(await fetch('/api/v1/auth/login',{method:'POST',headers:{[c.headerName]:c.token},body:new URLSearchParams({email:${JSON.stringify(credential.email)},password:${JSON.stringify(credential.password)}})})).status})()`);
  await cdp('Page.navigate',{url:fixture.url});await wait("!document.getElementById('app-view').hidden",'administracao preservada apos PIN');
}