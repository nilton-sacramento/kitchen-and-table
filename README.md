# KITCHEN & TABLE 2.0

SaaS de gestão para restaurantes, bares e lanchonetes, desenvolvido por entregas e com autorização por estabelecimento.

## Situação do desenvolvimento

Etapa 1 e Entregas 2.1–2.6 estão integradas à develop, base 28cb33b. Esta feature implementa a Entrega 2.7 — caixa, pagamentos, quitação e relatórios — para revisão em PR Draft com destino develop. Não é uma versão de produção.

Implementado nesta branch (contratos e validações em [Entrega 2.4](docs/entrega-2-4.md)):

- Cardápio por unidade: categorias, produtos cozinha/bar, preços exatos, disponibilidade, quantidades auditadas, combos, cópia autorizada e consulta operacional por PIN. Validação em [Entrega 2.5](docs/entrega-2-5.md).
- Mesas por unidade e atendimento operacional: comandas de mesa/balcão, idempotência, cancelamento e encerramento sem consumo/pagamento, com auditoria.

- Login administrativo por sessão, CSRF, BCrypt, restauração da autenticação após F5 e logout com invalidação no servidor.
- Dashboard com identificação real do usuário, estabelecimentos e função, sem indicadores fictícios.
- Gestão de funcionários, identidades, vínculos independentes por unidade e associação controlada.
- Função principal, funções adicionais, exceções individuais, delegações administrativas e auditoria.
- PIN operacional por vínculo, bloqueio persistente após tentativas incorretas, desbloqueio/redefinição autorizados, inatividade e troca de operador.

Pedidos, filas de cozinha/bar, preparo, entregas e decisões setoriais implementados; veja [Entrega 2.6](docs/entrega-2-6.md). Caixa, pagamentos e relatórios reais desta feature estão na [Entrega 2.7](docs/entrega-2-7.md). Módulos futuros continuam indisponíveis. Não há cadastro público de proprietários, contratação SaaS ou teste gratuito implementados. O setup técnico é exclusivo da inicialização de um banco genuinamente novo.

Esta branch inclui restauração da seção/unidade, rascunhos elegíveis e organização/prévia de permissões, validados com PostgreSQL descartável em 18/11/2026. Consulte [implementação, testes e limites](docs/interface-contexto-permissoes.md); o [planejamento anterior](docs/planejamento-correcoes-interface.md) permanece como referência.

## Tecnologias

Java 17, Spring Boot 3.3.13, Spring Security, JDBC/JdbcTemplate, PostgreSQL 16, Flyway e Maven. Frontend HTML/CSS/JavaScript nativo, servido pelo Spring Boot. Docker é usado para PostgreSQL local e fixtures descartáveis; Node e Chrome são necessários para o smoke, sem dependências npm.

## Estrutura

```text
src/main/java/com/kitchentable/   backend e autorização
src/main/resources/
  application.yml              configuração por ambiente
  db/migration/                migrações Flyway V1–V14
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

**Esta feature aplica migrações até V16 ao iniciar. Não execute contra o PostgreSQL operacional.** Use somente um banco local novo explicitamente destinado ao desenvolvimento ou as fixtures descartáveis. Veja [instalação e variáveis](docs/instalacao-local.md) e [compatibilidade das migrações](docs/entrega-2-3.md).

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
node --test scripts/test-interface-contexto.mjs scripts/test-atendimento.mjs scripts/test-cardapio.mjs scripts/test-pedidos.mjs scripts/test-financeiro.mjs
node scripts/test-interface-browser.mjs
node scripts/smoke-funcionarios.mjs
```

O Maven compila, testa e empacota. A suíte desta feature contém 355 testes Java aprovados (276 anteriores e 79 financeiros), sem falhas, erros ou ignorados. Os testes Node somam 42; o Chrome com API controlada mantém 26 verificações, separadas das 46 verificações do smoke com backend real. Resultados, contratos e limites em [Entrega 2.7](docs/entrega-2-7.md). A suíte inclui mocks, Tomcat temporário e PostgreSQL 16 descartável via Testcontainers. O smoke inclui a extensão operacional e inicia outra fixture com banco, navegador e credenciais sintéticas próprios. Não usa o PostgreSQL operacional nem precisa de `DB_PASSWORD`.

A expiração automatizada pode ser executada separadamente com `mvn '-Dtest=SessionInactivityIntegrationTest' test`; o prazo reduzido existe somente no teste. O prazo administrativo normal permanece 30 minutos.

Os testes manuais têm **57 aprovações conciliadas** em 18/11/2026: 19 confirmadas pelo proprietário, 34 execuções assistidas históricas e quatro pendências posteriormente comprovadas. O T50 incluiu reidentificação segura na nova aba bloqueada, troca real de Atendente para Caixa e retorno à primeira sem dados. Isso não representa repetição dos 57 casos no mesmo cenário. Isso é independente da aprovação automatizada. Roteiro, CSV, conciliação, relatórios e capturas ficam em `tests/manual/etapa-2.3/`, somente locais e ignorados pelo Git. Consulte os [guias dos testes](docs/organizacao-testes.md) e [scripts](scripts/README.md).

## Segurança e limites

Sessão administrativa em memória, cookie HttpOnly/SameSite=Lax, proteção CSRF e HTTP Basic desabilitado. Senhas e PINs não são guardados no armazenamento do navegador. Reiniciar o backend encerra as sessões administrativas.

Antes de piloto: revisar HTTPS/cookie Secure, infraestrutura, sessões em múltiplas instâncias, proteção contra tentativas em massa, retenção de auditoria, dependências e as pendências documentadas. HTTP é somente local; os testes não aprovam implantação de produção. Consulte [SECURITY.md](SECURITY.md) e [Entrega 2.3](docs/entrega-2-3.md).

Nunca publique credenciais, `.env`, tokens, perfis Chrome, relatórios privados, capturas, dumps ou volumes de banco. Publicar código no GitHub não implanta o SaaS. Merge em `develop` ou `main` depende de autorização separada.
