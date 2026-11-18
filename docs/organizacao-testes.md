# Organização dos testes

A estrutura Maven/Spring Boot é preservada: backend em src/main/java, frontend em src/main/resources/static, configuração em src/main/resources/application.yml e migrações em src/main/resources/db/migration. Não mover esses diretórios para organizar materiais de teste.

- Testes automatizados Java: src/test/java. Executar Maven verify; os testes de integração usam PostgreSQL 16 descartável via Testcontainers.
- Smoke de interface: scripts/smoke-funcionarios.mjs, que inclui scripts/smoke-operacional.mjs. Executar após Maven verify, com Docker e Chrome disponíveis; usa fixture e banco próprios descartáveis.
- CI: .github/workflows/ci.yml. Estrutura e comandos preservados.
- Materiais manuais locais: tests/manual/etapa-2.3/roteiro, acompanhamento, relatorios e evidencias. Todo esse diretório é ignorado pelo Git; não publicar dados de execução por exceções de ignore.
- Privados e execução: .tools/testes-manuais-2-3. Credenciais, perfis de navegador, estado do banco, controladores e seus tokens permanecem ali e não devem ser anexados a revisões.
- Integridade da reorganização: .tools/reorganizacao-manual/movimentacao-antes.json e movimentacao-depois.json; versões originais dos arquivos cujos caminhos mudaram ficam em originais/.

## Execução local existente

Os scripts Iniciar.ps1, Parar.ps1, Status.ps1 e Abrir-*.ps1 permanecem em .tools/testes-manuais-2-3. Status.ps1 é consulta; Parar.ps1 interrompe somente o backend de testes. Finalizar.ps1 destrói o banco descartável: não usar durante a preservação dos testes manuais. A reorganização não autoriza sua execução.

O controlador visual em execucao-assistida/controlador.mjs é distinto do runner do backend. Foi pausado para a movimentação, preservando o banco e as janelas. Antes de retomá-lo, conferir o estado local e não executar novamente casos já aprovados sem necessidade e autorização. O gerador de relatório e o gerador de roteiro são ferramentas de escrita, não verificadores: não os executar apenas para validar sintaxe.

## Resultados e segurança

O CSV original é histórico, não deve ser reescrito para simular validações. Consultar acompanhamento/CONCILIACAO-T01-T19.md para as aprovações informadas pelo proprietário. Capturas, registros HTTP e resultados da execução assistida permanecem locais. Nunca versionar credenciais, cookies, tokens, perfis Chrome ou dumps de banco.

A pasta .ci-validation é um worktree Git registrado e não um temporário comum. Não mover, apagar ou limpar por esta reorganização. Pacotes antigos de revisão também foram preservados.

## Validação da reorganização

Maven verify aprovado com 137 testes, sem falhas, erros ou ignorados; smoke Chrome existente concluído com saída 0. Relatório local em tests/manual/etapa-2.3/relatorios/REORGANIZACAO.md. Planejamento posterior em docs/planejamento-correcoes-interface.md; nenhuma correção funcional implementada.

## Regressões de interface da missão de 18/11

`node --test scripts/test-interface-contexto.mjs` testa lógica sem DOM. `node scripts/test-interface-browser.mjs` serve o frontend real em Chrome com API controlada, sem substituir a integração real. Ambos mantêm artefatos locais em .tools/missao-2026-11-18. Consulte [resultados e bloqueios](interface-contexto-permissoes.md). O smoke real continua obrigatório.

## Recuperação e auditoria final de 18/11/2026

O cenário antigo não foi recuperado. Um cenário novo e isolado de PostgreSQL 16, com dados sintéticos, permanece em .tools/testes-manuais-2-3/cenarios/recuperacao-2026-11-18. Seus dados e credenciais não são os históricos. Para consultar o controlador ativo, usar Controlar.ps1 -Acao status -Cenario recuperacao-2026-11-18. Não executar o Iniciar/Finalizar padrão supondo acessar esse cenário.

O consolidado final dos 57 casos, o procedimento complementar do T50 e as evidências ficam somente locais em tests/manual/etapa-2.3/relatorios/auditoria-final-2026-11-18 e evidencias/auditoria-final-2026-11-18. O CSV e o roteiro originais permanecem intactos. T01–T19 vêm da confirmação do proprietário; T20–T57 combinam evidências assistidas e retomadas documentadas. A nova aba operacional pode exigir PIN porque ocultar a anterior bloqueia a sessão compartilhada; reidentificar o Atendente antes de Trocar operador não dispensa controles de segurança.
