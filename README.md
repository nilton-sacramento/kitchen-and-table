# KITCHEN & TABLE 2.0

SaaS de gestão para restaurantes, bares e lanchonetes, em desenvolvimento por etapas.

A Etapa 1 implementa a interface inicial e a autenticação por sessão. Esta feature acrescenta a Entrega 2.1: gestão administrativa de funcionários e vínculos, em revisão. Não é uma versão pronta para produção.

**Antes de executar esta feature com dados existentes, leia [o plano de migrações e testes](docs/entrega-2-1.md). Não iniciar contra o PostgreSQL operacional sem aprovação específica de backup/migração.**

## Arquitetura implementada

- Java 17, Spring Boot 3.3.13 e Maven.
- Spring Security com sessão HTTP, CSRF e BCrypt.
- PostgreSQL 16, JdbcTemplate/JDBC e migrações Flyway.
- Frontend HTML, CSS responsivo e JavaScript nativo em `src/main/resources/static`, servido pela mesma aplicação.
- Docker Compose somente para o PostgreSQL local.

Angular, JWT, JPA, WebSockets e operação offline não fazem parte desta implementação. O texto anterior de planejamento foi preservado integralmente em [docs/README-planejamento-original.md](docs/README-planejamento-original.md); não representa o estado atual nem substitui as decisões de negócio aprovadas. [docs/prompt-master.md](docs/prompt-master.md) foi preservado como recebido, mas está vazio.

## Funcionalidades disponíveis

- Login com usuário existente; identificação de nome, e-mail, estabelecimento e função.
- Dashboard com dados reais da API, sem indicadores fictícios.
- Consulta e seleção de estabelecimentos vinculados.
- Permanência da sessão após F5, logout com invalidação no servidor e expiração por inatividade.
- Cookie HttpOnly/SameSite=Lax, CSRF no login/logout e HTTP Basic desabilitado.
- Navegação para módulos futuros, sinalizados como em desenvolvimento.

Cadastro/gestão de funcionários, vínculos, delegações administrativas mínimas e associação controlada estão implementados nesta feature. PIN, permissões granulares completas, funções adicionais, temporários, produtos, mesas, comandas, pedidos, cozinha, bar, estoque, caixa e relatórios continuam pendentes. A função exibida no painel não equivale a autorização granular implementada.

## Executar localmente no Windows

Pré-requisitos: Java 17, Maven e Docker Desktop. Na raiz do clone, em PowerShell, configure `DB_PASSWORD` com a senha do banco que será utilizado. Para evitar gravá-la no histórico, use entrada oculta:

```powershell
$senhaBancoLocal = Read-Host 'Senha do PostgreSQL local' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $senhaBancoLocal).Password
mvn spring-boot:run
```

Abra http://localhost:8080. A aplicação fica vinculada a 127.0.0.1 por padrão. Não há publicação da aplicação na internet neste procedimento.

**Banco já existente:** mantenha o contêiner original em execução e use a mesma senha e endereço. Não execute setup novamente. Não suba outro banco do clone: Compose pode criar outro volume por causa do nome diferente da pasta. `DB_URL` e `DB_USER` podem ser informados no mesmo terminal quando necessário. Nenhum comando aqui muda a senha do banco existente.

**Instalação realmente nova:** depois de configurar DB_PASSWORD, `docker compose up -d db` cria o banco local. O setup único é descrito em [docs/configuracao-inicial.md](docs/configuracao-inicial.md). Não o execute para o proprietário já cadastrado.

Não há senha padrão do banco no código. Um arquivo .env do Compose não é carregado automaticamente pelo Spring Boot. Nunca envie senhas, chaves de setup, arquivos .env, dumps ou volumes ao GitHub.

## Testes

```powershell
mvn test
```

Os testes conectam somente a PostgreSQL 16 descartável para a Entrega 2.1; os testes anteriores usam mocks/Tomcat isolado. Docker é necessário, sem acesso ao banco operacional. A expiração usa Tomcat real em porta aleatória de loopback e timeout de dois segundos somente no contexto de teste; o prazo normal permanece 30 minutos.

Para executar somente a expiração:

```powershell
mvn '-Dtest=SessionInactivityIntegrationTest' test
```

O frontend não exige Node para executar. Com Node instalado, `node --check src/main/resources/static/assets/app.js` verifica a sintaxe.

## Segurança e limites

Sessões estão na memória do servidor e se encerram ao reiniciar. Antes de produção será necessário revisar HTTPS, cookie Secure (`KT_COOKIE_SECURE=true` em HTTPS), infraestrutura, atualizações de dependências, permissões por estabelecimento, revogação de sessões e proteção contra tentativas repetidas. O HTTP local usa cookie sem Secure por necessidade de desenvolvimento.

Senhas nos testes são fictícias e usadas exclusivamente com dependências simuladas. Não representam contas operacionais. A exceção de CSRF do setup permanece limitada ao endpoint protegido pela chave externa e pelo bloqueio de inicialização única; a interface não utiliza esse endpoint.

## Documentação e versionamento

- [Entrega 2.1: funcionalidades, segurança e migrações](docs/entrega-2-1.md)
- [Autenticação por sessão](docs/autenticacao-sessao.md)
- [Situação da Etapa 1 e evidências](docs/etapa-1.md)
- [Fluxo de branches e propostas de PR](docs/fluxo-publicacao.md)

Fluxo: `feature/etapa-1-autenticacao` → `develop` → `main`. Publicação exige autorização; merges não são automáticos. A publicação do código no GitHub não é implantação do SaaS.
