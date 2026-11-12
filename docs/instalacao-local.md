# Instalação local e variáveis

Arquitetura da Etapa 1: Java 17/Spring Boot, Maven, JDBC/JdbcTemplate, Flyway, PostgreSQL 16 e frontend HTML/CSS/JavaScript estático servido pelo backend. Não há Angular, JWT ou JPA nesta entrega. Recursos operacionais do planejamento ainda não são implementados.

O código da Etapa 1 está na branch feature/etapa-1-autenticacao até aprovação do PR para develop. A branch chore/github-ci contém infraestrutura; não inventa um segundo backend.

Pré-requisitos: Java 17, Maven, Docker Desktop (banco local) e Node para verificação de sintaxe opcional. No checkout que contém pom.xml, com o banco existente em execução, use PowerShell:

```powershell
$senhaBancoLocal = Read-Host 'Senha do PostgreSQL local existente' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $senhaBancoLocal).Password
mvn spring-boot:run
```

Abra http://localhost:8080. Use o proprietário existente, sem repetir setup. Não inclua senha em linha de comando nem imprima variáveis. Ao encerrar o ambiente, feche o terminal ou remova DB_PASSWORD do processo.

| Variável | Uso |
| --- | --- |
| DB_PASSWORD | Obrigatória no clone da Etapa 1, sem senha padrão |
| DB_URL | Padrão jdbc:postgresql://localhost:5432/kitchen_table |
| DB_USER | Padrão kitchen_table |
| PORT | Padrão 8080 |
| SERVER_ADDRESS | Padrão 127.0.0.1, somente local |
| KT_SESSION_TIMEOUT | Padrão 30m por inatividade |
| KT_COOKIE_SECURE | false para HTTP local; true em HTTPS após revisão de implantação |
| KT_SETUP_KEY | Somente configuração única de banco genuinamente novo; nunca usar no frontend |

O .env do Compose não é carregado automaticamente pelo Spring Boot. Não versionar .env ou segredos.

Não execute docker compose up no clone esperando migrar o banco original: nomes de projeto diferentes podem criar volumes diferentes. Para o banco já cadastrado, mantenha o contêiner original em execução e use a mesma URL, usuário e senha. Não troque a senha nem apague/recrie volume. Com dados reais, qualquer migração exige plano de backup e aprovação.

Sessões são mantidas pelo backend e restauradas após F5. Sair invalida a sessão. Senhas não ficam em localStorage/sessionStorage. Reiniciar o servidor encerra as sessões. Proteção por estabelecimento e permissões operacionais devem ser implementadas em etapas próprias.
