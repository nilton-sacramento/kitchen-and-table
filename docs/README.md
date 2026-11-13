# Documentação do KITCHEN & TABLE 2.0

Índice por assunto, sem mover os documentos existentes ou quebrar seus endereços. Para o estado atual, comece pelo [README principal](../README.md). Relatórios de entregas descrevem suas versões e preservam resultados históricos, que não devem ser tratados como total atual da suíte.

## Começar e contribuir

- [Estrutura do projeto e arquivos principais](estrutura-projeto.md)
- [Instalação e variáveis](instalacao-local.md)
- [Setup técnico — somente banco novo](configuracao-inicial.md)
- [Contribuição](../CONTRIBUTING.md)
- [Scripts de validação](../scripts/README.md)
- [Organização e execução dos testes](organizacao-testes.md)

## Arquitetura e entregas

- [Autenticação por sessão — base da Etapa 1](autenticacao-sessao.md)
- [Etapa 1](etapa-1.md)
- [Entrega 2.1 — funcionários e vínculos](entrega-2-1.md)
- [Entrega 2.2 — funções e permissões](entrega-2-2.md)
- [Revisão final da Entrega 2.2](revisao-final-entrega-2-2.md)
- [Entrega 2.3 — PIN e segurança operacional](entrega-2-3.md)
- [Correções de interface planejadas, não implementadas](planejamento-correcoes-interface.md)

## Segurança, CI e GitHub

- [Política de segurança](../SECURITY.md)
- [CI definitivo](ci.md)
- [Fluxo de publicação — preparação histórica, com orientação atual](fluxo-publicacao.md)
- [Proteções do GitHub — recomendações e estado histórico](protecoes-github.md)
- [Backup e recuperação](backup-recuperacao.md)
- [Auditoria estrutural e organização desta revisão](reorganizacao-repositorio.md)

## Registros históricos preservados

- [Planejamento original](README-planejamento-original.md): contém tecnologias/intenções que não equivalem à implementação atual.
- [Preparação inicial do GitHub](github-preparacao.md): registro da preparação da Etapa 1.
- [Transição do CI](transicao-ci.md): história do bootstrap; o workflow ativo já testa o commit real do evento.
- [Proposta histórica de PR da Etapa 1](prs/etapa-1-para-develop.md)
- [Proposta histórica de promoção a main](prs/develop-para-main.md): presença do documento não autoriza merge.
- [prompt-master.md](prompt-master.md): arquivo recebido vazio, preservado sem inventar conteúdo.

## Materiais que permanecem somente locais

Roteiro, acompanhamento, conciliação T01–T19, relatórios e evidências da etapa 2.3 ficam em tests/manual/etapa-2.3 e são ignorados pelo Git. Credenciais, perfis e estado de execução ficam em .tools. O guia dos testes explica a localização; nenhum relatório privado é anexado ao repositório público.
