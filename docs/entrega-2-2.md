# Entrega 2.2 — funções e permissões

Implementação na feature/entrega-2-2-funcoes-permissoes, base develop 2c4108acc8752d63333c57eea3ad902241997aba. Este documento não autoriza merge, implantação ou migração operacional.

## Implementado

- Função principal preservada e até quatro funções adicionais operacionais (ATENDENTE, CAIXA, COZINHA, BAR), sem repetição da principal ou atribuição adicional de GERENTE/PROPRIETARIO.
- Catálogo com 52 códigos: três legados preservados e 49 códigos da matriz técnica, com nome, descrição, módulo, categoria, criticidade, disponibilidade e restrição administrativa.
- Padrões operacionais conservadores por função; nenhum padrão financeiro ou administrativo para funcionários. ESTABELECIMENTO_VISUALIZAR é uma capacidade básica de vínculo ativo.
- Exceção individual: HERDAR remove o registro; PERMITIR grava true; NEGAR grava false e prevalece sobre todas as heranças.
- Resultado configurado e resultado efetivo separados. Módulo futuro/legado inativo sempre apresenta efetiva=false.
- Seis delegações da 2.1 integradas ao avaliador: são a origem explícita das capacidades do gerente, sem concessão automática por função. Permitir individualmente não contorna a necessidade de delegação. Negar pode restringir uma delegação existente.
- Gestão de permissões por gerente: proprietário escolhe alvos comuns específicos, capacidade de alterar adicionais e códigos concedíveis/revogáveis independentes. Nem gerente, nem proprietário, nem o próprio executor podem ser alvos da delegação de gerente.
- Criticidade exige limite expresso por código quando delegada. A autorização para gerenciar limites, nomear gerente ou alterar autoridade administrativa não pode ser atribuída por uma exceção genérica.
- Revalidação no backend em cada operação e após locks em mutações, com versão do vínculo e auditoria na mesma transação.
- Interface de funções, origens, exceções, resultado, criticidade e delegações reais, em desktop/tablet/mobile.

## Escolhas conservadoras e limites

As classificações/padrões da matriz não são declarados decisões históricas. A implementação utiliza as sugestões operacionais como contratos futuros sem liberar operações. Não há permissões financeiras automáticas, inclusive para CAIXA e GERENTE. Permissões futuras podem ser configuradas antecipadamente; não são autorização para operar módulos inexistentes. Antes de ativar cada módulo, sua política de autorizações específicas, limites e aprovações deve ser revisada.

Funções adicionais nunca alteram autoridade administrativa. Somente a função principal GERENTE combinada às delegações autoriza as ações administrativas existentes. O proprietário mantém sua autoridade, mas o módulo não administra seu acesso e não cria bypass de integridade ou recursos futuros.

Para gerente, voltar a HERDAR exige poder tanto de conceder quanto de revogar o código: remover uma negação pode aumentar privilégios e remover uma concessão pode reduzi-los. Esse limite intencional impede usar HERDAR como atalho. Permissões reservadas aos fluxos administrativos não aceitam exceção individual.

A troca da função principal remove eventual adicional repetida e elimina as duas famílias de delegação do vínculo. Também remove esse vínculo das listas de alvos delegados para impedir que uma promoção/rebaixamento restaure acesso antigo; o proprietário pode autorizar novamente. A auditoria registra adicionais, gestores e novos limites anteriores. Exceções individuais permanecem registradas, sempre submetidas à hierarquia; elas não ressuscitam autoridade administrativa sem nova delegação.

Revogar uma delegação impede novas alterações pelo gerente. Não desfaz retroativamente alterações que ele realizou quando autorizado. As permissões dos funcionários continuam independentes e são modificadas por um administrador autorizado.

## Banco e migrações

V1, V2, V3 e V4 não são modificadas. V5 adiciona vinculos_funcoes_adicionais, chave composta por unidade/vínculo e triggers de validação para funções permitidas e não repetição. V6 amplia permissoes e preenche funcoes_permissoes com padrões conservadores. usuarios_permissoes é reutilizada, mantendo unicidade por vínculo/código.

V7 cria limites_gestao_permissoes, limites_gestao_alvos e limites_gestao_codigos. Chaves compostas impedem alvos de outra unidade. Concessão e revogação são flags independentes. A delegação identifica o proprietário autorizador, data e versão, sem sementes iniciais. O backend valida os papéis dos envolvidos além das FKs.

As migrações não modificam senhas, hashes, UUIDs ou contas existentes. A atualização é testada de V1 e de V4 até V7, com validação de checksums e preservação de credenciais/delegações anteriores, exclusivamente em PostgreSQL 16 descartável.

