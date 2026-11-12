# Proteções do GitHub — regras e limites

main e develop devem integrar alterações por PR, bloquear force push/exclusão e manter resolução de conversas. Para trabalho individual, não exigir aprovação de outra pessoa: required_approving_review_count = 0, mantendo a revisão pelo proprietário e o checklist. Não ativar exigência de CODEOWNERS ou aprovação do último push sem combinar colaboradores.

Quando as permissões permitirem, exigir PR e aplicar a regra aos administradores, sem bypass de usuários/apps desnecessário. Não criar restrições de push por pessoa que impeçam o único proprietário de operar. Não exigir assinatura de commits ou merge queue sem preparação própria.

Exigir CI somente após observar uma execução real bem-sucedida e seu check exato (planejado: Java 17 e JavaScript). Não registrar nomes imaginados ou checks que não executaram. O primeiro PR da Etapa 1 não terá automaticamente o workflow enquanto ele não existir na base/checkout correspondente; integrar a infraestrutura e atualizar/reexecutar os checks antes da aprovação final da implementação.

Proteção não autoriza merge. PR Draft continua sem merge até decisão explícita do responsável. Evitar status checks obrigatórios prematuros que bloqueiem a própria instalação do CI.

## Conferir na interface quando necessário

Settings → Branches → proteção de main/develop (ou Rules → Rulesets, conforme disponível). Exigir PR, zero aprovações externas se solo, resolver conversas, bloquear force push/deletion e aplicar a admins. Depois de CI real, selecionar o nome efetivo do check do GitHub Actions. Não marcar restrições adicionais sem avaliar o fluxo.

Settings → Code security (ou Code security and analysis): conferir dependency graph, Dependabot alerts, secret scanning e push protection. Ativar somente opções disponíveis para o repositório público e a conta, sem contratar plano pago. .github/dependabot.yml só será efetivo quando integrado à main por PR aprovado.

Settings → Actions → General: token com leitura mínima; não permitir criar/aprovar PRs via workflow sem necessidade. O workflow também declara contents: read. Não usar segredos privilegiados em PR não confiável.

A documentação oficial informa disponibilidade de proteção de branches em repositórios públicos no GitHub Free, mas permissões efetivas e estado das regras precisam ser confirmados na conta. Recursos de segurança não devem ser considerados ativos só por existir este arquivo.

Referências: https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches e https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-security-and-analysis-settings-for-your-repository

Consulte docs/github-preparacao.md para o que foi efetivamente aplicado, preparado ou bloqueado nesta sessão.

Estado aplicado: as regras de main/develop descritas acima foram configuradas e verificadas pela API com check real aprovado. Consulte docs/github-preparacao.md para evidencias e limites. A ordem recomendada de revisao e integracao futura e PR #2 (infraestrutura), depois CI no PR #1 (implementacao). Nenhum desses merges foi executado.
