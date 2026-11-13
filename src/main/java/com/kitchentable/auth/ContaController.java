package com.kitchentable.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ContaController {
 private final JdbcTemplate jdbc;
 public ContaController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 @GetMapping("/auth/me")
 public Map<String,Object> me(Authentication auth) {
  var rows=jdbc.query("SELECT id,nome,email FROM usuarios WHERE lower(email)=lower(?) AND ativo=true",
    (rs,i)->Map.<String,Object>of("id",rs.getObject("id",UUID.class),"nome",rs.getString("nome"),"email",rs.getString("email")),auth.getName());
  if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  return rows.get(0);
 }
 @GetMapping("/estabelecimentos")
 public List<Map<String,Object>> unidades(Authentication auth) {
  return jdbc.query("SELECT e.id,e.nome,f.codigo AS funcao FROM estabelecimentos e " +
    "JOIN usuarios_estabelecimentos ue ON ue.estabelecimento_id=e.id " +
    "JOIN usuarios u ON u.id=ue.usuario_id JOIN funcoes f ON f.id=ue.funcao_id " +
    "WHERE lower(u.email)=lower(?) AND u.ativo=true AND ue.ativo=true AND e.ativo=true AND (f.codigo='PROPRIETARIO' OR NOT EXISTS(SELECT 1 FROM usuarios_permissoes up JOIN permissoes p ON p.id=up.permissao_id WHERE up.vinculo_id=ue.id AND p.codigo='ESTABELECIMENTO_VISUALIZAR' AND NOT up.permitido)) ORDER BY e.nome",
    (rs,i)->Map.<String,Object>of("id",rs.getObject("id",UUID.class),"nome",rs.getString("nome"),"funcao",rs.getString("funcao")),auth.getName());
 }
}
