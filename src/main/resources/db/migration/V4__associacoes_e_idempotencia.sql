-- Referencia de solicitacao nao e credencial. Toda consulta/aceitacao exige autorizacao.
ALTER TABLE usuarios_estabelecimentos ADD CONSTRAINT ux_vinculo_identidade_unidade UNIQUE(id,usuario_id,estabelecimento_id);
CREATE TABLE associacoes_funcionarios (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 usuario_id UUID NOT NULL REFERENCES usuarios(id),
 origem_id UUID NOT NULL REFERENCES estabelecimentos(id),
 destino_id UUID NOT NULL REFERENCES estabelecimentos(id),
 vinculo_origem_id UUID NOT NULL,
 solicitante_id UUID NOT NULL REFERENCES usuarios(id),
 criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
 expira_em TIMESTAMPTZ NOT NULL,
 consumida_em TIMESTAMPTZ,
 CHECK (origem_id <> destino_id),
 CHECK (expira_em > criado_em),
 FOREIGN KEY(vinculo_origem_id,usuario_id,origem_id) REFERENCES usuarios_estabelecimentos(id,usuario_id,estabelecimento_id)
);
CREATE INDEX ix_associacoes_destino ON associacoes_funcionarios(destino_id,expira_em);
CREATE TABLE operacoes_administrativas (
 estabelecimento_id UUID NOT NULL REFERENCES estabelecimentos(id),
 requisicao_id UUID NOT NULL,
 executor_id UUID NOT NULL REFERENCES usuarios(id),
 tipo VARCHAR(40) NOT NULL,
 resumo_requisicao CHAR(64) NOT NULL,
 vinculo_id UUID NOT NULL,
 criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(estabelecimento_id,requisicao_id),
 FOREIGN KEY(vinculo_id,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id)
);
