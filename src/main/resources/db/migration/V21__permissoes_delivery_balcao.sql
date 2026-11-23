-- Capacidades explicitas, sem concessao automatica a papeis, funcionarios ou gerentes.
INSERT INTO permissoes(codigo,nome,descricao,modulo,categoria,critica,disponivel,exclusiva_proprietario) VALUES
 ('DELIVERY_CONSULTAR','Consultar delivery','Consultar pedidos externos e seu andamento na unidade','DELIVERY','OPERACIONAL',false,true,false),
 ('DELIVERY_REGISTRAR','Registrar delivery manual','Criar comanda e pedido externo com estoque e producao reais','DELIVERY','OPERACIONAL',false,true,false),
 ('DELIVERY_STATUS_ALTERAR','Atualizar entrega ou retirada','Registrar despacho e conclusao logistica sem simular pagamento','DELIVERY','OPERACIONAL',true,true,false),
 ('VENDA_RAPIDA_CONSULTAR','Consultar vendas rapidas','Consultar vendas de balcao pagas no momento, producao e retirada','BALCAO','OPERACIONAL',false,true,false),
 ('VENDA_RAPIDA_REGISTRAR','Registrar venda rapida','Confirmar pedido e recebimento na mesma transacao; exige permissoes financeiras','BALCAO','FINANCEIRA',true,true,false);
