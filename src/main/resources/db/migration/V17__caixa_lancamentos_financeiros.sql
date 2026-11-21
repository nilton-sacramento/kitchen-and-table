-- Somente estruturas novas. V1-V16 imutaveis; nenhum saldo/credencial preenchido.
CREATE TABLE configuracoes_caixa (
 estabelecimento_id UUID PRIMARY KEY REFERENCES estabelecimentos(id), limite_operadores INTEGER CHECK(limite_operadores>0),
 desconto_limite_centavos BIGINT NOT NULL CHECK(desconto_limite_centavos BETWEEN 0 AND 999999999999999), desconto_limite_bps INTEGER NOT NULL CHECK(desconto_limite_bps BETWEEN 0 AND 10000),
 sangria_limite_centavos BIGINT NOT NULL CHECK(sangria_limite_centavos BETWEEN 0 AND 999999999999999), divergencia_limite_centavos BIGINT NOT NULL CHECK(divergencia_limite_centavos BETWEEN 0 AND 999999999999999),
 versao BIGINT NOT NULL DEFAULT 0 CHECK(versao>=0), atualizado_por UUID NOT NULL, atualizado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(atualizado_por,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id)
);
CREATE TABLE turnos_caixa (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), estabelecimento_id UUID NOT NULL REFERENCES configuracoes_caixa(estabelecimento_id),
 estado VARCHAR(10) NOT NULL DEFAULT 'ABERTO' CHECK(estado IN ('ABERTO','FECHADO')), responsavel_id UUID NOT NULL, aberto_por UUID NOT NULL,
 aberto_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), valor_inicial_centavos BIGINT NOT NULL CHECK(valor_inicial_centavos BETWEEN 0 AND 999999999999999),
 versao BIGINT NOT NULL DEFAULT 0 CHECK(versao>=0), ciclo INTEGER NOT NULL DEFAULT 1 CHECK(ciclo>0), fechado_em TIMESTAMPTZ, fechado_por UUID,
 UNIQUE(id,estabelecimento_id), FOREIGN KEY(responsavel_id,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 FOREIGN KEY(aberto_por,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id), FOREIGN KEY(fechado_por,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 CHECK((estado='ABERTO' AND fechado_em IS NULL AND fechado_por IS NULL) OR (estado='FECHADO' AND fechado_em IS NOT NULL AND fechado_por IS NOT NULL))
);
CREATE UNIQUE INDEX ux_turno_aberto_unidade ON turnos_caixa(estabelecimento_id) WHERE estado='ABERTO';
CREATE INDEX ix_turnos_unidade_data ON turnos_caixa(estabelecimento_id,aberto_em DESC);
CREATE TABLE operadores_caixa (
 turno_id UUID NOT NULL, estabelecimento_id UUID NOT NULL, vinculo_id UUID NOT NULL, ativo BOOLEAN NOT NULL DEFAULT true,
 PRIMARY KEY(turno_id,vinculo_id), FOREIGN KEY(turno_id,estabelecimento_id) REFERENCES turnos_caixa(id,estabelecimento_id),
 FOREIGN KEY(vinculo_id,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id)
);
CREATE TABLE autorizacoes_financeiras (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), estabelecimento_id UUID NOT NULL, turno_id UUID NOT NULL, solicitante UUID NOT NULL, credencial_versao BIGINT NOT NULL,
 tipo VARCHAR(30) NOT NULL CHECK(tipo IN ('DESCONTO','DESCONTO_ESTORNO','SANGRIA','ESTORNO','FECHAMENTO')), entidade_id UUID,
 requisicao_hash CHAR(64) NOT NULL, dados JSONB NOT NULL, motivo VARCHAR(500) NOT NULL CHECK(length(btrim(motivo))>0),
 estado VARCHAR(12) NOT NULL DEFAULT 'PENDENTE' CHECK(estado IN ('PENDENTE','APROVADA','RECUSADA','REVOGADA','CONSUMIDA')),
 criado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), expira_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()+interval '10 minutes',
 autorizador UUID, motivo_decisao VARCHAR(500), decidido_em TIMESTAMPTZ, consumida_em TIMESTAMPTZ, versao BIGINT NOT NULL DEFAULT 0,
 UNIQUE(id,estabelecimento_id), FOREIGN KEY(turno_id,estabelecimento_id) REFERENCES turnos_caixa(id,estabelecimento_id),
 FOREIGN KEY(solicitante,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id), FOREIGN KEY(autorizador,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 CHECK(expira_em>criado_em), CHECK(estado NOT IN ('APROVADA','CONSUMIDA') OR (autorizador IS NOT NULL AND decidido_em IS NOT NULL)),
 CHECK(estado<>'CONSUMIDA' OR consumida_em IS NOT NULL)
);
CREATE INDEX ix_autorizacoes_fin_unidade ON autorizacoes_financeiras(estabelecimento_id,criado_em DESC);
CREATE TABLE lancamentos_financeiros (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), estabelecimento_id UUID NOT NULL, turno_id UUID NOT NULL, comanda_id UUID,
 tipo VARCHAR(20) NOT NULL CHECK(tipo IN ('ABERTURA','PAGAMENTO','ESTORNO','DESCONTO','DESCONTO_ESTORNO','SANGRIA','REFORCO')),
 meio VARCHAR(10) CHECK(meio IN ('DINHEIRO','PIX','DEBITO','CREDITO')), valor_centavos BIGINT NOT NULL CHECK(valor_centavos BETWEEN 0 AND 999999999999999),
 recebido_centavos BIGINT CHECK(recebido_centavos BETWEEN 0 AND 999999999999999),
 troco_centavos BIGINT GENERATED ALWAYS AS (CASE WHEN tipo='PAGAMENTO' AND meio='DINHEIRO' THEN recebido_centavos-valor_centavos ELSE 0 END) STORED,
 natureza VARCHAR(30), original_id UUID, executor UUID NOT NULL, executor_nome VARCHAR(150) NOT NULL, motivo VARCHAR(500), autorizacao_id UUID,
 ocorrido_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE(id,estabelecimento_id),
 FOREIGN KEY(turno_id,estabelecimento_id) REFERENCES turnos_caixa(id,estabelecimento_id), FOREIGN KEY(comanda_id,estabelecimento_id) REFERENCES comandas(id,estabelecimento_id),
 FOREIGN KEY(original_id,estabelecimento_id) REFERENCES lancamentos_financeiros(id,estabelecimento_id), FOREIGN KEY(executor,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 FOREIGN KEY(autorizacao_id,estabelecimento_id) REFERENCES autorizacoes_financeiras(id,estabelecimento_id),
 CHECK((tipo='ESTORNO' AND natureza IN ('CORRECAO_LANCAMENTO','ESTORNO_PAGAMENTO')) OR (tipo='DESCONTO_ESTORNO' AND natureza='REVERSAO_DESCONTO') OR (tipo NOT IN ('ESTORNO','DESCONTO_ESTORNO') AND natureza IS NULL)),
 CHECK((tipo IN ('ESTORNO','DESCONTO_ESTORNO'))=(natureza IS NOT NULL)),
 CHECK((tipo='PAGAMENTO' AND meio='DINHEIRO')=(recebido_centavos IS NOT NULL)),
 CHECK(tipo NOT IN ('ABERTURA','SANGRIA','REFORCO') OR meio='DINHEIRO'),
 CHECK(tipo='ABERTURA' OR valor_centavos>0), CHECK((tipo IN ('PAGAMENTO','ESTORNO','DESCONTO','DESCONTO_ESTORNO'))=(comanda_id IS NOT NULL)),
 CHECK((tipo IN ('ESTORNO','DESCONTO_ESTORNO'))=(original_id IS NOT NULL)), CHECK((tipo IN ('DESCONTO','DESCONTO_ESTORNO'))=(meio IS NULL)),
 CHECK((tipo='PAGAMENTO' AND meio='DINHEIRO' AND recebido_centavos>=valor_centavos) OR (NOT(tipo='PAGAMENTO' AND meio='DINHEIRO') AND recebido_centavos IS NULL)),
 CHECK(tipo IN ('ABERTURA','PAGAMENTO') OR (motivo IS NOT NULL AND length(btrim(motivo))>0))
);
CREATE UNIQUE INDEX ux_compensacao_unica ON lancamentos_financeiros(estabelecimento_id,original_id) WHERE original_id IS NOT NULL;
CREATE UNIQUE INDEX ux_abertura_turno_unica ON lancamentos_financeiros(turno_id) WHERE tipo='ABERTURA';
CREATE INDEX ix_lancamento_comanda ON lancamentos_financeiros(estabelecimento_id,comanda_id,ocorrido_em);
CREATE INDEX ix_lancamento_turno ON lancamentos_financeiros(estabelecimento_id,turno_id,ocorrido_em);
CREATE INDEX ix_lancamento_executor ON lancamentos_financeiros(estabelecimento_id,executor,ocorrido_em);
CREATE TABLE confirmacoes_estorno (
 estorno_id UUID PRIMARY KEY, estabelecimento_id UUID NOT NULL, executor UUID NOT NULL, confirmado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(estorno_id,estabelecimento_id) REFERENCES lancamentos_financeiros(id,estabelecimento_id), FOREIGN KEY(executor,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id)
);
CREATE TABLE conferencias_caixa (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), estabelecimento_id UUID NOT NULL, turno_id UUID NOT NULL, versao_turno BIGINT NOT NULL,
 esperado_centavos BIGINT NOT NULL CHECK(esperado_centavos>=0), informado_centavos BIGINT NOT NULL CHECK(informado_centavos BETWEEN 0 AND 999999999999999),
 diferenca_centavos BIGINT GENERATED ALWAYS AS (informado_centavos-esperado_centavos) STORED, motivo VARCHAR(500), executor UUID NOT NULL, criado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(id,estabelecimento_id), FOREIGN KEY(turno_id,estabelecimento_id) REFERENCES turnos_caixa(id,estabelecimento_id), FOREIGN KEY(executor,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 CHECK(informado_centavos=esperado_centavos OR (motivo IS NOT NULL AND length(btrim(motivo))>0))
);
CREATE TABLE relatorios_fechamento (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), estabelecimento_id UUID NOT NULL, turno_id UUID NOT NULL, ciclo INTEGER NOT NULL, conferencia_id UUID NOT NULL,
 executor UUID NOT NULL, autorizacao_id UUID, dados JSONB NOT NULL, criado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE(turno_id,ciclo), UNIQUE(id,estabelecimento_id),
 FOREIGN KEY(turno_id,estabelecimento_id) REFERENCES turnos_caixa(id,estabelecimento_id), FOREIGN KEY(conferencia_id,estabelecimento_id) REFERENCES conferencias_caixa(id,estabelecimento_id),
 FOREIGN KEY(executor,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id), FOREIGN KEY(autorizacao_id,estabelecimento_id) REFERENCES autorizacoes_financeiras(id,estabelecimento_id)
);
CREATE TABLE quitacoes_comanda (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), estabelecimento_id UUID NOT NULL, turno_id UUID NOT NULL, comanda_id UUID NOT NULL,
 consumo_centavos BIGINT NOT NULL CHECK(consumo_centavos>=0), desconto_centavos BIGINT NOT NULL CHECK(desconto_centavos>=0), pago_centavos BIGINT NOT NULL CHECK(pago_centavos>=0),
 executor UUID NOT NULL, criado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE(comanda_id), UNIQUE(id,estabelecimento_id),
 FOREIGN KEY(turno_id,estabelecimento_id) REFERENCES turnos_caixa(id,estabelecimento_id), FOREIGN KEY(comanda_id,estabelecimento_id) REFERENCES comandas(id,estabelecimento_id),
 FOREIGN KEY(executor,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id), CHECK(consumo_centavos-desconto_centavos=pago_centavos)
);
CREATE TABLE operacoes_financeiras (
 estabelecimento_id UUID NOT NULL REFERENCES estabelecimentos(id), chave UUID NOT NULL, executor UUID NOT NULL, tipo VARCHAR(30) NOT NULL,
 requisicao_hash CHAR(64) NOT NULL, resultado JSONB NOT NULL, criado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(estabelecimento_id,chave),
 FOREIGN KEY(executor,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id)
);
CREATE FUNCTION financeiro_imutavel() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Lancamento ou evidencia financeira imutavel' USING ERRCODE='23514'; END $$;
CREATE TRIGGER lancamentos_imutaveis BEFORE UPDATE OR DELETE ON lancamentos_financeiros FOR EACH ROW EXECUTE FUNCTION financeiro_imutavel();
CREATE TRIGGER conferencias_imutaveis BEFORE UPDATE OR DELETE ON conferencias_caixa FOR EACH ROW EXECUTE FUNCTION financeiro_imutavel();
CREATE TRIGGER fechamento_imutavel BEFORE UPDATE OR DELETE ON relatorios_fechamento FOR EACH ROW EXECUTE FUNCTION financeiro_imutavel();
CREATE TRIGGER quitacoes_imutaveis BEFORE UPDATE OR DELETE ON quitacoes_comanda FOR EACH ROW EXECUTE FUNCTION financeiro_imutavel();
CREATE TRIGGER confirmacoes_estorno_imutaveis BEFORE UPDATE OR DELETE ON confirmacoes_estorno FOR EACH ROW EXECUTE FUNCTION financeiro_imutavel();
CREATE TRIGGER operacoes_financeiras_imutaveis BEFORE UPDATE OR DELETE ON operacoes_financeiras FOR EACH ROW EXECUTE FUNCTION financeiro_imutavel();
CREATE FUNCTION validar_lancamento_financeiro() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t turnos_caixa; o lancamentos_financeiros; saldo NUMERIC;
BEGIN
 PERFORM 1 FROM configuracoes_caixa WHERE estabelecimento_id=NEW.estabelecimento_id FOR SHARE;
 SELECT * INTO t FROM turnos_caixa WHERE id=NEW.turno_id AND estabelecimento_id=NEW.estabelecimento_id FOR UPDATE;
 IF NOT FOUND OR t.estado<>'ABERTO' THEN RAISE EXCEPTION 'Turno fechado ou inexistente' USING ERRCODE='23514'; END IF;
 IF NEW.comanda_id IS NOT NULL THEN
  PERFORM 1 FROM comandas WHERE id=NEW.comanda_id AND estabelecimento_id=NEW.estabelecimento_id AND estado='ABERTA' FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Comanda nao esta aberta nesta unidade' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.original_id IS NOT NULL THEN
  SELECT * INTO o FROM lancamentos_financeiros WHERE id=NEW.original_id AND estabelecimento_id=NEW.estabelecimento_id;
  IF NOT FOUND OR o.turno_id<>NEW.turno_id OR o.comanda_id<>NEW.comanda_id OR o.valor_centavos<>NEW.valor_centavos OR
   (NEW.tipo='ESTORNO' AND (o.tipo<>'PAGAMENTO' OR o.meio IS DISTINCT FROM NEW.meio)) OR (NEW.tipo='DESCONTO_ESTORNO' AND o.tipo<>'DESCONTO') THEN
   RAISE EXCEPTION 'Compensacao deve corresponder ao original' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.tipo='SANGRIA' OR (NEW.tipo='ESTORNO' AND NEW.meio='DINHEIRO') THEN
  SELECT COALESCE(sum(CASE WHEN tipo IN ('ABERTURA','PAGAMENTO','REFORCO') AND meio='DINHEIRO' THEN valor_centavos WHEN tipo IN ('SANGRIA','ESTORNO') AND meio='DINHEIRO' THEN -valor_centavos ELSE 0 END),0) INTO saldo FROM lancamentos_financeiros WHERE turno_id=NEW.turno_id AND estabelecimento_id=NEW.estabelecimento_id;
  IF saldo<NEW.valor_centavos THEN RAISE EXCEPTION 'Dinheiro insuficiente no caixa' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER lancamento_financeiro_validar BEFORE INSERT ON lancamentos_financeiros FOR EACH ROW EXECUTE FUNCTION validar_lancamento_financeiro();
