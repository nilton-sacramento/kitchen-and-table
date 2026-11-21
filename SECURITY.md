# Segurança

O projeto está em desenvolvimento e não foi declarado pronto para produção. Não exponha o servidor de desenvolvimento à internet.

Não publique segredos, dados de clientes ou detalhes exploráveis em issues/PRs públicos. Se a opção de relato privado de vulnerabilidade estiver habilitada na aba Security do GitHub, utilize-a; caso não esteja, combine um canal privado com o proprietário antes de enviar detalhes sensíveis. A presença deste arquivo não habilita automaticamente o recurso.

Configurações operacionais devem vir do ambiente local. Não coloque DB_PASSWORD ou KT_SETUP_KEY no repositório, no frontend ou em logs. Senhas fictícias dos testes não são contas operacionais e não devem ser reutilizadas em contas reais.

Permissões por estabelecimento, delegações administrativas e revalidação de vínculo já existem; PIN operacional possui bloqueio persistente após tentativas incorretas. Esses controles não equivalem a aprovação de segurança para produção. Antes do piloto: revisar HTTPS/cookie Secure, suporte e vulnerabilidades de dependências, cobertura de autorização, expiração/revogação, proteção contra tentativas em massa e infraestrutura. Sessões administrativas atuais são em memória; sessões distribuídas, recuperação administrativa e autorizações críticas avançadas continuam pendentes.

Se um segredo real vazar, trate-o como comprometido e combine sua revogação/rotação com o responsável. Apenas apagar o texto do commit atual não remove o histórico. Não reescreva o histórico nem troque credenciais sem autorização.

## Caixa e pagamentos (Entrega 2.7)

Valores em centavos inteiros; recebimento depende de sessão/PIN, unidade, equipe e permissões atuais. Idempotência e bloqueios transacionais impedem duplicação de saldo. Autorizações críticas são pontuais do proprietário, com validade e uso único. Nenhum Pix/cartão/estorno movimenta banco automaticamente; não informar PAN, CVV ou senha bancária. Ledger e relatórios imutáveis preservam responsáveis e compensações. Sem fila de pagamentos offline; resultado incerto exige consulta ao servidor antes de reenvio. [Contratos, validações e limites](docs/entrega-2-7.md). HTTPS, retenção, backup/restauração e limites de taxa devem ser validados antes do piloto.
