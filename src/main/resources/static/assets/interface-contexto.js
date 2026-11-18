// Contexto de interface não é autorização: validar sessão/unidades antes de usar.
export function readNavigation(hash, pages) {
  const values = new URLSearchParams(String(hash || '').replace(/^#/, ''));
  const page = pages.includes(values.get('pagina')) ? values.get('pagina') : 'dashboard';
  const unit = values.get('unidade');
  return { page, unit: /^[0-9a-f-]{36}$/i.test(unit || '') ? unit : null };
}
export function authorizedUnit(units, requested) {
  return units.find(unit => unit.id === requested)?.id || units[0]?.id || '';
}
export function navigationHash(page, unit) {
  const params = new URLSearchParams({ pagina: page });
  if (unit) params.set('unidade', unit);
  return '#' + params;
}
const PREFIX = 'kt.draft.v1.';
const FIELDS = { nome: 150, emailContato: 255, telefone: 32, funcao: 20 };
// Só dados de cadastro; nunca serializar FormData ou formulários de credenciais.
export function createDraftStore(storage, now = () => Date.now()) {
  let identity = null;
  const keys = () => { try { return Array.from({ length: storage.length }, (_, i) => storage.key(i)).filter(k => k?.startsWith(PREFIX)); } catch { return []; } };
  const remove = key => { try { storage.removeItem(key); } catch { /* Storage indisponível: não bloquear o sistema. */ } };
  const key = (unit, record) => PREFIX + JSON.stringify([identity, unit, 'pessoa', record || 'nova']);
  const clear = () => keys().forEach(remove);
  function setIdentity(id) {
    identity = typeof id === 'string' && id ? id : null;
    for (const k of keys()) {
      try { if (!identity || JSON.parse(k.slice(PREFIX.length))[0] !== identity) remove(k); } catch { remove(k); }
    }
  }
  function read(unit, record, version) {
    if (!identity || !unit) return null;
    const k = key(unit, record);
    try {
      const value = JSON.parse(storage.getItem(k));
      if (!value) return null;
      if (value.schema !== 1 || !Number.isFinite(value.expires) || value.expires <= now() || value.version !== version) { remove(k); return null; }
      if (!value.fields || typeof value.fields !== 'object' || Array.isArray(value.fields) || Object.entries(value.fields).some(([f, v]) => !Object.hasOwn(FIELDS, f) || typeof v !== 'string' || v.length > FIELDS[f])) { remove(k); return null; }
      return value.fields;
    } catch { remove(k); return null; }
  }
  function save(unit, record, version, fields) {
    if (!identity || !unit) return;
    const safe = Object.fromEntries(Object.entries(fields).filter(([f, v]) => Object.hasOwn(FIELDS, f) && typeof v === 'string').map(([f, v]) => [f, v.slice(0, FIELDS[f])]));
    try { storage.setItem(key(unit, record), JSON.stringify({ schema: 1, version, expires: now() + 30 * 60 * 1000, fields: safe })); } catch { /* Sem persistência quando indisponível. */ }
  }
  function clearUnit(unit) {
    for (const k of keys()) { try { if (JSON.parse(k.slice(PREFIX.length))[1] === unit) remove(k); } catch { remove(k); } }
  }
  function pending(unit) { return keys().flatMap(k => { try { const [owner, scope, , record] = JSON.parse(k.slice(PREFIX.length)); return owner === identity && scope === unit ? [record] : []; } catch { remove(k); return []; } }); }
  return { setIdentity, read, save, clear, clearUnit, pending, discard: (unit, record) => remove(key(unit, record)) };
}
