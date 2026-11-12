# Integração contínua

Workflow: .github/workflows/ci.yml. Check estável observado: Java 17 e JavaScript (job build, workflow CI). Esse nome foi confirmado em execuções reais bem-sucedidas do GitHub Actions e aplicado como obrigatório; veja docs/github-preparacao.md.

## Execução

Dispara em PRs para develop/main e pushes para develop, feature/**, fix/** e chore/**. Existe também workflow_dispatch, que só aparece na interface quando o workflow está disponível na branch padrão conforme as regras do GitHub.

Runner ubuntu-latest, Temurin Java 17, Maven e Node 24. Actions fixadas por SHA completo; Dependabot preparado para propor atualizações. O job executa node --check e mvn verify, incluindo compilação, os 18 testes e empacotamento. Não há skip de testes.

Permissão do token: contents: read. Checkout sem persistir credenciais. Não há pull_request_target, segredos de repositório, publish, deployment, merge, tags ou acesso ao PostgreSQL externo. O teste de sessão usa apenas Tomcat temporário em loopback; dados/autenticação são simulados. Nenhum serviço PostgreSQL é necessário. Logs de Spring em nível INFO para reduzir saída de detalhes de teste. Relatórios XML com propriedades do ambiente não são publicados como artefatos.

## Bootstrap explícito antes da integração da Etapa 1

A branch de configuração parte de develop, que ainda não contém pom.xml. Por isso, somente enquanto não há backend integrado, o workflow faz um segundo checkout isolado do commit completo da feature correspondente a 04b136c em _ci/etapa-1 e o testa. Isso não copia o código para a branch nem executa merge. O resumo da execução registra bootstrap e diretório validado.

Quando pom.xml existir, o checkout do evento/PR é testado diretamente. Se a ancestralidade indicar que o backend já foi integrado e pom.xml desaparecer, o job falha; não pode ocultar uma remoção de backend usando bootstrap. Após a integração do código e da configuração, remover o fallback por outro PR de manutenção autorizado.

Um CI verde de bootstrap prova aquele commit fixo da Etapa 1, não um módulo futuro nem todas as alterações de configuração. actionlint foi utilizado localmente para validar a estrutura do workflow. É necessário conferir o escopo real de cada execução antes do merge.

## Dependabot e segurança

.github/dependabot.yml prepara atualizações semanais Maven e GitHub Actions, destinadas a develop e limitadas a cinco PRs por ecossistema. A configuração só passa a ser descoberta pelo Dependabot quando o arquivo estiver na branch padrão (main); nenhum merge em main foi autorizado. Arquivo preparado não significa serviço já ativo ou vulnerabilidades ausentes.

Secret scanning, push protection, dependency graph e alertas são recursos/configurações do GitHub, separados do YAML. Conferir estado real na API/interface; não inferir ativação apenas por ser repositório público. Consulte docs/protecoes-github.md.

## Verificações locais

No checkout com backend: mvn --batch-mode --no-transfer-progress verify e node --check src/main/resources/static/assets/app.js. Na branch de configuração sem backend, usar checkout isolado do commit fixo para reproduzir o bootstrap, sem alterar a pasta original nem o banco.

Uma execução local não é resultado remoto do Actions. Consulte docs/github-preparacao.md para o estado efetivo desta preparação.
