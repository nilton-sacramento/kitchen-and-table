-- Credencial operacional por vinculo: autoridade de uma unidade nao redefine outra.
CREATE TABLE credenciais_operacionais (
 vinculo_id UUID PRIMARY KEY,
 estabelecimento_id UUID NOT NULL,
 pin_hash TEXT NOT NULL,
 falhas INTEGER NOT NULL DEFAULT 0 CHECK(falhas BETWEEN 0 AND 3),
 bloqueado BOOLEAN NOT NULL DEFAULT false,
 motivo VARCHAR(20) CHECK(motivo IN ('TENTATIVAS','ADMINISTRATIVO')),
 versao BIGINT NOT NULL DEFAULT 0 CHECK(versao>=0),
 atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(vinculo_id,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id)
);
CREATE TABLE politicas_operacionais (
 estabelecimento_id UUID PRIMARY KEY REFERENCES estabelecimentos(id),
 inatividade_segundos INTEGER NOT NULL DEFAULT 300 CHECK(inatividade_segundos BETWEEN 1 AND 3600),
 versao BIGINT NOT NULL DEFAULT 0 CHECK(versao>=0)
);
CREATE TABLE sessoes_operacionais (
 id UUID PRIMARY KEY,
 vinculo_id UUID NOT NULL,
 estabelecimento_id UUID NOT NULL,
 versao_credencial BIGINT NOT NULL,
 ultimo_uso TIMESTAMPTZ NOT NULL DEFAULT now(),
 expira_em TIMESTAMPTZ NOT NULL,
 bloqueada BOOLEAN NOT NULL DEFAULT false,
 encerrada BOOLEAN NOT NULL DEFAULT false,
 criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(vinculo_id,estabelecimento_id) REFERENCES usuarios_estabelecimentos(id,estabelecimento_id)
);
CREATE INDEX ix_sessoes_operacionais_vinculo ON sessoes_operacionais(vinculo_id) WHERE NOT encerrada;
CREATE INDEX ix_sessoes_operacionais_unidade ON sessoes_operacionais(estabelecimento_id,expira_em) WHERE NOT encerrada;