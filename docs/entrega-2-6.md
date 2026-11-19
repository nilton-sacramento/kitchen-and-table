# Entrega 2.6 — Pedidos, cozinha, bar e entrega

Implementação exclusiva em feature/entrega-2-6-pedidos-cozinha-bar. Base exata da Entrega 2.5: ff14891a00a7cfe4aea54bed7f5611670b5a5f4d. O PR desta entrega deve ser Draft contra feature/entrega-2-5-cardapio-produtos enquanto a 2.5 não estiver integrada. Nenhum merge, deploy ou banco operacional está autorizado por esta implementação.

## Modelo e migrações

V15 acrescenta sequencias_pedidos, pedidos, linhas_pedido, itens_preparo, operacoes_pedido, solicitacoes_pedido e decisoes_pedido. FKs compostas incluem estabelecimento; linhas são snapshots imutáveis, protegidos por trigger. V16 ativa capacidades de pedidos e produção já catalogadas e adiciona consulta, entrega e solicitação de alteração. Não concede permissões críticas ou administrativas automaticamente. V1–V14 são preservadas.

Cada confirmação cria um pedido central, linhas de venda e itens físicos de produção. Uma linha de combo cobra seu preço próprio uma vez e guarda componentes/quantidades/setores. Cada componente gera item no setor correto; o componente não vira uma segunda cobrança. Nome, produto, preço, composição, setor e observação são históricos. O executor e a data da criação e de cada ação são registrados. Atendimento compartilhado não substitui o autor da comanda pelo autor do pedido.

## Confirmação e estoque

O atendente consulta ofertas atuais, seleciona itens e revisa antes de enviar. Preço e digest da oferta devem corresponder ao catálogo no servidor; alteração exige nova revisão. O digest identifica uma oferta, não é credencial nem substitui autorização. Quantidades de estoque e versões de saldo não integram o digest: vendas de outro atendente não alteram o preço confirmado, mas o saldo é novamente validado e bloqueado na confirmação.

A baixa ocorre na mesma transação do pedido, encaminhamento e auditoria. Combos são decompostos e consumos repetidos do mesmo produto são somados. Produtos simples são bloqueados em ordem de UUID, evitando saldo negativo e venda concorrente da última unidade. A sequência do pedido é obtida após os locks de estoque. Não há reserva fictícia, estoque próprio de combo, compra, insumo ou ficha técnica.

Idempotency-Key UUID é obrigatório na confirmação e na solicitação. A chave fica vinculada à unidade, executor, comanda e conteúdo. Repetir o mesmo envio retorna a operação existente; outro conteúdo ou operador conflita. A autorização atual é revalidada antes de permitir um replay. Sem conexão, a tela mantém a intenção imutável para retentativa enquanto aberta; F5/saída descartam o editor. Consulte os pedidos confirmados antes de iniciar outro após perda de conexão e atualização.

## Produção, entregas e comandas

Itens passam por AGUARDANDO → EM_PREPARO → PRONTO → ENTREGUE. Estado geral é calculado a partir de todos os itens: uma bebida pronta não conclui o pedido inteiro. Cada item usa versão própria e as mutações serializam brevemente o pedido; cozinha e bar podem progredir sem uma versão de item invalidar a do outro setor.

Entrega parcial permite selecionar itens prontos. Tudo junto exige todos os itens ativos prontos e entrega do conjunto completo na mesma transação. A entrega registra o executor verdadeiro. Repetição/versão obsoleta não duplica a operação.

Comanda aberta aceita novos pedidos; encerrada/cancelada não aceita. Pedido histórico bloqueia encerramento operacional sem consumo, mesmo que um futuro código tente limpar flags. O latch de consumo da 2.4 permanece protegido. O guard de integração futura continua para PAGAMENTO_REGISTRAR/CAIXA_OPERAR; PEDIDO_REGISTRAR/ENVIAR saem desse guard global porque agora possuem integração real. O teste antigo mantém a mesma asserção 409, exercitando o guard com PAGAMENTO_REGISTRAR, ainda futuro. Não há quitação ou fechamento financeiro nesta entrega.

