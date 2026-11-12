# Recuperação e backup

## Código

Após publicação autorizada, commits e branches remotas permitem recuperar o código sem sobrescrever a cópia atual. Clone o repositório em uma pasta nova e confira o commit desejado antes de executar. Não use git reset --hard, limpeza recursiva ou force push para recuperar trabalho sem avaliação e autorização.

O projeto original possui snapshot local de recuperação com ZIP e manifesto SHA-256, criado na conclusão da Etapa 1. Ele não é incluído no GitHub. Mantenha uma cópia externa segura do ZIP e do manifesto; arquivo no mesmo disco não protege contra perda desse disco.

## Banco e segredos

Git e ZIP dos fontes não são backup do PostgreSQL. O banco atual permanece no volume existente. Não execute docker compose down -v, recriação de banco ou setup para recuperar o código.

Antes de migração relevante: combinar janela de operação, identificar banco/volume corretos, gerar backup lógico protegido com pg_dump, verificar sua integridade e ensaiar restauração em banco isolado. Dumps podem conter dados pessoais e hashes: manter fora do Git, com acesso restrito e retenção definida. Nenhum dump ou restauração é executado por este documento.

Credenciais de ambiente devem ser recuperadas de armazenamento privado apropriado, não do repositório público. Não salvar senhas em histórico de comandos. A restauração do código não muda a senha operacional do PostgreSQL.

Tags de versão somente depois da aprovação da integração correspondente. Até lá, use os hashes dos commits e as branches, sem criar uma tag antecipada.
