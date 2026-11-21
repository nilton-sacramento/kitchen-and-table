# Entrega 2.7 — Caixa, pagamentos e relatórios

Feature feature/entrega-2-7-caixa-pagamentos, base develop 28cb33ba6d5fc8353b9995598bf40ea38c563849. Não representa deploy, aprovação de piloto ou merge.

## Implementado

- Caixa único por estabelecimento e índice parcial impedindo dois turnos abertos. Política inicial exige escolha explícita do proprietário: limite positivo ou ilimitado de operadores, limites de desconto (valor e percentual), sangria e diferença. Sem concessões financeiras automáticas por função.
- Abertura por operador com PIN, permissão específica e CAIXA_OPERAR; valor inicial físico separado de receita. Equipe/responsável alterados com versão, justificativa, PIN ativo e permissões já existentes. Adicionar à equipe não concede capacidades.
- Conta dos snapshots reais dos pedidos. Combo cobrado pela linha comercial, nunca novamente pelos componentes. Cancelados e originais substituídos permanecem no histórico, excluídos do consumo cobrável.
- Recebimentos parciais/combinados em dinheiro, Pix, débito e crédito. Dinheiro entregue/troco separados do valor aplicado. Pix/cartão exigem conferência externa explícita; sem conexão bancária, cobrança remota ou PAN/CVV.
- Desconto por valor/percentual com arredondamento de meio centavo para cima, motivo e limites acumulados. Ambos os limites precisam ser respeitados. Acima deles, autorização específica do proprietário.
- Compensação integral de lançamento original: correção de registro ou estorno, com autorização e original imutável. Reversão de desconto também exige aprovação. Estorno externo de Pix/cartão fica pendente até confirmação manual da devolução feita fora do sistema. Correção interna não afirma devolução bancária.
- Sangria e reforço físicos com motivo; sangria não ultrapassa dinheiro esperado e acima do limite exige aprovação. Abertura/reforço não viram receita.
- Quitação financeira apenas com saldo zero, pedidos entregues/cancelados e sem solicitações/devoluções externas pendentes. Certificado imutável. As flags teve_consumo e possui_pendencias não são apagadas para forçar encerramento. Fluxo antigo sem consumo preservado e separado.
- Conferência imutável esperado/contado/diferença vinculada à versão do turno; movimento posterior a invalida. Fechamento bloqueia comandas abertas com consumo/pedidos/lançamentos, solicitações e devoluções pendentes. Não encerra comandas automaticamente; vazias podem permanecer abertas. Diferença acima do limite exige autorização.
- Relatório automático imutável por ciclo. Reabertura por sessão administrativa de proprietário/gerente com permissão específica, motivo e versão, sem outro turno aberto. Relatórios anteriores preservados; fechamento seguinte cria novo ciclo.
- Relatórios por unidade, período (início inclusive, fim exclusive), meio e funcionário autorizado: recebimentos, vendas quitadas, descontos, sangrias, reforços, compensações, cancelamentos/substituições, conferências e ciclos. Consolidação só para proprietário revalidado em cada unidade. Histórico individual exige capacidade adicional.

## Segurança e transações

Toda API valida identidade, vínculo ativo, unidade e permissões atuais; mutações operacionais exigem PIN/sessão e equipe do turno. Identificadores do cliente nunca substituem o principal. Sessão administrativa não vira operador; PIN não dá autoridade administrativa. CSRF/cookies/expiração anteriores preservados.

Ordem principal: revalidação existente (unidade compartilhada, credencial e sessão), trava financeira transacional por unidade, política, turno e comanda/pedidos. Fechamento usa política exclusiva para coordenar também novas comandas/consumo. Mutações financeiras da mesma unidade são serializadas para preservar saldo; unidades distintas não compartilham esse bloqueio. Monitorar contenção antes de escalar.

Pagamento/desconto líquido impede solicitação/aceite de cancelamento ou substituição operacional. Comanda travada protege decisão e cálculo. Novos pedidos podem atualizar saldo e invalidam revisão anterior. Preparo/entrega preservam permissões setoriais.

Autorização pontual do proprietário, sem alegar delegação temporária genérica: dez minutos no relógio do servidor; solicitante/PIN, unidade, turno, entidade, tipo, corpo normalizado, versão da política e autorizador vinculados. Uso único, consumo na transação do lançamento/auditoria. Revogação, expiração, mudança de PIN/corpo/política impedem consumo. Decisões versionadas e auditadas. Não há código secreto de associação nesse fluxo.

