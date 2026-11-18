// Interface real de permissoes; autorizacao definitiva permanece no servidor.
const operational = ['ATENDENTE', 'CAIXA', 'COZINHA', 'BAR'];
const roleNames = { ATENDENTE:'Atendente', CAIXA:'Caixa', COZINHA:'Cozinha', BAR:'Bar', GERENTE:'Gerente' };
const node = (tag, text = '', cls = '') => { const n = document.createElement(tag); n.textContent = text; n.className = cls; return n; };
function check(parent, label, checked = false) {
  const l = node('label', '', 'employee-check'); const i = document.createElement('input'); i.type = 'checkbox'; i.checked = checked;
  l.append(i, document.createTextNode(label)); parent.append(l); return i;
}
export function createPermissionsEditor({ call, editor, finishForm, run, fail, generation, rows, refresh }) {
  async function open(row) {
    const current = generation();
    try {
      const panel = await call(`/funcionarios/${row.vinculoId}`); if (current !== generation()) return;
      const form = editor(`Funções e permissões: ${panel.nome}`); form.classList.add('permission-form');
      form.append(node('p', `Função principal: ${roleNames[panel.principal] || panel.principal}.`, 'muted small'));
      const identifiers = node('details', '', 'employee-identifiers'); identifiers.append(node('summary', 'Identificador do vínculo'), node('code', panel.vinculoId)); form.append(identifiers);
      const additional = node('fieldset'); additional.append(node('legend', 'Funções adicionais operacionais'));
      const fields = operational.filter(c => c !== panel.principal).map(c => { const field = check(additional, roleNames[c], panel.adicionais.includes(c)); field.dataset.role = c; return [c, field]; });
      additional.disabled = !panel.alterarAdicionais;
      const saveRoles = node('button', 'Salvar funções adicionais', 'secondary'); saveRoles.type = 'button'; saveRoles.disabled = !panel.alterarAdicionais;
      saveRoles.dataset.permissionDisabled = String(!panel.alterarAdicionais);
      saveRoles.addEventListener('click', () => { if (confirm('Confirmar alteração de funções adicionais nesta unidade?')) run(() => call(`/funcionarios/${row.vinculoId}/adicionais`, 'PUT', { funcoes: fields.filter(([, i]) => i.checked).map(([c]) => c), versao: panel.versao }), 'Funções adicionais atualizadas. Abra novamente para consultar o resultado.'); });
      additional.append(saveRoles); form.append(additional);
      form.append(node('p', 'Herdar segue as funções; permitir concede explicitamente; negar prevalece sobre heranças. Contratos futuros não habilitam operações. A autoridade administrativa continua sujeita à hierarquia e às delegações.', 'muted small'));
      const search = document.createElement('input'); search.type = 'search'; search.placeholder = 'Filtrar permissões'; search.setAttribute('aria-label', 'Filtrar permissões'); form.append(search);
      const totals = node('p', '', 'permission-overview'); totals.setAttribute('role', 'status');
      const effective = panel.permissoes.filter(i => i.efetiva).length;
      const future = panel.permissoes.filter(i => !i.permissao.disponivel).length;
      totals.textContent = effective + (effective === 1 ? ' permissão efetiva nesta unidade. ' : ' permissões efetivas nesta unidade. ') + future + (future === 1 ? ' contrato futuro não habilita operações.' : ' contratos futuros não habilitam operações.');
      form.append(totals);
      const filter = document.createElement('select'); filter.setAttribute('aria-label', 'Mostrar permissões'); filter.id = 'permission-view-filter'; const filterLabel = node('label', 'Mostrar permissões'); filterLabel.htmlFor = filter.id; form.append(filterLabel);
      for (const [value, text] of [['todas','Todas'],['efetivas','Acesso permitido'],['negadas','Acesso não permitido'],['individuais','Exceções individuais'],['criticas','Críticas'],['futuras','Contratos futuros']]) { const o = node('option', text); o.value = value; filter.append(o); }
      form.append(filter);
      const visibleStatus = node('p', '', 'muted small'); visibleStatus.setAttribute('role', 'status'); form.append(visibleStatus);
      const groups = node('div', '', 'permission-groups'); form.append(groups);
      for (const category of ['ADMINISTRATIVA', 'OPERACIONAL', 'FINANCEIRA']) {
        const categoryBox = node('section', '', 'permission-category'); categoryBox.append(node('h4', { ADMINISTRATIVA:'Administração', OPERACIONAL:'Operação', FINANCEIRA:'Financeiro' }[category]));
        const modules = new Map();
        function sectionFor(p) {
          if (modules.has(p.modulo)) return modules.get(p.modulo);
          const box = node('details', '', 'permission-module'); box.open = panel.permissoes.some(i => i.permissao.modulo === p.modulo && i.permissao.disponivel);
          const names = { FUNCIONARIOS:'Funcionários', ESTABELECIMENTOS:'Estabelecimentos', SEGURANCA:'Segurança operacional', FUNCOES:'Funções', PERMISSOES:'Permissões', PRODUTOS:'Produtos', CAIXA:'Caixa', FINANCEIRO:'Financeiro', PEDIDOS:'Pedidos', ESTOQUE:'Estoque', MESAS:'Mesas', COMANDAS:'Comandas', COZINHA:'Cozinha', BAR:'Bar' };
          box.append(node('summary', names[p.modulo] || p.modulo));
          const body = node('div', '', 'permission-group'); box.append(body); categoryBox.append(box); modules.set(p.modulo, body); return body;
        }
        for (const item of panel.permissoes.filter(i => i.permissao.categoria === category)) {
          const p = item.permissao; const section = sectionFor(p); const card = node('article', '', 'permission-card'); card.dataset.code = p.codigo; card.dataset.filter = `${p.nome} ${p.codigo} ${p.modulo} ${p.descricao} ${item.estado}`.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLocaleLowerCase('pt-BR'); card.dataset.effective = String(item.efetiva); card.dataset.individual = String(item.estado !== 'HERDAR'); card.dataset.critical = String(p.critica); card.dataset.future = String(!p.disponivel);
          card.append(node('strong', p.nome), node('small', `${p.modulo}${p.critica ? ' · Crítica' : ''}${p.disponivel ? '' : ' · Contrato futuro/legado inativo'}`, 'muted'));
          const detail = node('details'); detail.append(node('summary', 'Código técnico'), node('code', p.codigo)); card.append(detail);
          card.append(node('p', p.descricao), node('p', `Origem: ${item.herdadaDe.join(', ') || 'Sem padrão herdado'}`, 'muted small'));
          card.append(node('p', `${ {HERDAR:'Padrão herdado das funções',PERMITIR:'Concessão individual',NEGAR:'Negação individual'}[item.estado] }. Configuração: ${item.configurada ? 'permitida' : 'negada'}. Agora: ${item.efetiva ? 'pode usar' : 'não pode usar'}.`, 'permission-result'));
          if (item.restricao) card.append(node('p', item.restricao, 'muted small'));
          const label = node('label', `Exceção: ${p.nome}`); const select = document.createElement('select'); select.id = `permission-${p.codigo}`; label.htmlFor = select.id;
          const grant = panel.conceder.includes(p.codigo), revoke = panel.revogar.includes(p.codigo);
          for (const [value, text] of [['HERDAR','Herdar'],['PERMITIR','Permitir'],['NEGAR','Negar']]) {
            const option = node('option', text); option.value = value;
            option.disabled = value === 'HERDAR' ? !(grant && revoke) : value === 'PERMITIR' ? !grant : !revoke;
            select.append(option);
          }
          select.value = item.estado; select.disabled = !grant && !revoke;
          const save = node('button', 'Aplicar exceção', 'secondary'); save.type = 'button'; save.setAttribute('aria-label', `Aplicar exceção: ${p.nome}`);
          const updateAction = () => {
            const blocked = select.disabled || !select.selectedOptions.length || select.selectedOptions[0].disabled;
            save.disabled = blocked; save.dataset.permissionDisabled = String(blocked);
          };
          select.addEventListener('change', updateAction); updateAction();
          if (grant !== revoke) card.append(node('p', `Você pode apenas ${grant ? 'permitir' : 'negar'} esta capacidade. Voltar a Herdar exige autorização para conceder e revogar.`, 'muted small'));
          save.addEventListener('click', () => {
            if (confirm(`Confirmar ${ {HERDAR:'seguir os padrões das funções',PERMITIR:'conceder individualmente',NEGAR:'negar individualmente'}[select.value] } para ${p.nome}, somente nesta unidade?${p.critica ? ' Esta permissão é crítica.' : ''}`)) run(() => call(`/funcionarios/${row.vinculoId}/individuais/${encodeURIComponent(p.codigo)}`, 'PUT', { estado: select.value, versao: panel.versao }), 'Permissão atualizada. Abra novamente para consultar o resultado.');
          });
          card.append(label, select, save); section.append(card);
        }
        if (modules.size) groups.append(categoryBox);
      }
      function applyFilter() {
        const term = search.value.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLocaleLowerCase('pt-BR');
        for (const card of groups.querySelectorAll('.permission-card')) {
          const kind = filter.value;
          const match = kind === 'todas' || kind === 'efetivas' && card.dataset.effective === 'true' || kind === 'negadas' && card.dataset.effective === 'false' || kind === 'individuais' && card.dataset.individual === 'true' || kind === 'criticas' && card.dataset.critical === 'true' || kind === 'futuras' && card.dataset.future === 'true';
          card.hidden = !match || !card.dataset.filter.includes(term);
        }
        const visible = [...groups.querySelectorAll('.permission-card')].filter(card => !card.hidden).length; visibleStatus.textContent = visible ? visible + ' permissões correspondem ao filtro.' : 'Nenhuma permissão corresponde ao filtro.';
        for (const box of groups.querySelectorAll('.permission-module')) { box.hidden = ![...box.querySelectorAll('.permission-card')].some(c => !c.hidden); if ((term || filter.value !== 'todas') && !box.hidden) box.open = true; }
        for (const category of groups.querySelectorAll('.permission-category')) category.hidden = ![...category.querySelectorAll('.permission-module')].some(m => !m.hidden);
      }
      search.addEventListener('input', applyFilter); filter.addEventListener('change', applyFilter); applyFilter();
      finishForm(form, () => {}); form.querySelector('button[type=submit]').remove();
    } catch (e) { if (current === generation()) { if (e.status === 403) await refresh().catch(() => {}); fail(e); } }
  }
  async function limits(row) {
    const current = generation();
    try {
      const [data, catalog] = await Promise.all([call(`/funcionarios/${row.vinculoId}/limites`), call('/catalogo')]); if (current !== generation()) return;
      const form = editor(`Delegação de permissões: ${row.nome}`); form.classList.add('permission-form');
      form.append(node('p', 'Somente o proprietário define estes limites. Conceder e revogar são independentes. Herdar exige ambos. Nenhuma autorização permite administrar a si próprio, outros gerentes ou o proprietário.', 'muted small'));
      const additional = check(form, 'Pode alterar funções adicionais dos alvos selecionados', data.adicionais);
      const targets = node('fieldset'); targets.append(node('legend', 'Funcionários comuns que pode administrar'));
      const targetFields = rows().filter(r => operational.includes(r.funcao)).map(r => [r.vinculoId, check(targets, `${r.nome} · ${r.funcao}`, data.alvos.includes(r.vinculoId))]); form.append(targets);
      const codes = node('fieldset'); codes.append(node('legend', 'Limites por permissão (críticas exigem seleção expressa)'));
      const codeFields = catalog.filter(p => !p.exclusivaProprietario).map(p => {
        const previous = data.permissoes.find(l => l.codigo === p.codigo); const card = node('div', '', 'permission-card'); card.dataset.code = p.codigo;
        card.append(node('strong', `${p.nome}${p.critica ? ' · Crítica' : ''}`), node('small', p.disponivel ? p.modulo : 'Contrato futuro; não habilita execução', 'muted'));
        const grant = check(card, 'Pode conceder', previous?.conceder); const revoke = check(card, 'Pode revogar', previous?.revogar); codes.append(card); return { codigo: p.codigo, grant, revoke };
      }); form.append(codes);
      finishForm(form, () => { if (confirm('Confirmar os novos limites administrativos deste gerente?')) run(() => call(`/funcionarios/${row.vinculoId}/limites`, 'PUT', {
        adicionais: additional.checked, alvos: targetFields.filter(([, i]) => i.checked).map(([id]) => id),
        permissoes: codeFields.filter(c => c.grant.checked || c.revoke.checked).map(c => ({ codigo: c.codigo, conceder: c.grant.checked, revogar: c.revoke.checked })), versao: data.versao,
      }), 'Limites de gestão de permissões atualizados.'); });
    } catch (e) { if (current === generation()) { if (e.status === 403) await refresh().catch(() => {}); fail(e); } }
  }
  return { open, limits };
}