## Alteração e cancelamento controlados

O atendimento apenas solicita, informa motivo e, para alteração, revisa uma proposta completa com preço/composição atuais. Os setores envolvidos incluem tanto os itens originais quanto os novos componentes propostos. Um setor novo recebe a solicitação mesmo sem possuir item no pedido original. Cada setor vê somente seus itens propostos, sem preços administrativos.

Decidir exige permissão do setor para preparo e capacidade crítica PEDIDO_ENVIADO_ALTERAR ou PEDIDO_CANCELAR, concedida explicitamente pelo proprietário ou pela hierarquia existente dentro de seus limites. A função cozinha/bar não recebe essas capacidades críticas como padrão. A interface orienta procurar o responsável quando falta autorização.

A aprovação exige versão atual do pedido e da decisão. Setor que já autorizou fica impedido de continuar preparo/entrega enquanto aguarda outros. Recusa preserva pedido e saldo, liberando continuidade. Antes de executar unanimidade, o backend revalida vínculos, permissões e versão/bloqueio das credenciais de solicitante e decisores. Revogação ou redefinição de PIN invalida consentimentos pendentes. Expiração de uma sessão não substitui essa decisão de negócio: execução atual sempre exige sessão válida do decisor que conclui.

Cancelamento autorizado devolve somente quantidades efetivamente consumidas cujos itens ainda estavam AGUARDANDO. EM_PREPARO/PRONTO não devolvem automaticamente ingredientes/estoque. Não se inventa devolução de produto originalmente não controlado. Cancelamento não realiza estorno financeiro.

Alteração executada preserva o pedido original cancelado e cria um substituto com snapshots novos e referência ao original. Proposta com preço/composição indisponível ou saldo insuficiente é recusada sem efeitos parciais. Locks de devolução/novo consumo seguem uma única ordem global. Itens já entregues, inclusive parcialmente, bloqueiam cancelamento/alteração neste fluxo: dependem de futuro tratamento financeiro explícito.

## Autorização, sessão e isolamento

Todas as APIs são operacionais e exigem principal de PIN válido, estabelecimento/vínculo ativos, cabeçalho X-Operador-Vinculo correspondente ao principal e capacidade específica. Sessão administrativa não substitui PIN. O cabeçalho nunca autentica. Toda mutação mantém CSRF; mudanças de operador, bloqueio e inatividade preservam os mecanismos da 2.3. Revogação confirmada bloqueia novas operações; locks compartilhados de unidade coordenam a operação com revogações administrativas exclusivas.

A fila consulta somente seu setor/unidade e não expõe totais/preços, hashes de credenciais ou tokens. Recursos JavaScript públicos são caminhos exatos de código estático, não dados protegidos. A navegação em hash preserva seção/comanda no F5, sujeito à revalidação existente. Editor em memória é destruído ao sair/bloquear/trocar contexto e não usa Web Storage para credenciais ou pedidos.

Auditoria transacional usa os tipos PEDIDO e SOLICITACAO_PEDIDO da tabela existente. Criação, envio, encaminhamento, preparo, pronto, entrega, solicitações, consentimentos, recusas, execução e movimentações de estoque guardam autores e datas. PINs e hashes não fazem parte dos eventos.

## APIs implementadas

Prefixo /api/v1/operacional/estabelecimentos/{unidade}; header X-Operador-Vinculo em todas:

| Método | Caminho | Finalidade |
|---|---|---|
| GET | /pedidos/ofertas | Catálogo e digest atuais para revisão |
| GET/POST | /comandas/{id}/pedidos | Consultar ou confirmar novo pedido |
| GET | /pedidos/{id} | Snapshot e estados atuais |
| GET | /pedidos/{id}/historico | Eventos do pedido e solicitações |
| POST | /pedidos/{id}/entregas | Confirmar itens prontos e versões |
| GET/POST | /pedidos/{id}/solicitacoes | Consultar/solicitar alteração ou cancelamento |
| GET | /fila/{COZINHA ou BAR} | Itens do setor |
| POST | /fila/{setor}/itens/{id} | Iniciar preparo ou marcar pronto |
| GET | /fila/{setor}/solicitacoes | Decisões e proposta somente do setor |
| POST | /fila/{setor}/solicitacoes/{id}/decisao | Autorizar/recusar com versões e motivo |

