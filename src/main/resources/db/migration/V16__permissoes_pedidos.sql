UPDATE permissoes SET disponivel=true WHERE codigo IN ('PEDIDO_REGISTRAR','PEDIDO_ENVIAR','PEDIDO_ENVIADO_ALTERAR','PEDIDO_CANCELAR','COZINHA_FILA_CONSULTAR','COZINHA_PREPARO_ATUALIZAR','BAR_FILA_CONSULTAR','BAR_PREPARO_ATUALIZAR');
INSERT INTO permissoes(codigo,nome,descricao,modulo,categoria,critica,disponivel,exclusiva_proprietario) VALUES
 ('PEDIDO_CONSULTAR','Consultar pedidos','Consultar pedidos e snapshots da unidade autorizada','PEDIDOS','OPERACIONAL',false,true,false),
 ('PEDIDO_ENTREGAR','Confirmar entrega','Entregar itens prontos respeitando tudo junto e concorrencia','PEDIDOS','OPERACIONAL',false,true,false),
 ('PEDIDO_ALTERACAO_SOLICITAR','Solicitar alteracao de pedido','Solicitar alteracao ou cancelamento sem executar a aprovacao do setor','PEDIDOS','OPERACIONAL',false,true,false);
INSERT INTO funcoes_permissoes(funcao_id,permissao_id) SELECT f.id,p.id FROM funcoes f CROSS JOIN permissoes p WHERE f.codigo IN ('ATENDENTE','CAIXA') AND p.codigo IN ('PEDIDO_CONSULTAR','PEDIDO_ENTREGAR','PEDIDO_ALTERACAO_SOLICITAR') ON CONFLICT DO NOTHING;
-- Aprovacao de alteracao/cancelamento exige codigo critico existente e poder do setor; nenhum default critico ou administrativo novo.
