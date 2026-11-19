# Entrega 2.5 — Cardápio, produtos e combos

Implementação na feature/entrega-2-5-cardapio-produtos, baseada na develop validada 48abeebceb3cf7a1d2e233f809ba24f12c482a1c (Entrega 2.4 integrada). PR Draft para develop; não autoriza merge, produção ou migração de banco operacional. Resultados finais, commits, HEAD e CI serão registrados após validação; descrição de cobertura não é aprovação de teste.

## Modelo e contratos

V13 cria categorias_cardapio, produtos_cardapio, componentes_combo, movimentos_cardapio e copias_cardapio. V14 reutiliza o catálogo de produtos/estoque, habilita os contratos implementados, classifica a gestão como administrativa e adiciona CARDAPIO_COPIAR. V1–V12 permanecem imutáveis. Não há concessão automática a gerente nem ativação de pedidos/financeiro nesta entrega.

Categorias possuem nome único por unidade, ordem, situação e versão. Produtos têm categoria da mesma unidade, nome, descrição, tipo SIMPLES/COMBO imutável, preço inteiro em centavos, setor COZINHA/BAR nos simples, situação, disponibilidade manual, quantidade opcional controlada e versão. Preço de 0 a 9.999.999.999 centavos; quantidade é inteira não negativa. A API rejeita coerção silenciosa de números fracionários para inteiros. O frontend converte texto decimal com BigInt para centavos, sem arredondamento financeiro por ponto flutuante.

Disponibilidade efetiva exige produto ativo/disponível, categoria ativa e quantidade positiva quando controlada. Combo tem preço próprio, não possui estoque próprio e exige componentes simples da mesma unidade, sem repetição/recursão. Sua disponibilidade considera cada quantidade necessária. Receitas atuais podem ser editadas com versão e auditoria; vendas futuras deverão capturar composição e preço em snapshot próprio, não consultar composição atual para reescrever histórico.

Nenhuma exclusão de categorias/produtos é exposta. Triggers preservam escopo, tipo e referências. FKs compostas isolam unidades. Mutações administrativas reutilizam o lock por unidade e exigem versão em edições/ajustes. Auditoria participa da mesma transação.

## Autoridade

Administração exige sessão administrativa, vínculo/unidade ativos e função principal proprietário ou gerente com a capacidade efetiva específica. PIN não administra cardápio; função adicional não concede autoridade administrativa. Negação individual e limites de concessão existentes continuam válidos.

- PRODUTO_CONSULTAR: consulta administrativa ou consulta operacional por PIN.
- PRODUTO_GERENCIAR: categorias, produtos e composição, sempre no fluxo administrativo.
- PRODUTO_PRECO_ALTERAR: preço inicial e alteração de preço.
- PRODUTO_DISPONIBILIDADE_ALTERAR: situação/disponibilidade inicial e mudanças.
- ESTOQUE_AJUSTAR: contagem autorizada e alteração de controle de quantidade.
- ESTOQUE_CONSULTAR: consulta de movimentos.
- CARDAPIO_COPIAR: prévia/confirmação nas duas unidades; gestão/preço/disponibilidade também necessários no destino.

Novos produtos controlados começam em zero. Quantidades iniciais são informadas pelo ajuste auditado com motivo, não por cadastro ou cópia. Nenhum PIN/senha/hash é incluído na auditoria ou DTO de cardápio.

## APIs

Prefixo administrativo /api/v1/estabelecimentos/{unidade}/cardapio:

| Método/caminho | Finalidade |
| --- | --- |
| GET / | Catálogo real de categorias e produtos/combos |
| POST /categorias; PUT /categorias/{id} | Cadastro/edição com ordem e situação |
| POST /produtos; PUT /produtos/{id} | Produto simples ou combo; versão nas edições |
| POST /produtos/{id}/ajustes | Contagem absoluta, motivo e versão |
| GET /produtos/{id}/movimentos | Histórico de quantidade |
| GET /produtos/{id}/historico | Eventos do produto/combo/estoque |
| POST /copias/previa | Destino e prefixo opcional; conflitos e prazo |
| POST /copias/{id}/confirmar | Confirmação atômica, descartável e idempotente |

GET /api/v1/operacional/estabelecimentos/{unidade}/cardapio exige sessão PIN, PRODUTO_CONSULTAR e X-Operador-Vinculo como precondição contra abas antigas. A identidade vem do principal. Escritas exigem CSRF atual. Validações e isolamento são no serviço, não apenas na interface.

## Cópia autorizada

