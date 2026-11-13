-- Preserva V1-V7. O lock do vinculo sincroniza INSERT/UPDATE de adicionais
-- com UPDATE da principal, inclusive em transacoes SQL fora dos servicos.
CREATE OR REPLACE FUNCTION validar_funcao_adicional() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM usuarios_estabelecimentos
  WHERE id=NEW.vinculo_id AND estabelecimento_id=NEW.estabelecimento_id FOR UPDATE;
 IF NOT FOUND THEN
  RAISE EXCEPTION 'Vinculo invalido para funcao adicional' USING ERRCODE='23503';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM funcoes WHERE id=NEW.funcao_id AND codigo IN ('ATENDENTE','CAIXA','COZINHA','BAR'))
 OR EXISTS(SELECT 1 FROM usuarios_estabelecimentos WHERE id=NEW.vinculo_id AND funcao_id=NEW.funcao_id)
 THEN RAISE EXCEPTION 'Funcao adicional invalida' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;