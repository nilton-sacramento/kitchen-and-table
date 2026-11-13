# Entrega 2.3 — PIN e segurança operacional

## Escopo e estado

Implementação na branch `feature/entrega-2-3-pin-seguranca`, destinada a PR **Draft** para `develop`. Base integrada da Entrega 2.2: `35f1568b5a3827393822680437c92e1e72b8ca0d`. Nenhum merge, deploy ou acesso ao PostgreSQL operacional faz parte desta entrega.

São reais: definição/redefinição autorizada de PIN, autenticação operacional, bloqueio persistente após três erros consecutivos, bloqueio/desbloqueio administrativo, inatividade por unidade, troca de operador, revogação e consulta de eventos. O proprietário continua usando sua autenticação administrativa; gerentes não recebem novas capacidades automaticamente.

Pedidos, caixa, produtos, estoque, relatórios financeiros, temporários e notificações externas não foram implementados. A tela operacional declara essa limitação, sem simular operações.

## Arquitetura e limites de identidade

A identidade continua em `usuarios` e a autorização independente por unidade em `usuarios_estabelecimentos`. O PIN é definido **por vínculo**, preservando a identidade única. Assim, um administrador de uma unidade não redefine o segredo de outra; associação de identidade não copia PIN, bloqueio ou permissões. A coluna legada `usuarios.pin_hash`, até então sem fluxo operacional, permanece intacta.

A identificação fornecida ao funcionário é o UUID do vínculo e da unidade, disponíveis ao administrador autorizado na tela de segurança. Esses identificadores não são segredos; não há listagem pública de funcionários. A entrada anônima exige ambos e o PIN.

O hash utiliza BCrypt com salt aleatório e custo 12. PINs numéricos de seis posições preservam zeros à esquerda. Não há recuperação do valor anterior: um responsável autorizado define um novo PIN. Os DTOs omitem credenciais em `toString`, falhas de parsing retornam mensagem genérica e auditoria/respostas não recebem o PIN nem seu hash. Não há armazenamento de credenciais em localStorage/sessionStorage.

A entrada correta cria uma sessão operacional no banco, rotaciona o identificador da sessão HTTP e o CSRF. O principal `OperadorPrincipal` contém somente identificadores; permissões são calculadas novamente no servidor. O UUID interno da sessão operacional não é entregue ao navegador.

`ROLE_OPERADOR` não é `ROLE_USER`: PIN não autentica APIs administrativas, inclusive para funcionário com função GERENTE. Uma sessão administrativa ativa deve ser encerrada antes de entrar com PIN. A autenticação administrativa existente, cookies HttpOnly, SameSite=Lax, CSRF e HTTP Basic desabilitado são preservados. Logout encerra a sessão operacional e invalida a sessão HTTP. A sessão HTTP é invalidada mesmo se o encerramento/auditoria no banco falhar.

## Autorização administrativa

A V10 acrescenta quatro capacidades críticas, ativas e sem padrões por função:

| Código | Ação |
| --- | --- |
| `PIN_GERENCIAR` | Definir/redefinir PIN |
| `OPERACIONAL_DESBLOQUEAR` | Bloquear/desbloquear operador |
| `INATIVIDADE_CONFIGURAR` | Configurar prazo por unidade |
| `SEGURANCA_EVENTOS_CONSULTAR` | Consultar eventos autorizados |

Proprietário mantém autoridade máxima. Para gerente, é necessária concessão explícita pela gestão de permissões da 2.2; funções adicionais operacionais não concedem autoridade administrativa. Para localizar funcionários pela listagem, um gerente também precisa da consulta de funcionários já existente; conceder PIN não concede consulta automaticamente. Além da capacidade efetiva, o backend verifica vínculo ativo, unidade ativa, função principal e limites de funções comuns da delegação existente. Gerente não administra o próprio PIN, outro gerente ou proprietário. O proprietário usa login administrativo, não PIN nesta entrega.

