-- Evolucao incremental: preservar a V20 ja exercitada e congelar o carrinho da venda rapida.
-- Substituicao autorizada continua no fluxo2.6, apos regularizacao financeira2.7.
CREATE OR REPLACE FUNCTION validar_novo_pedido_externo() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM comandas WHERE id=NEW.comanda_id AND estabelecimento_id=NEW.estabelecimento_id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM atendimentos_externos e WHERE e.comanda_id=NEW.comanda_id AND e.estabelecimento_id=NEW.estabelecimento_id
   AND (e.estado<>'REGISTRADO' OR (e.tipo='VENDA_RAPIDA' AND NEW.substitui_id IS NULL AND EXISTS(SELECT 1 FROM pedidos p WHERE p.comanda_id=NEW.comanda_id AND p.estabelecimento_id=NEW.estabelecimento_id)))) THEN
  RAISE EXCEPTION 'Atendimento despachado, concluido ou venda rapida confirmada nao aceita novo pedido' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
