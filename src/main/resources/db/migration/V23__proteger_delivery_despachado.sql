-- Apos despacho, correcoes operacionais silenciosas nao podem cancelar itens em transito.
-- Fluxo de retorno/regularizacao de entrega externa depende de regra propria, nao inferida aqui.
CREATE FUNCTION validar_alteracao_externa() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c UUID;
BEGIN
 SELECT comanda_id INTO c FROM pedidos WHERE estabelecimento_id=NEW.estabelecimento_id AND id=NEW.pedido_id;
 IF EXISTS(SELECT 1 FROM atendimentos_externos WHERE estabelecimento_id=NEW.estabelecimento_id AND comanda_id=c AND estado<>'REGISTRADO') THEN
  RAISE EXCEPTION 'Atendimento despachado ou concluido nao admite cancelamento ou alteracao operacional' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER externo_antes_solicitacao BEFORE INSERT ON solicitacoes_pedido FOR EACH ROW EXECUTE FUNCTION validar_alteracao_externa();
CREATE TRIGGER externo_antes_cancelamento BEFORE UPDATE OF estado ON itens_preparo FOR EACH ROW WHEN(NEW.estado='CANCELADO' AND OLD.estado<>'CANCELADO') EXECUTE FUNCTION validar_alteracao_externa();
