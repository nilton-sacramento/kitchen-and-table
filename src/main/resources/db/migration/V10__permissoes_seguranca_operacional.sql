INSERT INTO permissoes(codigo,nome,descricao,modulo,categoria,critica,disponivel,exclusiva_proprietario) VALUES
 ('PIN_GERENCIAR','Definir ou redefinir PIN','Criar ou substituir PIN operacional sem recuperar o valor anterior','SEGURANCA','ADMINISTRATIVA',true,true,false),
 ('OPERACIONAL_DESBLOQUEAR','Bloquear ou desbloquear operador','Administrar bloqueio operacional de funcionarios comuns autorizados','SEGURANCA','ADMINISTRATIVA',true,true,false),
 ('INATIVIDADE_CONFIGURAR','Configurar inatividade','Definir o prazo de bloqueio operacional da unidade','SEGURANCA','ADMINISTRATIVA',true,true,false),
 ('SEGURANCA_EVENTOS_CONSULTAR','Consultar eventos de seguranca','Consultar tentativas, bloqueios e autorizacoes dentro dos limites recebidos','SEGURANCA','ADMINISTRATIVA',true,true,false);
-- Sem padroes por funcao e sem concessoes a gerentes existentes.