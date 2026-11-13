CREATE TABLE vinculos_funcoes_adicionais (
 vinculo_id UUID NOT NULL,
 estabelecimento_id UUID NOT NULL,
 funcao_id UUID NOT NULL REFERENCES funcoes(id),
 criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(vinculo_id,funcao_id),
 FOREIGN KEY(vinculo_id,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id)
);
-- As verificacoes tambem protegem escritas SQL fora do servico.
CREATE FUNCTION validar_funcao_adicional() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM funcoes WHERE id=NEW.funcao_id AND codigo IN ('ATENDENTE','CAIXA','COZINHA','BAR'))
 OR EXISTS(SELECT 1 FROM usuarios_estabelecimentos WHERE id=NEW.vinculo_id AND funcao_id=NEW.funcao_id)
 THEN RAISE EXCEPTION 'Funcao adicional invalida' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validar_funcao_adicional BEFORE INSERT OR UPDATE ON vinculos_funcoes_adicionais
 FOR EACH ROW EXECUTE FUNCTION validar_funcao_adicional();
CREATE FUNCTION validar_funcao_principal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM vinculos_funcoes_adicionais WHERE vinculo_id=NEW.id AND funcao_id=NEW.funcao_id)
 THEN RAISE EXCEPTION 'Funcao principal repetida nas adicionais' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validar_funcao_principal BEFORE UPDATE OF funcao_id ON usuarios_estabelecimentos
 FOR EACH ROW EXECUTE FUNCTION validar_funcao_principal();
CREATE INDEX ix_adicionais_unidade ON vinculos_funcoes_adicionais(estabelecimento_id,vinculo_id);