Definir um novo PIN em credencial bloqueada exige também `OPERACIONAL_DESBLOQUEAR`: redefinição não é um atalho para contornar a autorização de desbloqueio. Versão do vínculo protege edições administrativas contra estado obsoleto. Leituras de eventos são filtradas pela unidade e, para gerente, pelos funcionários dentro dos seus limites.

## Bloqueio, revogação e concorrência

- Cada erro conhecido incrementa contador transacional. No terceiro, bloqueia a credencial e todos os desbloqueios daquele vínculo. Nova sessão HTTP ou reinício não remove o bloqueio do banco. Sucesso zera erros consecutivos.
- Credencial inexistente/inativa, PIN incorreto e bloqueio retornam o mesmo 401 sem detalhes. Uma verificação BCrypt fictícia não persistida evita omitir o trabalho de hash para identidades inválidas. Falhas não são atribuídas falsamente ao funcionário: executor anônimo e vínculo alvo ficam separados.
- Falha e auditoria confirmam antes de retornar 401. Lançar uma exceção dentro da transação nesse caminho reverteria o contador; o serviço retorna resultado nulo e o controller responde após commit.
- Redefinição, bloqueio e desativação incrementam versão da credencial/inutilizam sessões anteriores. Desbloquear, reativar ou restaurar acesso à unidade não revive um desbloqueio antigo. Negação de `ESTABELECIMENTO_VISUALIZAR` encerra sessões do vínculo.
- Cada contexto/atividade operacional revalida usuário, vínculo, unidade, permissão de acesso, credencial, versão, bloqueio e prazo. O principal também deve corresponder ao usuário real do vínculo. Não há permissões em cache na sessão.
- Mutações administrativas mantêm o bloqueio exclusivo por unidade da 2.1. Login/contexto usam bloqueio compartilhado por unidade, credencial com UPDATE/SHARE e bloqueio da sessão individual. Unidades são ordenadas ao trocar operador para evitar inversão de locks. Uma revogação confirmada impede a próxima operação; uma operação já iniciada e serializada antes da revogação pode concluir antes dela.
- GET de estado administrativo usa compartilhamento para estabilizar autorização, sem SELECT FOR UPDATE exclusivo de toda a unidade. Esse lock breve ainda pode aguardar uma mutação administrativa, intencionalmente.

## Inatividade e interface

Prazo padrão operacional: **300 segundos**. Configuração administrativa: **30 a 3600 segundos**, por unidade e com controle de versão. A sessão administrativa mantém os 30 minutos anteriores. O teste automatizado pode inserir um prazo de um segundo somente no PostgreSQL descartável; o formulário/API não aceitam esse valor.

GET de contexto/polling não renova atividade. Interações reais de teclado/toque/ponteiro geram atividade protegida por CSRF e somente uma sessão ainda válida recebe novo prazo. Aumentar a configuração não ressuscita uma sessão vencida. O navegador usa o tempo restante calculado no servidor e relógio monotônico; o backend decide o acesso mesmo com JavaScript manipulado.

A interface remove nomes e informações protegidas ao bloquear, perder validação ou ocultar a aba. Não basta uma sobreposição CSS. Desbloqueio exige novamente identificação e PIN. F5 restaura somente um contexto que o servidor ainda considere válido. Troca de operador encerra o contexto anterior e registra as identidades corretas.

`createOperationalDrafts` oferece um cofre **somente em memória**, isolado por unidade/vínculo: bloqueio recusa leitura/escrita; novo desbloqueio do mesmo operador preserva os valores; troca/logout os apagam. Não existem formulários de pedidos para integrar nesta entrega. Não se promete persistência de rascunho após F5/fechamento; nenhum rascunho foi colocado em Web Storage. Esse mecanismo não protege memória contra código malicioso já executando na própria origem.

