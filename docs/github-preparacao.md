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
