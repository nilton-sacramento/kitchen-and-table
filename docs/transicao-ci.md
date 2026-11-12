# Transição do CI e ordem dos PRs — revisão adicional

Nenhum merge foi autorizado nesta revisão. Os comandos deste guia são um procedimento futuro, não comandos já executados.

## Diagnóstico e correções

PR #2 parte de develop sem pom.xml ou frontend. Executar Maven/Node diretamente nessa branch falharia por ausência de projeto. O bootstrap atual testa um checkout isolado de 04b136c, commit imutável já publicado da Etapa 1, sem copiar fontes para develop. Um verde de bootstrap comprova aquele código, não a implementação de cada PR futuro.

A revisão simulou a composição dos dois PRs por git merge-tree, sem alterar refs, índice ou árvore de trabalho. Foi encontrado conflito em .gitignore. O commit ac04310 no PR #1 alinha o arquivo ao PR #2, mantendo todas as exclusões. A nova simulação é limpa. Nenhum arquivo de negócio foi alterado.

O CI atual usa o checkout padrão do evento: em pull_request, normalmente é o commit de teste de merge do GitHub; em push, o commit publicado. Enquanto não existe pom.xml, porém, a compilação é do SHA fixo explicitamente registrado. Isso é uma exceção temporária, não a solução definitiva.

## Workflow definitivo preparado

.github/ci-templates/ci-sem-bootstrap.yml não está em .github/workflows e não é executado automaticamente. É a versão final para ativar no PR #1 depois que o PR #2 estiver integrado por aprovação do responsável.

Ela utiliza github.event.pull_request.head.sha em PR e github.sha em push/execução manual. Confere o SHA efetivamente presente em HEAD antes e depois dos testes. Exige pom.xml e JavaScript no próprio checkout; não contém checkout secundário, diretório _ci nem SHA fixo da aplicação. Os SHAs de Actions continuam fixados por segurança e atualizados pelo Dependabot — isso não é dependência de um commit do produto.

O nome do check continua Java 17 e JavaScript, com contents: read, checkout sem credenciais persistidas, sem segredos, sem pull_request_target e sem skip de testes. Node valida sintaxe; Maven verify compila, executa testes e empacota. Também roda em push para main depois de uma integração futura aprovada, sem publicar ou implantar nada.

## Procedimento futuro exato, somente após autorização

1. Revisar o diff do PR #2, seu commit mais recente e os checks. Tirar o PR #2 de Draft somente com autorização. Confirmar Create a merge commit, para preservar seus commits individuais. Não usar bypass, rebase ou squash nesta sequência se a intenção é preservar os hashes originais.
2. Depois da aprovação expressa, integrar PR #2 em develop pela interface/API. Confirmar que main continua intacta. Aguardar CI de push em develop, ainda explicitamente em bootstrap.
3. Atualizar a feature com develop, também somente quando a atualização por merge estiver autorizada. No clone limpo:

```powershell
git fetch origin
git switch feature/etapa-1-autenticacao
git merge --no-ff origin/develop
```

Esse merge futuro incorpora a infraestrutura sem reescrever a feature; não foi executado durante a revisão. Se surgir conflito por alterações posteriores, interromper e revisar; não resolver escolhendo um lado inteiro sem conferir os arquivos.

4. Ativar o workflow definitivo no próprio PR #1, antes de aprovar sua integração:

```powershell
Copy-Item -LiteralPath '.github/ci-templates/ci-sem-bootstrap.yml' -Destination '.github/workflows/ci.yml'
node --check src/main/resources/static/assets/app.js
mvn --batch-mode --no-transfer-progress '-Ddebug=false' '-Dlogging.level.org.springframework=INFO' verify
git diff --check
git add -- .github/workflows/ci.yml
git commit -m 'ci: validar o commit de cada PR sem bootstrap'
git push origin feature/etapa-1-autenticacao
```

5. Aguardar novos checks do PR #1 e de seu push. Conferir no resumo Bootstrap: false e commit validado igual ao HEAD efetivo da feature. Não reutilizar o verde antigo do PR #2. Verificar que develop não avançou novamente; a proteção strict exige branch atualizada. Rever diff e ausência de segredos.
6. Somente com nova aprovação, tirar o PR #1 de Draft e integrar com Create a merge commit. Aguardar CI de push em develop: agora sem bootstrap, validando o novo commit de develop. Se falhar, não promover para main nem mascarar a falha com bypass; abrir correção revisada.
7. Promoção de develop para main será outro PR, com autorização e testes próprios. Não criar tag nem realizar essa promoção nesta revisão.

## Proteções e dependências

As proteções atuais permitem esse fluxo: PR e check real obrigatórios, branch atualizada, conversas resolvidas, zero aprovações externas, sem exigência de CODEOWNERS/último push, regras aplicadas a administradores e bloqueio de force push/exclusão. Draft é uma trava deliberada: o PR não pode ser integrado enquanto permanecer assim. Não é necessário enfraquecer a regra.

O PR #2 não inclui backend e depende temporariamente dos fontes do PR #1 apenas para os testes de bootstrap; a execução remota já demonstrou acesso ao commit público fixo. O PR #1 não altera .github antes de receber a infraestrutura. A única edição sobreposta relevante era .gitignore, agora idêntica nas duas branches. README da arquitetura vem do PR #1; docs históricos/prompt-master devem ser preservados. O Dependabot de versões continua dependendo de integração futura aprovada na branch padrão.

## Verificações a repetir

Após PR #2: CI de develop e confirmação das proteções. Após atualização da feature e ativação do definitivo: actionlint se disponível, JavaScript, Maven verify e checks remotos novos no HEAD do PR #1. Após PR #1: CI de develop sem bootstrap, revisão dos arquivos preservados e smoke test local com proprietário/banco existentes, sem setup ou recriação de dados. Para main, exigir novo PR e resultados correspondentes.

Riscos restantes: mudanças posteriores podem reintroduzir conflitos; o CI não substitui auditoria de vulnerabilidades ou testes com PostgreSQL real; cache e runners não são ambiente de produção; código de PR é executado com token mínimo, mas alterações no próprio workflow ainda exigem revisão humana. Não disponibilizar segredos operacionais ao workflow.
