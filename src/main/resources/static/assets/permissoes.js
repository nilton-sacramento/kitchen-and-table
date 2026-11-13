// Interface real de permissoes; autorizacao definitiva permanece no servidor.
const operational = ['ATENDENTE', 'CAIXA', 'COZINHA', 'BAR'];
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
      form.append(node('p', `Função principal: ${panel.principal}. Vínculo nesta unidade: ${panel.vinculoId}`, 'muted small'));
      const additional = node('fieldset'); additional.append(node('legend', 'Funções adicionais operacionais'));
      const fields = operational.filter(c => c !== panel.principal).map(c => [c, check(additional, c, panel.adicionais.includes(c))]);
      additional.disabled = !panel.alterarAdicionais;
      const saveRoles = node('button', 'Salvar funções adicionais', 'secondary'); saveRoles.type = 'button'; saveRoles.disabled = !panel.alterarAdicionais;
      saveRoles.dataset.permissionDisabled = String(!panel.alterarAdicionais);
      saveRoles.addEventListener('click', () => { if (confirm('Confirmar alteração de funções adicionais nesta unidade?')) run(() => call(`/funcionarios/${row.vinculoId}/adicionais`, 'PUT', { funcoes: fields.filter(([, i]) => i.checked).map(([c]) => c), versao: panel.versao }), 'Funções adicionais atualizadas. Abra novamente para consultar o resultado.'); });
      additional.append(saveRoles); form.append(additional);
      form.append(node('p', 'Herdar segue as funções; permitir concede explicitamente; negar prevalece sobre heranças. Contratos futuros não habilitam operações. A autoridade administrativa continua sujeita à hierarquia e às delegações.', 'muted small'));
      const search = document.createElement('input'); search.type = 'search'; search.placeholder = 'Filtrar permissões'; search.setAttribute('aria-label', 'Filtrar permissões'); form.append(search);
      const groups = node('div', '', 'permission-groups'); form.append(groups);
      for (const category of ['ADMINISTRATIVA', 'OPERACIONAL', 'FINANCEIRA']) {
        const section = node('section', '', 'permission-group'); section.append(node('h4', category));
        for (const item of panel.permissoes.filter(i => i.permissao.categoria === category)) {
          const p = item.permissao; const card = node('article', '', 'permission-card'); card.dataset.code = p.codigo; card.dataset.filter = `${p.nome} ${p.codigo}`.toLocaleLowerCase('pt-BR');
          card.append(node('strong', p.nome), node('small', `${p.modulo}${p.critica ? ' · Crítica' : ''}${p.disponivel ? '' : ' · Contrato futuro/legado inativo'}`, 'muted'));
          const detail = node('details'); detail.append(node('summary', 'Código técnico'), node('code', p.codigo)); card.append(detail);
          card.append(node('p', p.descricao), node('p', `Origem: ${item.herdadaDe.join(', ') || 'Sem padrão herdado'}`, 'muted small'));
          card.append(node('p', `Estado individual: ${item.estado}. Resultado configurado: ${item.configurada ? 'permitido' : 'negado'}. Acesso efetivo: ${item.efetiva ? 'permitido' : 'negado'}.`, 'permission-result'));
          if (item.restricao) card.append(node('p', item.restricao, 'muted small'));
          const label = node('label', `Exceção: ${p.nome}`); const select = document.createElement('select'); select.id = `permission-${p.codigo}`; label.htmlFor = select.id;
          const grant = panel.conceder.includes(p.codigo), revoke = panel.revogar.includes(p.codigo);
          for (const [value, text] of [['HERDAR','Herdar'],['PERMITIR','Permitir'],['NEGAR','Negar']]) {
            const option = node('option', text); option.value = value;
            option.disabled = value === 'HERDAR' ? !(grant && revoke) : value === 'PERMITIR' ? !grant : !revoke;
            select.append(option);
          }
          select.value = item.estado; select.disabled = !grant && !revoke;
          const save = node('button', 'Aplicar exceção', 'secondary'); save.type = 'button'; save.disabled = select.disabled; save.dataset.permissionDisabled = String(select.disabled); save.setAttribute('aria-label', `Aplicar exceção: ${p.nome}`);
          save.addEventListener('click', () => {
            if (confirm(`Confirmar ${select.value} para ${p.nome}, somente nesta unidade?`)) run(() => call(`/funcionarios/${row.vinculoId}/individuais/${encodeURIComponent(p.codigo)}`, 'PUT', { estado: select.value, versao: panel.versao }), 'Permissão atualizada. Abra novamente para consultar o resultado.');
          });
          card.append(label, select, save); section.append(card);
        }
        groups.append(section);
      }
      search.addEventListener('input', () => { const term = search.value.toLocaleLowerCase('pt-BR'); groups.querySelectorAll('.permission-card').forEach(c => { c.hidden = !c.dataset.filter.includes(term); }); });
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