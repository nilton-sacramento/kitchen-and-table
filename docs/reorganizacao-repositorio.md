# Auditoria e organização do repositório

Revisão estrutural de 13/11/2026, na branch feature/entrega-2-3-pin-seguranca. Não altera regras de negócio, banco operacional ou arquitetura. Não autoriza merge ou deploy.

## Estrutura anterior e decisão

A aplicação já seguia Maven/Spring Boot: backend em src/main/java; configuração, Flyway e frontend em src/main/resources; testes Java em src/test/java; smoke em scripts; automação em .github. Mover essas áreas teria custo de compatibilidade sem benefício concreto.

A navegação documental era a lacuna principal: README sem mapa completo, ausência de índice temático/guia de scripts e instruções de instalação, contribuição e CI ainda descrevendo versões anteriores. O histórico das entregas continha totais válidos na época que precisavam ser distinguidos do total atual.

## Organização resultante

- README principal: propósito, implementação, tecnologias, mapa de pastas, execução local, testes, documentação e limites.
- docs/README.md: índice por assunto, separando guias atuais, entregas e registros históricos, sem mover seus endereços.
- docs/estrutura-projeto.md: mapa do backend, frontend, banco, configuração, testes e ferramentas locais; arquivos principais para abrir no editor.
- scripts/README.md: entrada correta do smoke, pré-requisitos, saídas e verificação de sintaxe.
- CONTRIBUTING.md e docs/instalacao-local.md: fluxos atuais e distinção entre ambiente manual preservado, fixtures descartáveis e instalação genuinamente nova.
- docs/ci.md, docs/autenticacao-sessao.md e SECURITY.md: estado atual, limites e orientações corrigidas, sem mudar controles de segurança.
- docs/organizacao-testes.md e tests/manual/README.md: guias da reorganização manual anterior, agora disponíveis para revisão pública sem materiais privados.
- docs/planejamento-correcoes-interface.md: planejamento já solicitado, explicitamente sem implementação ou nova autorização funcional.

Nenhum arquivo existente foi movido nesta auditoria geral. Assim, imports, recursos, nomes de classes, scripts executáveis, configuração de build e caminhos usados pelo CI permanecem intactos. Apenas links de documentação foram acrescentados/atualizados.

## Testes manuais e integridade

A reorganização anterior moveu 155 materiais locais para tests/manual/etapa-2.3: roteiro, acompanhamento, relatórios e evidências. Os hashes foram conferidos antes/depois. As 101 capturas assistidas e a captura de preparação, os registros HTTP e os originais foram preservados. Materiais locais permanecem ignorados pelo Git; manifestos e originais ficam em .tools/reorganizacao-manual.

O consolidado mantém 53 aprovações: 19 confirmadas manualmente pelo proprietário e documentadas na conciliação T01–T19, mais 34 comprovadas na execução assistida. CSV histórico e conciliação permanecem separados. T38 está bloqueado; T50, T51 e T57 continuam em revisão manual. Aprovação automatizada não altera esses resultados.

## Áreas auditadas e preservadas

| Área | Conclusão |
| --- | --- |
| Backend | Pacotes auth, config e funcionarios coerentes com a implementação atual; nenhum pacote reorganizado |
| Frontend | HTML e assets servidos pelo backend; imports e recursos preservados |
| Migrações | V1–V10 mantidas byte a byte; validação somente nas fixtures descartáveis |
| Configuração | pom.xml, application.yml e Compose preservados; credenciais externas e loopback mantidos |
| Testes e scripts | Fontes Java e smoke preservados; documentação de execução acrescentada |
| GitHub Actions | Workflow ativo e permissões preservados; documentação de CI atualizada |
| Gerados e ferramentas | target e .tools ignorados; nada excluído ou promovido ao código público |
| Worktree | .ci-validation preservado como worktree registrado, sem limpeza ou movimentação |

O template .github/ci-templates/ci-sem-bootstrap.yml coincide com o workflow atual, mas é referência histórica, não um segundo workflow ativo. Arquivos duplicados e divergentes nos pacotes antigos de revisão foram preservados. docs/prompt-master.md está vazio e não recebeu conteúdo inventado.

## Validação e publicação

Validação local desta revisão: Maven verify com BUILD SUCCESS, 137 testes aprovados, zero falhas, erros ou ignorados; smoke Chrome com SMOKE_BROWSER_SUCCESS e saída 0. Sintaxe JavaScript/PowerShell e actionlint do workflow aprovados. Links públicos conferidos sem referências ausentes; hashes de código, migrações, configuração, CI e materiais manuais preservados. Backend e recursos de interface locais responderam HTTP 200. PostgreSQL operacional não acessado. Logs e manifestos permanecem privados em .tools/reorganizacao-geral.

O CI remoto deve validar o commit exato publicado; um resultado antigo não substitui esse check. Resultado e link efetivos serão informados no relatório final ao proprietário.

O commit estrutural contém documentação e .gitignore. Não inclui relatórios privados, credenciais, evidências, perfis Chrome, bancos, ferramentas locais ou saídas de build. A autorização de push é exclusiva desta feature. Sem merge, alteração de develop/main, deploy ou acesso ao PostgreSQL operacional.

## Melhorias futuras — propostas, não executadas

- Acompanhar o crescimento dos serviços de funcionarios e extrair responsabilidades somente mediante evidência e revisão arquitetural; não refatorar por estética nesta tarefa.
- Avaliar automatizar a verificação de links documentais em CI em uma alteração própria.
- Revisar retenção e backup externo seguro dos materiais locais; ignorar no Git não constitui backup.
- Tratar as melhorias de interface apenas após autorização específica e revisão independente.

## Arquivos da revisão pública

.gitignore; README.md; CONTRIBUTING.md; SECURITY.md; docs/README.md; docs/estrutura-projeto.md; docs/instalacao-local.md; docs/autenticacao-sessao.md; docs/ci.md; docs/fluxo-publicacao.md; docs/protecoes-github.md; docs/organizacao-testes.md; docs/planejamento-correcoes-interface.md; docs/reorganizacao-repositorio.md; scripts/README.md; tests/manual/README.md.

Relatórios privados e evidências não integram essa lista. A organização manual anterior é publicada somente por seus guias e regra de ignore, sem enviar os materiais locais.
