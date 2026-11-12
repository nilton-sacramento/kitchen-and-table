# Integração contínua — workflow definitivo

Workflow ativo: .github/workflows/ci.yml. Check obrigatório: Java 17 e JavaScript, associado ao app oficial GitHub Actions. Nome preservado para compatibilidade com as proteções existentes.

## Código efetivamente validado

Em pull_request o checkout usa github.event.pull_request.head.sha; em push ou execução manual usa github.sha. Antes de testar, compara esse valor com git rev-parse HEAD e exige pom.xml e o arquivo JavaScript no próprio checkout. O resumo repete o commit validado. Não há checkout secundário nem fallback para SHA fixo da aplicação.

A proteção strict de develop exige a feature atualizada com a base; alterações posteriores da base exigem nova atualização e checks atuais. Depois de uma integração autorizada, o push em develop valida seu próprio commit de merge. Não reutilizar um verde de outro commit ou do antigo bootstrap.

Dispara em PRs para develop/main e em pushes para main, develop, feature/**, fix/** e chore/**. workflow_dispatch fica disponível conforme as regras do GitHub para a branch padrão. Não faz deploy, merge, tags ou publicação de artefatos.

## Execução e segurança

Runner ubuntu-latest, Temurin Java 17, Node 24 e Maven verify: sintaxe JavaScript, compilação, testes e empacotamento. Actions fixadas por SHA completo para segurança da cadeia de dependências; esses SHAs não são commits fixos do produto. Dependabot está preparado para propor atualizações dessas Actions.

Token contents: read; checkout sem persistir credenciais; sem secrets, pull_request_target ou acesso ao banco local/produção. Não imprime ambiente nem publica os relatórios XML de testes. Logs Spring em INFO. Testes usam mocks e Tomcat temporário somente em loopback, sem serviço PostgreSQL externo. A suíte existente tem 18 testes, incluindo expiração real de sessão com timeout reduzido apenas no contexto de teste.

## Histórico da transição

O PR #2 foi integrado em develop por autorização, no merge commit 30f788a661e3c72017b1099b62d23cf04c2e95ea. Seu CI passou ainda com o bootstrap, necessário porque aquela branch não tinha backend. Depois, develop foi incorporada na feature com merge commit e o template definitivo foi ativado no próprio PR #1. O PR #1 continua Draft e seu merge não foi autorizado.

O arquivo .github/ci-templates/ci-sem-bootstrap.yml permanece como referência da transição, fora dos workflows ativos. O workflow atual já é a versão sem bootstrap. Os registros anteriores de execuções em bootstrap são evidências históricas, não o comportamento atual desta branch.

## Dependabot e limites

.github/dependabot.yml prepara atualizações semanais Maven/Actions para develop. A descoberta da configuração de versões depende da integração aprovada do arquivo na branch padrão main; não está sendo declarada ativa antes disso. Alertas e atualizações automáticas de segurança do Dependabot foram habilitados separadamente no repositório, assim como secret scanning e push protection.

CI verde não equivale a auditoria completa de vulnerabilidades ou validação de produção. Ver docs/transicao-ci.md, docs/protecoes-github.md e SECURITY.md.

## Reproduzir localmente

```powershell
node --check src/main/resources/static/assets/app.js
mvn --batch-mode --no-transfer-progress '-Ddebug=false' '-Dlogging.level.org.springframework=INFO' verify
```

Testes não exigem DB_PASSWORD ou banco operacional. Para executar a aplicação com dados existentes, configurar DB_PASSWORD e manter o contêiner original conforme README; não repetir setup nem recriar volumes.