Um perfil do navegador compartilha o cookie entre abas, como na autenticação existente; não se promete dois operadores independentes no mesmo perfil. Dispositivos/perfis distintos têm sessões próprias, sempre vinculadas à unidade. Reiniciar o servidor perde as sessões HTTP em memória e exige autenticação nova; o bloqueio de credenciais permanece no PostgreSQL.

## APIs reais

Todas as escritas abaixo exigem CSRF. As administrativas também exigem login administrativo e autorização no serviço.

| Método e caminho | Finalidade |
| --- | --- |
| POST `/api/v1/operacional/entrar` | Identificação, unidade e PIN; 204 ou 401 genérico |
| GET `/api/v1/operacional/sessao` | Estado seguro da sessão; sem contexto quando bloqueado |
| GET `/api/v1/operacional/contexto` | Contexto revalidado; 423 quando bloqueado |
| POST `/api/v1/operacional/atividade` | Renovar prazo somente se acesso ainda válido |
| POST `/api/v1/operacional/bloquear` | Bloquear a sessão atual, com auditoria |
| POST `/api/v1/auth/logout` | Logout existente, com encerramento operacional |
| GET `.../seguranca-operacional/capacidades` | Controles administrativos efetivos |
| GET `.../seguranca-operacional/funcionarios/{vinculo}` | Estado sem hash ou PIN |
| PUT `.../seguranca-operacional/funcionarios/{vinculo}/pin` | Definir/redefinir com versão |
| PUT `.../seguranca-operacional/funcionarios/{vinculo}/bloqueio` | Bloquear/desbloquear com versão |
| GET/PUT `.../seguranca-operacional/inatividade` | Consultar/alterar prazo com versão |
| GET `.../seguranca-operacional/eventos` | Até 100 eventos autorizados |

O prefixo administrativo `...` é `/api/v1/estabelecimentos/{unidade}`. Os endpoints operacionais atuais são a fundação de acesso; não constituem operações financeiras ou de pedidos.

## Auditoria

Reutiliza `auditoria_eventos` e `AuditoriaAdministrativaService` na mesma transação. Registra definição/redefinição, erros, bloqueio por tentativas, bloqueio/desbloqueio administrativo, inatividade, revogação, desativação com sessões, entrada, saída e troca de operador. Metadados não incluem o segredo. Falha na auditoria reverte a mutação, inclusive contador, sem autenticar.

Bloqueio por três erros gera evento com `notificacaoPendente: true`. A consulta administrativa mostra eventos reais e informa que envio de e-mail/push **não existe**. O ator administrativo é também o autorizador da ação; tentativas sem autenticação têm executor nulo. O registro histórico não é apagado ao desativar vínculo.

## Migrações e preservação

- **V9__seguranca_operacional.sql**: `credenciais_operacionais`, `politicas_operacionais`, `sessoes_operacionais`; FKs compostas vínculo/unidade, índices e restrições. Nenhuma nova credencial é preenchida automaticamente.
- **V10__permissoes_seguranca_operacional.sql**: quatro capacidades; nenhuma concessão a gerentes, nenhum padrão por função.
- V1–V8 permanecem sem alteração. Os testes verificam instalação limpa V1→V10, atualização V4→V10 e V8→V10, checksums antigos e credenciais administrativas preservadas.
- Não há migração destrutiva ou script de downgrade. Antes de qualquer uso com dados existentes: autorização separada, backup e ensaio de restauração. Não apagar histórico Flyway nem editar migrações já aplicadas; correções futuras serão novas versões.

## Validação reproduzível, sem banco operacional

Pré-requisitos: Java 17, Maven, Docker Desktop, Node 24 e Google Chrome. No clone real, PowerShell:

```powershell
mvn --batch-mode --no-transfer-progress '-Ddebug=false' '-Dlogging.level.org.springframework=INFO' verify
node scripts/smoke-funcionarios.mjs
```

