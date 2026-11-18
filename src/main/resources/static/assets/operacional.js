// Rascunhos apenas em memoria, isolados por operador/unidade; nenhuma credencial, identidade ou rascunho em Web Storage.
export function createOperationalDrafts(){
  let owner=null,unlocked=false;const values=new Map();
  return {
    bind(key){if(owner!==key)values.clear();owner=key;unlocked=true;},
    lock(){unlocked=false;},
    set(key,value){if(!unlocked)throw new Error('Acesso bloqueado');values.set(key,structuredClone(value));},
    get(key){if(!unlocked)throw new Error('Acesso bloqueado');return structuredClone(values.get(key));},
    clear(){owner=null;unlocked=false;values.clear();},
  };
}
// Somente uma marca booleana por aba; nunca identidade, PIN ou rascunhos.
export function createOperationalLockState(storage){
  const key='kt.operational.locked';let required=true;
  try{const previous=storage.getItem(key);storage.setItem(key,'1');if(previous!=='1')storage.removeItem(key);required=previous==='1';}catch{}
  return {get required(){return required;},require(){required=true;try{storage.setItem(key,'1');}catch{}},confirmPin(){required=false;try{storage.removeItem(key);}catch{}}};
}
const el=(tag,text='',cls='')=>{const n=document.createElement(tag);n.textContent=text;n.className=cls;return n;};
if(document.getElementById('operational-root')){
  const $=id=>document.getElementById(id);const drafts=createOperationalDrafts();
  let storage=null;try{storage=window.sessionStorage;}catch{}const lockState=createOperationalLockState(storage);
  let unlocked=false,generation=0,deadline=0,timer=null,activityTimer=null,lastTouch=0,context=null,submitting=false,adminSession=false,loggingOut=false,pendingLogin=null,leaving=false,visibilityTimer=null;
  async function api(path,method='GET',body){
    const started=performance.now();const headers={};if(method!=='GET'){const c=await(await fetch('/api/v1/auth/csrf',{credentials:'same-origin',cache:'no-store'})).json();headers[c.headerName]=c.token;if(body)headers['Content-Type']='application/json';}
    const response=await fetch(`/api/v1/${path}`,{method,credentials:'same-origin',cache:'no-store',headers,...(body?{body:JSON.stringify(body)}:{})});
    if(!response.ok){const e=new Error(response.status===401?'Não foi possível desbloquear. Confira os dados ou procure o responsável.':'Não foi possível confirmar a operação. Tente novamente ou procure o responsável.');e.status=response.status;throw e;}
    if(response.status===204)return null;const result=await response.json();
    const c=result?.contexto||(typeof result?.validadeMs==='number'?result:null);
    if(c)c.validadeMs=Math.max(0,c.validadeMs-(performance.now()-started));
    return result;
  }
  function error(text){$('op-error').textContent=text;$('op-error').hidden=!text;}
  function mask(message='Acesso bloqueado. Informe sua identificação e PIN novamente.'){
    lockState.require();generation++;unlocked=false;context=null;clearTimeout(timer);clearTimeout(activityTimer);drafts.lock();$('op-protected').replaceChildren();$('op-protected').hidden=true;$('op-locked').hidden=false;$('op-state').textContent=message;$('op-pin').value='';
  }
  function schedule(c){deadline=performance.now()+c.validadeMs;clearTimeout(timer);timer=setTimeout(()=>lock(),Math.max(0,c.validadeMs));}
  function show(c){
    if(c.validadeMs<=0){void lock();return;}
    const unchanged=unlocked&&context&&context.vinculoId===c.vinculoId&&context.estabelecimentoId===c.estabelecimentoId&&context.nome===c.nome&&context.estabelecimento===c.estabelecimento&&JSON.stringify(context.permissoes)===JSON.stringify(c.permissoes);
    if(unchanged){context=c;schedule(c);return;}
    const key=`${c.estabelecimentoId}:${c.vinculoId}`;drafts.bind(key);context=c;unlocked=true;schedule(c);$('op-error').hidden=true;$('op-locked').hidden=true;$('op-protected').hidden=false;$('op-protected').replaceChildren();
    $('op-protected').append(el('h2','Acesso desbloqueado'),el('strong',c.nome),el('p',c.estabelecimento,'muted'),el('p','Sua identidade está validada no servidor. Pedidos, caixa, estoque e demais operações ainda não foram disponibilizados.','muted small'));
    const allowed=c.permissoes.filter(p=>p.permissao.disponivel&&p.efetiva);$('op-protected').append(el('p',allowed.length?`Capacidades operacionais disponíveis: ${allowed.map(p=>p.permissao.nome).join(', ')}`:'Nenhuma operação dos módulos futuros está habilitada.'));
    for(const [label,action] of [['Bloquear agora',()=>lock()],['Trocar operador',()=>lock(true)]]){const b=el('button',label,'secondary');b.type='button';b.addEventListener('click',action);$('op-protected').append(b);}
    $('op-exit').textContent='Sair do acesso operacional';
  }
  async function lock(switchOperator=false){
    submitting=true; $('op-enter').disabled=true;
    if(switchOperator){drafts.clear();$('op-person').value='';}mask(switchOperator?'Identifique o próximo operador.':'Acesso bloqueado por segurança. Informe novamente o PIN.');
    try{await api('operacional/bloquear','POST');}catch{error('A tela está protegida. Não foi possível confirmar o bloqueio no servidor; a sessão será revalidada antes de qualquer acesso.');}finally{submitting=false; $('op-enter').disabled=adminSession||loggingOut;if(switchOperator)$('op-person').focus();}
  }
  $('op-form').addEventListener('submit',async event=>{
    event.preventDefault();if(submitting||loggingOut)return;submitting=true;const current=++generation;const body={estabelecimentoId:$('op-unit').value.trim(),identificacao:$('op-person').value.trim(),pin:$('op-pin').value};$('op-pin').value='';$('op-enter').disabled=true;error('');
    const task=(async()=>{
      try{await api('operacional/entrar','POST',body);body.pin='';if(loggingOut||current!==generation)return;const c=await api('operacional/contexto');if(current===generation){lockState.confirmPin();show(c);}}
      catch(e){if(current===generation){mask();error(e.status?e.message:'Não foi possível conectar ao servidor. Confira a conexão e tente novamente.');}}
      finally{body.pin='';$('op-pin').value='';submitting=false;$('op-enter').disabled=adminSession||loggingOut;}
    })();
    pendingLogin=task;try{await task;}finally{if(pendingLogin===task)pendingLogin=null;}
  });
  async function refresh(initial=false){
    const current=generation;try{
      const result=await api('operacional/sessao');if(current!==generation)return;
      adminSession=result.administrativo;
      if(result.administrativo){mask('Há uma sessão administrativa neste navegador. Encerre-a para entrar com PIN.');$('op-enter').disabled=true;return;}
      if(lockState.required){if(result.desbloqueado)await lock();else mask();return;}
      if(result.desbloqueado&&unlocked&&context&&(context.vinculoId!==result.contexto.vinculoId||context.estabelecimentoId!==result.contexto.estabelecimentoId)){mask('O operador mudou em outra aba. Informe sua identificação e PIN novamente.');return;}
      if(result.desbloqueado&&(initial||unlocked)){show(result.contexto);}else if(!result.desbloqueado&&unlocked)mask();
      if(initial&&!result.desbloqueado)$('op-state').textContent='Informe as identificações e seu PIN.';
    }catch{if(current===generation)mask('Não foi possível validar a sessão. Informe o PIN quando a conexão estiver disponível.');}
    finally{if(initial)$('op-enter').disabled=adminSession||submitting||loggingOut;}
  }
  function activity(event){
    if(!event.isTrusted||!unlocked||submitting||activityTimer)return;
    activityTimer=setTimeout(async()=>{activityTimer=null;if(!unlocked)return;const current=generation;
      try{const c=await api('operacional/atividade','POST');if(current===generation&&unlocked){lastTouch=performance.now();schedule(c);}}catch{if(current===generation)mask();}
    },Math.max(0,1000-(performance.now()-lastTouch)));
  }
  for(const name of ['pointerdown','keydown','touchstart'])document.addEventListener(name,activity,{passive:true});
  $('op-exit').addEventListener('click',async()=>{
    if(loggingOut)return;loggingOut=true;$('op-enter').disabled=true;$('op-exit').disabled=true;drafts.clear();mask('Encerrando sessão…');
    try{if(pendingLogin)await pendingLogin;await api('auth/logout','POST');window.location.assign('/operacional.html');}
    catch{error('Não foi possível confirmar a saída. A tela permanece bloqueada; confira a conexão e tente encerrar novamente.');}
    finally{loggingOut=false;$('op-exit').disabled=false;$('op-enter').disabled=adminSession||submitting;}
  });
  document.addEventListener('visibilitychange',()=>{
    clearTimeout(visibilityTimer);
    if(document.visibilityState==='hidden'&&unlocked)visibilityTimer=setTimeout(()=>{if(!leaving&&document.visibilityState==='hidden'&&unlocked)void lock();},0);
    else if(document.visibilityState==='visible'&&unlocked)void refresh();
  });
  window.addEventListener('pagehide',()=>{leaving=true;clearTimeout(visibilityTimer);drafts.lock();$('op-protected').replaceChildren();$('op-protected').hidden=true;});
  window.addEventListener('pageshow',e=>{leaving=false;if(e.persisted){mask();refresh(true);}});
  setInterval(()=>{if(unlocked&&document.visibilityState==='visible'){if(performance.now()>=deadline)lock();else refresh();}},5000);
  refresh(true);
}