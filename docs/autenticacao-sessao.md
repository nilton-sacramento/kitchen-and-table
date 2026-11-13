# Autenticação por sessão — base da Etapa 1

Este guia descreve a base preservada. Funcionários, permissões e PIN foram ampliados nas [Entregas 2.1](entrega-2-1.md), [2.2](entrega-2-2.md) e [2.3](entrega-2-3.md); consulte também a [instalação atual](instalacao-local.md).

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

Configure DB_PASSWORD no terminal conforme o README (sem senha padrão). Somente em banco novo de desenvolvimento autorizado, use a configuração descrita no guia de instalação e execute na raiz:

```powershell
mvn spring-boot:run
```

No ambiente manual existente, use os scripts privados em .tools/testes-manuais-2-3 em vez desse comando, preservando o cenário.

Abra http://localhost:8080 e pressione Ctrl+F5 para obter o JavaScript atualizado. Use a conta do ambiente de desenvolvimento autorizado. Não acessar o PostgreSQL operacional, recriar banco ou repetir setup.

- `KT_SESSION_TIMEOUT`: padrão `30m`, expiração por inatividade no servidor. Requisições da sessão renovam a contagem.
- `KT_COOKIE_SECURE`: padrão `false` exclusivamente para HTTP local. Em ambiente HTTPS configurar `true`; TLS e proxies devem ser revisados antes de qualquer publicação. Não usar `true` no HTTP local, pois o cookie não será enviado.
- `SERVER_ADDRESS`: padrão `127.0.0.1`. Não há publicação nesta entrega.

## Roteiro no navegador, um teste por vez

1. Login correto: dashboard com dados existentes do proprietário.
2. F5: continua autenticado; a interface atual abre Visão geral. Preservar a seção e a unidade selecionadas é uma melhoria planejada, ainda não implementada.
3. Sair: volta ao formulário.
4. Após sair, F5 continua no formulário; abrir `/api/v1/auth/me` ou `/api/v1/estabelecimentos` retorna 401, sem dados.
5. Senha incorreta: erro e nenhum dashboard.
6. Expiração normal: no ambiente descartável, mantenha o prazo de 30 minutos. Após login, aguarde 31 minutos completos sem interação ou requisição no mesmo perfil. Retornar à aba visível pode revalidar e renovar a sessão; se houver renovação durante a espera, contar novamente ou registrar pendência. Depois F5 deve exigir login e `/api/v1/auth/me` deve retornar 401. Não alterar o relógio nem a configuração normal. No roteiro manual atual, T57 continua pendente de execução pessoal.

## Verificação automatizada e limites

`mvn test` verifica autenticação, proteção da API, CSRF real, rotação do ID, persistência da sessão e invalidação pelo logout. `SessionInactivityIntegrationTest` inicia Tomcat real em porta aleatória, somente em 127.0.0.1, com cliente HTTP e cookies reais. Um listener exclusivo do teste reduz o timeout de cada sessão para dois segundos; após 3,2 segundos sem requisições, a conta e os estabelecimentos devem retornar 401. O teste não chama invalidate(), não altera o application.yml e não conecta ao PostgreSQL. Também confere HttpOnly e SameSite=Lax no Set-Cookie real. A configuração normal permanece 30 minutos. Os demais testes de autenticação da Etapa 1 usam MockMvc e dependências de banco simuladas. As integrações acrescentadas nas entregas seguintes usam PostgreSQL 16 descartável, sem acessar o banco operacional. `node --check src/main/resources/static/assets/app.js` verifica sintaxe, se Node estiver disponível.

Para repetir apenas a expiração automatizada, no PowerShell da raiz do projeto:

```powershell
mvn '-Dtest=SessionInactivityIntegrationTest' test
```

Não é necessário parar o servidor da porta 8080 nem mudar variáveis de ambiente para esse teste.

Permissões por unidade, delegações, revogação de acesso e bloqueios operacionais foram ampliados nas entregas seguintes. Recuperação administrativa de senha, proteção global contra tentativas em massa e sessões distribuídas continuam pendentes. Sessão autenticada não concede acesso automático a qualquer estabelecimento: operações protegidas devem revalidar vínculo e autoridade no backend. Não iniciar módulos futuros sem autorização.
