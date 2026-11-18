# Diagnóstico do ambiente de testes — continuação de 18/11/2026

Status histórico do diagnóstico inicial: infraestrutura bloqueada. As cinco melhorias locais anteriores foram preservadas; nenhum commit, push, merge ou deploy foi realizado.

## Fatos verificados

- A branch continua feature/entrega-2-3-pin-seguranca, base de5fc5427648838879760b83b6dde510e9d90f6e, com as alterações locais anteriores.
- O Windows mantém registro do Docker Desktop 4.99.0, mas sua pasta registrada de instalação não foi encontrada. O executável docker não está no PATH nem foi localizado nas buscas concluídas.
- Há plugins Docker no perfil local. Docker Compose responde à versão e consegue ler o serviço db com config --no-interpolate --services. Isso NÃO comprova execução de containers.
- O contexto registrado é desktop-linux, com endpoint local dockerDesktopLinuxEngine. Não há pipe acessível desse engine.
- WSL está presente, com distribuição docker-desktop registrada como versão 2. A tentativa de executar /bin/true falhou na montagem do disco registrado, com Wsl/Service/CreateInstance/MountDisk/ERROR_PATH_NOT_FOUND.
- O caminho registrado do disco da distribuição termina em %LOCALAPPDATA%/Docker/wsl/main/ext4.vhdx e não está disponível. A pasta registrada da aplicação é %LOCALAPPDATA%/Programs/DockerDesktop e também não está disponível.
- O serviço WSL está ativo. vmcompute estava parado e foi acionado automaticamente pela tentativa de usar WSL; não foram alteradas configurações de inicialização ou recursos opcionais do Windows. O engine Docker não iniciou.
- Existe um Docker Desktop Installer nos Downloads, com assinatura válida da Docker Inc. Não foi executado. Não foi baixado outro instalador.

Isso explica a indisponibilidade técnica, mas não permite afirmar por que as pastas desapareceram, que houve desinstalação, nem que todos os dados Docker foram perdidos. A busca complementar fora dos locais principais ficou parcial; não representa uma varredura completa de backups.

## PostgreSQL manual anterior

O estado local registra kt_manual_23 e um container anterior, mas o estado.json é histórico e não prova que o container ainda exista. O health na porta local 18083 não responde. Sem daemon não foi possível enumerar containers, imagens, mounts ou volumes. Não foi aberto nenhum PostgreSQL operacional.

O disco ausente é o disco registrado da distribuição WSL; os dados de containers podem depender de outro disco ou backup. Não concluir que a ausência desse arquivo prova que o banco está vazio ou definitivamente perdido. Não montar o swap.vhdx encontrado em temporários como se fosse armazenamento PostgreSQL.

Não foram removidos containers, imagens, volumes, discos, locks ou bancos. Não foram executados reset de fábrica, prune, down -v, wsl --unregister ou instalação/reparo. Nenhum cenário manual novo foi criado para substituir os dados anteriores.

As variáveis DB_URL, DB_USER, DB_PASSWORD, DOCKER_HOST, DOCKER_CONTEXT e override de socket não estão definidas no processo. Isso não impede os testes Testcontainers, que fornecem configuração própria; não preencher essas variáveis com dados operacionais como tentativa de contorno.

## Resultado das verificações

| Verificação | Resultado nesta continuação |
| --- | --- |
| Preservação das alterações anteriores | 22 arquivos conferidos por SHA-256, sem diferenças no início |
| Maven verify completo | BUILD FAILURE por infraestrutura: 143 casos, 22 aprovados, 121 erros de inicialização Docker, zero falhas de asserção e zero ignorados |
| Testes Node | 20 aprovados |
| Chrome com API controlada | 26 verificações aprovadas; não é integração PostgreSQL |
| Smoke oficial com backend real | Bloqueado: fixture encerrou antes de iniciar |
| Integração real das cinco melhorias | Não aprovada; pendente de Docker/PostgreSQL |
| T38/T50/T51/T57 | Não executados nesta sessão; classificações históricas preservadas |

Não houve defeito funcional comprovado nas verificações que conseguiram executar. Não foi alterado código funcional para contornar a infraestrutura.

## Intervenção necessária e retomada segura

1. O proprietário precisa localizar uma cópia do armazenamento Docker ou um backup, ou confirmar outro local de dados. Não enviar credenciais ou conteúdo do banco em conversa pública.
2. Definir a recuperação do disco e o reparo da instalação antes de iniciar um Docker vazio. O instalador existente foi verificado, mas executá-lo é uma alteração do sistema e pode exigir elevação administrativa; não foi autorizado/realizado automaticamente por este diagnóstico.
3. Não aceitar opções de apagar dados, resetar fábrica ou desregistrar a distribuição sem autorização específica e cópia recuperável.
4. Após recuperar a aplicação/engine local, verificar docker info, Compose, containers e volumes. Conferir o container manual por ID e rótulo, não apenas por nome ou porta; inspecionar somente os metadados necessários, sem imprimir env/credenciais.
5. Se o container antigo não estiver acessível, registrar a situação e confirmar que qualquer ambiente novo é distinto e não afeta dados existentes. Não reutilizar o estado antigo como prova de um cenário novo.
6. Só então repetir Maven verify e smoke oficial, validar as cinco melhorias no backend real e retomar os quatro casos originais. Não iniciar o backend contra application.yml padrão para contornar a falha do ambiente manual.

Os scripts locais podem ter runner.lock/estado de processos antigos. Não executar Iniciar ou Finalizar sem verificar esses artefatos e o estado real do container. Esta sessão não apagou locks nem tentou criar outro cenário.

Logs e metadados desta continuação ficam em .tools/continuacao-2026-11-18, ignorados pelo Git. Relatório local completo e continuidade em tests/manual/etapa-2.3/relatorios/continuacao-2026-11-18.

## Atualização posterior — recuperação em 18/11/2026

O bloqueio descrito acima foi resolvido nesta sessão com instalação oficial assinada do Docker Desktop, após backup privado de metadados e autorização expressa para remover somente sua entrada órfã de desinstalação no HKCU. WSL e dados existentes não foram removidos. Cliente/engine 29.9.3 e Compose 5.7.0 respondem; não foi necessário reiniciar ou atualizar WSL.

O primeiro engine disponível estava vazio; o banco anterior não foi recuperado. Foi criado um cenário novo isolado de PostgreSQL 16.16, com banco kt_manual_23, dez migrações aplicadas e interface local na porta 18083. Arquivos e evidências antigos permanecem intactos. Essa recuperação não comprova perda definitiva de qualquer backup externo.

Maven verify completo passou: 143 testes, zero falhas/erros/ignorados. Node 20, Chrome controlado 26 e smoke Chrome com backend real passaram. Um erro de argumentos em um teste novo foi corrigido antes da repetição completa. Nenhuma regra funcional foi enfraquecida.

Relatório privado atualizado: tests/manual/etapa-2.3/relatorios/recuperacao-2026-11-18/RELATORIO-AMBIENTE.md. Credenciais do cenário novo permanecem somente em .tools; não copiar para este documento. Nenhum banco operacional foi acessado, nem houve commit, push, merge ou deploy.
