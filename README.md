# KITCHEN & TABLE 2.0

SaaS de gestão para restaurantes, bares e lanchonetes, desenvolvido por entregas e com autorização por estabelecimento.

## Situação do desenvolvimento

Etapa 1 e Entregas 2.1/2.2 estão integradas à `develop`. A Entrega 2.3, PIN e segurança operacional, está em revisão na branch `feature/entrega-2-3-pin-seguranca`, PR #5. Ainda não é uma versão de produção. A Entrega 2.4 e os módulos operacionais futuros não foram iniciados.

Implementado nesta branch:

- Login administrativo por sessão, CSRF, BCrypt, restauração da autenticação após F5 e logout com invalidação no servidor.
- Dashboard com identificação real do usuário, estabelecimentos e função, sem indicadores fictícios.
- Gestão de funcionários, identidades, vínculos independentes por unidade e associação controlada.
- Função principal, funções adicionais, exceções individuais, delegações administrativas e auditoria.
- PIN operacional por vínculo, bloqueio persistente após tentativas incorretas, desbloqueio/redefinição autorizados, inatividade e troca de operador.

O catálogo de permissões de produtos, pedidos, caixa e outros módulos futuros não representa operações disponíveis. Não há cadastro público de proprietários, contratação SaaS ou teste gratuito implementados. O setup técnico é exclusivo da inicialização de um banco genuinamente novo.

Pendências de interface relatadas incluem restaurar a seção/unidade após F5, preservar rascunhos elegíveis e melhorar a organização das permissões. O [planejamento das correções](docs/planejamento-correcoes-interface.md) não autoriza nem representa sua implementação.

## Tecnologias

Java 17, Spring Boot 3.3.13, Spring Security, JDBC/JdbcTemplate, PostgreSQL 16, Flyway e Maven. Frontend HTML/CSS/JavaScript nativo, servido pelo Spring Boot. Docker é usado para PostgreSQL local e fixtures descartáveis; Node e Chrome são necessários para o smoke, sem dependências npm.

## Estrutura

```text
src/main/java/com/kitchentable/   backend e autorização
src/main/resources/
  application.yml              configuração por ambiente
  db/migration/                migrações Flyway V1–V10
  static/                      telas HTML e assets CSS/JavaScript
src/test/java/                 testes Java e fixtures descartáveis
scripts/                       smoke automatizado de interface
.github/                       CI, Dependabot e template de PR
docs/                          documentação e índice por assunto
tests/manual/                  guia; materiais de execução locais ignorados
.tools/                        ferramentas, perfis e privados locais ignorados
target/                        saídas Maven ignoradas
```

Consulte o [guia da estrutura](docs/estrutura-projeto.md), o [índice da documentação](docs/README.md) e as [orientações de contribuição](CONTRIBUTING.md).

## Instalação e execução local

Pré-requisitos: Java 17, Maven e Docker Desktop. Para smoke: Node 24 e Chrome instalado. Execute os comandos na raiz do clone.

**Esta feature aplica migrações até V10 ao iniciar. Não execute contra o PostgreSQL operacional.** Use somente um banco local novo explicitamente destinado ao desenvolvimento ou as fixtures descartáveis. Veja [instalação e variáveis](docs/instalacao-local.md) e [compatibilidade das migrações](docs/entrega-2-3.md).

Somente para uma instalação local genuinamente nova, sem conflito de porta/volume com outros bancos:

```powershell
$senhaBancoLocal = Read-Host 'Senha exclusiva do novo PostgreSQL de desenvolvimento' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $senhaBancoLocal).Password
docker compose up -d db
mvn spring-boot:run
```

Abra http://127.0.0.1:8080. O servidor e a porta publicada do banco ficam em loopback por padrão. Não há senha padrão no código; o `.env` do Compose não é carregado automaticamente pelo Spring Boot. Não remova volumes nem use esse procedimento para recriar um banco existente.

A criação do primeiro proprietário em banco novo é descrita no [setup técnico](docs/configuracao-inicial.md); não é um fluxo público do site. Não repetir setup para cadastros existentes.

O ambiente manual já preparado usa seus próprios scripts privados: [organização dos testes](docs/organizacao-testes.md). Não reiniciá-lo com Compose ou `mvn spring-boot:run`.

## Testes

Com Docker em execução:

```powershell
mvn --batch-mode --no-transfer-progress '-Ddebug=false' '-Dlogging.level.org.springframework=INFO' verify
node scripts/smoke-funcionarios.mjs
```

O Maven compila, testa e empacota. A suíte atual contém **137 testes**, incluindo mocks, Tomcat temporário e PostgreSQL 16 descartável via Testcontainers. O smoke inclui a extensão operacional e inicia outra fixture com banco, navegador e credenciais sintéticas próprios. Não usa o PostgreSQL operacional nem precisa de `DB_PASSWORD`.

A expiração automatizada pode ser executada separadamente com `mvn '-Dtest=SessionInactivityIntegrationTest' test`; o prazo reduzido existe somente no teste. O prazo administrativo normal permanece 30 minutos.

Os testes manuais mantêm **53 aprovações conciliadas** e pendências **T38, T50, T51 e T57**. Isso é independente da aprovação automatizada. Roteiro, CSV, conciliação, relatórios e capturas ficam em `tests/manual/etapa-2.3/`, somente locais e ignorados pelo Git. Consulte os [guias dos testes](docs/organizacao-testes.md) e [scripts](scripts/README.md).

## Segurança e limites

Sessão administrativa em memória, cookie HttpOnly/SameSite=Lax, proteção CSRF e HTTP Basic desabilitado. Senhas e PINs não são guardados no armazenamento do navegador. Reiniciar o backend encerra as sessões administrativas.

Antes de piloto: revisar HTTPS/cookie Secure, infraestrutura, sessões em múltiplas instâncias, proteção contra tentativas em massa, retenção de auditoria, dependências e as pendências documentadas. HTTP é somente local; os testes não aprovam implantação de produção. Consulte [SECURITY.md](SECURITY.md) e [Entrega 2.3](docs/entrega-2-3.md).

Nunca publique credenciais, `.env`, tokens, perfis Chrome, relatórios privados, capturas, dumps ou volumes de banco. Publicar código no GitHub não implanta o SaaS. Merge em `develop` ou `main` depende de autorização separada.
