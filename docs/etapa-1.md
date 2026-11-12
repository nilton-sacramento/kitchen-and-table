# Etapa 1 — entrega integrada no repositório

## Escopo e preservação

A branch feature/etapa-1-autenticacao incorpora a fundação existente sem reescrever main ou develop. Origem da integração: projeto original já testado pelo responsável, mantido intacto. README anterior preservado byte a byte em docs/README-planejamento-original.md; docs/prompt-master.md permanece vazio e sem alteração. Nenhum banco, usuário, estabelecimento, senha operacional ou volume foi modificado.

Implementado: backend Java 17/Spring Boot/JDBC/Flyway/PostgreSQL 16, frontend HTML/CSS/JavaScript, login por sessão, CSRF, restauração após F5, logout no servidor, expiração por inatividade, consulta de usuário/estabelecimentos e navegação para módulos em desenvolvimento. Sem dados fictícios apresentados como indicadores reais.

A única mudança de configuração nos fontes importados é a remoção do fallback de senha do banco. DB_PASSWORD passa a ser obrigatória no ambiente. Isso não troca a senha existente; o operador deve informar a mesma senha do banco que utilizará. Os fontes Java, testes e migração foram comparados com a origem sem divergências. A interface também foi preservada, com remoção apenas de uma linha vazia excedente no fim do CSS apontada por git diff --check, sem alteração de comportamento.

## Execução dos testes no clone

Em 12/11/2026, America/Sao_Paulo, foram executados no clone:

```powershell
mvn -o test
node --check src/main/resources/static/assets/app.js
```

Resultado: BUILD SUCCESS, 18 testes executados, nenhuma falha, erro ou teste ignorado. Sintaxe JavaScript aprovada. O modo offline usa dependências já disponíveis; mvn test pode baixar dependências em outro ambiente.

| Classe | Testes aprovados |
| --- | ---: |
| SecurityConfigTest | 2 |
| WebAccessTest | 8 |
| SessionAuthenticationTest | 7 |
| SessionInactivityIntegrationTest | 1 |

O teste de inatividade inicia Tomcat real em loopback com porta aleatória, usa cookies reais e timeout de dois segundos somente no contexto de teste. Após 3,2 segundos sem requisições, o acesso retorna 401. O prazo normal de 30 minutos não foi alterado. Não houve conexão ao PostgreSQL nesses testes, pois as dependências de dados são simuladas.

Testes manuais anteriormente confirmados pelo responsável no projeto original: login correto, dados do proprietário/estabelecimento/função, F5, logout, proteção após logout, senha incorreta, ausência da janela Basic do Chrome e visualização em tablet. Não foram repetidos no navegador pelo agente durante a integração no clone.

## Revisão de segurança e publicação

Foram revisados conteúdo dos arquivos publicáveis, configurações, documentação, frontend, uso das credenciais de testes e os três commits do histórico preexistente. Não foram identificados segredos operacionais, chaves privadas, tokens de provedores, JWTs, hashes de senha literais ou URLs com credenciais. As senhas dos testes são fictícias, utilizadas com dependências simuladas, sem ligação ao proprietário real. O README histórico não contém credenciais operacionais identificadas.

A revisão por padrões e contexto não é uma garantia absoluta contra todo segredo possível. A lista efetiva de arquivos do índice deve continuar sendo revisada antes do push. Os logs e relatórios XML do Maven não fazem parte da publicação.

.gitignore preserva exclusões úteis existentes e acrescenta proteção para variantes .env, credenciais/certificados, backups, dumps e recovery. A inclusão é explícita por grupos, sem git add -f. Nenhum .env, target, recovery, backup ou dado PostgreSQL faz parte da entrega.

## Arquivos incluídos

- pom.xml, docker-compose.yml e src/main/java: backend e configuração de build.
- src/main/resources/application.yml e db/migration/V1__foundation.sql: ambiente e migração original.
- src/main/resources/static: interface existente.
- src/test/java: quatro classes, 18 testes.
- README.md: estado atual, execução, limites e links.
- docs/README-planejamento-original.md: cópia integral do README anterior.
- docs/autenticacao-sessao.md e docs/configuracao-inicial.md: operação local sem segredos embutidos.
- docs/fluxo-publicacao.md e docs/prs/: comandos futuros e textos dos PRs.
- .gitignore: exclusões revisadas.

Não foram copiados ZIPs, dados do banco, relatórios locais com caminhos pessoais, scripts de recuperação específicos do diretório original ou documentos antigos contraditórios como instruções atuais.

## Pendências e riscos conhecidos

- Não há implantação de produção nesta entrega. HTTPS, cookie Secure em HTTPS e infraestrutura exigem revisão própria.
- Sessões são locais à memória do servidor e terminam ao reiniciar. Não há armazenamento compartilhado de sessões.
- Permissões granulares, revogação administrativa, recuperação de senha e limitação de tentativas continuam pendentes.
- Seleção com múltiplos vínculos reais e validação visual exaustiva em dispositivos/navegadores ainda precisam de verificação manual adicional.
- A matriz de dependências deve ser revista quanto a suporte e vulnerabilidades antes de produção; a revisão de segredos não é uma auditoria completa de vulnerabilidades.
- Para usar o banco original a partir do clone, manter o contêiner existente e usar a mesma DB_URL/DB_USER/DB_PASSWORD. Subir outro Compose pode criar outro volume por causa do nome do projeto; não é uma migração dos dados.
- GitHub CLI não está disponível nesta sessão; PRs estão preparados como arquivos, sem criação remota.

A Etapa 2 não foi iniciada. Push depende de autorização. feature → develop exige revisão; develop → main exige aprovação posterior e testes no commit efetivo. Nenhum merge automático é autorizado.
