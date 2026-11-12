-- Expandir, sem substituir identidades, hashes ou vinculos existentes.
ALTER TABLE usuarios ALTER COLUMN email DROP NOT NULL;
ALTER TABLE usuarios ALTER COLUMN senha_hash DROP NOT NULL;
ALTER TABLE usuarios_estabelecimentos
 ADD COLUMN nome_exibicao VARCHAR(150),
 ADD COLUMN email_contato VARCHAR(255),
 ADD COLUMN telefone VARCHAR(32),
 ADD COLUMN versao BIGINT NOT NULL DEFAULT 0 CHECK (versao >= 0);
