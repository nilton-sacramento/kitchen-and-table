CREATE TABLE mesas (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 estabelecimento_id UUID NOT NULL REFERENCES estabelecimentos(id),
 identificacao VARCHAR(40) NOT NULL CHECK(length(btrim(identificacao)) BETWEEN 1 AND 40),
 ativo BOOLEAN NOT NULL DEFAULT true,
 versao BIGINT NOT NULL DEFAULT 0 CHECK(versao>=0),
 criado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(id,estabelecimento_id)
);
CREATE UNIQUE INDEX ux_mesa_identificacao_unidade ON mesas(estabelecimento_id,lower(btrim(identificacao)));
CREATE TABLE sequencias_comandas (
 estabelecimento_id UUID PRIMARY KEY REFERENCES estabelecimentos(id),
 ultimo BIGINT NOT NULL CHECK(ultimo>0)
);
CREATE TABLE comandas (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 estabelecimento_id UUID NOT NULL REFERENCES estabelecimentos(id),
 codigo VARCHAR(40) NOT NULL,
 modalidade VARCHAR(10) NOT NULL CHECK(modalidade IN ('MESA','BALCAO')),
 mesa_id UUID,
 cliente VARCHAR(120),
 estado VARCHAR(15) NOT NULL DEFAULT 'ABERTA' CHECK(estado IN ('ABERTA','ENCERRADA','CANCELADA')),
 aberto_por UUID NOT NULL,
 aberto_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 finalizado_por UUID,
 finalizado_em TIMESTAMPTZ,
 justificativa VARCHAR(500),
 teve_consumo BOOLEAN NOT NULL DEFAULT false,
 possui_pendencias BOOLEAN NOT NULL DEFAULT false,
 versao BIGINT NOT NULL DEFAULT 0 CHECK(versao>=0),
 UNIQUE(id,estabelecimento_id), UNIQUE(estabelecimento_id,codigo),
 FOREIGN KEY(mesa_id,estabelecimento_id) REFERENCES mesas(id,estabelecimento_id),
 FOREIGN KEY(aberto_por,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 FOREIGN KEY(finalizado_por,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 CHECK((modalidade='MESA' AND mesa_id IS NOT NULL) OR (modalidade='BALCAO' AND mesa_id IS NULL)),
 CHECK((estado='ABERTA' AND finalizado_em IS NULL AND finalizado_por IS NULL) OR
       (estado<>'ABERTA' AND finalizado_em IS NOT NULL AND finalizado_por IS NOT NULL AND NOT teve_consumo AND NOT possui_pendencias)),
 CHECK(estado<>'CANCELADA' OR length(btrim(justificativa))>0 AND justificativa IS NOT NULL)
);
CREATE UNIQUE INDEX ux_comanda_mesa_aberta ON comandas(estabelecimento_id,mesa_id) WHERE estado='ABERTA' AND mesa_id IS NOT NULL;
CREATE INDEX ix_comanda_unidade_estado ON comandas(estabelecimento_id,estado,aberto_em DESC);
CREATE INDEX ix_comanda_historico_mesa ON comandas(estabelecimento_id,mesa_id,aberto_em DESC);
CREATE TABLE operacoes_abertura_comanda (
 estabelecimento_id UUID NOT NULL REFERENCES estabelecimentos(id), chave UUID NOT NULL,
 executor_vinculo UUID NOT NULL, requisicao_hash CHAR(64) NOT NULL, comanda_id UUID NOT NULL,
 criado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(estabelecimento_id,chave),
 FOREIGN KEY(executor_vinculo,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 FOREIGN KEY(comanda_id,estabelecimento_id) REFERENCES comandas(id,estabelecimento_id)
);
CREATE FUNCTION proteger_mesa() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Historico de mesa protegido' USING ERRCODE='23514'; END IF;
 IF NEW.estabelecimento_id<>OLD.estabelecimento_id OR NEW.id<>OLD.id THEN RAISE EXCEPTION 'Escopo imutavel' USING ERRCODE='23514'; END IF;
 IF NOT NEW.ativo AND EXISTS(SELECT 1 FROM comandas WHERE mesa_id=OLD.id AND estado='ABERTA') THEN
  RAISE EXCEPTION 'Mesa ocupada nao pode ser desativada' USING ERRCODE='23514';
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER proteger_mesa_trigger BEFORE UPDATE OR DELETE ON mesas FOR EACH ROW EXECUTE FUNCTION proteger_mesa();
CREATE FUNCTION proteger_comanda() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Historico de comanda protegido' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.modalidade='MESA' THEN
   PERFORM 1 FROM mesas WHERE id=NEW.mesa_id AND estabelecimento_id=NEW.estabelecimento_id AND ativo FOR UPDATE;
   IF NOT FOUND THEN RAISE EXCEPTION 'Mesa indisponivel' USING ERRCODE='23514'; END IF;
  END IF;
 ELSE
  IF ROW(NEW.id,NEW.estabelecimento_id,NEW.codigo,NEW.modalidade,NEW.mesa_id,NEW.aberto_por,NEW.aberto_em)
     IS DISTINCT FROM ROW(OLD.id,OLD.estabelecimento_id,OLD.codigo,OLD.modalidade,OLD.mesa_id,OLD.aberto_por,OLD.aberto_em) THEN
   RAISE EXCEPTION 'Abertura imutavel' USING ERRCODE='23514';
  END IF;
  IF OLD.estado<>'ABERTA' OR (OLD.teve_consumo AND NOT NEW.teve_consumo) THEN
   RAISE EXCEPTION 'Historico finalizado ou consumo protegido' USING ERRCODE='23514';
  END IF;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER proteger_comanda_trigger BEFORE INSERT OR UPDATE OR DELETE ON comandas FOR EACH ROW EXECUTE FUNCTION proteger_comanda();
