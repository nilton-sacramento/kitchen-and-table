# Instalação local e variáveis

Guia da implementação local da Entrega 2.4. Java 17/Spring Boot, Maven, JDBC/JdbcTemplate, Flyway, PostgreSQL 16 e frontend HTML/CSS/JavaScript servido pelo backend. Etapa 1 e Entregas 2.1/2.2/2.3 estão integradas à develop; a Entrega 2.4 está em validação local. Veja o [estado atual](../README.md).

## Escolher o ambiente antes de iniciar

- Para testes automatizados: executar Maven verify com Docker disponível. Testcontainers cria PostgreSQL 16 descartável; não configurar banco operacional.
- Para o ambiente manual já preparado: usar os scripts em .tools/testes-manuais-2-3 e consultar [a organização dos testes](organizacao-testes.md). Preservar seu banco e os dados fictícios existentes. Não usar Compose ou Maven run para substituí-lo.
- Para desenvolvimento novo: usar um PostgreSQL local genuinamente novo, sem conflito com volumes ou portas existentes. O exemplo abaixo é exclusivamente para esse caso.

Iniciar a aplicação executa Flyway até V12. **Este guia não autoriza acesso ou migrações no PostgreSQL operacional.** Um banco com dados existentes exige plano próprio de backup, restauração ensaiada, compatibilidade e autorização. Não remover ou recriar volumes.

## Explorar mesas e comandas em ambiente isolado

Com Docker ativo, execute mvn verify e depois node scripts/ambiente-atendimento.mjs. O script reutiliza a fixture de testes, cria PostgreSQL 16 novo em loopback e inicia Spring Boot em porta aleatória. Não lê DB_URL operacional. A URL exata e o caminho do arquivo privado de acessos aparecem no terminal; senhas e PINs não são impressos. Há proprietário, Atendente e Caixa fictícios e duas unidades; o mesmo Atendente tem vínculos independentes nas duas.

No navegador, use a conta administrativa para administrar mesas e permissões. Saia antes de entrar em /operacional.html com unidade, vínculo e PIN do arquivo privado. Atendente/Caixa da unidade A têm concessões críticas explícitas apenas para exercitar o cenário; o vínculo B mantém seus próprios padrões. Esses dados existem somente na fixture.

Ctrl+C no terminal encerra a fixture criada por esse processo. Reexecutar cria outro cenário novo; não substitui o cenário manual antigo nem preserva dados dessa demonstração descartável. O cenário histórico da Entrega 2.3 permanece separado e não deve receber migrações desta feature.

## Instalação nova no Windows

Pré-requisitos: Java 17, Maven e Docker Desktop. Node 24 e Chrome são necessários para o smoke. O frontend não exige Node para ser servido.

Na raiz do clone, em PowerShell:

```powershell
$senhaBancoLocal = Read-Host 'Senha exclusiva do novo PostgreSQL de desenvolvimento' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $senhaBancoLocal).Password
docker compose up -d db
mvn spring-boot:run
```

Abra http://127.0.0.1:8080. Mantenha os testes locais, sem exposição à internet. Crie o primeiro proprietário apenas no banco genuinamente novo conforme [configuração inicial](configuracao-inicial.md). Não existe fluxo público de cadastro no site.

Ctrl+C encerra o backend. Não execute docker compose down -v: removeria o volume. Não use mudança de nome de projeto Compose como estratégia de atualização de um banco anterior.

## Configuração por ambiente

| Variável | Uso |
| --- | --- |
| DB_PASSWORD | Obrigatória, sem senha padrão |
| DB_URL | Padrão jdbc:postgresql://localhost:5432/kitchen_table |
| DB_USER | Padrão kitchen_table |
| PORT | Padrão 8080 |
| SERVER_ADDRESS | Padrão 127.0.0.1, somente local |
| KT_SESSION_TIMEOUT | Padrão 30m por inatividade administrativa |
| KT_COOKIE_SECURE | false para HTTP local; true exige HTTPS e revisão de implantação |
| KT_SETUP_KEY | Somente setup técnico de banco novo; nunca usar no frontend |

O .env do Compose não é carregado automaticamente pelo Spring Boot. Não versionar esse arquivo nem imprimir as variáveis. Ao encerrar, feche o terminal ou remova DB_PASSWORD/KT_SETUP_KEY do processo; não modifique credenciais de bancos existentes.

## Sessões e validação

A autenticação administrativa persiste após F5 por sessão do backend. Reiniciar o servidor encerra sessões em memória. Sair invalida a sessão. Restaurar a seção e a unidade selecionadas e tornar rascunhos elegíveis duráveis continuam melhorias planejadas, separadas da autenticação.

Permissões, vínculos e delegações são validados no backend por unidade. O PIN operacional tem suas regras próprias de bloqueio e inatividade; consulte [Entrega 2.3](entrega-2-3.md). Para validação use [os testes](organizacao-testes.md), sem reduzir a configuração normal nem acessar dados operacionais.
