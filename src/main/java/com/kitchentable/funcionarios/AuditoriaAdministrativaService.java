package com.kitchentable.funcionarios;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditoriaAdministrativaService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public AuditoriaAdministrativaService(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    void registrar(UUID unidade,UUID executor,UUID vinculoExecutor,String tipo,UUID alvo,Object antes,Object depois) {
        // Somente DTOs administrativos permitidos; nunca uma linha de usuarios/credenciais.
        Map<String,Object> dados=new LinkedHashMap<>();
        dados.put("vinculoExecutor",vinculoExecutor); dados.put("antes",antes); dados.put("depois",depois);
        try {
            jdbc.update("INSERT INTO auditoria_eventos(estabelecimento_id,usuario_id,tipo_evento,entidade_tipo,entidade_id,dados_evento) VALUES(?,?,?,'VINCULO_FUNCIONARIO',?,?::jsonb)",
                unidade,executor,tipo,alvo,json.writeValueAsString(dados));
        } catch(JsonProcessingException e) { throw new IllegalStateException("Falha ao registrar auditoria administrativa",e); }
    }
}
