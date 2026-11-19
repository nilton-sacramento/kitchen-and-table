CREATE TABLE sequencias_pedidos (estabelecimento_id UUID PRIMARY KEY REFERENCES estabelecimentos(id), ultimo BIGINT NOT NULL CHECK(ultimo>0));
CREATE TABLE pedidos (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), estabelecimento_id UUID NOT NULL, comanda_id UUID NOT NULL, codigo VARCHAR(40) NOT NULL,
 aberto_por UUID NOT NULL, aberto_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), tudo_junto BOOLEAN NOT NULL DEFAULT false,
 versao BIGINT NOT NULL DEFAULT 0 CHECK(versao>=0), substitui_id UUID,
 UNIQUE(id,estabelecimento_id), UNIQUE(estabelecimento_id,codigo),
 FOREIGN KEY(comanda_id,estabelecimento_id) REFERENCES comandas(id,estabelecimento_id),
 FOREIGN KEY(aberto_por,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 FOREIGN KEY(substitui_id,estabelecimento_id) REFERENCES pedidos(id,estabelecimento_id)
);
CREATE TABLE linhas_pedido (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), estabelecimento_id UUID NOT NULL, pedido_id UUID NOT NULL, produto_id UUID NOT NULL,
 nome VARCHAR(120) NOT NULL, tipo VARCHAR(10) NOT NULL CHECK(tipo IN ('SIMPLES','COMBO')), quantidade INTEGER NOT NULL CHECK(quantidade BETWEEN 1 AND 1000),
 preco_unitario_centavos BIGINT NOT NULL CHECK(preco_unitario_centavos BETWEEN 0 AND 9999999999),
 total_centavos BIGINT GENERATED ALWAYS AS (preco_unitario_centavos*quantidade) STORED, observacao VARCHAR(500), composicao JSONB NOT NULL,
 UNIQUE(id,estabelecimento_id), UNIQUE(id,pedido_id,estabelecimento_id),
 FOREIGN KEY(pedido_id,estabelecimento_id) REFERENCES pedidos(id,estabelecimento_id),
 FOREIGN KEY(produto_id,estabelecimento_id) REFERENCES produtos_cardapio(id,estabelecimento_id)
);
CREATE TABLE itens_preparo (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), estabelecimento_id UUID NOT NULL, pedido_id UUID NOT NULL, linha_id UUID NOT NULL, produto_id UUID NOT NULL,
 nome VARCHAR(120) NOT NULL, setor VARCHAR(10) NOT NULL CHECK(setor IN ('COZINHA','BAR')), quantidade INTEGER NOT NULL CHECK(quantidade>0), observacao VARCHAR(500),
 estado VARCHAR(20) NOT NULL DEFAULT 'AGUARDANDO' CHECK(estado IN ('AGUARDANDO','EM_PREPARO','PRONTO','ENTREGUE','CANCELADO')),
 versao BIGINT NOT NULL DEFAULT 0, atualizado_por UUID NOT NULL, atualizado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), entregue_em TIMESTAMPTZ,
 UNIQUE(id,estabelecimento_id), FOREIGN KEY(linha_id,pedido_id,estabelecimento_id) REFERENCES linhas_pedido(id,pedido_id,estabelecimento_id),
 FOREIGN KEY(produto_id,estabelecimento_id) REFERENCES produtos_cardapio(id,estabelecimento_id),
 FOREIGN KEY(atualizado_por,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 CHECK(estado<>'ENTREGUE' OR entregue_em IS NOT NULL)
);
CREATE INDEX ix_itens_preparo_fila ON itens_preparo(estabelecimento_id,setor,estado,atualizado_em);
CREATE INDEX ix_pedidos_comanda ON pedidos(estabelecimento_id,comanda_id,aberto_em);
CREATE TABLE operacoes_pedido (
 estabelecimento_id UUID NOT NULL, chave UUID NOT NULL, executor UUID NOT NULL, requisicao_hash CHAR(64) NOT NULL, pedido_id UUID NOT NULL,
 PRIMARY KEY(estabelecimento_id,chave), FOREIGN KEY(executor,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 FOREIGN KEY(pedido_id,estabelecimento_id) REFERENCES pedidos(id,estabelecimento_id)
);
CREATE TABLE solicitacoes_pedido (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), estabelecimento_id UUID NOT NULL, pedido_id UUID NOT NULL,
 tipo VARCHAR(15) NOT NULL CHECK(tipo IN ('ALTERACAO','CANCELAMENTO')), motivo VARCHAR(500) NOT NULL CHECK(length(btrim(motivo))>0), proposta JSONB, proposta_itens JSONB NOT NULL DEFAULT '[]'::jsonb,
 solicitado_por UUID NOT NULL, credencial_versao BIGINT NOT NULL, criado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 estado VARCHAR(15) NOT NULL DEFAULT 'PENDENTE' CHECK(estado IN ('PENDENTE','RECUSADA','EXECUTADA')), resultado_motivo VARCHAR(500), novo_pedido_id UUID,
 chave UUID NOT NULL, requisicao_hash CHAR(64) NOT NULL, UNIQUE(id,estabelecimento_id), UNIQUE(estabelecimento_id,chave),
 FOREIGN KEY(pedido_id,estabelecimento_id) REFERENCES pedidos(id,estabelecimento_id),
 FOREIGN KEY(solicitado_por,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 FOREIGN KEY(novo_pedido_id,estabelecimento_id) REFERENCES pedidos(id,estabelecimento_id),
 CHECK((tipo='ALTERACAO' AND proposta IS NOT NULL) OR (tipo='CANCELAMENTO' AND proposta IS NULL))
);
CREATE UNIQUE INDEX ux_solicitacao_pedido_pendente ON solicitacoes_pedido(estabelecimento_id,pedido_id) WHERE estado='PENDENTE';
CREATE TABLE decisoes_pedido (
 estabelecimento_id UUID NOT NULL, solicitacao_id UUID NOT NULL, setor VARCHAR(10) NOT NULL CHECK(setor IN ('COZINHA','BAR')),
 estado VARCHAR(10) NOT NULL DEFAULT 'PENDENTE' CHECK(estado IN ('PENDENTE','ACEITA','RECUSADA')),
 executor UUID, credencial_versao BIGINT, motivo VARCHAR(500), versao BIGINT NOT NULL DEFAULT 0, decidido_em TIMESTAMPTZ,
 PRIMARY KEY(solicitacao_id,setor), FOREIGN KEY(solicitacao_id,estabelecimento_id) REFERENCES solicitacoes_pedido(id,estabelecimento_id),
 FOREIGN KEY(executor,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 CHECK(estado='PENDENTE' OR (executor IS NOT NULL AND decidido_em IS NOT NULL AND credencial_versao IS NOT NULL))
);
CREATE FUNCTION proteger_pedido_historico() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Historico de pedido protegido' USING ERRCODE='23514'; END IF;
 IF NEW.id<>OLD.id OR NEW.estabelecimento_id<>OLD.estabelecimento_id THEN RAISE EXCEPTION 'Escopo imutavel' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='linhas_pedido' THEN RAISE EXCEPTION 'Snapshot imutavel' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='pedidos' THEN
  IF ROW(NEW.comanda_id,NEW.codigo,NEW.aberto_por,NEW.aberto_em,NEW.tudo_junto,NEW.substitui_id) IS DISTINCT FROM ROW(OLD.comanda_id,OLD.codigo,OLD.aberto_por,OLD.aberto_em,OLD.tudo_junto,OLD.substitui_id) THEN RAISE EXCEPTION 'Abertura imutavel' USING ERRCODE='23514'; END IF;
 END IF;
 IF TG_TABLE_NAME='itens_preparo' THEN
  IF ROW(NEW.pedido_id,NEW.linha_id,NEW.produto_id,NEW.nome,NEW.setor,NEW.quantidade,NEW.observacao) IS DISTINCT FROM ROW(OLD.pedido_id,OLD.linha_id,OLD.produto_id,OLD.nome,OLD.setor,OLD.quantidade,OLD.observacao) OR OLD.estado IN ('ENTREGUE','CANCELADO') THEN RAISE EXCEPTION 'Snapshot ou estado terminal protegido' USING ERRCODE='23514'; END IF;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER proteger_pedidos BEFORE UPDATE OR DELETE ON pedidos FOR EACH ROW EXECUTE FUNCTION proteger_pedido_historico();
CREATE TRIGGER proteger_linhas_pedido BEFORE UPDATE OR DELETE ON linhas_pedido FOR EACH ROW EXECUTE FUNCTION proteger_pedido_historico();
CREATE TRIGGER proteger_itens_preparo BEFORE UPDATE OR DELETE ON itens_preparo FOR EACH ROW EXECUTE FUNCTION proteger_pedido_historico();
