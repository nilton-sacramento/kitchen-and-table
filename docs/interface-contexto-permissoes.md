# Contexto de interface, rascunhos e permissões

Validação em 18/11/2026: regressão completa e smoke com PostgreSQL real aprovados na branch feature/entrega-2-3-pin-seguranca. Publicação autorizada no PR #5, que permanece Draft; integração e deploy exigem autorização separada.

## Navegação e sessão

A seção e o estabelecimento selecionados são representados no fragmento da URL. Esse estado não é autorização. Antes de restaurá-lo, app.js consulta /auth/me e /estabelecimentos e aplica somente uma unidade autorizada. Se a unidade não estiver mais disponível, seu rascunho é descartado e a seleção usa uma unidade retornada pelo servidor, ou nenhuma quando não há vínculos.

F5, voltar/avançar e retorno à aba usam o mesmo fluxo. Revalidação com contexto inalterado preserva o editor; mudança de unidade limpa imediatamente a tela anterior. Geração de requisições e serialização da restauração impedem respostas antigas de misturar contextos. Falhas de revalidação não autorizam exibir dados antigos.

Login com outra identidade começa na Visão geral. Logout/expiração detectada limpam contexto e rascunhos. Um BroadcastChannel sem credenciais propaga a invalidação às demais abas administrativas do mesmo perfil. Não há polling novo para renovar a sessão.

## Rascunhos elegíveis

Somente o cadastro/edição de pessoas usa persistência em sessionStorage:

- Nome, e-mail de contato, telefone; função selecionada apenas no cadastro novo.
- Escopo por usuário validado, unidade, formulário e vínculo ou cadastro novo.
- Metadados de versão do formato, versão do registro e validade de 30 minutos após a edição.
- Limites de tamanho e lista explícita de campos. Nunca serializar formulários inteiros.

Não armazenar senha, PIN, cookie, token, credencial Basic ou código temporário. Formulários de segurança operacional, permissões e delegações não são persistidos. Conteúdo só é restaurado depois da validação de acesso; versão antiga do registro impede recuperar a edição antiga. Salvar continua exigindo ação explícita, CSRF e controle de concorrência do backend.

Cancelar/descartar, salvamento confirmado, logout, expiração detectada e perda de autorização removem o rascunho correspondente. Trocar de unidade oculta o editor e permite retomar somente seu escopo autorizado. Storage indisponível não bloqueia a aplicação; nesse caso não há garantia de persistência.

sessionStorage não é backup nem criptografia. Dados de contato são pessoais: políticas de dispositivo compartilhado, XSS e acesso local continuam exigindo revisão antes do piloto. Fechar a aba pode perder rascunhos.

## Permissões e prévia

A interface agrupa por categoria/módulo, permite recolher grupos, pesquisar sem acentos e filtrar acesso efetivo, negações, exceções, críticas e contratos futuros. O resumo vem do painel do backend, não de outro cálculo de autorização em JavaScript. Identificadores técnicos ficam recolhidos, e rótulos distinguem função principal, adicionais e exceções.

Novo endpoint compatível e somente de leitura:

GET /api/v1/estabelecimentos/{unidade}/permissoes/padroes

Retorna função e seu catálogo padrão a partir de funcoes_permissoes. Exige sessão administrativa e vínculo válido na unidade, e permissão efetiva de visualizar a unidade, reutilizando o avaliador existente. Não grava permissões, não aplica prévias, não adiciona funções e não concede autoridade a gerentes. Não houve migração nova nem alteração de V1–V10. O avaliador de permissões existente permanece intacto.

A prévia aparece no cadastro e na alteração da função principal. Mostra padrões herdados; não é o acesso efetivo do funcionário e preserva o significado de suas exceções. Alterar funções/delegações continua sujeito às regras e auditoria já existentes. Módulos futuros permanecem indisponíveis.

## Validação disponível e limites

- Maven verify: 143 testes aprovados, zero falhas, erros ou ignorados, com PostgreSQL 16 descartável.
- Node: 20 aprovados. Chrome com API controlada: 26 verificações aprovadas, separadas da integração real.
- Smoke Chrome oficial com Spring Boot/PostgreSQL reais: aprovado, incluindo hierarquia, revogação, PIN, sessão e resoluções desktop/tablet/360px.
- Dois novos testes PostgreSQL de padrões e isolamento aprovados; quatro novos testes HTTP também aprovados. A primeira tentativa revelou argumentos invertidos na montagem de um teste novo; essa chamada foi corrigida e toda a suíte repetida.
- Navegação B/Funcionários após F5, rascunho A após F5/alternância A–B e painel real de permissões em 360px foram conferidos no cenário manual novo. Logout/login não restaurou o editor anterior.

O cenário manual antigo não foi recuperado. Foi criado um cenário novo, isolado e com credenciais próprias, preservando os 155 arquivos históricos conferidos por hash. T38, T51 e T57 foram comprovados; T57 teve 31,51 minutos reais sem renovação, F5 exigiu login e a API retornou 401. T50 foi comprovado na auditoria final: o bloqueio ao ocultar a aba é esperado; após reidentificar o Atendente na nova aba, Trocar operador e entrar como Caixa, o retorno à original por mais de cinco segundos mostrou contexto vazio e exigiu identificação. O roteiro original e a pendência histórica foram preservados. O consolidado tem 57 aprovações de origens documentadas, sem repetir T01–T19. Detalhes e capturas permanecem somente locais em tests/manual/etapa-2.3/relatorios/recuperacao-2026-11-18.

## Comandos

```powershell
node --test scripts/test-interface-contexto.mjs
node scripts/test-interface-browser.mjs
mvn --batch-mode --no-transfer-progress verify
node scripts/smoke-funcionarios.mjs
```

Os dois primeiros não dependem de PostgreSQL. O teste Chrome com API controlada não é validação de backend. Os dois últimos exigem Docker e banco descartável criado pelas fixtures. Nunca iniciar a aplicação no PostgreSQL operacional para executar estes testes.

Logs, capturas, checkpoints e continuidade desta sessão estão em .tools/missao-2026-11-18 e no relatório local em tests/manual/etapa-2.3/relatorios/missao-2026-11-18. Esses registros são históricos da validação; publicação, integração e deploy seguem autorizações específicas e o CI do commit efetivamente enviado.