Não executar `spring-boot:run`, Docker Compose de dados existentes ou setup nesta validação. A suíte fornece explicitamente URL/usuário/senha de containers PostgreSQL 16 exclusivos, vinculados a loopback com porta aleatória, sem volumes/reuso. O smoke inicializa outro container descartável e um Tomcat próprio em porta aleatória. Credenciais sintéticas são geradas em memória, não impressas, e o processo remove seu perfil Chrome aleatório.

A suíte preserva os 98 testes anteriores e acrescenta 39 de PIN/segurança: formato/zeros, hash, contador persistente, hierarquia e permissões, sessão/CSRF/logout, isolamento, revogação/desativação, inatividade, reautenticação/troca, respostas sem segredos, auditoria/rollback e corridas ordenadas por locks do PostgreSQL. Apenas expectativas de quantidade de migrações/catálogo nos testes antigos foram ampliadas; nenhum cenário anterior foi removido ou ignorado.

O smoke anterior de funcionários/permissões continua obrigatório e chama `scripts/smoke-operacional.mjs`: administração real de PIN/inatividade/eventos, teclado, screenshots desktop/tablet/360px, autenticação, F5, prazo real de 30 segundos, ocultação, bloqueio remoto, três erros, desbloqueio, troca, impossibilidade de elevar gerente por PIN e logout. Evidências locais: `.tools/browser-artifacts/seguranca-*.png` e `operacional-*.png`, ignoradas no Git. CI executa o HEAD exato do evento, sem bootstrap/SHA fixo da aplicação, permissões somente `contents: read` e sem secrets de banco.

Os resultados reais e o link do CI devem ser conferidos no PR e no relatório da entrega; a descrição de cobertura não substitui um resultado de execução.

## Riscos e pendências

1. PIN tem baixa entropia por definição. BCrypt e três erros persistentes reduzem tentativas online, mas não substituem HTTPS, proteção de backups e infraestrutura. É possível provocar bloqueio de uma pessoa conhecendo seus identificadores; acesso responsável de desbloqueio continua necessário.
2. Ainda não existe limitador distribuído de tentativas por origem/dispositivo, proteção de borda nem dimensionamento contra muitas verificações BCrypt simultâneas. São requisitos de endurecimento antes de exposição pública, não autorização para produção.
3. Sessões HTTP em memória não suportam múltiplas instâncias sem estratégia adicional. Registros operacionais encerrados não têm política automática de retenção/limpeza nesta entrega; auditoria deve ser preservada.
4. Notificação externa, recuperação autônoma, PIN de proprietário, formulários reais de rascunho e operações dos módulos futuros não estão implementados. O responsável define/redefine PIN com confirmação, nunca recupera o anterior.
5. Tela de entrada exige identificadores técnicos completos. É funcional e não enumera pessoas publicamente; uma identificação amigável/QR exigiria avaliação separada de privacidade/usabilidade.
6. Confirmar HTTPS e `KT_COOKIE_SECURE=true`, política de sessão, infraestrutura e revisão independente antes de qualquer utilização real. Nenhum banco existente foi migrado por esta implementação.

## Onde visualizar no Antigravity

- Backend: `src/main/java/com/kitchentable/funcionarios/OperacionalService.java`, `OperacionalController.java`, `SegurancaOperacionalController.java`, `OperacionalDtos.java`, `OperadorPrincipal.java` e `config/SecurityConfig.java`.
- Frontend: `src/main/resources/static/operacional.html`, `assets/operacional.js`, `assets/seguranca-operacional.js`, integração em `assets/funcionarios.js` e estilos em `assets/app.css`.
- Banco: `src/main/resources/db/migration/V9__seguranca_operacional.sql` e `V10__permissoes_seguranca_operacional.sql`.
- Testes: `src/test/java/com/kitchentable/funcionarios/OperacionalPostgresTest.java`, suíte anterior e `scripts/smoke-operacional.mjs`.
- CI: `.github/workflows/ci.yml`, mantendo template correspondente em `.github/ci-templates/ci-sem-bootstrap.yml`.

