-- Evolucao explicita: manter consumo/pendencias historicos ao encerrar com quitacao valida.
CREATE FUNCTION totais_financeiros_comanda(u UUID,c UUID) RETURNS TABLE(consumo NUMERIC,desconto NUMERIC,pago NUMERIC) LANGUAGE sql AS $$
 SELECT COALESCE((SELECT sum(l.total_centavos) FROM linhas_pedido l JOIN pedidos p ON p.id=l.pedido_id AND p.estabelecimento_id=l.estabelecimento_id WHERE p.estabelecimento_id=u AND p.comanda_id=c AND EXISTS(SELECT 1 FROM itens_preparo i WHERE i.estabelecimento_id=u AND i.pedido_id=p.id AND i.estado<>'CANCELADO')),0),
 COALESCE((SELECT sum(CASE WHEN tipo='DESCONTO' THEN valor_centavos WHEN tipo='DESCONTO_ESTORNO' THEN -valor_centavos ELSE 0 END) FROM lancamentos_financeiros WHERE estabelecimento_id=u AND comanda_id=c),0),
 COALESCE((SELECT sum(CASE WHEN tipo='PAGAMENTO' THEN valor_centavos WHEN tipo='ESTORNO' THEN -valor_centavos ELSE 0 END) FROM lancamentos_financeiros WHERE estabelecimento_id=u AND comanda_id=c),0)
$$;
CREATE FUNCTION validar_saldo_financeiro() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s RECORD;
BEGIN
 IF NEW.comanda_id IS NULL THEN RETURN NEW; END IF;
 SELECT * INTO s FROM totais_financeiros_comanda(NEW.estabelecimento_id,NEW.comanda_id);
 IF NEW.tipo='PAGAMENTO' THEN s.pago:=s.pago+NEW.valor_centavos;
 ELSIF NEW.tipo='ESTORNO' THEN s.pago:=s.pago-NEW.valor_centavos;
 ELSIF NEW.tipo='DESCONTO' THEN s.desconto:=s.desconto+NEW.valor_centavos;
 ELSIF NEW.tipo='DESCONTO_ESTORNO' THEN s.desconto:=s.desconto-NEW.valor_centavos; END IF;
 IF s.pago<0 OR s.desconto<0 OR s.pago+s.desconto>s.consumo THEN RAISE EXCEPTION 'Saldo financeiro inconsistente' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
-- Ordenacao dos triggers: validacao/locks do turno e comanda antes do saldo.
CREATE TRIGGER zz_saldo_financeiro_validar BEFORE INSERT ON lancamentos_financeiros FOR EACH ROW EXECUTE FUNCTION validar_saldo_financeiro();
CREATE FUNCTION validar_quitacao_comanda() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s RECORD;
BEGIN
 PERFORM 1 FROM configuracoes_caixa WHERE estabelecimento_id=NEW.estabelecimento_id FOR SHARE;
 PERFORM 1 FROM turnos_caixa WHERE id=NEW.turno_id AND estabelecimento_id=NEW.estabelecimento_id AND estado='ABERTO' FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Quitacao exige turno aberto' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM comandas WHERE id=NEW.comanda_id AND estabelecimento_id=NEW.estabelecimento_id AND estado='ABERTA' FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Quitacao exige comanda aberta' USING ERRCODE='23514'; END IF;
 PERFORM id FROM pedidos WHERE estabelecimento_id=NEW.estabelecimento_id AND comanda_id=NEW.comanda_id ORDER BY id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Comanda sem pedidos utiliza encerramento operacional' USING ERRCODE='23514'; END IF;
 SELECT * INTO s FROM totais_financeiros_comanda(NEW.estabelecimento_id,NEW.comanda_id);
 IF s.consumo<>NEW.consumo_centavos OR s.desconto<>NEW.desconto_centavos OR s.pago<>NEW.pago_centavos OR s.consumo-s.desconto<>s.pago THEN RAISE EXCEPTION 'Conta nao esta quitada' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM pedidos p JOIN itens_preparo i ON i.pedido_id=p.id AND i.estabelecimento_id=p.estabelecimento_id WHERE p.estabelecimento_id=NEW.estabelecimento_id AND p.comanda_id=NEW.comanda_id AND i.estado NOT IN ('ENTREGUE','CANCELADO')) OR
 EXISTS(SELECT 1 FROM pedidos p JOIN solicitacoes_pedido r ON r.pedido_id=p.id AND r.estabelecimento_id=p.estabelecimento_id WHERE p.estabelecimento_id=NEW.estabelecimento_id AND p.comanda_id=NEW.comanda_id AND r.estado='PENDENTE') OR
 EXISTS(SELECT 1 FROM lancamentos_financeiros l WHERE l.estabelecimento_id=NEW.estabelecimento_id AND l.comanda_id=NEW.comanda_id AND l.tipo='ESTORNO' AND l.natureza='ESTORNO_PAGAMENTO' AND l.meio<>'DINHEIRO' AND NOT EXISTS(SELECT 1 FROM confirmacoes_estorno e WHERE e.estorno_id=l.id AND e.estabelecimento_id=l.estabelecimento_id)) THEN
 RAISE EXCEPTION 'Comanda possui pendencias operacionais ou externas' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validar_quitacao BEFORE INSERT ON quitacoes_comanda FOR EACH ROW EXECUTE FUNCTION validar_quitacao_comanda();