O startup normal do Spring Boot executa Flyway. Não iniciar esta feature com a URL do PostgreSQL operacional. Uma futura aplicação real exige aprovação específica, backup protegido e ensaio de restauração. Nenhuma migração operacional foi executada nesta entrega. Não usar clean, drop, truncamento, recriação de volumes ou migração antiga editada.

## Cálculo e autorização

PermissoesService é o avaliador central. O vínculo e o estabelecimento vêm da URI e da identidade da sessão, nunca de um executor enviado no corpo.

Base = união dos padrões da principal e adicionais. A base recebe as seis capacidades administrativas explicitamente delegadas ao gerente. Exceções são aplicadas, com NEGAR prevalente. Depois são aplicados os limites de hierarquia, autoridade exclusiva e disponibilidade. Proprietário não recebe bloqueio individual e não habilita contratos futuros.

O catálogo não é um mapa de authorities congeladas no Spring Security. Sessão continua apenas identificando o usuário. AutorizacaoAdministrativaService utiliza o avaliador nas ações existentes. Alterar função principal também possui validação própria, separada da edição de contato. ContaController filtra unidades com negação de ESTABELECIMENTO_VISUALIZAR; FuncionarioRepository revalida essa negação nas operações protegidas. Identificação da conta por /auth/me permanece preservada.

Consultas usam transações somente de leitura e snapshot REPEATABLE_READ, sem FOR UPDATE. Uma leitura iniciada antes de revogação confirmada pode terminar no snapshot anterior. Novas chamadas revalidam o estado atual. Escritas usam o lock de unidade existente, READ_COMMITTED e versão otimista. Aceite entre unidades mantém ordem estável de locks e não copia adicionais, exceções ou limites.

PUTs exigem versão e não inserem duplicatas: repetição com versão antiga retorna 409, inclusive se a resposta anterior se perder; o cliente deve recarregar e confirmar o estado. Cadastros/associações continuam com as chaves de idempotência da 2.1. Não há repetição automática de escrita com versão atualizada silenciosamente.

## APIs reais

Base: /api/v1/estabelecimentos/{unidade}/permissoes

| Método | Caminho | Uso |
| --- | --- | --- |
| GET | /catalogo | Catálogo para usuário vinculado à unidade |
| GET | /capacidades | Proprietário e alvos delegados ao gerente atual |
| GET | /minhas | Resultado e origens do usuário autenticado |
| GET | /alvos | Funcionários visíveis para esta gestão |
| GET | /funcionarios/{id} | Principal, adicionais, exceções e efetivas do alvo autorizado |
| PUT | /funcionarios/{id}/adicionais | Substituir conjunto operacional com versao |
| PUT | /funcionarios/{id}/individuais/{codigo} | Estado HERDAR/PERMITIR/NEGAR e versao |
| GET | /funcionarios/{id}/limites | Proprietário consulta limites do gerente |
| PUT | /funcionarios/{id}/limites | Proprietário substitui alvos, códigos, adicionais e versao |

401 sem sessão; 403 sem autoridade; 404 alvo fora da unidade; 400 entrada inválida; 409 versão/integridade conflitante. Toda mutação exige CSRF. Não há endpoints para executar caixa, estoque, pedidos ou pagamentos.

## Auditoria

Eventos: FUNCOES_ADICIONAIS_ALTERADAS, PERMISSAO_INDIVIDUAL_ALTERADA, LIMITES_PERMISSOES_ALTERADOS e ampliação do snapshot de FUNCAO_PRINCIPAL_ALTERADA.

Os registros incluem executor/vínculo/unidade/alvo, antes/depois, resultado APLICADA, data do evento e autorizador. Para um gerente, autorizador é o proprietário que concedeu o limite, com versão da autorização. Isso é rastreabilidade de delegação, não uma aprovação financeira por operação. Falha da auditoria reverte alteração, versão e limites. Não são registrados senha, PIN, token ou identificador de sessão.

O módulo não permite apagar histórico. Não fornece trilha externa imutável nem registra cada leitura/negação como evento persistente. Auditoria avançada/retencão continua pendente.

## Código no Antigravity

Backend: src/main/java/com/kitchentable/funcionarios/PermissoesService.java, PermissoesController.java, PermissoesDtos.java e integração em AutorizacaoAdministrativaService, FuncionarioService, FuncionarioRepository, FuncionarioExceptionHandler e auth/ContaController.java.

Frontend: src/main/resources/static/assets/permissoes.js, funcionarios.js e app.css. A página HTML existente é reutilizada; o editor cria elementos DOM seguros, sem inserir HTML de dados externos.