## Código e execução

Backend em src/main/java/com/kitchentable/pedidos: PedidoRegistroService (confirmação/snapshots), ProducaoService (filas/entregas), AlteracaoPedidoService (decisões), PedidoRepository, PedidoDtos e PedidoController. Integrações existentes: AcessoAtendimentoService, EstoqueCardapioService, ComandaService e auditoria.

Frontend servido pelo Spring em src/main/resources/static/assets: pedidos.js (consulta/ações na comanda), pedido-editor.js (catálogo, carrinho e revisão), producao.js (filas/decisões), pedido-contratos.js (centavos/intenção), pedido-ui.js (rótulos), atendimento.js e operacional.js (contexto). Menu administrativo aponta para acesso operacional real; não executa operações sob a sessão do proprietário.

Execute mvn verify com Docker Desktop ativo; o Testcontainers cria PostgreSQL 16 exclusivo em loopback, com credenciais sintéticas. Depois execute node --test scripts/test-interface-contexto.mjs scripts/test-atendimento.mjs scripts/test-cardapio.mjs scripts/test-pedidos.mjs e node scripts/smoke-funcionarios.mjs. Nunca aponte esta feature para banco operacional: o startup aplica até V16.

## Validação e limites

Validação local de 19/11/2026: mvn verify aprovado com 276 testes Java (220 anteriores e 56 novos), zero falhas/erros/ignorados. Node: 32 aprovados. Chrome com API controlada: 26 verificações aprovadas. Smoke oficial completo com Spring Boot e PostgreSQL 16 reais aprovado: produtos/combos, revisão, cozinha/bar, entrega parcial/tudo junto, cancelamento por unanimidade, alteração com recusa e nova aprovação incluindo setor novo, duas unidades com funcionários diferentes, F5, desktop/tablet/360px, PIN, inatividade real, bloqueio, troca de operador e logout. Capturas e logs ficam somente em .tools/entrega-2-6; validação humana e CI no HEAD publicado são verificações separadas. A cobertura nova inclui migração V14→V16 com login legado/checksums, estoque/combos, preço obsoleto, transações, idempotência, concorrência, hierarquia/PIN/CSRF, isolamento, snapshot, produção, tudo junto/parcial, decisões/revogações e devoluções por estágio. Testes controlados de interface não substituem smoke com Spring/PostgreSQL reais. Testes manuais históricos T01–T57 não são reclassificados por estes testes.

Filas são atualizadas pelo botão Atualizar fila; não há push externo, impressão ou notificação fictícia. Listas não são paginadas e usam consultas adicionais por pedido/solicitação: medir/otimizar antes de volume elevado. Entrega parcial opera por item/lote de produção, sem fracionar a quantidade de um mesmo item. Cancelamento/alteração após entrega e fechamento financeiro seguem fora do escopo. HTTPS/cookie Secure, rate limiting distribuído, operação em múltiplas instâncias, retenção/backup e teste humano de usabilidade continuam obrigatórios antes de piloto. Os novos módulos não tornam a aplicação pronta para produção.

## Ajustes comprovados durante a validação

A recusa de proposta com oferta alterada não marca a transação externa para rollback: a validação sem escrita permite registrar a recusa atomicamente. A whitelist pública contém somente os cinco novos arquivos JavaScript estáticos; APIs continuam protegidas. O frontend usa solicitacoes, conforme o DTO da fila, mantém avisos após recarregar decisões e associa rótulos aos campos do editor. O smoke aguarda um novo documento no F5 (performance.timeOrigin diferente), sem validar o DOM anterior; asserções de negócio e tempos de segurança foram preservados. Testes de módulos futuros foram atualizados para capacidades ainda futuras, mantendo suas negações e ampliando a verificação da cozinha agora implementada.
