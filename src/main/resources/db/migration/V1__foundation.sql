CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE usuarios (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 nome VARCHAR(150) NOT NULL,
 email VARCHAR(255) NOT NULL,
 senha_hash TEXT NOT NULL,
 pin_hash TEXT,
 ativo BOOLEAN NOT NULL DEFAULT TRUE,
 criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
 atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_usuarios_email_lower ON usuarios (lower(email));

CREATE TABLE estabelecimentos (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 nome VARCHAR(180) NOT NULL,
 fuso_horario VARCHAR(80) NOT NULL DEFAULT 'America/Bahia',
 ativo BOOLEAN NOT NULL DEFAULT TRUE,
 criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
 atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE funcoes (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 codigo VARCHAR(60) NOT NULL UNIQUE,
 nome VARCHAR(100) NOT NULL
);

CREATE TABLE permissoes (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 codigo VARCHAR(100) NOT NULL UNIQUE,
 descricao TEXT NOT NULL
);

CREATE TABLE funcoes_permissoes (
 funcao_id UUID NOT NULL REFERENCES funcoes(id),
 permissao_id UUID NOT NULL REFERENCES permissoes(id),
 PRIMARY KEY(funcao_id, permissao_id)
);

CREATE TABLE usuarios_estabelecimentos (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 usuario_id UUID NOT NULL REFERENCES usuarios(id),
 estabelecimento_id UUID NOT NULL REFERENCES estabelecimentos(id),
 funcao_id UUID NOT NULL REFERENCES funcoes(id),
 ativo BOOLEAN NOT NULL DEFAULT TRUE,
 criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
 atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(usuario_id, estabelecimento_id),
 UNIQUE(id, estabelecimento_id)
);
CREATE INDEX ix_vinculos_unidade ON usuarios_estabelecimentos(estabelecimento_id, ativo);

CREATE TABLE usuarios_permissoes (
 vinculo_id UUID NOT NULL REFERENCES usuarios_estabelecimentos(id),
 permissao_id UUID NOT NULL REFERENCES permissoes(id),
 permitido BOOLEAN NOT NULL,
 PRIMARY KEY(vinculo_id, permissao_id)
);

CREATE TABLE sessoes (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 usuario_id UUID NOT NULL REFERENCES usuarios(id),
 token_hash TEXT NOT NULL UNIQUE,
 expira_em TIMESTAMPTZ NOT NULL,
 revogada_em TIMESTAMPTZ,
 criado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_sessoes_usuario ON sessoes(usuario_id);

CREATE TABLE auditoria_eventos (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 estabelecimento_id UUID REFERENCES estabelecimentos(id),
 usuario_id UUID REFERENCES usuarios(id),
 tipo_evento VARCHAR(100) NOT NULL,
 entidade_tipo VARCHAR(100),
 entidade_id UUID,
 dados_evento JSONB NOT NULL DEFAULT '{}'::jsonb,
 ocorrido_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_auditoria_unidade_data ON auditoria_eventos(estabelecimento_id, ocorrido_em DESC);

CREATE TABLE dispositivos (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 usuario_id UUID NOT NULL REFERENCES usuarios(id),
 estabelecimento_id UUID NOT NULL REFERENCES estabelecimentos(id),
 identificador_publico VARCHAR(120) NOT NULL UNIQUE,
 revogado_em TIMESTAMPTZ,
 criado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sincronizacao_operacoes (
 id UUID PRIMARY KEY,
 dispositivo_id UUID NOT NULL REFERENCES dispositivos(id),
 estabelecimento_id UUID NOT NULL REFERENCES estabelecimentos(id),
 tipo VARCHAR(100) NOT NULL,
 estado VARCHAR(30) NOT NULL CHECK (estado IN ('RECEBIDA','PROCESSADA','REJEITADA','CONFLITO')),
 resposta JSONB,
 recebido_em TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(dispositivo_id, id)
);

INSERT INTO funcoes(codigo,nome) VALUES
 ('PROPRIETARIO','Proprietário'),('GERENTE','Gerente'),('ATENDENTE','Atendente'),
 ('CAIXA','Operador de caixa'),('COZINHA','Cozinha'),('BAR','Bar');

INSERT INTO permissoes(codigo,descricao) VALUES
 ('ESTABELECIMENTO_VISUALIZAR','Visualizar unidade autorizada'),
 ('FUNCIONARIO_GERENCIAR','Gerenciar vínculos de funcionários'),
 ('PERMISSAO_GERENCIAR','Gerenciar permissões por unidade');
