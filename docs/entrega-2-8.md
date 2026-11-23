# Entrega 2.8 — Delivery manual e venda rápida

Base funcional: develop02edbfb91fea15c0514fed81399c3da09be3aaee (Entrega2.7 integrada). Feature feature/entrega-2-8-delivery-venda-rapida. Publicação somente em PRDraft; sem autorização de merge, produção ou acesso a bancos anteriores. A PR10 permanece independente: seu README não foi sobrescrito/incorporado nesta feature.

## Modelo e integração reais

Delivery e venda rápida são canais especializados sobre uma comanda BALCAO e pedidos2.6 reais. Não existe um segundo carrinho persistente, estoque, sistema de preparo, ledger ou caixa. atendimentos_externos é metadado1:1 por comanda, com FKs compostas por estabelecimento, origem, referência opcional, estado/versão e autores. Não exige nem armazena endereço/telefone. Use um nome ou código mínimo quando necessário; não insira dados bancários ou contatos nas observações.

Criação transacional: capacidades atuais, unidade/PIN, serialização financeira, idempotência persistida, abertura da comanda, pedido/snapshots/baixa de estoque/filas e, quando informado, recebimento financeiro2.7. Na venda rápida com total positivo, pagamento integral é obrigatório na mesma transação. Falha de pagamento, preço, estoque ou auditoria reverte toda a criação; não deixa comanda órfã ou baixa sem venda. Total zero não cria pagamento fictício. O preço/composição histórico vem das ofertas reais; combo cobra a linha comercial uma vez e consome os componentes no setor correto.

Venda rápida preserva o balcão com pagamento posterior, disponível no fluxo antigo. O novo carrinho é congelado após confirmação; não se acrescentam pedidos pela API genérica à venda rápida confirmada. Substituição autorizada continua no fluxo de decisões2.6 após regularização financeira2.7 e antes da conclusão logística. Preparo não é acelerado artificialmente: todos os produtos seguem seus setores e permissões. Ser pago não marca um item PRONTO/ENTREGUE.

## Estados e cancelamento

A produção é derivada dos itens existentes, sem outra máquina de estados de cozinha/bar. Logística separada:

- DELIVERY: REGISTRADO → SAIU_PARA_ENTREGA → ENTREGUE; também pode registrar entrega direta ou RETIRADO a partir de REGISTRADO.
- VENDA_RAPIDA: REGISTRADO → RETIRADO.
- CANCELADO somente de REGISTRADO, com justificativa, permissão crítica, pedidos cancelados e pagamentos/descontos regularizados.

Despacho/conclusão exigem produção completa e ausência de solicitações pendentes. Concluir como ENTREGUE/RETIRADO exige PEDIDO_ENTREGAR e usa ProducaoService para entregar os itens prontos, preservando tudo junto/entrega parcial anteriores. O despacho não afirma recebimento do cliente. O financeiro não muda por uma transição logística.

Pagar não conclui logística. Entregar não afirma pagamento. A quitação financeira2.7 exige saldozero, produção entregue, pendências resolvidas e logística final. Uma chamada direta à API antiga não contorna essa exigência: há guard de serviço e trigger no certificado de quitação.

Solicitações de alteração/cancelamento usam o editor e as decisões setoriais existentes. Após despacho/conclusão são bloqueadas, inclusive pelas APIs antigas e triggers; um retorno/correção complexa exige regra própria, não inventada nesta entrega. Não há retorno automático ao estoque de produto preparado. Os critérios2.6 de devolução apenas de itens ainda AGUARDANDO são preservados. Estorno não cancela pedido e cancelamento não simula estorno bancário.

## Dinheiro e recebimentos externos

Usa PagamentoService, ContaRepository, Caixa/Fechamento, autorizações e relatórios2.7. Valores em centavos inteiros/strings; frontend BigInt. Dinheiro recebido/troco são separados do valor aplicado. Pix, débito e crédito exigem conferência externa explícita e não aumentam o dinheiro físico. Não há integração bancária ou confirmação presumida pela origem PLATAFORMA.

