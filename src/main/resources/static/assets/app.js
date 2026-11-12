const $ = (id) => document.getElementById(id);
const modules = [
  ['funcionarios', 'Funcionários', '♙', 'Equipe, vínculos e permissões de acesso.'],
  ['produtos', 'Produtos', '◇', 'Produtos, cardápio e combos do estabelecimento.'],
  ['mesas', 'Mesas', '▦', 'Organização e acompanhamento do atendimento em mesas.'],
  ['comandas', 'Comandas', '▤', 'Consumo e acompanhamento de cada atendimento.'],
  ['pedidos', 'Pedidos', '≡', 'Pedidos e encaminhamento aos setores de produção.'],
  ['cozinha', 'Cozinha', '♧', 'Acompanhamento do preparo na cozinha.'],
  ['bar', 'Bar', '♜', 'Acompanhamento dos itens preparados no bar.'],
  ['estoque', 'Estoque', '▧', 'Itens, movimentações e rastreabilidade de estoque.'],
  ['caixa', 'Caixa', '▣', 'Abertura, movimentações e fechamento do caixa.'],
  ['relatorios', 'Relatórios', '▥', 'Informações operacionais e financeiras.'],
];
const roles = { PROPRIETARIO: 'Proprietário', GERENTE: 'Gerente', ATENDENTE: 'Atendente', CAIXA: 'Operador de caixa', COZINHA: 'Cozinha', BAR: 'Bar' };
let authenticated = false;
let establishments = [];
let generation = 0;
let loadingEstablishments = false;

class ApiError extends Error {
  constructor(status) { super('Falha na requisição'); this.status = status; }
}

async function getApi(path, options = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(`/api/v1/${path}`, {
      method: 'GET', ...options,
      headers: { Accept: 'application/json', ...options.headers },
      credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: controller.signal,
    });
    if (!response.ok) throw new ApiError(response.status);
    return response.status === 204 ? null : await response.json();
  } finally { clearTimeout(timeout); }
}

async function postApi(path, body) {
  // O Spring rotaciona o token após login/logout; buscar um token atual antes de cada POST.
  const csrf = await getApi('auth/csrf');
  return getApi(path, {
    method: 'POST', body,
    headers: { [csrf.headerName]: csrf.token, 'Content-Type': 'application/x-www-form-urlencoded' },
  });
}

function errorMessage(error, login = false) {
  if (error.status === 401) return login ? 'E-mail ou senha inválidos, ou conta inativa. Confira seus dados.' : 'Seu acesso não é mais válido. Entre novamente.';
  if (error.status === 403) return 'Seu acesso não permite consultar estas informações.';
  if (error.name === 'AbortError') return 'O servidor demorou para responder. Tente novamente.';
  if (error instanceof ApiError) return 'Não foi possível carregar os dados do servidor. Tente novamente.';
  return 'Não foi possível conectar ao servidor ou ler sua resposta. Verifique a conexão e tente novamente.';
}

function showLoginError(message) {
  $('login-error').textContent = message;
  $('login-error').hidden = false;
}

function logout(message = '') {
  generation++;
  authenticated = false;
  establishments = [];
  loadingEstablishments = false;
  $('login-form').reset();
  $('password').type = 'password';
  $('show-password').textContent = 'Mostrar senha';
  $('show-password').setAttribute('aria-pressed', 'false');
  $('app-view').hidden = true;
  $('login-view').hidden = false;
  $('login-error').hidden = true;
  $('login-status').textContent = '';
  ['account-name', 'account-email', 'user-name', 'user-email', 'avatar', 'establishment-name', 'establishment-role', 'user-role'].forEach((id) => { $(id).textContent = ''; });
  $('establishment-select').replaceChildren();
  closeMenu();
  if (message) showLoginError(message);
  $('email').focus();
}

function closeMenu() {
  $('sidebar').classList.remove('open');
  $('menu-toggle').setAttribute('aria-expanded', 'false');
}