Prévia exige autorização em origem e destino, bloqueados em ordem de UUID para evitar inversão. Prazo de dez minutos, vínculo ao administrador solicitante e hashes dos dois catálogos. A prévia informa quantidades e conflitos de nomes/tamanho; não há sobrescrita silenciosa. É possível gerar nova prévia com prefixo explícito para resolver nomes existentes. Alteração do catálogo após a prévia exige regenerá-la.

Confirmação revalida as duas autorizações e o solicitante, cria IDs próprios e remapeia categorias/componentes. Preços são copiados como valores iniciais independentes, sem sincronização posterior. Quantidades no destino sempre começam em zero; não existe transferência física ou movimento de estoque fictício. Repetir confirmação devolve o mesmo resultado; confirmações concorrentes produzem uma cópia e um evento em cada unidade. Falha de auditoria reverte a transação inteira.

## Contrato interno de estoque para 2.6

EstoqueCardapioService é um serviço interno sem endpoint de consumo na 2.5. Exige transação existente, sessão PIN e PEDIDO_ENVIAR efetiva (ainda indisponível nesta entrega). A 2.6 deverá chamá-lo somente ao confirmar um pedido, após decomposição de combos e validação do snapshot. Ele bloqueia produtos em ordem determinística, revalida disponibilidade/quantidade, mantém saldo não negativo e grava consumo/auditoria por operação. O pedido deve ser idempotente antes de invocar consumo. Nenhuma baixa ocorre por mera consulta/seleção de produto.

A estratégia de eventual devolução depende do estágio real de preparo da 2.6; não existe devolução automática de ingredientes preparados nesta entrega. Não há estoque avançado, compras, fornecedores, ERP, fiscal ou vendas fictícias.

## Interface e arquivos

- Backend: src/main/java/com/kitchentable/cardapio, com DTOs, repositório, serviços, controllers e estoque interno.
- Autorização/auditoria: ampliações pequenas nos componentes existentes de funcionarios; mesas/comandas preservadas.
- Frontend: assets/cardapio.js; integração em app.js, atendimento.js, index.html e whitelist de assets.
- Operacional: botão Cardápio usa a sessão PIN e mantém a seção após F5; dados saem da tela ao bloquear/trocar operador. Administração preserva unidade/seção pela navegação existente e ignora respostas obsoletas.
- Testes: CardapioPostgresTest, test-cardapio.mjs e smoke-cardapio.mjs, integrados ao smoke oficial.

## Validação reproduzível

Com Docker, Java 17, Maven, Node 24 e Chrome, na raiz:

~~~powershell
mvn --batch-mode --no-transfer-progress '-Ddebug=false' '-Dlogging.level.org.springframework=INFO' verify
node --test scripts/test-interface-contexto.mjs scripts/test-atendimento.mjs scripts/test-cardapio.mjs
node scripts/test-interface-browser.mjs
node scripts/smoke-funcionarios.mjs
~~~

PostgreSQL 16 descartável, sem dados operacionais. O teste novo aplica V1–V12, preserva credencial sintética/checksums e valida atualização para as novas versões. Os 183 cenários anteriores são mantidos, ajustando somente contagens esperadas de migrações/catálogo.

O smoke usa cadastro/edição, produtos COZINHA/BAR, preço exato, ajuste de quantidade, combo, cópia entre unidades sem quantidade física e consulta PIN, com F5 e desktop/tablet/360px. Evidências privadas ficam ignoradas em .tools; não publicar credenciais, perfis ou capturas.

## Limites e riscos

Listagens ainda sem paginação; avaliar carga e reduzir consultas de componentes antes de grande escala. Gestão serializada por unidade para manter revalidação administrativa consistente. Não existe pedido ou baixa física exposta na 2.5. HTTPS, cookie Secure, limitação de tentativas, sessões distribuídas, backups/restauração e retenção seguem requisitos antes do piloto. Aprovação local/CI não autoriza produção.

## Resultados locais confirmados

Maven verify: 220 testes aprovados, zero falhas/erros/ignorados (183 preservados e 37 novos). Node: 29 testes aprovados. Chrome com API controlada: 26 verificações aprovadas. Smoke oficial com backend/PostgreSQL 16/Chrome reais: aprovado com fluxo administrativo e consulta por PIN, histórico de contagem/motivo, cópia sem transferência física e F5 em duas unidades, desktop/tablet/360px. Nenhum dado operacional foi utilizado.

A validação encontrou e tratou coerção de centavos fracionários (agora rejeitada) e preparação da fixture após a revogação de PRODUTO_CONSULTAR no cenário anterior (concessão explícita pelo proprietário, sem desativar autorização). V1–V12 e os materiais históricos foram preservados. CI remoto deve ser conferido no HEAD publicado, sem inferir sucesso apenas destes resultados locais.
