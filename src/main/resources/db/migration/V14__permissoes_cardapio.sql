-- Reusar contratos existentes; administracao adicional exige perfil principal e concessao explicita.
UPDATE permissoes SET disponivel=true WHERE codigo IN ('PRODUTO_CONSULTAR','PRODUTO_GERENCIAR','PRODUTO_PRECO_ALTERAR','PRODUTO_DISPONIBILIDADE_ALTERAR','ESTOQUE_CONSULTAR','ESTOQUE_AJUSTAR');
UPDATE permissoes SET categoria='ADMINISTRATIVA' WHERE codigo IN ('PRODUTO_GERENCIAR','PRODUTO_PRECO_ALTERAR','PRODUTO_DISPONIBILIDADE_ALTERAR','ESTOQUE_AJUSTAR');
INSERT INTO permissoes(codigo,nome,descricao,modulo,categoria,critica,disponivel,exclusiva_proprietario) VALUES
 ('CARDAPIO_COPIAR','Copiar cardapio entre unidades','Previa e confirmacao autorizadas nas duas unidades; sem copiar quantidades fisicas','PRODUTOS','ADMINISTRATIVA',true,true,false);
-- Nenhuma nova heranca administrativa, critica ou financeira.