Idempotency-Key UUID por intenção, unidade/executor/tipo/corpo. Mesma chave retorna resultado persistido; corpo diferente conflita. Banco impede compensação duplicada e protege ledger/conferências/relatórios/certificados/respostas idempotentes contra alteração/exclusão.

Frontend usa BigInt e strings de centavos. Lançamento exige revisão/confirmar. Chave e corpo congelado só em memória; resultado desconhecido exige GET caixa/operacoes/{chave} antes de reenviar. Falha da consulta não permite reenvio. Atualizar/sair/bloquear descarta intenção: consultar histórico antes de começar outra. Não há fila financeira offline nem fechamento offline.

## Arquivos e APIs

| Área | Caminhos |
| --- | --- |
| Política, equipe, caixa e autorizações | src/main/java/com/kitchentable/caixa/ |
| Conta, pagamento, desconto e quitação | src/main/java/com/kitchentable/pagamentos/ |
| Relatórios | src/main/java/com/kitchentable/relatorios/ |
| Interface operacional | src/main/resources/static/assets/caixa-operacional.js e conta.js |
| Administração e relatórios | src/main/resources/static/assets/caixa-admin.js e relatorios.js |
| Dinheiro e confirmação | src/main/resources/static/assets/financeiro.js e financeiro-ui.js |
| Integração | app.js, atendimento.js, operacional.js, static/index.html |
| Testes Java | src/test/java/com/kitchentable/caixa/ |
| Node/Chrome | scripts/test-financeiro.mjs e scripts/smoke-caixa.mjs |

APIs administrativas: /api/v1/estabelecimentos/{unidade}/caixa. Operacionais: /api/v1/operacional/estabelecimentos/{unidade}/caixa com X-Operador-Vinculo. Conta em .../comandas/{id}/conta; recebimentos/descontos/quitação em .../caixa/turnos/{turno}/comandas/{comanda}; compensações/confirmação em .../lancamentos/{id}. Relatórios em .../relatorios-financeiros; consolidação em /api/v1/financeiro/consolidado. POST financeiro exige Idempotency-Key e CSRF; PUT versionado exige CSRF. Contratos completos nos records CaixaDtos, PagamentoDtos e controllers reais.

## Migrações

V1–V16 byte a byte preservadas. V17 cria estruturas financeiras. V18 ativa capacidades implementadas e adiciona cinco códigos, sem atribuir poderes financeiros a funções/usuários. V19 valida saldo, quitação e fechamento no banco; consumo só pode ser encerrado com certificado correspondente, mantendo as travas históricas.

Testes PostgreSQL 16 descartável: instalação limpa V1→V19 e upgrade V16→V19 com credencial/hash legado e comanda com consumo, sem repair/baseline/clean. Não executar no operacional. Reversão de aplicação exige plano compatível com V19 e backup validado, sem apagar ledger; não fornecemos rollback destrutivo.

## Executar e validar

Requisitos em README/CONTRIBUTING. Não iniciar com datasource operacional.

1. mvn --batch-mode --no-transfer-progress verify, com Docker disponível. Testcontainers cria bancos próprios em loopback/portas aleatórias.
2. node --test scripts/test-interface-contexto.mjs scripts/test-atendimento.mjs scripts/test-cardapio.mjs scripts/test-pedidos.mjs scripts/test-financeiro.mjs
3. node scripts/smoke-funcionarios.mjs depois de Maven. Fixture Spring Boot/PG16 e Chrome próprios, credenciais sintéticas em memória; capturas ignoradas em .tools/browser-artifacts. Não executar smoke-caixa isoladamente.
4. Ambiente manual somente descartável conforme scripts/README.md. Proprietário configura política e concede permissões individuais antes do acesso por PIN.

Validação local final em 21/11/2026: Maven verify com 355 testes aprovados, sem falhas, erros ou ignorados; 42 testes Node; 46 verificações Chrome com backend real; 26 verificações Chrome com API controlada. Compilação, empacotamento, sintaxe e actionlint aprovados. O CI deve ser conferido no SHA da PR publicada. Automação não equivale à aprovação manual do proprietário/piloto.

## Limitações e piloto

