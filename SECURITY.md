# Segurança

O projeto está em desenvolvimento e não foi declarado pronto para produção. Não exponha o servidor de desenvolvimento à internet.

Não publique segredos, dados de clientes ou detalhes exploráveis em issues/PRs públicos. Se a opção de relato privado de vulnerabilidade estiver habilitada na aba Security do GitHub, utilize-a; caso não esteja, combine um canal privado com o proprietário antes de enviar detalhes sensíveis. A presença deste arquivo não habilita automaticamente o recurso.

Configurações operacionais devem vir do ambiente local. Não coloque DB_PASSWORD ou KT_SETUP_KEY no repositório, no frontend ou em logs. Senhas fictícias dos testes não são contas operacionais e não devem ser reutilizadas em contas reais.

Antes de produção: revisar HTTPS, cookie Secure, suporte e vulnerabilidades de dependências, permissões por estabelecimento, expiração/revogação de sessões, limitação de tentativas e infraestrutura. Sessões atuais são em memória; não há sessões distribuídas nem autorização granular completa.

Se um segredo real vazar, trate-o como comprometido e combine sua revogação/rotação com o responsável. Apenas apagar o texto do commit atual não remove o histórico. Não reescreva o histórico nem troque credenciais sem autorização.
