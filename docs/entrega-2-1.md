# Entrega 2.1 — cadastro e gestão inicial de funcionários

Escopo desta feature: gestão administrativa, vínculos por unidade, delegações mínimas de gerente, associação controlada e auditoria. Não implementa PIN, funções adicionais, matriz geral de permissões, temporários, caixa, pedidos ou estoque.

## Banco e segurança da execução

Não iniciar esta feature com a URL do banco operacional durante os testes. Não executar setup novamente, não recriar volume e não alterar credenciais existentes. Os testes usam exclusivamente PostgreSQL 16 descartável via Testcontainers, sem URL externa, porta fixa ou volume nomeado. A aplicação de teste recebe a conexão do contêiner por configuração de prioridade superior às variáveis do ambiente.

O funcionamento normal do Spring Boot aplica Flyway ao banco configurado. Portanto, executar spring-boot:run contra o banco original só poderá ocorrer depois da revisão e autorização específica da migração, com backup protegido e restauração ensaiada. Esta entrega não executa essa operação.

Migrações novas (V1 preservada):

- V2__identidade_e_vinculos_funcionarios.sql: e-mail/senha administrativos opcionais, nome/contatos locais e versão do vínculo.
- V3__delegacoes_administrativas.sql: limites explícitos dos gerentes. Nenhuma delegação inicial é concedida.
- V4__associacoes_e_idempotencia.sql: solicitações de associação, integridade entre identidade/origem/vínculo e idempotência administrativa.

Usuários, UUIDs, hashes e vínculos existentes não são substituídos. O login administrativo exige hash de senha presente. Funcionários novos são criados sem e-mail/senha administrativos, sem hash artificial e sem PIN. E-mail/telefone de contato são opcionais por vínculo, não criam login e não associam contas automaticamente.

## Autorização

Todos os endpoints exigem sessão, e toda mutação exige CSRF. O backend reconsulta usuário, estabelecimento, vínculo ativo, função e limites recebidos. O proprietário administra funcionários comuns e gerentes apenas nas unidades às quais está vinculado. Gerentes administram somente funções comuns explicitamente recebidas e apenas nas ações delegadas. Não podem criar/promover gerente, administrar outro gerente, modificar proprietário ou suas próprias delegações.

O módulo não administra o acesso do proprietário nem cria outro proprietário. A função principal é a única função nesta entrega. Alterar função elimina a delegação anterior do vínculo, preservando seu conteúdo na auditoria para impedir que privilégios antigos ressuscitem após nova promoção.

As flags de capacidade orientam a tela; não substituem autorização. Campos extras no JSON não são aplicados como permissões, função ou identidade: cada alteração tem DTO e endpoint próprios. Desativar vínculo não desativa a identidade global nem outras unidades. O nome editado é local; não altera o nome global ou dados de outras unidades.

As mutações administrativas usam lock exclusivo por estabelecimento dentro da transação e versão do vínculo. Locks entre duas unidades têm ordem estável. Consultas de capacidades, equipe, detalhe, delegações e associações usam transações somente de leitura com REPEATABLE_READ, sem SELECT FOR UPDATE. A unidade ativa, o usuário, o vínculo e a autorização são revalidados em cada chamada e pertencem ao mesmo snapshot que os dados consultados. Uma leitura iniciada antes do commit de revogação pode terminar com o estado anterior; novas leituras após esse commit já recusam o acesso. Isso não autoriza escritas: mutações revalidam sob lock e usam READ_COMMITTED. Locks de esquema normais do PostgreSQL não são removidos.

## Associação sem códigos autenticadores expostos

A origem autoriza uma solicitação referente a um vínculo visível e informa a unidade de destino. O destino lista solicitações válidas e um administrador autorizado aceita o novo vínculo, escolhendo função permitida naquela unidade. Permissões, contatos e credenciais não são copiados. O mesmo usuario_id é preservado.

Não há senha, bearer token ou código autenticador na resposta. O UUID da solicitação é apenas referência; possuir esse UUID não permite aceitar sem sessão e autorização no destino. Origem, destino e identidade são verificados novamente no aceite. A autorização do emissor, a situação da origem, o vencimento e o consumo único também são revalidados. Funções são independentes entre as unidades.

