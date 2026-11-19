export const el = (tag, text = "", cls = "") => {
  const n = document.createElement(tag);
  n.textContent = text;
  n.className = cls;
  return n;
};
export const states = {
  AGUARDANDO: "Aguardando preparo",
  EM_PREPARO: "Em preparo",
  PRONTO: "Pronto",
  ENTREGUE: "Entregue",
  CANCELADO: "Cancelado",
  PARCIALMENTE_ENTREGUE: "Parcialmente entregue",
  PENDENTE: "Aguardando setores",
  RECUSADA: "Recusada",
  EXECUTADA: "Concluída",
  ACEITA: "Aceita pelo setor",
};
