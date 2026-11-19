import { createCardapioScreen } from './cardapio.js';
import { createMesaScreen } from './mesas.js';
import { readNavigation, authorizedUnit, navigationHash, createDraftStore } from './interface-contexto.js';
import { createEmployeeScreen } from './funcionarios.js';
const $ = (id) => document.getElementById(id);
const modules = [
  ['funcionarios', 'Funcionários', '♙', 'Cadastro e vínculos por estabelecimento.'],
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
let identity = null;
let currentPage = 'dashboard';
let navigationRequest = 0;
let restoring = null;
let restoreAgain = false;
let draftStorage;
try { draftStorage = window.sessionStorage; } catch { draftStorage = null; }
const drafts = createDraftStore(draftStorage);
let authEvents;
try { authEvents = new BroadcastChannel('kt.session.events'); authEvents.onmessage = event => { if (event.data?.type === 'logout') logout('', false); }; } catch { /* Revalidação pelo backend continua obrigatória. */ }
const pageIds = ['dashboard', ...modules.map(m => m[0])];
const requestedContext = () => readNavigation(location.hash, pageIds);
function rememberContext(mode = 'replace') { const hash = navigationHash(currentPage, $('establishment-select').value); if (location.hash !== hash) history[mode === 'push' ? 'pushState' : 'replaceState'](null, '', location.pathname + location.search + hash); }
let establishments = [];
let generation = 0;
let loadingEstablishments = false;
const employeeScreen = createEmployeeScreen({ root: $('employee-view'), api: getApi, getEstablishments: () => establishments, drafts, onExpired: () => logout('Sua sessão expirou. Entre novamente.') });

const mesaScreen = createMesaScreen({ root: $('mesas-view'), api: getApi, onExpired: () => logout('Sua sessão expirou. Entre novamente.') });

const cardapioScreen = createCardapioScreen({root:$('cardapio-view'),api:getApi,getEstablishments:()=>establishments,onExpired:()=>logout('Sua sessão expirou. Entre novamente.')});

class ApiError extends Error {
  constructor(status, detail) { super('Falha na requisição'); this.status = status; this.detail = detail; }
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
    if (!response.ok) {
      let detail;
      try { const problem = await response.json(); if (typeof problem.detail === 'string') detail = problem.detail; } catch { /* Resposta sem JSON. */ }
      throw new ApiError(response.status, detail);
    }
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

function logout(message = '', notify = true) {
  if (notify) authEvents?.postMessage({ type: 'logout' });
  employeeScreen.reset(); mesaScreen.reset(); cardapioScreen.reset();
  drafts.clear(); identity = null; restoreAgain = false;
  history.replaceState(null, '', location.pathname + location.search);
  generation++;
  authenticated = false;
  establishments = [];
  loadingEstablishments = false;
  $('login-form').reset();
  $('login-submit').disabled = false;
  $('login-form').removeAttribute('aria-busy');
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

function showPage(id, focus = true, historyMode = 'push') {
  currentPage = pageIds.includes(id) ? id : 'dashboard';
  id = currentPage;
  rememberContext(historyMode);
  const module = modules.find((item) => item[0] === id);
  const dashboard = !module;
  const products=id==='produtos';$('cardapio-view').hidden=!products;if(products)cardapioScreen.activate($('establishment-select').value);else cardapioScreen.reset();
  const employees = id === 'funcionarios';
  const mesas = id === 'mesas';
  $('mesas-view').hidden = !mesas;
  if (mesas) mesaScreen.activate($('establishment-select').value); else mesaScreen.reset();
  $('employee-view').hidden = !employees;
  if (employees) employeeScreen.activate($('establishment-select').value);
  else employeeScreen.reset();
  $('dashboard').hidden = !dashboard;
  $('module-view').hidden = dashboard || employees || mesas || products;
  $('page-title').textContent = dashboard ? 'Visão geral' : module[1];
  $('breadcrumb').textContent = dashboard ? 'VISÃO GERAL' : module[1].toLocaleUpperCase('pt-BR');
  $('page-description').textContent = dashboard ? 'Seu estabelecimento, no centro da gestão.' : employees ? 'Cadastros e vínculos independentes por unidade.' : 'Um próximo passo na evolução da sua gestão.';
  $('page-phase').textContent = mesas ? 'ENTREGA 2.4' : employees ? 'GESTÃO DA EQUIPE' : 'ACESSO INICIAL';
  if (module) {
    $('module-title').textContent = module[1];
    $('module-description').textContent = id === 'comandas' ? 'As comandas de mesa e balcão estão disponíveis no acesso operacional com identificação e PIN. Pedidos e pagamentos ainda não estão disponíveis.' : module[3];
    $('operational-link').hidden = id !== 'comandas';
    $('module-unavailable').hidden = id === 'comandas';
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
  const request = ++navigationRequest;
  const current = generation;
  try {
    await getApi('auth/me');
    if (current === generation && request === navigationRequest && authenticated) showPage(id);
  } catch (error) {
    if (current !== generation || request !== navigationRequest) return;
    if (error.status === 401) logout('Sua sessão expirou ou foi encerrada. Entre novamente.');
    else { $('data-error-text').textContent = errorMessage(error); $('data-error').hidden = false; }
  }
}

function selectEstablishment(activate = true) {
  const selected = establishments.find((item) => item.id === $('establishment-select').value);
  $('establishment-name').textContent = selected?.nome || 'Nenhum estabelecimento vinculado';
  const role = selected ? (roles[selected.funcao] || selected.funcao) : '—';
  $('establishment-role').textContent = selected ? `Sua função: ${role}` : 'Consulte o responsável pelo seu acesso.';
  $('user-role').textContent = role;
  if(activate&&!$('cardapio-view').hidden)cardapioScreen.activate(selected?.id);
  if (activate && !$('mesas-view').hidden) mesaScreen.activate(selected?.id);
  if (activate && !$('employee-view').hidden) employeeScreen.activate(selected?.id);
}

async function loadEstablishments(preferred = requestedContext().unit, activate = true) {
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
    $('establishment-select').value = authorizedUnit(data, preferred);
    $('selector-wrap').hidden = data.length < 2;
    selectEstablishment(activate);
  } catch (error) {
    if (current !== generation) return;
    if (error.status === 401) { logout(errorMessage(error)); return; }
    establishments = [];
    $('establishment-name').textContent = 'Estabelecimentos indisponíveis';
    $('establishment-select').replaceChildren();
    employeeScreen.reset(); mesaScreen.reset(); cardapioScreen.reset();
    $('data-error-text').textContent = errorMessage(error);
    $('data-error').hidden = false;
    throw error;
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
    identity = user.id; drafts.setIdentity(identity);
    $('password').value = '';
    $('account-name').textContent = $('user-name').textContent = user.nome;
    $('account-email').textContent = $('user-email').textContent = user.email;
    $('avatar').textContent = user.nome.trim().split(/\s+/).slice(0, 2).map((word) => word[0] || '').join('').toLocaleUpperCase('pt-BR');
    // Não restaurar âncoras de uma navegação anterior ao entrar na conta.
    if (window.location.hash) {
      window.history.replaceState(null, '', window.location.pathname + window.location.search);
    }
    await loadEstablishments(null, false);
    if (current !== generation || !authenticated) return;
    showPage('dashboard', true, 'replace');
    $('login-view').hidden = true; $('app-view').hidden = false;
    $('sidebar').scrollTop = 0;
  } catch (error) {
    if (current === generation) { if (authenticated) logout(errorMessage(error, true)); else showLoginError(errorMessage(error, true)); }
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
    badge.textContent = id === 'funcionarios' ? 'Equipe' : ['mesas','produtos'].includes(id) ? 'Gestão' : 'Em breve';
    button.append(badge);
    const card = document.createElement('button');
    card.type = 'button';
    card.className = 'module-card';
    const top = document.createElement('span');
    top.className = 'card-top';
    const status = document.createElement('span');
    status.className = 'phase-badge';
    status.textContent = id === 'funcionarios' ? 'GESTÃO DA EQUIPE' : id === 'mesas' ? 'GESTÃO DE MESAS' : id === 'produtos' ? 'CARDÁPIO REAL' : 'EM DESENVOLVIMENTO';
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
$('retry').addEventListener('click', () => restoreSession());
$('establishment-select').addEventListener('change', () => { employeeScreen.reset(); mesaScreen.reset(); cardapioScreen.reset(); rememberContext('push'); restoreSession(); });
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
document.addEventListener('keydown', (event) => { if (event.key === 'Escape' && $('menu-toggle').getAttribute('aria-expanded') === 'true') { closeMenu(); $('menu-toggle').focus(); } });
async function restoreSession() {
  // Uma requisição por ciclo de revalidação; não criar polling de sessão.
  if (restoring) { restoreAgain = true; return restoring; }
  restoring = restoreContext().finally(() => { restoring = null; if (restoreAgain && authenticated) { restoreAgain = false; return restoreSession(); } });
  return restoring;
}
async function restoreContext() {
  const current = ++generation;
  const previousIdentity = identity;
  const wanted = requestedContext();
  loadingEstablishments = false;
  $('app-view').hidden = true;
  $('login-view').hidden = true;
  $('login-submit').disabled = true;
  $('login-status').textContent = 'Verificando sua sessão…';
  try {
    const user = await getApi('auth/me');
    if (current !== generation) return;
    if (!user || typeof user.id !== 'string' || typeof user.nome !== 'string' || typeof user.email !== 'string') throw new Error('Resposta inválida');
    authenticated = true;
    identity = user.id;
    drafts.setIdentity(identity);
    $('account-name').textContent = $('user-name').textContent = user.nome;
    $('account-email').textContent = $('user-email').textContent = user.email;
    $('avatar').textContent = user.nome.trim().split(/\s+/).slice(0, 2).map(word => word[0]).join('').toLocaleUpperCase('pt-BR');
    await loadEstablishments(wanted.unit, false);
    if (current !== generation || !authenticated) return;
    const latest = requestedContext();
    if (latest.page !== wanted.page || latest.unit !== wanted.unit) { restoreAgain = true; return; }
    const unit = $('establishment-select').value;
    if (wanted.unit && wanted.unit !== unit) drafts.clearUnit(wanted.unit);
    const preserve = previousIdentity === identity && employeeScreen.currentUnit() === unit && currentPage === wanted.page && wanted.page === 'funcionarios';
    if (preserve) { await employeeScreen.revalidate(); rememberContext(); }
    else showPage(wanted.page, false, 'replace');
    if (current === generation && authenticated) { $('login-view').hidden = true; $('app-view').hidden = false; }
  } catch (error) {
    if (current === generation) logout(error.status === 401 ? '' : errorMessage(error));
  } finally {
    if (current === generation) { $('login-submit').disabled = false; $('login-status').textContent = ''; }
  }
}
window.addEventListener('popstate', () => { employeeScreen.reset(); mesaScreen.reset(); cardapioScreen.reset(); restoreSession(); });
window.addEventListener('hashchange', () => { if (authenticated) restoreSession(); });
// Conferir a sessão ao voltar a uma aba, sem polling que prolongue a inatividade.
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && authenticated) restoreSession();
});
window.addEventListener('pageshow', (event) => { if (event.persisted) restoreSession(); });
window.addEventListener('pagehide', () => { $('app-view').hidden = true; });
restoreSession();
