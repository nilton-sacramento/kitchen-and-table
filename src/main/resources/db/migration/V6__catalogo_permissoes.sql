ALTER TABLE permissoes
 ADD COLUMN nome VARCHAR(150) NOT NULL DEFAULT '',
 ADD COLUMN modulo VARCHAR(60) NOT NULL DEFAULT 'LEGADO',
 ADD COLUMN categoria VARCHAR(20) NOT NULL DEFAULT 'ADMINISTRATIVA' CHECK(categoria IN ('ADMINISTRATIVA','OPERACIONAL','FINANCEIRA')),
 ADD COLUMN critica BOOLEAN NOT NULL DEFAULT true,
 ADD COLUMN disponivel BOOLEAN NOT NULL DEFAULT false,
 ADD COLUMN exclusiva_proprietario BOOLEAN NOT NULL DEFAULT true;
UPDATE permissoes SET nome=descricao;
UPDATE permissoes SET nome='Visualizar unidade',modulo='ESTABELECIMENTOS',critica=false,disponivel=true,exclusiva_proprietario=false WHERE codigo='ESTABELECIMENTO_VISUALIZAR';
-- Codigos legados amplos continuam inativos; nenhuma migracao concede autoridade administrativa.
INSERT INTO permissoes(codigo,nome,descricao,modulo,categoria,critica,disponivel,exclusiva_proprietario) VALUES
 ('FUNCIONARIO_CONSULTAR','Consultar equipe','Consultar funcionarios autorizados','FUNCIONARIOS','ADMINISTRATIVA',false,true,false),
 ('FUNCIONARIO_CADASTRAR','Cadastrar funcionario','Criar identidade e vinculo conforme hierarquia','FUNCIONARIOS','ADMINISTRATIVA',false,true,false),
 ('FUNCIONARIO_EDITAR','Editar funcionario','Editar dados locais sem alterar autoridade','FUNCIONARIOS','ADMINISTRATIVA',false,true,false),
 ('FUNCIONARIO_ATIVAR','Ativar vinculo','Reativar vinculo na unidade','FUNCIONARIOS','ADMINISTRATIVA',true,true,false),
 ('FUNCIONARIO_DESATIVAR','Desativar vinculo','Desativar sem apagar historico','FUNCIONARIOS','ADMINISTRATIVA',true,true,false),
 ('FUNCIONARIO_ASSOCIAR','Associar identidade','Solicitar e aceitar associacao autorizada','FUNCIONARIOS','ADMINISTRATIVA',true,true,false),
 ('FUNCIONARIO_FUNCAO_PRINCIPAL_ALTERAR','Alterar funcao principal','Alterar funcao dentro da hierarquia','FUNCIONARIOS','ADMINISTRATIVA',true,true,false),
 ('FUNCIONARIO_FUNCOES_ADICIONAIS_ALTERAR','Alterar funcoes adicionais','Gerenciar funcoes operacionais adicionais','FUNCOES','ADMINISTRATIVA',true,true,true),
 ('GERENTE_NOMEAR','Nomear gerente','Nomeacao reservada ao proprietario','FUNCIONARIOS','ADMINISTRATIVA',true,true,true),
 ('DELEGACAO_ADMINISTRATIVA_GERENCIAR','Limites administrativos','Somente proprietario define autoridade administrativa','PERMISSOES','ADMINISTRATIVA',true,true,true),
 ('PERMISSOES_CONSULTAR','Consultar permissoes','Consultar origens e resultado efetivo','PERMISSOES','ADMINISTRATIVA',false,true,true),
 ('PERMISSOES_INDIVIDUAIS_GERENCIAR','Personalizar permissoes','Administrar excecoes com limites explicitos','PERMISSOES','ADMINISTRATIVA',true,true,true),
 ('AUDITORIA_ADMINISTRATIVA_CONSULTAR','Consultar auditoria','Contrato futuro de consulta de auditoria','AUDITORIA','ADMINISTRATIVA',false,false,true);
