# Configuração inicial — somente banco novo

Não executar este procedimento para o proprietário já cadastrado. O endpoint recusa inicialização quando existe usuário no banco.

Configure DB_PASSWORD no ambiente conforme o README. Para um banco genuinamente novo, configure uma chave temporária de setup via entrada oculta no terminal do servidor:

```powershell
$chaveSetupLocal = Read-Host 'Chave temporária de setup' -AsSecureString
$env:KT_SETUP_KEY = [System.Net.NetworkCredential]::new('', $chaveSetupLocal).Password
mvn spring-boot:run
```

Em outro terminal PowerShell, obtenha a mesma chave e a senha sem escrever valores no histórico e envie a inicialização:

```powershell
$chaveSetupLocal = Read-Host 'Mesma chave de setup' -AsSecureString
$senhaProprietarioLocal = Read-Host 'Senha do novo proprietário (mínimo 12 caracteres)' -AsSecureString
$cadastroInicial = @{
  nome = (Read-Host 'Nome do proprietário')
  email = (Read-Host 'E-mail do proprietário')
  estabelecimento = (Read-Host 'Nome do estabelecimento')
  senha = [System.Net.NetworkCredential]::new('', $senhaProprietarioLocal).Password
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/setup/proprietario' -Headers @{
  'X-Setup-Key' = [System.Net.NetworkCredential]::new('', $chaveSetupLocal).Password
} -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($cadastroInicial))
Remove-Variable cadastroInicial, senhaProprietarioLocal, chaveSetupLocal
```

Resposta esperada: 201 com os identificadores criados. Não registrar o conteúdo das variáveis, não usar chave no frontend e não publicar o servidor local. Depois de configurar um banco novo, pare o servidor, remova KT_SETUP_KEY do ambiente e reinicie. Este documento não autoriza executar setup no banco original.