function showPage(id, focus = true) {
  const module = modules.find((item) => item[0] === id);
  const dashboard = !module;
  $('dashboard').hidden = !dashboard;
  $('module-view').hidden = dashboard;
  $('page-title').textContent = dashboard ? 'Visão geral' : module[1];
  $('breadcrumb').textContent = dashboard ? 'VISÃO GERAL' : module[1].toLocaleUpperCase('pt-BR');
  $('page-description').textContent = dashboard ? 'Seu estabelecimento, no centro da gestão.' : 'Um próximo passo na evolução da sua gestão.';
  if (module) {
    $('module-title').textContent = module[1];
    $('module-description').textContent = module[3];
    $('module-symbol').textContent = module[2];
  }
  document.querySelectorAll('.nav-button').forEach((button) => {
    const active = button.dataset.page === (dashboard ? 'dashboard' : id);
    button.classList.toggle('active', active);
    if (active) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
  closeMenu();
  // A mudança de seção sempre começa pelo cabeçalho, sem rolagem causada pelo foco.
  if (focus) $('main-content').focus({ preventScroll: true });
  window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
}

async function openPage(id) {
  const current = generation;
  try {
    await getApi('auth/me');
    if (current === generation && authenticated) showPage(id);
  } catch (error) {
    if (current !== generation) return;
    if (error.status === 401) logout('Sua sessão expirou ou foi encerrada. Entre novamente.');
    else {
      $('data-error-text').textContent = errorMessage(error);
      $('data-error').hidden = false;
    }
  }
}

function selectEstablishment() {
  const selected = establishments.find((item) => item.id === $('establishment-select').value);
  $('establishment-name').textContent = selected?.nome || 'Nenhum estabelecimento vinculado';
  const role = selected ? (roles[selected.funcao] || selected.funcao) : '—';
  $('establishment-role').textContent = selected ? `Sua função: ${role}` : 'Consulte o responsável pelo seu acesso.';
  $('user-role').textContent = role;
}

async function loadEstablishments() {
  if (loadingEstablishments || !authenticated) return;
  loadingEstablishments = true;
  const current = generation;
  $('data-error').hidden = true;
  $('data-status').textContent = 'Consultando seus estabelecimentos…';
  $('data-status').hidden = false;
  $('establishment-name').textContent = 'Carregando estabelecimentos…';
  $('establishment-role').textContent = '';
  $('user-role').textContent = '—';
  $('selector-wrap').hidden = true;
  try {
    const data = await getApi('estabelecimentos');
    if (current !== generation) return;
    if (!Array.isArray(data) || !data.every((item) => item && typeof item.id === 'string' && typeof item.nome === 'string' && typeof item.funcao === 'string')) throw new Error('Resposta inválida');
    establishments = data;
    $('establishment-select').replaceChildren(...data.map((item) => {
      const option = document.createElement('option');
      option.value = item.id;
      option.textContent = item.nome;
      return option;
    }));
    $('selector-wrap').hidden = data.length < 2;
    selectEstablishment();
  } catch (error) {
    if (current !== generation) return;
    if (error.status === 401) { logout(errorMessage(error)); return; }
    establishments = [];
    $('establishment-name').textContent = 'Estabelecimentos indisponíveis';
    $('data-error-text').textContent = errorMessage(error);
    $('data-error').hidden = false;
  } finally {
    if (current === generation) { loadingEstablishments = false; $('data-status').hidden = true; }
  }
}

$('login-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const current = ++generation;
  const body = new URLSearchParams({ email: $('email').value.trim(), password: $('password').value });
  $('login-error').hidden = true;
  $('login-submit').disabled = true;
  $('login-form').setAttribute('aria-busy', 'true');
  $('login-status').textContent = 'Verificando seu acesso…';
  try {
    try {
      await postApi('auth/login', body);
    } finally {
      body.delete('password');
      $('password').value = '';
    }
    const user = await getApi('auth/me');
    if (current !== generation) return;
    if (!user || typeof user.id !== 'string' || typeof user.nome !== 'string' || typeof user.email !== 'string') throw new Error('Resposta inválida');
    authenticated = true;
    $('password').value = '';
    $('account-name').textContent = $('user-name').textContent = user.nome;
    $('account-email').textContent = $('user-email').textContent = user.email;
    $('avatar').textContent = user.nome.trim().split(/\s+/).slice(0, 2).map((word) => word[0] || '').join('').toLocaleUpperCase('pt-BR');
    $('login-view').hidden = true;
    $('app-view').hidden = false;
    // Não restaurar âncoras de uma navegação anterior ao entrar na conta.
    if (window.location.hash) {
      window.history.replaceState(null, '', window.location.pathname + window.location.search);
    }
    showPage('dashboard');
    $('sidebar').scrollTop = 0;
    await loadEstablishments();
  } catch (error) {
    if (current === generation) showLoginError(errorMessage(error, true));
  } finally {
    $('login-submit').disabled = false;
    $('login-form').removeAttribute('aria-busy');
    $('login-status').textContent = '';
  }
});

for (const [id, title, icon, description] of [['dashboard', 'Visão geral', '⊞', ''], ...modules]) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'nav-button';
  button.dataset.page = id;
  const symbol = document.createElement('span');
  symbol.className = 'nav-icon';
  symbol.setAttribute('aria-hidden', 'true');
  symbol.textContent = icon;
  button.append(symbol, document.createTextNode(title));
  if (id !== 'dashboard') {
    const badge = document.createElement('span');
    badge.className = 'coming';
    badge.textContent = 'Em breve';
    button.append(badge);
    const card = document.createElement('button');
    card.type = 'button';
    card.className = 'module-card';
    const top = document.createElement('span');
    top.className = 'card-top';
    const status = document.createElement('span');
    status.className = 'phase-badge';
    status.textContent = 'EM DESENVOLVIMENTO';
    top.append(symbol.cloneNode(true), status);
    const name = document.createElement('strong');
    name.textContent = title;
    const text = document.createElement('p');
    text.textContent = description;
    card.append(top, name, text);
    card.addEventListener('click', () => openPage(id));
    $('module-grid').append(card);
  }
  button.addEventListener('click', () => openPage(id));
  $('navigation').append(button);
}
$('logout').addEventListener('click', async () => {
  $('logout').disabled = true;
  try {
    await postApi('auth/logout', new URLSearchParams());
    logout();
  } catch (error) {
    if (error.status === 401) logout();
    else {
      $('data-error-text').textContent = 'Não foi possível confirmar a saída no servidor. Tente sair novamente. ' + errorMessage(error);
      $('data-error').hidden = false;
    }
  } finally { $('logout').disabled = false; }
});
$('retry').addEventListener('click', loadEstablishments);
$('establishment-select').addEventListener('change', selectEstablishment);
$('back-dashboard').addEventListener('click', () => openPage('dashboard'));
$('show-password').addEventListener('click', () => {
  const showing = $('password').type === 'password';
  $('password').type = showing ? 'text' : 'password';
  $('show-password').textContent = showing ? 'Ocultar senha' : 'Mostrar senha';
  $('show-password').setAttribute('aria-pressed', String(showing));
});
$('menu-toggle').addEventListener('click', () => {
  const open = $('sidebar').classList.toggle('open');
  $('menu-toggle').setAttribute('aria-expanded', String(open));
});
document.addEventListener('keydown', (event) => { if (event.key === 'Escape') { closeMenu(); $('menu-toggle').focus(); } });
async function restoreSession() {
  const current = ++generation;
  $('app-view').hidden = true;
  $('login-view').hidden = false;
  $('login-submit').disabled = true;
  $('login-status').textContent = 'Verificando sua sessão…';
  try {
    const user = await getApi('auth/me');
    if (current !== generation) return;
    if (!user || typeof user.nome !== 'string' || typeof user.email !== 'string') throw new Error('Resposta inválida');
    authenticated = true;
    $('account-name').textContent = $('user-name').textContent = user.nome;
    $('account-email').textContent = $('user-email').textContent = user.email;
    $('avatar').textContent = user.nome.trim().split(/\s+/).slice(0, 2).map((word) => word[0] || '').join('').toLocaleUpperCase('pt-BR');
    $('login-view').hidden = true;
    $('app-view').hidden = false;
    showPage('dashboard');
    await loadEstablishments();
  } catch (error) {
    if (current !== generation) return;
    logout(error.status === 401 ? '' : errorMessage(error));
  } finally {
    $('login-submit').disabled = false;
    $('login-status').textContent = '';
  }
}

// Conferir a sessão ao voltar a uma aba, sem polling que prolongue a inatividade.
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && authenticated) restoreSession();
});
window.addEventListener('pageshow', (event) => { if (event.persisted) restoreSession(); });
window.addEventListener('pagehide', () => { $('app-view').hidden = true; });
restoreSession();
