const el=(tag,text='',cls='')=>{const n=document.createElement(tag);n.textContent=text;n.className=cls;return n;};
export function createSecurityEditor({call,editor,finishForm,run,fail,generation,refresh,capabilities,getUnit}) {
  async function open(row){
    const current=generation();
    try{
      const data=await call(`/funcionarios/${row.vinculoId}`);if(current!==generation())return;
      const c=capabilities();const form=editor(`Segurança operacional: ${data.nome}`);
      form.append(el('p',`Estabelecimento: ${getUnit()}`,'muted small'));
      form.append(el('p',`Identificação do operador: ${data.vinculoId}`,'muted small'));
      form.append(el('p',data.configurado?`PIN definido. Acesso ${data.bloqueado?'bloqueado':'liberado'}. Tentativas restantes: ${data.tentativasRestantes}.`:'PIN ainda não definido. Nenhum acesso operacional foi liberado.','permission-result'));
      if(c.gerenciarPin){
        const label=el('label',data.configurado?'Novo PIN de seis dígitos':'PIN inicial de seis dígitos');const input=document.createElement('input');input.id='security-pin';input.type='password';input.inputMode='numeric';input.autocomplete='off';input.maxLength=6;input.minLength=6;input.pattern='[0-9]{6}';input.required=true;label.htmlFor=input.id;
        const confirmLabel=el('label','Confirmar o novo PIN');const confirmPin=input.cloneNode();confirmPin.id='security-pin-confirm';confirmLabel.htmlFor=confirmPin.id;form.append(label,input,confirmLabel,confirmPin);
        form.append(el('p','O PIN anterior não pode ser recuperado. A redefinição invalida os desbloqueios anteriores somente desta unidade.','muted small'));
        finishForm(form,()=>{
          confirmPin.setCustomValidity(input.value===confirmPin.value?'':'Os PINs devem ser iguais.');if(!form.reportValidity())return;
          if(!confirm('Confirmar a definição/redefinição do PIN deste funcionário?'))return;
          const body={pin:input.value,versao:data.versao};input.value='';confirmPin.value='';
          run(async()=>{try{return await call(`/funcionarios/${row.vinculoId}/pin`,'PUT',body);}finally{body.pin='';}},'PIN atualizado. Nenhum valor será exibido novamente.');
        });confirmPin.addEventListener('input',()=>confirmPin.setCustomValidity(''));
      }else{finishForm(form,()=>{});form.querySelector('button[type=submit]').remove();}
      if(c.desbloquear&&data.configurado){
        const action=el('button',data.bloqueado?'Desbloquear operador':'Bloquear operador','secondary');action.type='button';
        action.addEventListener('click',()=>{if(confirm(`Confirmar ${data.bloqueado?'desbloqueio':'bloqueio'} deste operador? Será exigido PIN novamente.`))run(()=>call(`/funcionarios/${row.vinculoId}/bloqueio`,'PUT',{bloqueado:!data.bloqueado,versao:data.versao}),'Estado de bloqueio operacional atualizado.');});form.append(action);
      }
    }catch(e){if(current===generation()){if(e.status===403)await refresh().catch(()=>{});fail(e);}}
  }
  async function policy(){
    const current=generation();try{
      const data=await call('/inatividade');if(current!==generation())return;const form=editor('Bloqueio por inatividade');
      const label=el('label','Prazo sem atividade, em segundos (30 a 3600)');const input=document.createElement('input');input.id='security-idle';input.type='number';input.min=30;input.max=3600;input.required=true;input.value=data.segundos;label.htmlFor=input.id;form.append(label,input,el('p','O bloqueio oculta informações e exige PIN. Não fecha caixa nem executa operações de outros módulos. Aumentar o prazo não reativa sessões já vencidas.','muted small'));
      finishForm(form,()=>{if(confirm('Confirmar o novo prazo de inatividade desta unidade?'))run(()=>call('/inatividade','PUT',{segundos:Number(input.value),versao:data.versao}),'Prazo de inatividade atualizado.');});
    }catch(e){if(current===generation())fail(e);}
  }
  async function events(){
    const current=generation();try{
      const rows=await call('/eventos');if(current!==generation())return;const form=editor('Eventos de segurança operacional');
      form.append(el('p','Até 100 eventos recentes dentro do seu acesso. Bloqueios por tentativas ficam registrados para consulta do proprietário; envio de notificações ainda não está implementado.','muted small'));
      if(!rows.length)form.append(el('p','Nenhum evento permitido nesta unidade.'));
      for(const row of rows){const card=el('article','','permission-card');card.append(el('strong',row.tipo.replace('OPERACIONAL_','').replaceAll('_',' ')),el('p',new Date(row.ocorridoEm).toLocaleString('pt-BR')));const details=el('details');details.append(el('summary','Identificadores do evento'),el('small',`Funcionário: ${row.funcionario||'Configuração da unidade'} · Executor: ${row.executor||'Não identificado'}`,'muted'));card.append(details);form.append(card);}
      finishForm(form,()=>{});form.querySelector('button[type=submit]').remove();
    }catch(e){if(current===generation())fail(e);}
  }
  return {open,policy,events};
}