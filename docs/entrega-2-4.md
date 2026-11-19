# Entrega 2.4 — Mesas e comandas

## Estado e rastreabilidade

Implementação local na branch feature/entrega-2-4-mesas-comandas, criada da develop limpa em 2c3322e76d626f83a898d00338cdd4cde46abac0. A publicação controlada da feature e de um Pull Request Draft para develop foi autorizada posteriormente. Merge e deploy continuam proibidos. A main permanece preservada. Os resultados desta revisão serão consolidados ao término da validação no relatório local .tools/entrega-2-4/RELATORIO-FINAL.md; este documento descreve os contratos implementados, não aprova testes manuais do proprietário.

## Funcionalidades reais

- Administração de mesas: cadastro, identificação, ativação/desativação, consulta e histórico por estabelecimento. Identificação normalizada e única na unidade; pode repetir em outra unidade. Não há exclusão.
- Situação derivada: indisponível quando inativa, ocupada se há comanda aberta, livre nos demais casos. Não existe um segundo campo editável de ocupação. Desativar mesa ocupada retorna conflito.
- Atendimento por sessão operacional com PIN: comandas de mesa ou balcão, código sequencial legível por unidade, identificação opcional de cliente, lista, detalhes e histórico.
- Qualquer operador autorizado da unidade pode finalizar uma comanda aberta por outro. Abertura é imutável; cada evento registra o executor real.
- ABERTA transita uma vez para ENCERRADA ou CANCELADA. Cancelamento exige justificativa. Não há reabertura nem exclusão de histórico.
- Encerramento é exclusivamente operacional e não registra pagamento. Só funciona sem marca de consumo, sem pendências e com integrações futuras de consumo/financeiro ainda indisponíveis.

Produtos, pedidos, pagamentos, saldo, cobrança, caixa financeiro e relatórios financeiros não foram implementados. Não há dados fictícios para representar esses módulos.

## Organização e arquivos para abrir no IDE

| Área | Arquivos/componentes |
| --- | --- |
| Backend mesas | src/main/java/com/kitchentable/mesas/: MesaController, MesaService, MesaRepository, MesaDtos |
| Backend comandas | src/main/java/com/kitchentable/comandas/: ComandaController, ComandaService, ComandaRepository, ComandaDtos, AtendimentoExceptionHandler |
| Autorização reutilizada | funcionarios/AcessoAtendimentoService, PermissoesService, OperacionalService e FuncionarioRepository |
| Auditoria reutilizada | funcionarios/AuditoriaAdministrativaService; tabela auditoria_eventos |
| Frontend | static/index.html, assets/app.js, assets/mesas.js, assets/atendimento.js, assets/operacional.js, assets/app.css |
| Migrações | src/main/resources/db/migration/V11__mesas_comandas.sql e V12__permissoes_mesas_comandas.sql |
| Testes PostgreSQL | src/test/java/com/kitchentable/comandas/MesasComandasPostgresTest.java |
| Node/Chrome | scripts/test-atendimento.mjs, smoke-mesas-comandas.mjs, smoke-operacional.mjs; entrada smoke-funcionarios.mjs |

Maven, HTML/CSS/JavaScript nativo e empacotamento pelo Spring Boot foram preservados. Não há projeto paralelo ou migração de pacotes existentes.

## Banco e compatibilidade

V1–V10 não devem ser editadas. V11 cria mesas, comandas, sequencias_comandas e operacoes_abertura_comanda. Não recria tabelas existentes. As FKs compostas impedem associar mesa e executor de outra unidade. O índice parcial ux_comanda_mesa_aberta garante uma única comanda aberta por mesa, inclusive contra concorrência.

Triggers preservam escopo, abertura e histórico, impedem exclusão e remoção da marca de consumo. Mesa inativa não pode receber abertura. Desativação com comanda aberta é recusada também pelo banco. Consumo/pendências não são editáveis nas APIs desta entrega.

V12 habilita os três códigos de atendimento já catalogados e adiciona três capacidades explícitas. Não concede novos padrões críticos nem poderes de gerente. Os padrões operacionais já existentes de Atendente/Caixa passam a corresponder a funcionalidades reais; negações individuais permanecem respeitadas.

O teste específico aplica V1–V10, inclui uma credencial administrativa sintética, aplica V11/V12, compara os dez checksums e faz login real. A suíte anterior continua exercitando instalação limpa e atualização desde V1, V4 e V8, com expectativas de catálogo/contagem atualizadas. Não existe rollback destrutivo automático: preservar histórico, parar a aplicação e restaurar um backup validado em ambiente isolado se uma reversão de dados for indispensável. Voltar só o binário não remove tabelas novas. Não executar contra o banco operacional.