INSERT INTO permissoes(codigo,nome,descricao,modulo,categoria,critica,disponivel,exclusiva_proprietario)
SELECT codigo,nome,nome,modulo,'OPERACIONAL',critica,false,false FROM (VALUES
 ('PRODUTO_CONSULTAR','Consultar produtos','PRODUTOS',false),('PRODUTO_GERENCIAR','Gerenciar produtos','PRODUTOS',false),
 ('PRODUTO_PRECO_ALTERAR','Alterar precos','PRODUTOS',true),('PRODUTO_DISPONIBILIDADE_ALTERAR','Alterar disponibilidade','PRODUTOS',false),
 ('MESA_CONSULTAR','Consultar mesas','MESAS',false),('ATENDIMENTO_ABRIR','Abrir atendimento','ATENDIMENTO',false),
 ('COMANDA_CONSULTAR','Consultar comanda','COMANDAS',false),('PEDIDO_REGISTRAR','Registrar pedido','PEDIDOS',false),
 ('PEDIDO_ENVIAR','Enviar pedido','PEDIDOS',false),('PEDIDO_ENVIADO_ALTERAR','Alterar pedido enviado','PEDIDOS',true),
 ('PEDIDO_CANCELAR','Cancelar pedido','PEDIDOS',true),('COZINHA_FILA_CONSULTAR','Fila da cozinha','COZINHA',false),
 ('COZINHA_PREPARO_ATUALIZAR','Atualizar preparo','COZINHA',false),('BAR_FILA_CONSULTAR','Fila do bar','BAR',false),
 ('BAR_PREPARO_ATUALIZAR','Atualizar preparo','BAR',false),('ESTOQUE_CONSULTAR','Consultar estoque','ESTOQUE',false),
 ('ESTOQUE_MOVIMENTAR','Movimentar estoque','ESTOQUE',false),('ESTOQUE_AJUSTAR','Ajustar estoque','ESTOQUE',true),
 ('ESTOQUE_TRANSFERIR','Transferir estoque','ESTOQUE',true),('RELATORIO_OPERACIONAL_CONSULTAR','Relatorio operacional','RELATORIOS',false)
) AS t(codigo,nome,modulo,critica);
INSERT INTO permissoes(codigo,nome,descricao,modulo,categoria,critica,disponivel,exclusiva_proprietario)
SELECT codigo,nome,nome,modulo,'FINANCEIRA',critica,false,false FROM (VALUES
 ('CAIXA_ABRIR','Abrir caixa','CAIXA',true),('CAIXA_OPERAR','Operar caixa','CAIXA',false),
 ('CAIXA_OPERADORES_CONFIGURAR','Configurar operadores','CAIXA',true),('PAGAMENTO_REGISTRAR','Registrar pagamento','PAGAMENTOS',false),
 ('VENDA_CANCELAR','Cancelar venda','VENDAS',true),('PAGAMENTO_ESTORNAR','Estornar pagamento','PAGAMENTOS',true),
 ('CAIXA_SANGRIA_REGISTRAR','Registrar sangria','CAIXA',true),('CAIXA_SUPRIMENTO_REGISTRAR','Registrar suprimento','CAIXA',false),
 ('DESPESA_REGISTRAR','Registrar despesa','FINANCEIRO',true),('CATEGORIA_FINANCEIRA_GERENCIAR','Categorias financeiras','FINANCEIRO',true),
 ('CAIXA_CONFERIR','Conferir caixa','CAIXA',true),('CAIXA_FECHAR','Fechar caixa','CAIXA',true),
 ('CAIXA_REABRIR','Reabrir caixa','CAIXA',true),('CAIXA_HISTORICO_CONSULTAR','Historico do caixa','CAIXA',false),
 ('FUNCIONARIO_HISTORICO_FINANCEIRO_CONSULTAR','Historico financeiro individual','FINANCEIRO',false),
 ('RELATORIO_FINANCEIRO_CONSULTAR','Relatorio financeiro','RELATORIOS',false)
) AS t(codigo,nome,modulo,critica);
INSERT INTO funcoes_permissoes(funcao_id,permissao_id)
 SELECT f.id,p.id FROM funcoes f CROSS JOIN permissoes p WHERE p.codigo='ESTABELECIMENTO_VISUALIZAR' ON CONFLICT DO NOTHING;
-- Padroes operacionais conservadores sao contratos futuros, nao habilitam operacoes.
INSERT INTO funcoes_permissoes(funcao_id,permissao_id)
SELECT f.id,p.id FROM funcoes f CROSS JOIN permissoes p WHERE
 (f.codigo IN ('ATENDENTE','CAIXA','COZINHA','BAR') AND p.codigo='PRODUTO_CONSULTAR') OR
 (f.codigo IN ('ATENDENTE','CAIXA') AND p.codigo IN ('MESA_CONSULTAR','ATENDIMENTO_ABRIR','COMANDA_CONSULTAR','PEDIDO_REGISTRAR','PEDIDO_ENVIAR')) OR
 (f.codigo='COZINHA' AND p.codigo IN ('COZINHA_FILA_CONSULTAR','COZINHA_PREPARO_ATUALIZAR')) OR
 (f.codigo='BAR' AND p.codigo IN ('BAR_FILA_CONSULTAR','BAR_PREPARO_ATUALIZAR')) ON CONFLICT DO NOTHING;