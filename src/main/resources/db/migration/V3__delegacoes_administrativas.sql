-- Ausencia de delegacao significa nenhum poder administrativo para gerente.
CREATE TABLE delegacoes_administrativas (
 vinculo_id UUID PRIMARY KEY REFERENCES usuarios_estabelecimentos(id),
 consultar BOOLEAN NOT NULL DEFAULT FALSE,
 cadastrar BOOLEAN NOT NULL DEFAULT FALSE,
 editar BOOLEAN NOT NULL DEFAULT FALSE,
 ativar BOOLEAN NOT NULL DEFAULT FALSE,
 desativar BOOLEAN NOT NULL DEFAULT FALSE,
 associar BOOLEAN NOT NULL DEFAULT FALSE,
 funcoes TEXT[] NOT NULL DEFAULT '{}',
 CHECK (funcoes <@ ARRAY['ATENDENTE','CAIXA','COZINHA','BAR']::TEXT[])
);