Banco: src/main/resources/db/migration/V5__funcoes_adicionais.sql, V6__catalogo_permissoes.sql, V7__limites_gestao_permissoes.sql.

Testes: FuncionariosPostgresTest.java, FuncionariosBrowserFixture.java e scripts/smoke-funcionarios.mjs. Os 18 testes da Etapa 1 permanecem intactos. Na suíte da 2.1, a expectativa do total de migrações passa de quatro para sete para refletir a evolução; não há remoção ou desativação de casos anteriores.

## Como validar sem banco operacional

Pré-requisitos: Java 17, Maven, Docker em modo Linux, Node 24 e Chrome instalado. Não fornecer URL/credencial operacional aos testes.

```powershell
mvn --batch-mode --no-transfer-progress '-Ddebug=false' '-Dlogging.level.org.springframework=INFO' verify
node scripts/smoke-funcionarios.mjs
```

A fixture cria banco e servidor exclusivos em loopback, sem porta 8080, volumes de dados reais ou perfil pessoal de navegador. Credenciais sintéticas são geradas em memória. O smoke usa sessões independentes de proprietário/gerente e aceita confirmações nativas somente no controlador de teste, inclusive após F5. Capturas ficam em .tools/browser-artifacts e não são publicadas no GitHub.

O CI mantém checkout do SHA real, token somente de leitura, testes obrigatórios e smoke. Docker indisponível provoca falha, não skip. Resultados reais e SHA final devem ser conferidos no PR.

## Reservado para próximas entregas

PIN, recuperação/bloqueio operacional, temporários, aprovação financeira avançada, execução de módulos futuros, sessões distribuídas e implantação de produção não fazem parte desta implementação. Funcionários sem credenciais administrativas continuam sem login até o fluxo operacional da Entrega 2.3.

## Riscos conhecidos

Sem RLS: isolamento depende das consultas e serviços, reforçados por FKs e testes. Locks por unidade serializam mutações e devem ser medidos antes de maior escala. Permissões são reconsultadas sem cache; otimização futura não pode congelar autorizações. A interface atualiza controles após consulta, recarga ou resposta negada; não há notificação push de revogação. Revisão humana de acessibilidade e dispositivos físicos continua recomendada.

Rollback de código não autoriza apagar novos registros; versões antigas não conhecem exceções e não devem voltar a atender requisições sem plano que preserve as restrições de acesso. Mudanças futuras dos padrões por função podem alterar muitos vínculos e exigem revisão/migração explícita.
## Evidências locais da entrega

Maven verify aprovado: 96 testes, zero falhas, erros ou ignorados. São 58 casos anteriores preservados e 38 casos novos (78 na suíte PostgreSQL e 18 da Etapa 1). O teste de contagem de migrações foi atualizado de 4 para 7; não houve exclusão de testes. Atualizações reais V1→V7 e V4→V7 foram verificadas em PostgreSQL 16 descartável, preservando hashes, vínculos, delegações e checksums anteriores. JAR empacotado sem as fixtures de teste.

Smoke Chrome final aprovado: fluxos anteriores, adicionais, três estados persistidos, origens/resultados, formulário real de limites, duas sessões isoladas, bloqueio de gerente sem limite, conflito de versão, concessão/revogação com sessão aberta e controles atualizados após 403. Layouts desktop 1366x900, tablet 768x1024 e mobile 360x800, com teclado e capturas locais. Actionlint, sintaxe dos quatro scripts JavaScript e diff check aprovados.

Durante a implementação foram corrigidas expectativas de teste que confundiam acesso à unidade com autoridade administrativa, sincronização do controlador do navegador, confirmação após F5 e uso do endpoint CDP de navegador para sessões isoladas. Na revisão, resultados efetivos foram alinhados à inatividade/negação da unidade; alvos explícitos da nova delegação foram unidos à lista antiga sem ampliar as ações por função; troca de função passou a limpar referências de delegação para evitar ressurreição de acesso. Resultados remotos devem ser confirmados no PR, sem presumir aprovação a partir destes resultados locais.
## Atualização da revisão final do PR #4

A revisão sobre c2bfcc5 identificou e corrigiu uma corrida de integridade dos triggers sob SQL concorrente e o botão de exceção que aceitava uma opção fora do limite visual do gerente. V1–V7 permanecem intactas; foi acrescentada V8__serializacao_funcoes_adicionais.sql. A suíte passa a 98 casos, incluindo as duas ordens da corrida, e verifica também V7→V8 com checksums preservados. O smoke passa a cobrir gerentes apenas-conceder e apenas-revogar. Consulte docs/revisao-final-entrega-2-2.md e o CI do HEAD atualizado; os 96 testes acima são evidência da versão inicial.