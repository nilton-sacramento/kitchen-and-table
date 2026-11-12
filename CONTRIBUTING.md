# Contribuir com o KITCHEN & TABLE 2.0

Preserve as decisões aprovadas e diferencie planejado, implementado e testado. Não inicie outro módulo sem autorização do responsável. Nunca recrie o banco, apague dados ou altere credenciais operacionais para obter testes verdes.

## Branches e revisão

- main: versões estáveis e aprovadas.
- develop: integração por Pull Request.
- feature/*: novas funcionalidades, partindo de develop.
- fix/*: correções, partindo de develop.
- chore/*: configuração e manutenção, partindo de develop.

Use commits pequenos e descritivos (feat:, fix:, test:, docs:, chore: ou ci:). Abra PR para develop, descrevendo resultado, arquivos principais, testes efetivos, riscos e alterações de banco. Promoção de develop para main exige autorização própria e novo PR. Não faça push direto em main, force push, merge automático ou tags antes da aprovação da integração.

A revisão do proprietário pode ocorrer pelo diff e checklist, sem impor aprovação externa quando ele for o único colaborador. Isso não autoriza merge automático.

## Verificação

Com backend disponível no checkout:

```powershell
mvn --batch-mode --no-transfer-progress verify
node --check src/main/resources/static/assets/app.js
```

Java 17 e Maven são necessários; Node só é necessário para a verificação de sintaxe. Os testes não acessam PostgreSQL: usam mocks e Tomcat temporário em loopback. Não desative testes nem CSRF. Não utilize segredos ou banco local/produção no CI.

Revise git diff, git diff --cached e a lista de arquivos antes de commit/push. Nunca inclua .env, credenciais, dumps, target, recovery ou ferramentas temporárias. Arquivos ignorados não devem ser adicionados com -f. A varredura por padrões não substitui revisão contextual.

Veja docs/ci.md, docs/instalacao-local.md, docs/backup-recuperacao.md e docs/protecoes-github.md.
