# Contribuir com o KITCHEN & TABLE 2.0

Preserve as decisões aprovadas e diferencie planejado, implementado e testado. Não inicie outro módulo sem autorização. Nunca recrie banco, apague dados ou altere credenciais operacionais para obter testes verdes.

## Onde trabalhar

Consulte [a estrutura](docs/estrutura-projeto.md), [a documentação por assunto](docs/README.md) e [os testes](docs/organizacao-testes.md). Mantenha as convenções Maven: código em src/main, testes Java em src/test e recursos estáticos servidos pelo backend. Não criar frontend ou backend duplicado para demonstração.

Antes de editar, confira branch e alterações locais. Preserve o trabalho manual existente. Migrações novas são incrementais; não editar V1–V10 já existentes como parte de uma reorganização.

## Branches e revisão

- main: versões estáveis e aprovadas.
- develop: integração por Pull Request.
- feature/*: funcionalidades em desenvolvimento.
- fix/*: correções autorizadas.
- chore/*: configuração e manutenção.

Novas branches normalmente partem da develop. Quando a tarefa exigir trabalhar em uma feature existente, preserve esse contexto e seu histórico. Use commits descritivos (feat:, fix:, test:, docs:, chore: ou ci:). PR para develop deve explicar comportamento, validação real, riscos e compatibilidade do banco. Promoção a main exige autorização própria.

Não faça force push, merge automático ou publicação sem autorização. Não execute alteração de banco operacional por causa de uma tarefa de documentação.

## Verificação

Pré-requisitos: Java 17, Maven, Docker Desktop; Node 24 e Chrome para o smoke. Na raiz:

```powershell
mvn --batch-mode --no-transfer-progress '-Ddebug=false' '-Dlogging.level.org.springframework=INFO' verify
node scripts/smoke-funcionarios.mjs
```

Os testes Java usam mocks, Tomcat temporário e PostgreSQL 16 descartável via Testcontainers. O smoke usa outra fixture descartável e inclui segurança operacional. Não utilizar banco operacional, segredos reais ou perfis pessoais. Não remover, ignorar ou enfraquecer testes para obter aprovação. Não desativar CSRF.

Para verificação de sintaxe, use os comandos de [scripts/README.md](scripts/README.md). Aprovação automatizada não substitui validação manual de usabilidade. Preserve os códigos e resultados dos testes manuais.

## Antes de publicar

Revise git diff, git diff --cached e os arquivos novos. Nunca inclua .env, credenciais, PINs, tokens, dumps, target, recovery, ferramentas temporárias ou materiais privados. Não adicionar arquivos ignorados com -f. Varredura por padrões não substitui revisão contextual.

O diretório local .ci-validation é um worktree Git e não deve ser removido como temporário. Não incluir seus arquivos no commit. Só publicar o escopo autorizado e conferir o CI no commit efetivamente enviado.

Veja [CI](docs/ci.md), [instalação](docs/instalacao-local.md), [backup](docs/backup-recuperacao.md), [fluxo de publicação](docs/fluxo-publicacao.md) e [segurança](SECURITY.md).