Validade técnica padrão: 15 minutos, configurável por kt.associacoes.validade (duração ISO-8601), positiva e limitada a uma hora. Isso não é a validade de funcionário temporário, que permanece fora do escopo. Solicitações expiradas/consumidas são preservadas. Vínculos já existentes, inclusive inativos, não são duplicados.

Sem um identificador confiável fornecido pela pessoa, nomes/contatos opcionais não permitem deduplicação automática de pessoas físicas. A interface distingue nova pessoa de associação existente; não faz pesquisa global nem junção automática por semelhança.

## Contrato HTTP

Base: /api/v1/estabelecimentos/{estabelecimentoId}/funcionarios

| Método | Caminho relativo | Uso |
| --- | --- | --- |
| GET | /capacidades | Capacidades do administrador atual |
| GET | / | Lista autorizada, sem proprietário |
| GET | /{vinculoId} | Detalhe no escopo da unidade |
| POST | / | Nova identidade/vínculo; Idempotency-Key UUID obrigatório |
| PATCH | /{vinculoId} | Nome/contatos locais e versao |
| PATCH | /{vinculoId}/situacao | ativo e versao obrigatórios |
| PATCH | /{vinculoId}/funcao-principal | Função e versao; gerente só pelo proprietário |
| GET/PUT | /{vinculoId}/autorizacao-administrativa | Proprietário consulta/define limites do gerente |
| POST | /{vinculoId}/associacoes | Solicita associação com destinoId |
| GET | /associacoes | Solicitações recebidas válidas |
| POST | /associacoes/{id}/aceitar | Aceita com função e Idempotency-Key |

401: sem autenticação; 403: sem autorização; 404: recurso fora do escopo/indisponível; 409: versão antiga, repetição incompatível, vínculo existente ou solicitação indisponível. Erros de integridade não retornam SQL ou detalhes do banco.

Idempotency-Key deve ser mantida ao repetir uma requisição cujo resultado se perdeu. A chave é vinculada ao executor, unidade, tipo e conteúdo. Reutilização incompatível retorna 409. Repetição válida não cria outra identidade/vínculo nem duplica o evento. Alterações com versão antiga exigem atualização, não sobrescrita automática.

## Auditoria

As mutações e seus eventos são confirmados na mesma transação; falha da auditoria reverte a alteração. Os registros identificam executor, vínculo do executor, unidade, alvo e snapshots administrativos antes/depois. Nunca incluem senha, PIN, hash ou sessão. O módulo não expõe exclusão de histórico.

## Testes e reprodução

Pré-requisitos: Java 17, Maven, Docker Desktop em modo Linux e Node para sintaxe/smoke. Nenhum DB_PASSWORD operacional é necessário para os testes.

```powershell
mvn --batch-mode --no-transfer-progress '-Ddebug=false' '-Dlogging.level.org.springframework=INFO' verify
node --check src/main/resources/static/assets/app.js
node --check src/main/resources/static/assets/funcionarios.js
node scripts/smoke-funcionarios.mjs
```

Os 18 testes da Etapa 1 são mantidos sem alterações. FuncionariosPostgresTest contém 40 testes (30 originais e 10 da revisão independente): atualização V1→V4 com conta antiga preservada, login real, identidades sem credenciais administrativas, hierarquia, isolamento, revogação em sessão existente, concorrência, auditoria com rollback real e associação com validade/consumo/revogação.

O smoke usa Chrome instalado (ou KT_CHROME_EXECUTABLE), um perfil próprio e uma fixture Java de teste que cria outro PostgreSQL descartável. Credenciais são aleatórias, trocadas somente em memória pelo controlador, sem impressão. O banco/test server são fechados ao encerrar. Não usa perfil pessoal, porta 8080 ou banco existente. Capturas ficam em .tools/browser-artifacts, fora do Git. No runner efêmero de CI, Chrome headless usa --no-sandbox; não há segredos ou dados operacionais nesse ambiente.

A fixture de navegador existe em src/test, não entra no JAR da aplicação. Não há testes desativados se Docker faltar: a execução falha. O CI também executa o smoke e verifica o SHA real do evento, com token somente de leitura. Relatórios XML e capturas não são publicados como artefatos.

## Antes da aplicação real das migrações

1. Revisar e aprovar o PR, os resultados e o plano de implantação.
2. Identificar banco/volume e variáveis corretos sem imprimir segredos.
3. Gerar backup protegido fora do repositório, verificar integridade e ensaiar restauração em banco isolado.
4. Definir janela, versão anterior e responsável pela recuperação.
5. Aplicar novas migrações somente por autorização; confirmar login/vínculos do proprietário e smoke administrativo.

