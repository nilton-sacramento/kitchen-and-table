# Segurança

O projeto está em desenvolvimento e não foi declarado pronto para produção. Não exponha o servidor de desenvolvimento à internet.

Não publique segredos, dados de clientes ou detalhes exploráveis em issues/PRs públicos. Se a opção de relato privado de vulnerabilidade estiver habilitada na aba Security do GitHub, utilize-a; caso não esteja, combine um canal privado com o proprietário antes de enviar detalhes sensíveis. A presença deste arquivo não habilita automaticamente o recurso.

Configurações operacionais devem vir do ambiente local. Não coloque DB_PASSWORD ou KT_SETUP_KEY no repositório, no frontend ou em logs. Senhas fictícias dos testes não são contas operacionais e não devem ser reutilizadas em contas reais.

Permissões por estabelecimento, delegações administrativas e revalidação de vínculo já existem; PIN operacional possui bloqueio persistente após tentativas incorretas. Esses controles não equivalem a aprovação de segurança para produção. Antes do piloto: revisar HTTPS/cookie Secure, suporte e vulnerabilidades de dependências, cobertura de autorização, expiração/revogação, proteção contra tentativas em massa e infraestrutura. Sessões administrativas atuais são em memória; sessões distribuídas, recuperação administrativa e autorizações críticas avançadas continuam pendentes.

Se um segredo real vazar, trate-o como comprometido e combine sua revogação/rotação com o responsável. Apenas apagar o texto do commit atual não remove o histórico. Não reescreva o histórico nem troque credenciais sem autorização.