Na criação com pagamento em dinheiro é obrigatório confirmar que o valor entrou no caixa físico. Dinheiro recebido fora da loja sem entrada física é recusado. Na conta2.7, registre DINHEIRO somente para recebimento físico efetivo; não converta repasse de plataforma em dinheiro. Recebimentos externos Pix/cartão podem ser registrados pelo valor efetivamente conferido nos meios existentes, sem novo ledger. Valores líquidos com comissões, taxas, retenções ou repasses sem correspondência no contrato não são calculados nem conciliados automaticamente: manter pendência até regra/valor de recebimento verificável. Não criar um pagamento para forçar fechamento.

Delivery pode receber posteriormente em parcelas/meios combinados pela conta existente. Descontos, compensações integrais, autorizações pontuais, confirmação de devolução externa, quitação, conferência e fechamento mantêm os contratos2.7. Relatórios acrescentam vendas liquidadas por canal (COMANDA/DELIVERY/VENDA_RAPIDA), calculadas dos mesmos certificados, sem duplicar receita. Recebimento e venda liquidada continuam separados.

## Segurança, idempotência e concorrência

Todas as APIs são operacionais: sessão por PIN, vínculo/unidade ativos, X-Operador-Vinculo compatível com o principal e capacidade atual. Cabeçalho não autentica. Toda escrita exige CSRF. Sessão administrativa não substitui operador. Nenhuma nova herança ou delegação administrativa é concedida pela migração.

| Código | Operação | Padrão novo |
|---|---|---|
| DELIVERY_CONSULTAR | Lista, itens e histórico do canal | Sem concessão automática |
| DELIVERY_REGISTRAR | Criar delivery | Sem concessão automática; exige abertura/pedido/produto existentes |
| DELIVERY_STATUS_ALTERAR | Despacho/conclusão logística | Crítica; concessão explícita, entrega ainda exige PEDIDO_ENTREGAR |
| VENDA_RAPIDA_CONSULTAR | Consultar vendas rápidas | Sem concessão automática |
| VENDA_RAPIDA_REGISTRAR | Pedido e pagamento imediato | Crítica/financeira; exige capacidades de pedido/caixa/pagamento e equipe |

Retirada da venda rápida usa PEDIDO_ENTREGAR; cancelamento ainda exige COMANDA_CANCELAR e regularização. Gerente continua sujeito aos alvos/códigos delegados, sem autoelevação ou privilégios por função adicional. As capacidades de comanda/pedido da unidade continuam regendo a consulta aos respectivos registros base; não há uma base paralela escondida do atendimento autorizado.

Criação e logística reutilizam operacoes_financeiras para guardar resultado/chave/conteúdo/autor, sem criar lançamento monetário adicional. Serialização por unidade, locks de comanda/pedidos e versões preservam a ordem com recebimentos, estoque e fechamento. Mesma intenção simultânea retorna uma criação; chave de outro autor/corpo conflita. A autorização é revalidada antes de replay. Consulta de resultado só do autor/canal autorizados. Eventos transacionais registram unidade, usuário, vínculo/nome e motivo logístico; sem PIN/hash/senhas. Campos de referência não são duplicados no novo evento de criação.

Frontend mantém intenção congelada só em memória. Antes de criar/reenviar consulta o resultado da chave; falha na consulta não autoriza POST cego. Troca de tela/unidade/operador invalida callbacks. Campos não podem mudar após congelamento da intenção. F5 restaura canal/id pelo hash, mas não restaura intenções ou rascunhos de pedidos; consulte a lista/histórico antes de iniciar outra venda com resultado desconhecido. Bloqueio/logout descartam a tela; sem fila offline.

## APIs

Prefixo /api/v1/operacional/estabelecimentos/{unidade}/externos:

| Método/caminho | Contrato |
|---|---|
| GET ?tipo=DELIVERY ou VENDA_RAPIDA | Lista do canal autorizado |
| GET /{comandaId} | Metadados e pedidos históricos reais |
| GET /{comandaId}/historico | Eventos do canal com executor/data/motivo |
| GET /operacoes/{chave}?tipo=... | Resultado de criação do próprio operador |
| POST / | ExternoDtos.Criacao: tipo, origem, referência opcional, pedido2.6 e pagamento opcional; obrigatório na venda rápida de valor positivo |
| POST /{comandaId}/estado | Estado, versão e motivo; Idempotency-Key |

