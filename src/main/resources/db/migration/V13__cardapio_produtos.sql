CREATE TABLE categorias_cardapio (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), estabelecimento_id UUID NOT NULL REFERENCES estabelecimentos(id),
 nome VARCHAR(80) NOT NULL CHECK(length(btrim(nome))>0), ordem INTEGER NOT NULL DEFAULT 0 CHECK(ordem>=0),
 ativo BOOLEAN NOT NULL DEFAULT true, versao BIGINT NOT NULL DEFAULT 0, criado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE(id,estabelecimento_id)
);
CREATE UNIQUE INDEX ux_categoria_nome_unidade ON categorias_cardapio(estabelecimento_id,lower(btrim(nome)));
CREATE TABLE produtos_cardapio (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), estabelecimento_id UUID NOT NULL REFERENCES estabelecimentos(id),
 categoria_id UUID NOT NULL, nome VARCHAR(120) NOT NULL CHECK(length(btrim(nome))>0), descricao VARCHAR(1000),
 tipo VARCHAR(10) NOT NULL CHECK(tipo IN ('SIMPLES','COMBO')), preco_centavos BIGINT NOT NULL CHECK(preco_centavos BETWEEN 0 AND 9999999999),
 setor VARCHAR(10) CHECK(setor IN ('COZINHA','BAR')), ativo BOOLEAN NOT NULL DEFAULT true, disponivel BOOLEAN NOT NULL DEFAULT true,
 controla_quantidade BOOLEAN NOT NULL DEFAULT false, quantidade INTEGER NOT NULL DEFAULT 0 CHECK(quantidade>=0),
 versao BIGINT NOT NULL DEFAULT 0, criado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), atualizado_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(id,estabelecimento_id), FOREIGN KEY(categoria_id,estabelecimento_id) REFERENCES categorias_cardapio(id,estabelecimento_id),
 CHECK((tipo='SIMPLES' AND setor IS NOT NULL) OR (tipo='COMBO' AND setor IS NULL AND NOT controla_quantidade AND quantidade=0))
);
CREATE UNIQUE INDEX ux_produto_nome_unidade ON produtos_cardapio(estabelecimento_id,lower(btrim(nome)));
CREATE TABLE componentes_combo (
 estabelecimento_id UUID NOT NULL, combo_id UUID NOT NULL, produto_id UUID NOT NULL, quantidade INTEGER NOT NULL CHECK(quantidade BETWEEN 1 AND 1000),
 PRIMARY KEY(combo_id,produto_id), CHECK(combo_id<>produto_id),
 FOREIGN KEY(combo_id,estabelecimento_id) REFERENCES produtos_cardapio(id,estabelecimento_id),
 FOREIGN KEY(produto_id,estabelecimento_id) REFERENCES produtos_cardapio(id,estabelecimento_id)
);
CREATE TABLE movimentos_cardapio (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), estabelecimento_id UUID NOT NULL, produto_id UUID NOT NULL,
 operacao_id UUID NOT NULL, tipo VARCHAR(20) NOT NULL CHECK(tipo IN ('AJUSTE','CONSUMO','DEVOLUCAO')),
 anterior INTEGER NOT NULL CHECK(anterior>=0), posterior INTEGER NOT NULL CHECK(posterior>=0),
 executor UUID NOT NULL, motivo VARCHAR(500) NOT NULL CHECK(length(btrim(motivo))>0), ocorrido_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(produto_id,estabelecimento_id) REFERENCES produtos_cardapio(id,estabelecimento_id),
 FOREIGN KEY(executor,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 UNIQUE(estabelecimento_id,operacao_id,produto_id,tipo)
);
CREATE INDEX ix_movimentos_cardapio_unidade ON movimentos_cardapio(estabelecimento_id,produto_id,ocorrido_em);
CREATE TABLE copias_cardapio (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), origem UUID NOT NULL REFERENCES estabelecimentos(id), destino UUID NOT NULL REFERENCES estabelecimentos(id),
 executor UUID NOT NULL REFERENCES usuarios(id), prefixo VARCHAR(41) NOT NULL DEFAULT '', hash_origem CHAR(64) NOT NULL, hash_destino CHAR(64) NOT NULL,
 expira_em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()+interval '10 minutes', resultado JSONB,
 CHECK(origem<>destino)
);
CREATE FUNCTION proteger_cardapio() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Historico de cardapio protegido' USING ERRCODE='23514'; END IF;
 IF NEW.id<>OLD.id OR NEW.estabelecimento_id<>OLD.estabelecimento_id THEN RAISE EXCEPTION 'Escopo imutavel' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='produtos_cardapio' THEN IF NEW.tipo<>OLD.tipo THEN RAISE EXCEPTION 'Tipo imutavel' USING ERRCODE='23514'; END IF; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER proteger_categoria_cardapio BEFORE UPDATE OR DELETE ON categorias_cardapio FOR EACH ROW EXECUTE FUNCTION proteger_cardapio();
CREATE TRIGGER proteger_produto_cardapio BEFORE UPDATE OR DELETE ON produtos_cardapio FOR EACH ROW EXECUTE FUNCTION proteger_cardapio();
CREATE FUNCTION validar_componente_combo() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM produtos_cardapio WHERE id=NEW.combo_id AND estabelecimento_id=NEW.estabelecimento_id AND tipo='COMBO') OR
 NOT EXISTS(SELECT 1 FROM produtos_cardapio WHERE id=NEW.produto_id AND estabelecimento_id=NEW.estabelecimento_id AND tipo='SIMPLES') THEN
 RAISE EXCEPTION 'Componentes devem ser produtos simples da mesma unidade' USING ERRCODE='23514'; END IF; RETURN NEW;
END $$;
CREATE TRIGGER validar_componente_combo BEFORE INSERT OR UPDATE ON componentes_combo FOR EACH ROW EXECUTE FUNCTION validar_componente_combo();