## API administrativa (sessão administrativa)

Prefixo /api/v1/estabelecimentos/{unidade}/mesas:

| Método/caminho | Contrato |
| --- | --- |
| GET / | Mesas da unidade |
| GET /{id} | Detalhe da mesa |
| POST / | {identificacao} |
| PATCH /{id} | {identificacao, versao} |
| PATCH /{id}/situacao | {ativo, versao} |
| GET /{id}/historico | Uso por comandas, inclusive finalizadas |

Toda operação exige usuário/vínculo/unidade ativos, função principal Proprietário ou Gerente e MESA_GERENCIAR efetiva. Sessão operacional não administra mesas. Gerente adicional não se torna autoridade administrativa.

## API operacional (sessão PIN)

Prefixo /api/v1/operacional/estabelecimentos/{unidade}:

| Método/caminho | Permissão/contrato |
| --- | --- |
| GET /mesas e /mesas/{id} | MESA_CONSULTAR |
| GET /comandas[?estado=ABERTA] e /comandas/{id} | COMANDA_CONSULTAR |
| GET /comandas/{id}/historico | COMANDA_CONSULTAR |
| POST /comandas | ATENDIMENTO_ABRIR; {modalidade:MESA ou BALCAO, mesaId, cliente}; header Idempotency-Key UUID |
| POST /comandas/{id}/encerrar | COMANDA_ENCERRAR; {versao, justificativa opcional} |
| POST /comandas/{id}/cancelar | COMANDA_CANCELAR; {versao, justificativa obrigatória} |

Todas exigem X-Operador-Vinculo correspondente ao contexto da tela. Esse header é apenas uma precondição contra abas antigas: nunca autentica, seleciona executor ou concede acesso. O executor é extraído exclusivamente de OperadorPrincipal. Troca de cookie/principal em outra aba impede que uma tela antiga envie operação como a nova pessoa. Sessão administrativa não substitui identificação operacional.

Mutações exigem CSRF válido. Códigos: 400 entrada inválida, 401 sem autenticação, 403 permissão/contexto incompatível, 404 recurso ausente no escopo, 409 versão/mesa ocupada/duplicidade/transição inválida, 423 sessão operacional bloqueada ou expirada. Respostas não incluem SQL, hashes, PINs, senhas ou tokens de associação.

## Permissões e revogação

| Código | Natureza | Padrão |
| --- | --- | --- |
| MESA_GERENCIAR | Administrativa crítica; proprietário ou gerente principal autorizado | Sem concessão automática a gerente |
| MESA_CONSULTAR | Operacional; catálogo existente | Heranças preexistentes de Atendente/Caixa; negação individual prevalece |
| ATENDIMENTO_ABRIR | Operacional; catálogo existente | Heranças preexistentes de Atendente/Caixa |
| COMANDA_CONSULTAR | Operacional; catálogo existente | Heranças preexistentes de Atendente/Caixa |
| COMANDA_ENCERRAR | Operacional crítica | Concessão explícita |
| COMANDA_CANCELAR | Operacional crítica | Concessão explícita |

Permissões efetivas são calculadas pelo avaliador existente em cada operação, na mesma transação. O proprietário delega pelos controles existentes; gerentes continuam sujeitos a alvos, códigos e limites da Entrega 2.2. Não há cache de permissão nem autorização baseada só em botões. Revogação confirmada impede a próxima operação, inclusive retentativa idempotente.

## Concorrência e consistência

Administração preserva o bloqueio por unidade do sistema existente. Atendimento reutiliza o lock compartilhado da unidade, credencial e sessão operacional, protegendo a revalidação contra revogação administrativa. Leituras não bloqueiam mesas/comandas para escrita; o lock da sessão operacional continua necessário à validação de expiração.

Abertura serializa a chave de idempotência com advisory transaction lock; a mesma unidade/chave/executor/conteúdo devolve a mesma comanda. Outro conteúdo/executor retorna conflito. Chave não é credencial e não é persistida em armazenamento do navegador. A tela mantém intenção imutável em memória durante retentativas. Recarregar perde a intenção: consulte a lista antes de tentar uma abertura novamente após falha de conexão.

Mesas são bloqueadas antes de comandas, tanto ao abrir quanto ao finalizar, evitando inversão entre as duas operações. Versões impedem sobrescrita; índice parcial é a última barreira contra duas comandas simultâneas. A sequência por unidade gera códigos sem colisão. Finalização concorrente produz uma transição e um evento. Não há exclusividade por autor original.

