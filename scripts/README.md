# Scripts de validação

Executar na raiz do repositório. Java 17, Maven e Docker são necessários para as fixtures; Node 24 e Chrome para o smoke. Não há instalação npm.

| Script | Uso |
| --- | --- |
| smoke-funcionarios.mjs | Entrada do smoke completo: funcionários, associação, funções, permissões, segurança operacional e autenticação |
| smoke-operacional.mjs | Extensão importada pelo smoke principal; não executar isoladamente esperando iniciar uma fixture |

```powershell
mvn --batch-mode --no-transfer-progress '-Ddebug=false' '-Dlogging.level.org.springframework=INFO' verify
node scripts/smoke-funcionarios.mjs
```

O smoke usa classes de teste compiladas pelo Maven, cria PostgreSQL 16 descartável próprio e navegador com perfil aleatório, e encerra seus recursos ao terminar. Não usa o banco operacional nem o cenário manual existente. Capturas ficam em .tools/browser-artifacts, ignoradas pelo Git. Antes de repetir uma execução cuja evidência precise ser preservada, guardar suas capturas localmente: os nomes de saída podem ser reutilizados.

Verificar somente a sintaxe:

```powershell
node --check scripts/smoke-funcionarios.mjs
node --check scripts/smoke-operacional.mjs
node --check src/main/resources/static/assets/app.js
node --check src/main/resources/static/assets/funcionarios.js
node --check src/main/resources/static/assets/permissoes.js
node --check src/main/resources/static/assets/seguranca-operacional.js
node --check src/main/resources/static/assets/operacional.js
```

O frontend é servido pelo Spring Boot e não requer um processo Node em execução. Os scripts privados Iniciar/Parar/Status/Abrir-* do ambiente manual permanecem em .tools/testes-manuais-2-3. Não são dependências do CI nem ferramentas de produção. Consulte [instalação](../docs/instalacao-local.md), [estrutura](../docs/estrutura-projeto.md) e [testes](../docs/organizacao-testes.md).
