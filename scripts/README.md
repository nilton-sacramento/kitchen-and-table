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

## Testes de contexto e formulários

`node --test scripts/test-interface-contexto.mjs`: testes puros de contexto/drafts.

`node scripts/test-interface-browser.mjs`: Chrome real com API controlada de teste, sem PostgreSQL. Evidências e perfil exclusivo permanecem ignorados em .tools/missao-2026-11-18; não são apagados automaticamente. Esse teste não valida o backend nem altera aprovações manuais.

## Entrega 2.4

O smoke oficial importa smoke-mesas-comandas.mjs, com administração de mesas, duas unidades, atendimento por PIN, abertura idempotente, F5, encerramento operacional, cancelamento justificado e capturas desktop/tablet/360px. Não executar a extensão sozinha.

Testes puros adicionais: node --test scripts/test-atendimento.mjs. O comando conjunto é node --test scripts/test-interface-contexto.mjs scripts/test-atendimento.mjs. Backend e migrações: MesasComandasPostgresTest em src/test/java/com/kitchentable/comandas, executado pelo mvn verify com PostgreSQL 16 descartável.

Ambiente navegável exclusivamente descartável: após mvn verify, execute node scripts/ambiente-atendimento.mjs. URL aleatória em loopback e arquivo privado de credenciais são informados no terminal. Ctrl+C encerra a fixture criada pelo script. Não usar para migrar o cenário manual anterior.

## Entrega 2.5

Testes puros: node --test scripts/test-cardapio.mjs. O smoke oficial importa smoke-cardapio.mjs e cobre cadastro/edição, preços, estoque, combo, cópia entre duas unidades e consulta por PIN com F5 em desktop/tablet/360px. Evidências privadas permanecem em .tools/browser-artifacts e .tools/entrega-2-5. Não executar a extensão sozinha.

## Entrega 2.6

node --test scripts/test-pedidos.mjs valida centavos, intenção de retentativa e rotas de produção. smoke-pedidos.mjs integra o smoke oficial (não executar isoladamente): cria perfis fictícios cozinha/bar no banco descartável, usa PIN e APIs reais, percorre confirmação/preparo/entrega/decisões. Credenciais ficam somente em memória; capturas locais são ignoradas em .tools. PedidosPostgresTest integra mvn verify.

## Entrega 2.7

node --test scripts/test-financeiro.mjs: precisão monetária, escopo, corpo congelado e consulta anterior à retentativa. smoke-caixa.mjs integra o smoke oficial, não execução isolada: PostgreSQL 16/backend reais, política explícita, pedidos 2.6, pagamentos combinados, autorização, resposta perdida, quitação, movimentos e fechamento. Credenciais em memória; capturas/logs privados em .tools. Java integra mvn verify, migrações até V19 só em bancos descartáveis. [Contratos e limites](../docs/entrega-2-7.md).

## Entrega 2.8

node --test scripts/test-externos.mjs valida intenção congelada, consulta antes de retentativa, precisão monetária e rotas delivery/venda rápida. O comando conjunto do CI acrescenta esse arquivo aos cinco anteriores.

smoke-funcionarios.mjs importa smoke-externos.mjs com backend/PostgreSQL16/Chrome reais. Não executar a extensão isoladamente. Capturas passam a usar uma subpasta única por execução em .tools/browser-artifacts, preservando as anteriores. Credenciais sintéticas permanecem somente em memória, sem dependência da demonstração privada. Novas migrações V20–V23 somente em bancos descartáveis; consulte docs/entrega-2-8.md.
