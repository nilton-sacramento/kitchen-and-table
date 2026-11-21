-- Habilitar somente os contratos financeiros agora implementados. Nenhuma heranca nova.
UPDATE permissoes SET disponivel=true WHERE codigo IN ('CAIXA_ABRIR','CAIXA_OPERAR','CAIXA_OPERADORES_CONFIGURAR','PAGAMENTO_REGISTRAR','PAGAMENTO_ESTORNAR','CAIXA_SANGRIA_REGISTRAR','CAIXA_SUPRIMENTO_REGISTRAR','CAIXA_CONFERIR','CAIXA_FECHAR','CAIXA_REABRIR','CAIXA_HISTORICO_CONSULTAR','FUNCIONARIO_HISTORICO_FINANCEIRO_CONSULTAR','RELATORIO_FINANCEIRO_CONSULTAR');
INSERT INTO permissoes(codigo,nome,descricao,modulo,categoria,critica,disponivel,exclusiva_proprietario) VALUES
 ('CAIXA_POLITICA_CONFIGURAR','Configurar limites do caixa','Definir explicitamente limites e operadores; exclusiva do proprietario','CAIXA','ADMINISTRATIVA',true,true,true),
 ('CAIXA_AUTORIZACAO_GERENCIAR','Autorizar operacoes financeiras criticas','Aprovacao pontual pelo proprietario, limitada ao conteudo e prazo','CAIXA','ADMINISTRATIVA',true,true,true),
 ('CONTA_CONSULTAR','Consultar conta da comanda','Consultar consumo historico, descontos, pagamentos e saldo da unidade','PAGAMENTOS','FINANCEIRA',false,true,false),
 ('COMANDA_FINANCEIRO_ENCERRAR','Encerrar comanda quitada','Encerrar comanda entregue, sem pendencias e com saldo zero','PAGAMENTOS','FINANCEIRA',true,true,false),
 ('COMANDA_DESCONTO_APLICAR','Aplicar ou compensar desconto','Desconto auditado e limitado, com autorizacao adicional quando exigida','PAGAMENTOS','FINANCEIRA',true,true,false);
