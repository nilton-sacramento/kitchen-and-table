# Planejamento das correções funcionais após a reorganização

Status: somente planejamento. A reorganização foi concluída e validada antes deste documento. Nenhum código funcional foi alterado. Implementação depende de autorização específica do proprietário.

## Base e limites

Preservar autenticação por sessão, CSRF, hierarquia, isolamento por estabelecimento e revogação no backend. Não alterar regras de negócio, conceder privilégios automáticos, aplicar migrações operacionais ou iniciar módulos futuros. Não confundir melhorias relatadas pelo proprietário com defeitos novamente reproduzidos nesta reorganização.

Os testes manuais mantêm 53 aprovações conciliadas e quatro pendências: T38, T50, T51 e T57. Smoke automatizado aprovado não altera esses resultados.

## 1. Permanência da seção após F5

Problema relatado: atualização retorna à Visão geral. O fluxo restoreSession em assets/app.js seleciona dashboard ao restaurar autenticação. Planejar a restauração da navegação somente depois de confirmar a sessão real e carregar os estabelecimentos autorizados.

Proposta técnica: representar seção e unidade no estado de navegação, preferencialmente com URL normalizada e valores permitidos, sem credenciais. Considerar navegação voltar/avançar. Dados da URL são pedidos de navegação, nunca autorização. Seção inválida deve ter recuperação compreensível sem executar operações.

Componentes prováveis: app.js e smoke-funcionarios.mjs. Critérios: F5 preserva a seção acessível, voltar/avançar coerentes, sessão expirada exige login e nenhum conteúdo protegido aparece antes da revalidação.

## 2. Permanência do estabelecimento após F5

Problema relatado: unidade B retorna à A. Restaurar seleção somente se a unidade ainda constar da consulta autorizada ao backend. Fazer seleção antes de carregar funcionários, permissões ou segurança operacional. Invalidar carregamentos antigos quando a unidade mudar, evitando exibir resposta de A em B.

Proposta: usar o mesmo estado de navegação da seção; verificar contrato atual de /auth/me e /estabelecimentos antes da implementação. Unidade removida ou vínculo revogado deve limpar contexto e rascunhos correspondentes e apresentar mensagem clara. Não criar contexto de autorização somente no frontend.

Componentes prováveis: app.js, funcionarios.js e controladores de interface que recebem a unidade. Critérios: F5 em B permanece em B, IDs manipulados não autorizam acesso, respostas atrasadas não misturam unidades e logout impede restauração protegida.

## 3. Preservação segura de rascunhos

Problema relatado: formulários não salvos devem sobreviver à atualização com isolamento e autenticação. Hoje há rascunhos em memória, que não garantem essa durabilidade.

Proposta técnica para revisão: sessionStorage apenas para campos explicitamente permitidos de formulários administrativos, com versão do formato, prazo limitado e escopo por usuário, estabelecimento, tipo de formulário e registro. Restaurar somente após revalidar sessão, vínculo e autorização no backend. Nunca incluir senha, PIN, token, cookie, credenciais HTTP Basic, códigos temporários de associação ou payload genérico de formulário.

Limpar no logout, troca de usuário, expiração detectada, revogação de acesso, descarte explícito e conclusão do salvamento. Na troca de unidade, ocultar imediatamente o rascunho e recuperar somente o escopo autorizado correspondente. Revalidar versão do registro antes de salvar; não enviar automaticamente rascunhos nem sobrescrever dados alterados por outra pessoa.

Antes de implementar: inventariar os campos reais e sua classificação, além de verificar necessidade de expiração independente em abas ainda abertas. O mecanismo proposto não deve armazenar segredos nem ser tratado como backup ou proteção contra acesso indevido ao dispositivo.

Critérios: rascunho elegível sobrevive F5, não aparece a outro usuário/unidade, é descartado após logout/expiração e não restaura PIN ou permissões sem revalidação. Testar também falha de rede e concorrência de versões.

## 4. Organização da interface de permissões

Melhoria relatada: lista extensa e pouco intuitiva. Planejar agrupamento por módulo, distinção clara entre função principal/adicionais, padrões herdados, exceções individuais e acesso efetivo. Exibir criticidade e justificativas de bloqueio sem alterar o cálculo de permissões existente.

Proposta: filtros acessíveis e resumo de alterações, preservando os limites do gerente, controles sem autoridade desabilitados e indicação inequívoca de contratos de módulos futuros. Não apresentar operações financeiras futuras como disponíveis.

Componentes prováveis: permissoes.js e app.css. Critérios: uso em 360px, tablet e desktop; teclado e foco preservados; mensagens compreensíveis; conceder, negar e herdar mantêm os mesmos contratos e validações do backend.

## 5. Sugestão de permissões básicas conforme a função

Melhoria relatada: sugerir permissões básicas ao escolher a função. Reutilizar o catálogo e os padrões por função existentes, sem criar uma segunda matriz independente no JavaScript.

Proposta: mostrar os padrões herdados e a prévia do resultado ao escolher a função; preservar exceções individuais de forma explícita. Não converter silenciosamente sugestões em concessões individuais nem sobrescrever negações. Funções adicionais operacionais não concedem autoridade administrativa. Permissões críticas continuam dependendo das autorizações já aprovadas.

Componentes prováveis: funcionarios.js e permissoes.js. Alterações de API somente se uma lacuna real impedir a prévia; submeter esse ajuste ao escopo de implementação antes de mudar contratos.

Critérios: funcionário comum não recebe poderes de gerente/proprietário; gerente não amplia próprios limites; negações individuais prevalecem; padrões são independentes por estabelecimento e operações futuras continuam indisponíveis.

## Ordem e validação propostas

1. Reproduzir os problemas autorizados em ambiente descartável e registrar evidências específicas.
2. Corrigir restauração de seção/unidade em um incremento verificável.
3. Implementar rascunhos elegíveis após revisar campos e limpeza de sessão.
4. Melhorar permissões e prévia de padrões, preservando os contratos atuais.
5. Executar Maven verify completo e smoke Chrome, acrescentando cenários de regressão coerentes com as mudanças.
6. Realizar validação pessoal do proprietário e retestar os casos pertinentes sem substituir aprovação manual por automação.

T38 requer um cenário sem PIN, preservando a credencial atual. T50 requer revisão da sequência de abas frente ao bloqueio automático. T51 requer confirmação manual do passo offline. T57 requer 31 minutos contínuos sem renovação de sessão. Essas pendências não autorizam enfraquecer controles ou alterar resultados.

Não há autorização neste documento para implementação, commit, push, merge, deploy, alteração do banco operacional ou início da Entrega 2.4.