## Auditoria e segurança da interface

Mesa criada/editada/ativada/desativada e comanda aberta/finalizada produzem eventos na transação da operação. Falha de auditoria reverte a operação. Eventos contêm unidade, usuário e vínculo reais, nome do executor capturado no momento, entidade, antes/depois e justificativa. O histórico da comanda consulta os eventos no escopo da unidade.

Texto é renderizado com textContent. Não há HTML de cliente/mesa inserido diretamente. A tela invalida requisições e limpa dados ao trocar unidade, operador, bloquear ou sair; respostas antigas não repovoam outra unidade. Administração preserva unidade/seção no hash após F5. A tela operacional preserva seção/comanda no hash, mas revalida a sessão/PIN no backend e não armazena credenciais. Nenhuma identificação do cliente ou rascunho de comanda é gravada em Web Storage.

## Validação e reprodução

Com Docker Desktop ativo, Java 17, Maven, Node 24 e Chrome, na raiz:

~~~powershell
mvn --batch-mode --no-transfer-progress '-Ddebug=false' '-Dlogging.level.org.springframework=INFO' verify
node --test scripts/test-interface-contexto.mjs scripts/test-atendimento.mjs
node scripts/test-interface-browser.mjs
node scripts/smoke-funcionarios.mjs
~~~

As fixtures criam seus próprios bancos PostgreSQL 16 e não leem DB_URL operacional. O smoke oficial cobre as entregas anteriores e a extensão de mesas/comandas. Capturas ficam em .tools/browser-artifacts e devem ser preservadas antes de repetir. Os 57 registros manuais históricos da Entrega 2.3 permanecem separados, sem redefinição de resultados.

## Limites e riscos para revisão

- Encerramento não é pagamento; integrações futuras precisam bloquear/finalizar com base no consumo real. Ao habilitar PEDIDO_REGISTRAR, PEDIDO_ENVIAR, PAGAMENTO_REGISTRAR ou CAIXA_OPERAR, o guard recusa finalização até revisão explícita. Não liberar esses códigos para contornar o guard.
- Sequência e operações por unidade/sessão têm serialização deliberada; medir carga real antes de piloto. Listagens ainda não são paginadas.
- Sessões continuam locais à instância; HTTPS, cookie Secure, backups, rate limiting e monitoramento seguem obrigatórios para piloto. Nenhum teste local aprova produção.
- Histórico possui nomes/identificação de cliente: definir retenção e controle de acesso operacional antes do piloto. Não publicar evidências privadas.
- Operações em tela antiga recebem bloqueio/revalidação; não há sincronização automática em tempo real de todas as mesas. Use Atualizar lista; conflito é confirmado pelo backend.
- Revisão e autorização posteriores são necessárias antes de publicar ou integrar. Não iniciar pedidos/caixa/financeiro nesta entrega.

## Ambiente para revisão visual

Após mvn verify, node scripts/ambiente-atendimento.mjs inicia uma fixture nova com PostgreSQL 16 e aplicação reais em loopback. A URL e o arquivo local privado de acessos aparecem no terminal. Há duas unidades, proprietário, Atendente e Caixa, sem usar dados reais. Ctrl+C encerra somente essa fixture. Consulte docs/instalacao-local.md.

## Resultados locais em 18/11/2026

- Maven verify: 183 testes aprovados, zero falhas, erros ou ignorados; 143 anteriores preservados e 40 novos de mesas/comandas.
- Node: 26 testes aprovados (20 anteriores e seis novos).
- Chrome com API controlada: 26 verificações aprovadas, sem substituir a validação real.
- Smoke oficial com backend e PostgreSQL 16 descartável: aprovado, incluindo atendimento compartilhado real entre dois operadores, abertura preservada, executor auditado, F5, unidades, desktop/tablet/360px e todas as verificações anteriores de PIN/logout.
- V1–V10 e 155 materiais históricos do manifesto local preservados por SHA-256.

Correções encontradas durante a implementação: limpeza indevida ao ativar a tela de mesas, envio obsoleto após obter CSRF na troca de unidade e distinção entre cliente ausente e o texto literal null na idempotência. As regressões correspondentes foram exercitadas. Os testes não acessaram o banco operacional. Os resultados acima descrevem a validação local anterior. Na publicação controlada, os testes são repetidos e o CI deve ser conferido no HEAD efetivamente enviado. Nenhum merge ou deploy está autorizado. A validação manual do proprietário continua independente dos testes automatizados.
