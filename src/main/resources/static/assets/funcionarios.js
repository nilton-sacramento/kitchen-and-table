const roles = { ATENDENTE: 'Atendente', CAIXA: 'Caixa', COZINHA: 'Cozinha', BAR: 'Bar', GERENTE: 'Gerente' };
const common = ['ATENDENTE', 'CAIXA', 'COZINHA', 'BAR'];
const uuidPattern = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
const el = (tag, text = '', className = '') => { const node = document.createElement(tag); node.textContent = text; node.className = className; return node; };
export function createEmployeeScreen({ root, api, onExpired, getEstablishments = () => [] }) {
  let unit = null, generation = 0, capabilities = null, rows = [], requests = [], busy = false;
  function reset() { generation++; unit = null; rows = []; requests = []; capabilities = null; busy = false; editorTrigger = null; root.removeAttribute('aria-busy'); root.replaceChildren(); }
  function path(suffix = '') { return `estabelecimentos/${encodeURIComponent(unit)}/funcionarios${suffix}`; }
  async function request(suffix, method = 'GET', data, key) {
    const destination = path(suffix);
    if (method === 'GET') return api(destination);
    const csrf = await api('auth/csrf');
    return api(destination, { method, headers: { [csrf.headerName]: csrf.token, 'Content-Type': 'application/json', ...(key ? { 'Idempotency-Key': key } : {}) }, body: JSON.stringify(data) });
  }
  let editorTrigger = null;
  function restoreFocus(key) {
    const candidate = key && root.querySelector(`[data-focus="${CSS.escape(key)}"]`);
    (candidate || root.querySelector('input[type="search"], button'))?.focus();
  }
  function message(text, error = false) {
    const node = root.querySelector('[data-message]');
    if (node) { node.textContent = text; node.className = error ? 'error' : 'employee-feedback'; node.hidden = !text; node.setAttribute('role', error ? 'alert' : 'status'); }
  }
  function fail(error) {
    if (error.status === 401) { reset(); onExpired(); return; }
    message(error.detail || (error.status === 403 ? 'Você não tem autorização para esta operação.' : error.status === 409 ? 'Os dados mudaram. Atualize a lista antes de tentar novamente.' : 'Não foi possível concluir. Confira a conexão e tente novamente.'), true);
  }
  async function run(operation, success) {
    if (busy) return;
    const current = generation;
    const focusKey = editorTrigger || document.activeElement?.dataset.focus;
    busy = true; root.setAttribute('aria-busy', 'true'); root.querySelectorAll('button').forEach((b) => { b.disabled = true; });
    try {
      await operation();
      if (current !== generation) return;
      await load(current);
      if (current === generation) message(success);
    } catch (error) { if (current === generation) fail(error); }
    finally { if (current === generation) { busy = false; root.removeAttribute('aria-busy'); root.querySelectorAll('button').forEach((b) => { b.disabled = false; }); restoreFocus(focusKey); editorTrigger = null; } }
  }
  function button(label, action, className = 'secondary') {
    const b = el('button', label, className); b.type = 'button'; b.dataset.focus = label; b.addEventListener('click', action); return b;
  }
  function input(form, name, label, value = '', { type = 'text', required = false, maxLength = 150, pattern } = {}) {
    const box = el('div'); const l = el('label', label); const field = document.createElement('input');
    field.id = `employee-${name}`; field.name = name; field.type = type; field.value = value || ''; field.required = required; field.maxLength = maxLength;
    if (pattern) field.pattern = pattern;
    l.htmlFor = field.id; box.append(l, field); form.append(box); return field;
  }
  function select(form, name, label, choices, selected) {
    const box = el('div'); const l = el('label', label); const field = document.createElement('select');
    field.name = name; field.id = `employee-${name}`; l.htmlFor = field.id;
    for (const code of choices) { const option = el('option', roles[code] || code); option.value = code; field.append(option); }
    if (selected && choices.includes(selected)) field.value = selected;
    box.append(l, field); form.append(box); return field;
  }
  function editor(title) {
    editorTrigger = document.activeElement?.dataset.focus || null;
    const slot = root.querySelector('[data-editor]'); slot.replaceChildren(); slot.hidden = false;
    const form = document.createElement('form'); form.className = 'employee-form'; form.setAttribute('aria-label', title);
    form.append(el('h3', title)); slot.append(form); return form;
  }
  function finishForm(form, action) {
    const controls = el('div', '', 'employee-actions'); const submit = el('button', 'Salvar', 'primary'); submit.type = 'submit';
    controls.append(submit, button('Cancelar', () => { const slot = root.querySelector('[data-editor]'); slot.replaceChildren(); slot.hidden = true; restoreFocus(editorTrigger); editorTrigger = null; })); form.append(controls);
    form.addEventListener('submit', (event) => { event.preventDefault(); if (form.reportValidity()) action(); });
    form.querySelector('input:not(:disabled),select:not(:disabled),button')?.focus();
    form.addEventListener('keydown', (event) => { if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); controls.querySelector('button[type=button]').click(); } });
  }
  function edit(row = null) {
    const form = editor(row ? 'Editar dados nesta unidade' : 'Cadastrar nova pessoa');
    const name = input(form, 'nome', 'Nome', row?.nome, { required: true });
    const email = input(form, 'email', 'E-mail de contato (opcional; não cria login)', row?.emailContato, { type: 'email', maxLength: 255 });
    const phone = input(form, 'telefone', 'Telefone (opcional)', row?.telefone, { type: 'tel', maxLength: 32 });
    const role = row ? null : select(form, 'funcao', 'Função principal', capabilities.funcoes);
    if (!row) form.append(el('p', 'Se esta pessoa já tem identidade no sistema, utilize a associação autorizada entre unidades. Nome ou contato não associam contas automaticamente.', 'muted small'));
    const key = crypto.randomUUID();
    finishForm(form, () => run(() => request(row ? `/${row.vinculoId}` : '', row ? 'PATCH' : 'POST', {
      nome: name.value.trim(), emailContato: email.value.trim() || null, telefone: phone.value.trim() || null,
      ...(row ? { versao: row.versao } : { funcao: role.value }),
    }, row ? null : key), row ? 'Dados locais atualizados.' : 'Funcionário cadastrado. O acesso por PIN pertence à Entrega 2.3.'));
  }
  function changeRole(row) {
    const form = editor('Alterar função principal'); const role = select(form, 'funcao', 'Função nesta unidade', capabilities.funcoes, row.funcao);
    form.append(el('p', 'Criar ou promover gerente exige proprietário. Uma mudança de função remove delegações administrativas antigas.', 'muted small'));
    finishForm(form, () => run(() => request(`/${row.vinculoId}/funcao-principal`, 'PATCH', { funcao: role.value, versao: row.versao }), 'Função atualizada.'));
  }
  async function delegation(row) {
    const current = generation;
    try {
      const data = await request(`/${row.vinculoId}/autorizacao-administrativa`); if (current !== generation) return;
      const form = editor(`Limites administrativos: ${row.nome}`); const fields = {};
      for (const [key, label] of Object.entries({ consultar: 'Consultar funcionários comuns', cadastrar: 'Cadastrar', editar: 'Editar', ativar: 'Ativar', desativar: 'Desativar', associar: 'Associar identidades entre unidades' })) {
        const l = el('label', '', 'employee-check'); const field = document.createElement('input'); field.type = 'checkbox'; field.checked = data[key]; fields[key] = field; l.append(field, document.createTextNode(label)); form.append(l);
      }
      form.append(el('p', 'Funções comuns que este gerente poderá administrar:', 'muted small'));
      const allowed = common.map((code) => { const l = el('label', '', 'employee-check'); const field = document.createElement('input'); field.type = 'checkbox'; field.checked = data.funcoes.includes(code); l.append(field, document.createTextNode(roles[code])); form.append(l); return [code, field]; });
      finishForm(form, () => run(() => request(`/${row.vinculoId}/autorizacao-administrativa`, 'PUT', {
        ...Object.fromEntries(Object.entries(fields).map(([key, field]) => [key, field.checked])),
        funcoes: allowed.filter(([, field]) => field.checked).map(([code]) => code), versao: data.versao,
      }), 'Limites do gerente atualizados.'));
    } catch (error) { if (current === generation) fail(error); }
  }
  function associate(row) {
    const form = editor(`Autorizar associação: ${row.nome}`);
    const known = getEstablishments().filter((item) => item.id !== unit);
    let choice;
    if (known.length) {
      const box = el('div'); const label = el('label', 'Unidade de destino'); choice = document.createElement('select');
      choice.id = 'employee-destino-conhecido'; label.htmlFor = choice.id;
      for (const item of known) { const option = el('option', item.nome); option.value = item.id; choice.append(option); }
      const other = el('option', 'Outra unidade (informar identificador)'); other.value = ''; choice.append(other);
      box.append(label, choice); form.append(box);
    }
    const target = input(form, 'destino', 'Identificador UUID fornecido pela unidade de destino', '', { required: !choice, maxLength: 36, pattern: uuidPattern });
    const toggle = () => { target.parentElement.hidden = !!choice?.value; target.disabled = !!choice?.value; target.required = !choice?.value; };
    choice?.addEventListener('change', toggle); toggle();
    form.append(el('p', 'Autoriza compartilhar somente a identidade/nome com o destino. Um administrador autorizado da outra unidade deverá aceitar a solicitação antes do vencimento. Contatos, senhas e permissões não serão copiados.', 'muted small'));
    finishForm(form, () => run(() => request(`/${row.vinculoId}/associacoes`, 'POST', { destinoId: choice?.value || target.value.trim() }), 'Solicitação enviada. Selecione a unidade de destino para aceitá-la, ou peça ao administrador autorizado dessa unidade.'));
  }
  function render() {
    root.replaceChildren();
    const head = el('div', '', 'section-heading'); const intro = el('div'); intro.append(el('span', 'EQUIPE DO ESTABELECIMENTO', 'eyebrow'), el('h2', 'Funcionários'));
    const actions = el('div', '', 'employee-actions'); actions.append(button('Atualizar', () => { if (!busy) activate(unit); }));
    if (capabilities.cadastrar && capabilities.funcoes.length) actions.append(button('Nova pessoa', () => edit(), 'primary'));
    head.append(intro, actions); root.append(head);
    root.append(el('p', 'Cadastros e vínculos reais desta unidade. O PIN e o acesso operacional serão disponibilizados na Entrega 2.3. Credenciais administrativas existentes são preservadas.', 'muted small'));
    const msg = el('p'); msg.dataset.message = ''; msg.setAttribute('role', 'status'); msg.hidden = true; root.append(msg);
    const slot = el('section', '', 'employee-editor'); slot.dataset.editor = ''; slot.hidden = true; root.append(slot);
    if (!capabilities.consultar) root.append(el('p', 'Seu acesso não permite listar a equipe. As demais ações também são validadas no servidor.', 'muted'));
    else {
      const search = el('input'); search.type = 'search'; search.placeholder = 'Buscar por nome ou contato'; search.setAttribute('aria-label', 'Buscar funcionários'); root.append(search);
      const wrap = el('div', '', 'employee-table-wrap'); const table = el('table', '', 'employee-table'); table.setAttribute('role', 'table'); table.setAttribute('aria-label', 'Funcionários desta unidade'); const thead = el('thead'); const tr = el('tr');
      ['Funcionário', 'Função', 'Contato', 'Situação', 'Ações'].forEach((text) => { const th = el('th', text); th.scope = 'col'; tr.append(th); }); thead.append(tr); const body = el('tbody'); table.append(thead, body); wrap.append(table); root.append(wrap);
      const empty = el('p', '', 'muted'); root.append(empty);
      function fill() {
        body.replaceChildren(); const term = search.value.toLocaleLowerCase('pt-BR'); const filtered = rows.filter((r) => `${r.nome} ${r.emailContato || ''} ${r.telefone || ''}`.toLocaleLowerCase('pt-BR').includes(term));
        empty.textContent = filtered.length ? '' : 'Nenhum funcionário encontrado nesta unidade.';
        for (const row of filtered) {
          const line = el('tr'); line.setAttribute('role', 'row'); const name = el('td');
          const details = el('details', '', 'employee-identifiers'); const summary = el('summary', 'Identificadores');
          summary.setAttribute('aria-label', `Identificadores de ${row.nome}`);
          details.append(summary, el('small', `Identidade: ${row.usuarioId}`, 'employee-identity'), el('small', `Vínculo: ${row.vinculoId}`, 'employee-link-id'));
          name.append(el('strong', row.nome), details);
          const contact = el('td'); contact.append(el('div', row.emailContato || 'Sem e-mail de contato'), el('small', row.telefone || '', 'muted'));
          const buttons = el('td'); const controls = el('div', '', 'employee-actions');
          if (capabilities.editar) { controls.append(button('Editar', () => edit(row))); controls.append(button('Função', () => changeRole(row))); }
          if ((row.ativo && capabilities.desativar) || (!row.ativo && capabilities.ativar)) controls.append(button(row.ativo ? 'Desativar' : 'Ativar', () => {
            if (window.confirm(`${row.ativo ? 'Desativar' : 'Ativar'} o vínculo de ${row.nome} nesta unidade? O histórico e as outras unidades serão preservados.`)) run(() => request(`/${row.vinculoId}/situacao`, 'PATCH', { ativo: !row.ativo, versao: row.versao }), 'Situação do vínculo atualizada.');
          }));
          if (capabilities.proprietario && row.funcao === 'GERENTE') controls.append(button('Limites do gerente', () => delegation(row)));
          if (capabilities.associar && row.ativo) controls.append(button('Associar a outra unidade', () => associate(row)));
          controls.querySelectorAll('button').forEach((b) => { b.setAttribute('aria-label', `${b.textContent}: ${row.nome}`); b.dataset.focus = `${row.vinculoId}:${b.textContent}`; });
          if (!controls.children.length) controls.append(el('span', 'Sem ações autorizadas', 'muted'));
          buttons.append(controls); line.append(name, el('td', roles[row.funcao] || row.funcao), contact, el('td', row.ativo ? 'Ativo' : 'Inativo'), buttons);
          ['Funcionário', 'Função', 'Contato', 'Situação', 'Ações'].forEach((label, index) => { line.children[index].dataset.label = label; line.children[index].setAttribute('role', 'cell'); });
          body.append(line);
        }
      }
      search.addEventListener('input', fill); fill();
    }
    if (capabilities.associar) {
      const section = el('section', '', 'user-section'); section.append(el('h3', 'Associações recebidas'));
      if (!requests.length) section.append(el('p', 'Nenhuma solicitação válida aguardando aceite nesta unidade.', 'muted small'));
      for (const item of requests) {
        const box = el('div', '', 'employee-request'); box.append(el('strong', item.nome), el('p', `Origem: ${item.origemId} · Vence em ${new Date(item.expiraEm).toLocaleString('pt-BR')}`, 'muted small'));
        const choice = select(box, `recebida-${item.id}`, 'Função no destino', capabilities.funcoes); const key = crypto.randomUUID();
        box.append(button('Aceitar vínculo', () => run(() => request(`/associacoes/${item.id}/aceitar`, 'POST', { funcao: choice.value }, key), 'Identidade associada. Contatos e permissões permanecem independentes.'), 'primary')); section.append(box);
      }
      root.append(section);
    }
  }
  async function load(current) {
    const c = await request('/capacidades'); if (current !== generation) return;
    const [employees, incoming] = await Promise.all([c.consultar ? request('') : Promise.resolve([]), c.associar ? request('/associacoes') : Promise.resolve([])]);
    if (current !== generation) return;
    if (!Array.isArray(employees) || !Array.isArray(incoming) || !Array.isArray(c.funcoes)) throw new Error('Resposta inválida');
    capabilities = c; rows = employees; requests = incoming; render();
  }
  async function activate(id) {
    reset(); unit = id || null; const current = generation;
    if (!unit) { root.append(el('p', 'Selecione um estabelecimento para acessar os funcionários.', 'muted')); return; }
    const status = el('p', 'Consultando acesso e equipe…', 'muted'); status.setAttribute('role', 'status'); root.append(status);
    try { await load(current); }
    catch (error) {
      if (current !== generation) return;
      if (error.status === 401) { reset(); onExpired(); return; }
      root.replaceChildren(); const msg = el('p'); msg.dataset.message = ''; msg.setAttribute('role', 'alert'); root.append(msg); fail(error); root.append(button('Tentar novamente', () => activate(unit)));
    }
  }
  return { activate, reset };
}