ALTER TABLE comandas ADD COLUMN quitacao_id UUID;
ALTER TABLE comandas ADD CONSTRAINT fk_comanda_quitacao_unidade FOREIGN KEY(quitacao_id,estabelecimento_id) REFERENCES quitacoes_comanda(id,estabelecimento_id);
DO $$ DECLARE constraint_name TEXT; found_count INTEGER:=0; BEGIN
 FOR constraint_name IN SELECT conname FROM pg_constraint WHERE conrelid='comandas'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%finalizado_em%' LOOP
 EXECUTE format('ALTER TABLE comandas DROP CONSTRAINT %I',constraint_name);found_count:=found_count+1;END LOOP;
 IF found_count<>1 THEN RAISE EXCEPTION 'Estrutura historica de finalizacao inesperada'; END IF;
END $$;
ALTER TABLE comandas ADD CONSTRAINT ck_comanda_finalizacao_financeira CHECK(
 (estado='ABERTA' AND finalizado_em IS NULL AND finalizado_por IS NULL AND quitacao_id IS NULL) OR
 (estado<>'ABERTA' AND finalizado_em IS NOT NULL AND finalizado_por IS NOT NULL AND ((NOT teve_consumo AND NOT possui_pendencias) OR (estado='ENCERRADA' AND quitacao_id IS NOT NULL)))
);
CREATE OR REPLACE FUNCTION proteger_comanda() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Historico de comanda protegido' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.quitacao_id IS NOT NULL THEN RAISE EXCEPTION 'Abertura nao pode conter quitacao' USING ERRCODE='23514'; END IF;
  IF NEW.modalidade='MESA' THEN
   PERFORM 1 FROM mesas WHERE id=NEW.mesa_id AND estabelecimento_id=NEW.estabelecimento_id AND ativo FOR UPDATE;
   IF NOT FOUND THEN RAISE EXCEPTION 'Mesa indisponivel' USING ERRCODE='23514'; END IF;
  END IF;
 ELSE
  IF ROW(NEW.id,NEW.estabelecimento_id,NEW.codigo,NEW.modalidade,NEW.mesa_id,NEW.aberto_por,NEW.aberto_em) IS DISTINCT FROM ROW(OLD.id,OLD.estabelecimento_id,OLD.codigo,OLD.modalidade,OLD.mesa_id,OLD.aberto_por,OLD.aberto_em) THEN RAISE EXCEPTION 'Abertura imutavel' USING ERRCODE='23514'; END IF;
  IF OLD.estado<>'ABERTA' OR (OLD.teve_consumo AND NOT NEW.teve_consumo) OR (OLD.possui_pendencias AND NOT NEW.possui_pendencias) THEN RAISE EXCEPTION 'Historico finalizado ou consumo protegido' USING ERRCODE='23514'; END IF;
  IF NEW.quitacao_id IS NOT NULL AND (NEW.estado<>'ENCERRADA' OR NOT EXISTS(SELECT 1 FROM quitacoes_comanda q WHERE q.id=NEW.quitacao_id AND q.estabelecimento_id=NEW.estabelecimento_id AND q.comanda_id=NEW.id AND q.executor=NEW.finalizado_por)) THEN RAISE EXCEPTION 'Quitacao invalida para esta comanda' USING ERRCODE='23514'; END IF;
 END IF; RETURN NEW;
END $$;
CREATE FUNCTION proteger_turno_caixa() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Historico do caixa protegido' USING ERRCODE='23514'; END IF;
 IF ROW(NEW.id,NEW.estabelecimento_id,NEW.aberto_por,NEW.aberto_em,NEW.valor_inicial_centavos) IS DISTINCT FROM ROW(OLD.id,OLD.estabelecimento_id,OLD.aberto_por,OLD.aberto_em,OLD.valor_inicial_centavos) THEN RAISE EXCEPTION 'Abertura de caixa imutavel' USING ERRCODE='23514'; END IF;
 IF OLD.estado='FECHADO' THEN
  IF NEW.estado<>'ABERTO' OR NEW.ciclo<>OLD.ciclo+1 THEN RAISE EXCEPTION 'Turno fechado exige reabertura controlada' USING ERRCODE='23514'; END IF;
 ELSIF NEW.estado='FECHADO' THEN
  IF NEW.ciclo<>OLD.ciclo OR NOT EXISTS(SELECT 1 FROM relatorios_fechamento r JOIN conferencias_caixa c ON c.id=r.conferencia_id AND c.estabelecimento_id=r.estabelecimento_id WHERE r.turno_id=OLD.id AND r.estabelecimento_id=OLD.estabelecimento_id AND r.ciclo=OLD.ciclo AND c.versao_turno=OLD.versao) THEN RAISE EXCEPTION 'Fechamento exige conferencia e relatorio preservados' USING ERRCODE='23514'; END IF;
 ELSIF NEW.ciclo<>OLD.ciclo THEN RAISE EXCEPTION 'Ciclo de caixa invalido' USING ERRCODE='23514'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER turno_caixa_protegido BEFORE UPDATE OR DELETE ON turnos_caixa FOR EACH ROW EXECUTE FUNCTION proteger_turno_caixa();
