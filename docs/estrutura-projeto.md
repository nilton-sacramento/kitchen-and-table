# Estrutura do projeto

Guia da estrutura real. A aplicação segue Maven e Spring Boot; frontend e backend são empacotados juntos. Nenhuma movimentação de pacotes Java é necessária para navegar ou desenvolver.

| Área | Localização | Responsabilidade |
| --- | --- | --- |
| Entrada Spring Boot | src/main/java/com/kitchentable/KitchenTableApplication.java | Inicialização da aplicação |
| Autenticação | src/main/java/com/kitchentable/auth/ | Sessão, conta e setup técnico |
| Configuração web | src/main/java/com/kitchentable/config/ | Spring Security e health |
| Funcionários e acesso | src/main/java/com/kitchentable/funcionarios/ | Vínculos, associação, autorização, permissões, auditoria e operação por PIN |
| Tela administrativa | src/main/resources/static/index.html | Login e painel administrativo |
| Tela operacional | src/main/resources/static/operacional.html | Identificação e desbloqueio operacional |
| Interface e estilos | src/main/resources/static/assets/ | JavaScript nativo e CSS responsivo |
| Ambiente | src/main/resources/application.yml | Variáveis externas e sessão |
| Banco | src/main/resources/db/migration/ | Flyway V1–V10; preservar migrações existentes |
| Testes Java | src/test/java/com/kitchentable/ | Segurança, integração e fixture Chrome |
| Smoke | scripts/ | Controladores Node do smoke descartável |
| Automação GitHub | .github/ | Workflow ativo, Dependabot, template de PR e template histórico de CI |
| Guias e histórico | docs/ | Índice por assunto em README.md |
| Testes manuais | tests/manual/ | Guia público; materiais locais por etapa ignorados pelo Git |
| Build | pom.xml e target/ | Configuração Maven e saída gerada, respectivamente |
| PostgreSQL local novo | docker-compose.yml | Banco de desenvolvimento com porta em loopback |

## Arquivos para acompanhar no editor

- app.js: login, sessão administrativa, navegação e seleção de estabelecimento.
- funcionarios.js: gestão de funcionários e vínculos.
- permissoes.js: funções, exceções e delegações.
- seguranca-operacional.js: administração de PIN, bloqueio e eventos.
- operacional.js: entrada, inatividade, troca e logout operacional.
- app.css: estilos compartilhados.

Todos ficam em src/main/resources/static/assets. As validações de negócio e autorização ficam nos serviços Java, não apenas nos controles visuais. Não mover assets sem atualizar HTML, imports, segurança, testes e CI.

## Ferramentas e arquivos locais

.tools mantém ferramentas auxiliares, logs, perfis de navegador e scripts do ambiente manual. É ignorado pelo Git. Não é outra aplicação nem uma fonte alternativa do produto. Scripts locais com dados privados não devem ser promovidos ao diretório público scripts sem revisão específica.

.ci-validation, quando presente neste workspace, é um worktree Git registrado. .tools/revisao-prs é um pacote antigo de revisão. Ambos foram auditados e preservados; não devem ser tratados como diretórios descartáveis apenas pelo nome. target é gerado pelo Maven. .git pertence ao Git e não deve ser reorganizado manualmente.

Não foi criado segundo backend, workspace duplicado, árvore alternativa de testes Java ou configuração paralela de build. Diretórios vazios e documentos históricos não foram excluídos.

## Onde encontrar instruções

[Instalação](instalacao-local.md), [scripts](../scripts/README.md), [testes](organizacao-testes.md), [contribuição](../CONTRIBUTING.md) e [índice completo](README.md).
