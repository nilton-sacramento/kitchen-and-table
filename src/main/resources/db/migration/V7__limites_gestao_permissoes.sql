CREATE TABLE limites_gestao_permissoes (
 gerente_id UUID PRIMARY KEY,
 estabelecimento_id UUID NOT NULL,
 adicionais BOOLEAN NOT NULL DEFAULT false,
 autorizador_id UUID NOT NULL REFERENCES usuarios(id),
 autorizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
 versao_autorizacao BIGINT NOT NULL CHECK(versao_autorizacao>=0),
 FOREIGN KEY(gerente_id,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id),
 UNIQUE(gerente_id,estabelecimento_id)
);
CREATE TABLE limites_gestao_alvos (
 gerente_id UUID NOT NULL,
 estabelecimento_id UUID NOT NULL,
 alvo_id UUID NOT NULL,
 PRIMARY KEY(gerente_id,alvo_id),
 CHECK(gerente_id<>alvo_id),
 FOREIGN KEY(gerente_id,estabelecimento_id) REFERENCES limites_gestao_permissoes(gerente_id,estabelecimento_id) ON DELETE CASCADE,
 FOREIGN KEY(alvo_id,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id)
);
CREATE TABLE limites_gestao_codigos (
 gerente_id UUID NOT NULL REFERENCES limites_gestao_permissoes(gerente_id) ON DELETE CASCADE,
 permissao_id UUID NOT NULL REFERENCES permissoes(id),
 conceder BOOLEAN NOT NULL DEFAULT false,
 revogar BOOLEAN NOT NULL DEFAULT false,
 PRIMARY KEY(gerente_id,permissao_id),
 CHECK(conceder OR revogar)
);
CREATE INDEX ix_limites_alvos_unidade ON limites_gestao_alvos(estabelecimento_id,alvo_id);