CREATE FUNCTION proteger_autorizacao_financeira() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Autorizacao historica protegida' USING ERRCODE='23514'; END IF;
 IF ROW(NEW.id,NEW.estabelecimento_id,NEW.turno_id,NEW.solicitante,NEW.credencial_versao,NEW.tipo,NEW.entidade_id,NEW.requisicao_hash,NEW.dados,NEW.motivo,NEW.criado_em,NEW.expira_em) IS DISTINCT FROM ROW(OLD.id,OLD.estabelecimento_id,OLD.turno_id,OLD.solicitante,OLD.credencial_versao,OLD.tipo,OLD.entidade_id,OLD.requisicao_hash,OLD.dados,OLD.motivo,OLD.criado_em,OLD.expira_em) THEN RAISE EXCEPTION 'Escopo e conteudo da autorizacao sao imutaveis' USING ERRCODE='23514'; END IF;
 IF NOT ((OLD.estado='PENDENTE' AND NEW.estado IN ('APROVADA','RECUSADA','REVOGADA')) OR (OLD.estado='APROVADA' AND NEW.estado IN ('CONSUMIDA','REVOGADA'))) OR NEW.versao<>OLD.versao+1 THEN RAISE EXCEPTION 'Transicao de autorizacao invalida' USING ERRCODE='23514'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER autorizacao_financeira_protegida BEFORE UPDATE OR DELETE ON autorizacoes_financeiras FOR EACH ROW EXECUTE FUNCTION proteger_autorizacao_financeira();
CREATE FUNCTION proteger_operadores_caixa() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Historico de operador protegido' USING ERRCODE='23514'; END IF;
 IF ROW(NEW.turno_id,NEW.estabelecimento_id,NEW.vinculo_id) IS DISTINCT FROM ROW(OLD.turno_id,OLD.estabelecimento_id,OLD.vinculo_id) THEN RAISE EXCEPTION 'Escopo de operador imutavel' USING ERRCODE='23514'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER operadores_caixa_protegidos BEFORE UPDATE OR DELETE ON operadores_caixa FOR EACH ROW EXECUTE FUNCTION proteger_operadores_caixa();
CREATE TRIGGER configuracao_caixa_nao_apagar BEFORE DELETE ON configuracoes_caixa FOR EACH ROW EXECUTE FUNCTION financeiro_imutavel();
CREATE FUNCTION bloquear_alteracao_pedido_financeiro() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE c UUID;s RECORD;BEGIN
 SELECT comanda_id INTO c FROM pedidos WHERE id=NEW.pedido_id AND estabelecimento_id=NEW.estabelecimento_id;
 SELECT * INTO s FROM totais_financeiros_comanda(NEW.estabelecimento_id,c);
 IF s.pago<>0 OR s.desconto<>0 THEN RAISE EXCEPTION 'Pedido possui conta movimentada; regularize os lancamentos antes de alterar' USING ERRCODE='23514'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER pedido_financeiro_antes_solicitar BEFORE INSERT ON solicitacoes_pedido FOR EACH ROW EXECUTE FUNCTION bloquear_alteracao_pedido_financeiro();
CREATE TRIGGER pedido_financeiro_antes_cancelar BEFORE UPDATE OF estado ON itens_preparo FOR EACH ROW WHEN(NEW.estado='CANCELADO' AND OLD.estado<>'CANCELADO') EXECUTE FUNCTION bloquear_alteracao_pedido_financeiro();

CREATE FUNCTION validar_confirmacao_estorno() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE t UUID;BEGIN
 SELECT turno_id INTO t FROM lancamentos_financeiros WHERE id=NEW.estorno_id AND estabelecimento_id=NEW.estabelecimento_id AND tipo='ESTORNO' AND natureza='ESTORNO_PAGAMENTO' AND meio<>'DINHEIRO';
 IF NOT FOUND THEN RAISE EXCEPTION 'Confirmacao exige estorno externo real' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM turnos_caixa WHERE estabelecimento_id=NEW.estabelecimento_id AND id=t AND estado='ABERTO' FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Confirmacao exige turno aberto ou reaberto' USING ERRCODE='23514'; END IF;
 RETURN NEW;END $$;
CREATE TRIGGER confirmacao_estorno_validar BEFORE INSERT ON confirmacoes_estorno FOR EACH ROW EXECUTE FUNCTION validar_confirmacao_estorno();