Todos são arquivos reais do mesmo clone e branch, editáveis no IDE. Não há cópia paralela para exibição.
## Resultado local verificado — 13/11/2026

`mvn verify`: **137 testes, zero falhas, zero erros, zero ignorados**, BUILD SUCCESS. Distribuição: FuncionariosPostgresTest 80; OperacionalPostgresTest 39; SecurityConfigTest 2; SessionAuthenticationTest 7; SessionInactivityIntegrationTest 1; WebAccessTest 8. Os 98 casos anteriores continuam presentes.

PostgreSQL 16 descartável: instalação V1→V10 e atualizações V4→V10/V8→V10 aprovadas; checksums V1–V8 e credenciais administrativas preservados. Chrome: fluxos anteriores e fluxos operacionais aprovados em desktop, tablet e 360px, incluindo inatividade real com prazo de 30 segundos apenas no container de teste.

A revisão de publicação verificou os arquivos candidatos e regras de ignore: sem tokens conhecidos/chaves privadas, `.env`, dumps, target, recovery ou evidências privadas incluídos. Credenciais de testes são sintéticas; a busca por padrões não constitui garantia universal de ausência de segredos. Nenhuma alteração no banco operacional e nenhuma concessão automática aos gerentes.

Durante a validação foram tratados: expectativas antigas de contagem de migrações/catálogo, rotação real de CSRF, persistência de falhas sem rollback acidental, impossibilidade de reativar sessão velha após desbloqueio/reativação, e invalidação HTTP mesmo sob falha transacional do logout. O smoke de teclado foi ajustado para focar o botão antes de simular a abertura programática do editor, reproduzindo a interação testada.

### Arquivos desta entrega

| Estado | Caminho |
| --- | --- |
| Alterado | `.github/ci-templates/ci-sem-bootstrap.yml` |
| Alterado | `.github/workflows/ci.yml` |
| Alterado | `README.md` |
| Alterado | `scripts/smoke-funcionarios.mjs` |
| Alterado | `src/main/java/com/kitchentable/config/SecurityConfig.java` |
| Alterado | `src/main/java/com/kitchentable/funcionarios/FuncionarioExceptionHandler.java` |
| Alterado | `src/main/java/com/kitchentable/funcionarios/FuncionarioRepository.java` |
| Alterado | `src/main/java/com/kitchentable/funcionarios/FuncionarioService.java` |
| Alterado | `src/main/java/com/kitchentable/funcionarios/PermissoesService.java` |
| Alterado | `src/main/resources/static/assets/app.css` |
| Alterado | `src/main/resources/static/assets/funcionarios.js` |
| Alterado | `src/main/resources/static/index.html` |
| Alterado | `src/test/java/com/kitchentable/funcionarios/FuncionariosPostgresTest.java` |
| Criado | `docs/entrega-2-3.md` |
| Criado | `scripts/smoke-operacional.mjs` |
| Criado | `src/main/java/com/kitchentable/funcionarios/OperacionalController.java` |
| Criado | `src/main/java/com/kitchentable/funcionarios/OperacionalDtos.java` |
| Criado | `src/main/java/com/kitchentable/funcionarios/OperacionalService.java` |
| Criado | `src/main/java/com/kitchentable/funcionarios/OperadorPrincipal.java` |
| Criado | `src/main/java/com/kitchentable/funcionarios/SegurancaOperacionalController.java` |
| Criado | `src/main/resources/db/migration/V10__permissoes_seguranca_operacional.sql` |
| Criado | `src/main/resources/db/migration/V9__seguranca_operacional.sql` |
| Criado | `src/main/resources/static/assets/operacional.js` |
| Criado | `src/main/resources/static/assets/seguranca-operacional.js` |
| Criado | `src/main/resources/static/operacional.html` |
| Criado | `src/test/java/com/kitchentable/funcionarios/OperacionalPostgresTest.java` |
