-- Metadados de canal sobre a comanda/pedido reais; sem novo ledger nem dados de endereço.
CREATE TABLE atendimentos_externos (
 comanda_id UUID PRIMARY KEY, estabelecimento_id UUID NOT NULL,
 tipo VARCHAR(15) NOT NULL CHECK(tipo IN ('DELIVERY','VENDA_RAPIDA')),
 origem VARCHAR(15) NOT NULL CHECK(origem IN ('TELEFONE','WHATSAPP','PLATAFORMA','OUTRO','BALCAO')),
 referencia VARCHAR(120) CHECK(length(btrim(referencia)) BETWEEN 1 AND 120),
 estado VARCHAR(25) NOT NULL DEFAULT 'REGISTRADO' CHECK(estado IN ('REGISTRADO','SAIU_PARA_ENTREGA','ENTREGUE','RETIRADO','CANCELADO')),
 versao BIGINT NOT NULL DEFAULT 0 CHECK(versao>=0),
 criado_por UUID NOT NULL, criado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 atualizado_por UUID NOT NULL, atualizado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(comanda_id,estabelecimento_id),
 FOREIGN KEY(comanda_id,estabelecimento_id) REFERENCES comandas(id,estabelecimento_id),
 FOREIGN KEY(criado_por,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 FOREIGN KEY(atualizado_por,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 CHECK((tipo='DELIVERY' AND origem<>'BALCAO') OR (tipo='VENDA_RAPIDA' AND origem='BALCAO' AND estado<>'SAIU_PARA_ENTREGA' AND estado<>'ENTREGUE'))
);
CREATE INDEX ix_externos_unidade_tipo ON atendimentos_externos(estabelecimento_id,tipo,criado_em DESC);
CREATE FUNCTION proteger_atendimento_externo() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Historico externo protegido' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NOT EXISTS(SELECT 1 FROM comandas WHERE id=NEW.comanda_id AND estabelecimento_id=NEW.estabelecimento_id AND modalidade='BALCAO' AND estado='ABERTA') THEN RAISE EXCEPTION 'Canal exige comanda de balcao aberta da unidade' USING ERRCODE='23514'; END IF;
 ELSE
  IF ROW(NEW.comanda_id,NEW.estabelecimento_id,NEW.tipo,NEW.origem,NEW.referencia,NEW.criado_por,NEW.criado_em) IS DISTINCT FROM ROW(OLD.comanda_id,OLD.estabelecimento_id,OLD.tipo,OLD.origem,OLD.referencia,OLD.criado_por,OLD.criado_em) OR NEW.versao<>OLD.versao+1 THEN RAISE EXCEPTION 'Identidade externa imutavel ou versao invalida' USING ERRCODE='23514'; END IF;
  IF NOT ((OLD.estado='REGISTRADO' AND NEW.estado IN ('SAIU_PARA_ENTREGA','ENTREGUE','RETIRADO','CANCELADO')) OR (OLD.estado='SAIU_PARA_ENTREGA' AND NEW.estado='ENTREGUE')) THEN RAISE EXCEPTION 'Transicao logistica invalida' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER externo_protegido BEFORE INSERT OR UPDATE OR DELETE ON atendimentos_externos FOR EACH ROW EXECUTE FUNCTION proteger_atendimento_externo();
CREATE FUNCTION validar_quitacao_externa() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM atendimentos_externos WHERE comanda_id=NEW.comanda_id AND estabelecimento_id=NEW.estabelecimento_id AND estado NOT IN ('ENTREGUE','RETIRADO','CANCELADO')) THEN RAISE EXCEPTION 'Conclua a logistica antes da quitacao financeira' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER externo_antes_quitacao BEFORE INSERT ON quitacoes_comanda FOR EACH ROW EXECUTE FUNCTION validar_quitacao_externa();
CREATE FUNCTION validar_novo_pedido_externo() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM comandas WHERE id=NEW.comanda_id AND estabelecimento_id=NEW.estabelecimento_id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM atendimentos_externos WHERE comanda_id=NEW.comanda_id AND estabelecimento_id=NEW.estabelecimento_id AND estado<>'REGISTRADO') THEN RAISE EXCEPTION 'Atendimento despachado ou concluido nao aceita novo pedido' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER externo_antes_pedido BEFORE INSERT ON pedidos FOR EACH ROW EXECUTE FUNCTION validar_novo_pedido_externo();