POST exige chave UUID e CSRF. Pagamento inicial contém turnoId, meio, valorCentavos, recebidoCentavos, confirmadoExternamente e dinheiroRecebidoNoCaixa. Saldo/revisão da nova conta são calculados no servidor, nunca aceitos como verdade pelo cliente. Demais pagamentos, solicitações, filas, estornos e quitação utilizam as APIs anteriores, sem duplicá-las.

## Banco e arquivos

V1–V19 preservadas. V20 cria metadados/índice/FKs/imutabilidade e guards de pedido/quitação; V21 inclui cinco capacidades sem heranças; V22 congela o carrinho rápido sem impedir substituição regularizada; V23 impede alteração/cancelamento após despacho. V20 foi preservada após seu primeiro ensaio; correções adicionais são migrações incrementais. Não executar no PostgreSQL operacional nem na demonstração2.7 antiga. Não há rollback destrutivo: planejar compatibilidade e backup/restauração isolada antes de qualquer uso operacional.

- Backend: src/main/java/com/kitchentable/externos/ (ExternoDtos, ExternoController e ExternoService).
- Integrações: PagamentoService, AlteracaoPedidoService, RelatorioFinanceiroService, Acesso/Auditoria e exception handler existentes.
- Frontend: assets/externos.js e externo-contratos.js, menu atendimento/app; pedido-editor.js reutilizado por callback de revisão, pedidos.js sem adição genérica na venda rápida, relatorios.js com canais.
- Testes: src/test/java/com/kitchentable/caixa/Externo*; scripts/test-externos.mjs e smoke-externos.mjs; CI ampliado.

## Executar e validar

Requisitos Java17/Maven/Docker, Node24/Chrome. Na raiz:

```powershell
mvn --batch-mode --no-transfer-progress '-Ddebug=false' '-Dlogging.level.org.springframework=INFO' verify
node --test scripts/test-interface-contexto.mjs scripts/test-atendimento.mjs scripts/test-cardapio.mjs scripts/test-pedidos.mjs scripts/test-financeiro.mjs scripts/test-externos.mjs
node scripts/smoke-funcionarios.mjs
```

Somente PostgreSQL16 descartável via Testcontainers; não ler DB_URL operacional. O smoke principal importa a extensão2.8, cria perfis/credenciais próprios e valida backend/frontend reais. Não executar smoke-externos sozinho esperando que crie fixture. Capturas ficam em subpasta única por execução de .tools/browser-artifacts, sem sobrescrever evidências anteriores. Logs/relatórios locais em .tools/entrega-2-8, fora do Git.

Cobertura: criação/estoque/combos, preço manipulado, pagamento e troco, rollback por falha de pagamento/auditoria, logística independente, versões, antigas APIs, permissões/PIN/CSRF, concorrência de intenção/última unidade/retirada/despacho versus cancelamento, isolamento, idempotência, instalação limpa/upgrade19, checksums e regressão. Validação local em22/11/2026: Maven verify aprovado com407testes Java (355anteriores+52novos),0falhas/erros/ignorados;55Node aprovados;54verificações Chrome com Spring Boot/PostgreSQL reais (46anteriores+8novas);26Chrome com API controlada, separados da integração real. Seis novos testes de concorrência e upgradeV19→V23 aprovados. Sintaxe/actionlint aprovados. Os resultados do CI devem ser conferidos no HEAD efetivamente publicado na PR Draft; não equivalem à homologação humana ou produção.

## Limitações

Sem integrações externas, app de entregador, GPS, comissões, taxas, endereço/telefone ou automação bancária. Sem operação offline; repetição consciente após resultado incerto. Listas sem paginação e serialização deliberada exigem avaliação de carga antes de piloto. HTTPS/cookieSecure, rate limiting, retenção, backup/restauração, sessões distribuídas e homologação humana continuam pendentes para produção.

A demonstração preparada em .tools/demo-local continua usando JAR02edbfb e banco19, sem receber esta feature ou migrações. Seu teste manual anterior não é alterado nem considerado homologação2.8. Nenhuma Entrega2.9 iniciada.
