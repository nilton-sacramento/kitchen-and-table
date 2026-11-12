## Problema e resultado

O repositório descrevia a visão de produto, mas ainda não continha a fundação executável. Esta alteração incorpora a Etapa 1 já desenvolvida: backend Spring Boot/PostgreSQL, interface HTML/CSS/JavaScript e login por sessão com restauração após F5 e logout no servidor.

## Escopo

- Login, identificação de usuário, vínculos e seleção de estabelecimento.
- Dashboard com dados reais, navegação responsiva e módulos futuros explicitamente em desenvolvimento.
- Sessão HttpOnly/SameSite=Lax, proteção CSRF, rotação de ID, expiração e HTTP Basic desabilitado.
- Migração original preservada; nenhum dado, volume ou senha existente alterado.
- README anterior preservado em docs/README-planejamento-original.md e prompt-master.md preservado sem alteração.
- DB_PASSWORD exigida por ambiente, sem senha padrão no código.

## Validação

Consulte docs/etapa-1.md para o resultado da execução no clone. O teste de inatividade usa Tomcat real e cookies em loopback, com dois segundos exclusivos do teste. Os testes não acessam o PostgreSQL. O fluxo com o proprietário real foi validado manualmente pelo responsável no projeto original.

## Limites

Não entrega módulos operacionais nem produção. Sessões estão em memória. HTTPS, cookie Secure em ambiente HTTPS, autorização granular, limitação de tentativas e sessões distribuídas continuam pendentes. O servidor local só escuta em 127.0.0.1 por padrão.

Este PR tem como base develop. A promoção a main exige nova aprovação e revisão, sem merge automático.
