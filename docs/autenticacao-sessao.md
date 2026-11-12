# Autenticação por sessão — Etapa 1

Esta versão substitui HTTP Basic por sessão HTTP do Spring Security. Não altera tabelas, usuários, hashes de senha nem vínculos. A tabela `sessoes` existente não é usada; sessões ficam na memória do servidor. Reiniciar o processo encerra as sessões. Em múltiplas instâncias será necessário armazenamento compartilhado de sessões, a definir antes de produção.

## Fluxo

- GET `/api/v1/auth/csrf`: público, fornece token CSRF associado à sessão. Token somente em memória do JavaScript.
- POST `/api/v1/auth/login`: formulário URL-encoded com `email` e `password`, mais cabeçalho CSRF. Sucesso 204; credenciais inválidas 401; CSRF ausente/inválido 403. O Spring valida o BCrypt existente e troca o ID da sessão contra fixação.
- GET `/api/v1/auth/me` e `/api/v1/estabelecimentos`: exigem a sessão; acesso anônimo retorna 401. Sem alterações nos filtros por usuário/vínculo existentes.
- POST `/api/v1/auth/logout`: exige CSRF, invalida a sessão, limpa autenticação e cookie; responde 204. O frontend só confirma saída após resposta do servidor.
- O token CSRF é renovado após login/logout; o frontend busca o token atual antes de cada POST.
- O setup continua restrito pela chave e bloqueado depois do primeiro usuário. É a única exceção CSRF, pois é um fluxo externo ao navegador protegido por chave. Não usar na interface nem repetir setup.

Cookie JSESSIONID HttpOnly, SameSite=Lax, somente por cookie (sem ID na URL). Nenhuma senha/credencial Basic é gravada em localStorage/sessionStorage. O frontend não lê o cookie de sessão. Respostas usam a política de cache do Spring Security. Arquivos da interface são públicos; dados da API são protegidos no backend.

Ao abrir ou atualizar a página, o frontend consulta a sessão no servidor. Navegação e retorno à aba verificam a sessão novamente. Não há polling para mantê-la viva artificialmente. A tela já renderizada pode permanecer visível enquanto a aba fica ociosa; a próxima navegação, atualização ou retorno à aba consulta o servidor e detecta expiração.

## Configuração local

Configure DB_PASSWORD no terminal conforme o README (sem senha padrão). No terminal VS Code que executa o Spring Boot, pare o processo com Ctrl+C e execute na raiz:

```powershell
mvn spring-boot:run
```

Abra http://localhost:8080 e pressione Ctrl+F5 para obter o JavaScript atualizado. Use o proprietário existente. Não recrie banco nem execute setup.

- `KT_SESSION_TIMEOUT`: padrão `30m`, expiração por inatividade no servidor. Requisições da sessão renovam a contagem.
- `KT_COOKIE_SECURE`: padrão `false` exclusivamente para HTTP local. Em ambiente HTTPS configurar `true`; TLS e proxies devem ser revisados antes de qualquer publicação. Não usar `true` no HTTP local, pois o cookie não será enviado.
- `SERVER_ADDRESS`: padrão `127.0.0.1`. Não há publicação nesta entrega.

## Roteiro no navegador, um teste por vez

1. Login correto: dashboard com dados existentes do proprietário.
2. F5: continua autenticado e abre Visão geral.
3. Sair: volta ao formulário.
4. Após sair, F5 continua no formulário; abrir `/api/v1/auth/me` ou `/api/v1/estabelecimentos` retorna 401, sem dados.
5. Senha incorreta: erro e nenhum dashboard.
6. Expiração: só depois dos anteriores, pare o servidor. No mesmo terminal, configure temporariamente:

```powershell
$env:KT_SESSION_TIMEOUT = '1m'
mvn spring-boot:run
```

Faça login. Deixe todas as abas do sistema sem uso por pelo menos dois minutos (o contêiner verifica a expiração periodicamente). Pressione F5: deve voltar ao login. Após o teste, pare o servidor e restaure o padrão:

```powershell
Remove-Item Env:KT_SESSION_TIMEOUT -ErrorAction SilentlyContinue
mvn spring-boot:run
```

## Verificação automatizada e limites

`mvn test` verifica autenticação, proteção da API, CSRF real, rotação do ID, persistência da sessão e invalidação pelo logout. `SessionInactivityIntegrationTest` inicia Tomcat real em porta aleatória, somente em 127.0.0.1, com cliente HTTP e cookies reais. Um listener exclusivo do teste reduz o timeout de cada sessão para dois segundos; após 3,2 segundos sem requisições, a conta e os estabelecimentos devem retornar 401. O teste não chama invalidate(), não altera o application.yml e não conecta ao PostgreSQL. Também confere HttpOnly e SameSite=Lax no Set-Cookie real. A configuração normal permanece 30 minutos. Os demais testes usam MockMvc; todos usam dependências de banco simuladas, sem modificar dados. `node --check src/main/resources/static/assets/app.js` verifica sintaxe, se Node estiver disponível.

Para repetir apenas a expiração automatizada, no PowerShell da raiz do projeto:

```powershell
mvn '-Dtest=SessionInactivityIntegrationTest' test
```

Não é necessário parar o servidor da porta 8080 nem mudar variáveis de ambiente para esse teste.

Permissões granulares, revogação por mudança de função/desativação, recuperação de senha, limitação de tentativas e sessões distribuídas continuam pendentes. Sessão autenticada não concede acesso por estabelecimento automaticamente: futuros endpoints devem validar vínculo ativo e permissões a cada operação. Não avançar para esses módulos sem autorização.
