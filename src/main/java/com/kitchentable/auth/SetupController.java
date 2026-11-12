package com.kitchentable.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/setup")
public class SetupController {
 private final JdbcTemplate jdbc;
 private final PasswordEncoder encoder;
 private final String setupKey;
 public SetupController(JdbcTemplate jdbc, PasswordEncoder encoder,
    @Value("${KT_SETUP_KEY:}") String setupKey) {
  this.jdbc=jdbc; this.encoder=encoder; this.setupKey=setupKey;
 }
 public record Cadastro(@NotBlank @Size(max=150) String nome,
  @NotBlank @Email String email, @NotBlank @Size(min=12,max=200) String senha,
  @NotBlank @Size(max=180) String estabelecimento) {}
 @PostMapping("/proprietario")
 @Transactional
 public ResponseEntity<Map<String,Object>> cadastrar(@RequestHeader(value="X-Setup-Key",required=false) String key,
   @Valid @RequestBody Cadastro dados) {
  if (setupKey.isBlank() || key==null ||
    !MessageDigest.isEqual(setupKey.getBytes(StandardCharsets.UTF_8),key.getBytes(StandardCharsets.UTF_8)))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Chave de configuração inválida");
  // Impede duas inicializações simultâneas no mesmo banco.
  jdbc.execute("SELECT pg_advisory_xact_lock(81724391)");
  Integer count=jdbc.queryForObject("SELECT count(*) FROM usuarios",Integer.class);
  if (count!=null && count>0)
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Configuração inicial já realizada");
  UUID usuario=UUID.randomUUID(), unidade=UUID.randomUUID();
  jdbc.update("INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,?,?,?)",
   usuario,dados.nome().trim(),dados.email().trim().toLowerCase(java.util.Locale.ROOT),encoder.encode(dados.senha()));
  jdbc.update("INSERT INTO estabelecimentos(id,nome) VALUES(?,?)",unidade,dados.estabelecimento().trim());
  jdbc.update("INSERT INTO usuarios_estabelecimentos(usuario_id,estabelecimento_id,funcao_id) " +
    "SELECT ?,?,id FROM funcoes WHERE codigo='PROPRIETARIO'",usuario,unidade);
  jdbc.update("INSERT INTO auditoria_eventos(estabelecimento_id,usuario_id,tipo_evento,entidade_tipo,entidade_id) " +
    "VALUES(?,?,'SETUP_PROPRIETARIO','ESTABELECIMENTO',?)",unidade,usuario,unidade);
  return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("usuarioId",usuario,"estabelecimentoId",unidade));
 }
}
