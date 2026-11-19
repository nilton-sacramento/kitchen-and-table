package com.kitchentable.funcionarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.kitchentable.mesas.*;
import java.util.*;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Dados sintéticos opcionais, somente na fixture descartável de navegador. */
public final class AtendimentoFixtureData {
  private AtendimentoFixtureData() {}

  static Map<String, Object> preparar(
      ApplicationContext context, JsonNode fixture, UUID unidade, UUID outra) {
    var funcionarios = context.getBean(FuncionarioService.class);
    var op = context.getBean(OperacionalService.class);
    var permissoes = context.getBean(PermissoesService.class);
    var mesas = context.getBean(MesaService.class);
    var jdbc = context.getBean(JdbcTemplate.class);
    String email = fixture.get("email").asText();
    var auth =
        new UsernamePasswordAuthenticationToken(
            email, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    mesas.criar(unidade, auth, new MesaDtos.Criacao("01"));
    mesas.criar(outra, auth, new MesaDtos.Criacao("01"));
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < 2; i++) {
      var f =
          funcionarios.cadastrar(
              unidade,
              email,
              UUID.randomUUID(),
              new FuncionarioDtos.Cadastro(
                  i == 0 ? "Atendente ficticio" : "Caixa ficticio",
                  i == 0 ? "ATENDENTE" : "CAIXA",
                  null,
                  null));
      op.definir(
          unidade,
          f.vinculoId(),
          email,
          new OperacionalDtos.Definicao(
              fixture.get(i == 0 ? "pin" : "secondPin").asText(), f.versao()));
      for (String code : List.of("COMANDA_ENCERRAR", "COMANDA_CANCELAR"))
        permissoes.excecao(
            unidade,
            f.vinculoId(),
            code,
            email,
            new PermissoesDtos.Excecao(
                PermissoesDtos.Estado.PERMITIR,
                funcionarios.consultar(unidade, f.vinculoId(), email).versao()));
      result.put(i == 0 ? "atendente" : "caixa", f.vinculoId());
      if (i == 0) {
        UUID link = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO"
                + " usuarios_estabelecimentos(id,usuario_id,estabelecimento_id,funcao_id,nome_exibicao)"
                + " SELECT ?,?,?,id,'Atendente ficticio B' FROM funcoes WHERE codigo='ATENDENTE'",
            link,
            f.usuarioId(),
            outra);
        op.definir(
            outra, link, email, new OperacionalDtos.Definicao(fixture.get("pin").asText(), 0L));
        result.put("atendenteOutraUnidade", link);
      }
    }
    return result;
  }
}
