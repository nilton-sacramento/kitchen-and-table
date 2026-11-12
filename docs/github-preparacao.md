# Preparação profissional do GitHub

## Publicação da Etapa 1

develop e feature/etapa-1-autenticacao foram publicadas sem force push. PR Draft #1: https://github.com/nilton-sacramento/kitchen-and-table/pull/1, origem feature/etapa-1-autenticacao, destino develop. Nenhum merge realizado.

A branch chore/github-ci foi criada diretamente de develop (ba275cd), sem trazer a implementação por merge. Inclui workflow, Dependabot, template de PR, CONTRIBUTING, SECURITY e guias. O README da arquitetura real e a preservação do README histórico estão no PR #1. docs/prompt-master.md permanece vazio e intacto, como recebido.

## Validação local executada

- actionlint v1.7.12: workflow aprovado; binário oficial conferido contra checksum oficial, fora do versionamento.
- node --check: sintaxe do JavaScript aprovada no checkout isolado de 04b136c.
- mvn --batch-mode --no-transfer-progress -Ddebug=false -Dlogging.level.org.springframework=INFO verify: BUILD SUCCESS, 18 testes, zero falhas/erros/ignorados, JAR executável empacotado.
- Nenhum teste usa o PostgreSQL local ou de produção. O timeout normal continua 30 minutos; o teste de inatividade reduz somente as sessões do contexto de teste.

A reprodução local não equivale ao resultado remoto do Actions. Conferir as execuções efetivas na aba Actions: https://github.com/nilton-sacramento/kitchen-and-table/actions. O resumo de cada execução indica se utilizou o bootstrap do commit fixo.

## Segurança confirmada e pendências de ativação

A conta nilton-sacramento foi validada pelo Credential Manager e API oficial, com permissão administrativa. Token permaneceu em memória; nenhuma credencial foi gravada em arquivo ou exibida. Não houve necessidade de novo login ou plugin.

Na consulta inicial, o repositório foi confirmado público; secret scanning e secret scanning push protection estavam habilitados. Atualizações automáticas de segurança Dependabot estavam desabilitadas. Isso não informa sozinho o estado dos alertas de vulnerabilidade, que deve ser consultado separadamente.

A ativação de configurações e proteções deve ser confirmada pela API/interface. Não considerar um YAML ou uma instrução como configuração remota já aplicada. O Dependabot de versões depende de integração aprovada da configuração na branch padrão main. Nada será integrado em main sem autorização.

## Restrições mantidas

Não há merge automático, alterações de refs em main, force push, tags, implantação, serviço pago ou desenvolvimento da Etapa 2. O código original e o PostgreSQL existente não foram alterados. CI usa token somente de leitura, sem segredos privilegiados ou pull_request_target.

Branches: main estável aprovada; develop integração; feature/* funcionalidades; fix/* correções; chore/* manutenção/configuração. Tags somente depois da integração aprovada. Ver CONTRIBUTING.md e docs/protecoes-github.md.

## Resultado remoto efetivamente confirmado

- PR #2 Draft: https://github.com/nilton-sacramento/kitchen-and-table/pull/2, chore/github-ci → develop.
- CI de push aprovado: https://github.com/nilton-sacramento/kitchen-and-table/actions/runs/43267996083.
- CI de PR aprovado: https://github.com/nilton-sacramento/kitchen-and-table/actions/runs/43268163902.
- Nos logs reais dos dois runs foi encontrado o resumo de 18 testes, zero falhas, erros ou ignorados; sintaxe JavaScript e empacotamento também concluíram com sucesso.
- Os dois runs validaram explicitamente o bootstrap do commit fixo 04b136c. O PR #1 ainda precisa receber o CI da infraestrutura na base e ser revalidado no seu commit efetivo depois de integração aprovada do PR #2. Não considerar a ausência atual de check no PR #1 como aprovação automática.

Proteções de main e develop aplicadas e lidas novamente pela API: PR obrigatório, check Java 17 e JavaScript associado ao app oficial GitHub Actions (15368), branch atualizada, resolução de conversas, aplicação a administradores, force push/exclusão bloqueados. Zero aprovações externas obrigatórias, sem exigência de CODEOWNERS ou aprovação do último push. Isso preserva trabalho individual e não autoriza merge.

Configurações verificadas após aplicação: Dependabot alerts habilitados (HTTP 204), atualizações automáticas de segurança habilitadas e não pausadas, relato privado de vulnerabilidade habilitado, token padrão Actions read e sem permissão de criar/aprovar reviews. Secret scanning e push protection seguem habilitados. A API não ativou os campos adicionais de padrões genéricos e validação de segredos: permaneceram disabled após a tentativa, portanto não são declarados ativos. Não foi habilitado produto pago para isso.

Dependabot de versões Maven/Actions está preparado no YAML, mas não é declarado ativo antes da integração aprovada do arquivo à branch padrão. Nenhum PR de dependências é prometido antes dessa etapa. A revisão de segredos e um CI verde não equivalem a auditoria completa de vulnerabilidades ou preparação para produção.

Os arquivos e proteções foram publicados/configurados, mas os dois PRs continuam Draft e nenhum merge, tag ou avanço da main foi executado. main permanece em ba275cd3b08b4e56c5b59628935b74378de8985d.