Rollback de código não deve apagar funcionários, auditoria ou novas tabelas. Reimpor NOT NULL em e-mail/senha exigiria tratar cadastros operacionais e uma migração compensatória revisada; não é um rollback automático seguro. Não modificar V1 ou apagar dados para fazer o ambiente voltar a iniciar.

## Limites

Novos funcionários, inclusive novos gerentes sem credenciais administrativas, ainda não recebem login operacional. PIN é Entrega 2.3. Contas administrativas existentes continuam autenticando normalmente. Funções adicionais, matriz completa, autorizações críticas avançadas e temporários permanecem nas próximas entregas. Sessões em memória, infraestrutura/HTTPS e revisão de dependências continuam pendências de produção.

## Evidências locais da versão inicial (antes da revisão independente)

Maven verify: 48 testes aprovados, zero falhas, erros ou ignorados; JAR empacotado. Os arquivos dos 18 testes anteriores e a migracao V1 permanecem sem alteracoes. Actionlint e sintaxe JavaScript aprovados.

Smoke Chrome com banco descartavel: login, cadastro sem credenciais administrativas, nome renderizado como texto (incluindo tentativa de markup), edicao, ativacao/desativacao, funcao, delegacao, associacao entre unidades com identidade preservada e funcoes independentes, F5, logout, senha incorreta e layouts 1366x900, 768x1024 e 360x800 aprovados. Confirmacao nativa de situacao e automatizada no smoke; revisao humana de usabilidade continua recomendada.

As primeiras rodadas detectaram falhas de integracao JDBC/DOM e de sincronizacao do smoke; foram corrigidas e os testes repetidos. Nenhum teste foi removido ou marcado como ignorado. Resultados remotos devem ser consultados no PR; nao sao presumidos a partir dos testes locais.

## Ajustes da revisão independente do PR #3

Consultas foram separadas das mutações sem novas migrações. Os 10 testes adicionais verificam leitura durante escrita ainda não confirmada, revalidação de unidade/vínculo, revogação no destino com sessão existente, isolamento de solicitações, expiração sem consumo/auditoria, replay após revogação e quatro cenários ordenados de aceite versus revogação. Os testes de disputa mantêm aberta a primeira transação e verificam pg_blocking_pids para confirmar que a segunda aguarda um lock real, antes de liberar o commit. São testadas as duas ordens de execução tanto na origem quanto no destino.

As regras de associação não foram alteradas: revogação confirmada antes do aceite impede a operação; aceite confirmado antes da revogação mantém o vínculo já criado no destino. Revogar poderes administrativos não apaga retroativamente vínculos independentes. A revalidação existente após o lock passou pelos testes adicionais.

Em 360px, a tabela usa cartões com função, contato, situação e botões visíveis sem rolagem horizontal. Desktop e tablet mantêm tabela. UUIDs completos ficam em detalhes recolhíveis, acessíveis por teclado. O cabeçalho permite quebra do nome do usuário; ações têm área mínima de 44px no mobile, nomes acessíveis contextualizados e foco visível. Abrir editor foca o primeiro campo; Cancelar/Escape retorna ao acionador; salvar mantém referência de foco após atualização. Mensagens de erro usam role alert. Escape no editor não propaga ao atalho global; o atalho do menu só atua quando ele está aberto, evitando roubo de foco.

O smoke foi ampliado para verificar cartões em 360px, tabela em desktop/tablet, tamanho e posição horizontal das ações, identificadores via Enter, ordem de Tab, Escape e retorno de foco após salvar. Capturas locais agora incluem a página inteira para mostrar as ações no mobile. Isso não substitui auditoria completa de acessibilidade, testes com leitor de tela ou dispositivos físicos.

Validação local da revisão: Maven verify com 58 testes aprovados (18 da Etapa 1 e 40 de funcionários), zero falhas, erros ou ignorados. O smoke completo com PostgreSQL descartável e Chrome também passou, incluindo os novos controles de mobile/teclado. Resultados remotos serão registrados no PR com o SHA efetivamente validado. Mantêm-se os riscos de produção e migração descritos acima; nenhuma migração operacional foi executada. O ZIP anterior é uma fotografia de f849f82 e não representa estes ajustes.