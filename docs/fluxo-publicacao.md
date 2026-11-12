# Fluxo de publicação — sem push executado nesta preparação

## Estado de partida confirmado

main, develop e feature/etapa-1-autenticacao partiram do commit ba275cd3b08b4e56c5b59628935b74378de8985d. A branch ativa é feature/etapa-1-autenticacao. O remoto origin é https://github.com/nilton-sacramento/kitchen-and-table.git. A consulta remota nesta preparação encontrou somente main nesse commit.

O histórico anterior é preservado. Não são usados reset, force-push, rebase de main, commits em main ou merges automáticos. O projeto original fora deste clone não foi alterado.

## Etapa de publicação, somente depois de autorização

Antes de publicar, executar git fetch origin e conferir novamente se main avançou, quais branches remotas existem e se o diff segue aprovado. Se houver divergência, revisar antes de continuar; não sobrescrever branches.

Se o remoto ainda estiver no estado inspecionado e houver aprovação explícita para publicar develop e feature:

```powershell
git push --set-upstream origin develop
git push --set-upstream origin feature/etapa-1-autenticacao
```

Esses comandos não são executados pela preparação local. Primeiro cria-se develop no remoto como base do PR; ela ainda aponta para o commit original de main.

Abrir então PR feature/etapa-1-autenticacao → develop, preferencialmente como rascunho para revisão. Texto pronto em docs/prs/etapa-1-para-develop.md. Caso GitHub CLI esteja disponível e autenticado, o comando planejado é:

```powershell
gh pr create --draft --base develop --head feature/etapa-1-autenticacao --title 'feat: entregar fundação web e autenticação por sessão da Etapa 1' --body-file docs/prs/etapa-1-para-develop.md
```

GitHub CLI não foi encontrado nesta sessão. A criação efetiva do PR exigirá uma ferramenta autenticada disponível; pode ser feita pela interface do GitHub após os pushes autorizados. Não instalar ferramentas nem iniciar autenticação sem necessidade.

URL de comparação para o primeiro PR, válida depois de publicar as branches: https://github.com/nilton-sacramento/kitchen-and-table/compare/develop...feature/etapa-1-autenticacao

## Promoção futura, não autorizada nesta preparação

Somente após revisão/aprovação e integração do primeiro PR, atualizar develop local a partir do resultado efetivo do GitHub e executar novamente a suíte completa. Nenhum desses merges é automático.

O segundo texto é um rascunho em docs/prs/develop-para-main.md. Depois de autorização específica para promover, revisão do diff e testes no develop efetivo:

```powershell
gh pr create --draft --base main --head develop --title 'release: promover Etapa 1 para main' --body-file docs/prs/develop-para-main.md
```

Não abrir esse segundo PR antes da aprovação correspondente. Nenhum comando acima autoriza executar merges.

## Identidade e configuração

Os novos commits usam o nome já configurado no Git e o endereço público nilton-sacramento@users.noreply.github.com, passado por comando, sem alterar configuração global nem o histórico existente. A associação visual do autor no GitHub depende das configurações da conta.

.gitignore reduz risco de inclusão acidental, mas não substitui revisão dos arquivos e do índice. O README histórico e commits anteriores permanecem acessíveis. Arquivos de recuperação, target, dumps, credenciais e .env não devem ser adicionados, nem mesmo com git add -f.
