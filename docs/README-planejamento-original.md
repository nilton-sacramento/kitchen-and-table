# ?? KITCHEN & TABLE | Real-Time Restaurant Management & KDS System

> **Connecting the Kitchen to the Table with Speed, Resilience, and Precision.**

O **KITCHEN & TABLE** é uma plataforma SaaS PWA de alta performance desenvolvida para eliminar gargalos operacionais em bares e restaurantes, integrando garçons, cozinha e gestão financeira em tempo real.

---

## ?? Principais Recursos
- **Offline-First (PWA):** Resiliência total contra falhas de Wi-Fi via IndexedDB com sincronização automática.
- **Setorização Inteligente (Bar x Cozinha):** Encaminhamento automático de pedidos para o destino correto.
- **Alertas Reativos em Tempo Real:** Comunicação bidirecional via WebSockets (pedidos prontos e alertas de tempo limite).
- **Divisão de Comanda & Pré-Conta:** Suporte a rateio flexível, consumo individual e inclusão opcional de taxa de serviço (10%).
- **Gestão de Caixa & Financial Analytics:** Abertura/fechamento diário de caixa e painéis de faturamento (Dia, Mês e Ano).

---

## ??? Arquitetura e Tecnologias

### Backend
- **Linguagem / Framework:** Java 17+ / Spring Boot 3+
- **Segurança & Dados:** Spring Security (JWT / PIN) & Spring Data JPA
- **Tempo Real:** Spring WebSocket (STOMP / SockJS)
- **Banco de Dados:** PostgreSQL 15+

### Frontend
- **Framework:** Angular 17+ (PWA & Standalone Components)
- **Estilização:** Tailwind CSS (Mobile-First)
- **Estado Reativo:** RxJS

---

## ?? Estrutura do Repositório
- /backend: API RESTful e WebSockets em Spring Boot.
- /frontend: Aplicação reativa PWA em Angular.
- /database: Schemas e migrações SQL do PostgreSQL.
- /docs: Documentação técnica e especificação do Prompt Master.
