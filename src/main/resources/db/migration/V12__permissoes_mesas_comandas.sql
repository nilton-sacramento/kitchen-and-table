-- Ativar somente os contratos operacionais agora implementados. Manter herancas/negacoes existentes.
UPDATE permissoes SET disponivel=true WHERE codigo IN ('MESA_CONSULTAR','ATENDIMENTO_ABRIR','COMANDA_CONSULTAR');
INSERT INTO permissoes(codigo,nome,descricao,modulo,categoria,critica,disponivel,exclusiva_proprietario) VALUES
 ('MESA_GERENCIAR','Administrar mesas','Cadastrar, identificar e ativar mesas; exige autoridade administrativa principal','MESAS','ADMINISTRATIVA',true,true,false),
 ('COMANDA_ENCERRAR','Encerrar comanda sem consumo','Encerramento apenas operacional, sem consumo ou pendencias; nao registra pagamento','COMANDAS','OPERACIONAL',true,true,false),
 ('COMANDA_CANCELAR','Cancelar comanda sem consumo','Cancelamento autorizado com justificativa e historico; sem consumo ou pendencias','COMANDAS','OPERACIONAL',true,true,false);
-- Nenhum padrao para as novas capacidades criticas; nenhum poder administrativo concedido a gerentes.