Sem integração bancária, estorno bancário automático, emissão fiscal, despesas/categorias futuras ou recursos 2.8. Compensação integral; ajuste parcial pode usar reversão integral aprovada e novo recebimento correto, com revisão de saldo e original preservado. Correção complexa após quitação bloqueada; não reabre atendimento silenciosamente. Reabrir caixa não reabre comanda.

Delegação crítica temporária genérica não foi inventada: autorização específica do proprietário e limites existentes. Funções adicionais não criam autoridade administrativa.

Antes de piloto: HTTPS/cookie Secure, backup/restauração, retenção/auditoria, observabilidade/limites de taxa, dispositivos compartilhados, sessões em múltiplas instâncias, conciliação com extratos reais e treinamento humano. HTTP local e smoke não aprovam produção. Perda de conexão exige consulta ao servidor para resolver resultado incerto.

## Atualizações contratuais de testes anteriores

Os 276 casos anteriores são mantidos. Contagens de catálogo/migrações acompanham 68 códigos e 19 migrações. O teste de delegação crítica continua exigindo código expresso e rejeitando gerente fora do limite; após concessão explicitamente autorizada, CAIXA_FECHAR agora fica efetiva porque o módulo foi implementado (antes estava indisponível). Isso não concede CAIXA_OPERAR, participação na equipe ou PIN. O bloqueio de módulos financeiros futuros no encerramento antigo usa DESPESA_REGISTRAR, ainda futura, em vez de PAGAMENTO_REGISTRAR, agora real. Nenhum teste removido ou ignorado.

## Cobertura automatizada

Os 79 casos financeiros novos complementam os 276 anteriores: 2 de migrações, 42 de domínio/integração, 23 de segurança/API e 12 de concorrência. Métodos descritivos nas classes FinanceiroMigrationTest, FinanceiroPostgresTest, FinanceiroSegurancaTest e FinanceiroConcorrenciaTest. Nenhum caso anterior removido ou ignorado.

Concorrência em PostgreSQL real: dois operadores recebem o mesmo saldo; mesma chave simultânea; resposta perdida e consulta; novo pedido versus revisão; cancelamento versus recebimento; duas quitações; fechamento versus pagamento; duas sangrias; mutação em turno fechado; revogação antes da confirmação; duas compensações; reabertura versus fechamento. Travas e restrições são exercitadas com transações reais.

Node cobre precisão, limites, escopo, corpo congelado, rotas e consulta antes do reenvio. Chrome real cobre política/equipe, pedidos históricos, desconto autorizado, dinheiro/troco, Pix/cartões, resposta perdida, estorno/devolução, quitação, movimentos, fechamento/reabertura/relatórios, segunda unidade e regressão de PIN/CSRF/sessões em desktop/tablet/360px. Chrome com API controlada é evidência separada, não aprovação de integração. Os 57 resultados manuais históricos permanecem preservados, sem simular nova aprovação manual.

## Evidências e correções da implementação

Corrigida a consulta de caixa para exigir uma das capacidades financeiras atuais, em vez de uma capacidade administrativa adicional que caixas não recebem por padrão. Sessão, PIN, vínculo e unidade continuam obrigatórios. Caixa sem turno agora retorna HTTP 204; o cliente também trata resposta vazia. Há regressões de API/autorização, histórico individual e contexto. Formulários financeiros são inseridos antes de ações ficarem disponíveis, CSS usa o asset app.css existente, conta aparece antes da lista extensa de pedidos e ações não autorizadas ficam ocultas.

Evidências locais ignoradas: .tools/entrega-2-7/verify-publicacao.log, node-final.log, chrome-publicacao.log, chrome-controlado-final.log e evidencias/manifesto-final.json (18 capturas financeiras). Os hashes das 16 migrações históricas e dos 155 materiais manuais foram conferidos. Nenhum arquivo operacional ou dado real de banco foi utilizado. Rodadas intermediárias interrompidas não foram usadas como aprovação; compilar antes de iniciar a fixture Chrome, que utiliza target/classes.

A regressão de quitação também comprova uma comanda de mesa real: pagamento integral mantém a mesa ocupada; a quitação financeira libera a mesa e mantém consumo, pendências históricas e bloqueio de novos pedidos na comanda encerrada. Os 42 casos financeiros foram reexecutados com sucesso em PostgreSQL 16.
