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
        persistir(unidade,executor,vinculoExecutor,null,tipo,"VINCULO_FUNCIONARIO",alvo,antes,depois);
    }
    public void registrarOperacao(UUID unidade,UUID executor,UUID vinculoExecutor,String nome,String tipo,String entidade,UUID alvo,Object antes,Object depois) {
        if(!Set.of("MESA","COMANDA","CATEGORIA","PRODUTO","COMBO","ESTOQUE","COPIA_CARDAPIO","PEDIDO","SOLICITACAO_PEDIDO","CAIXA","AUTORIZACAO_FINANCEIRA","LANCAMENTO_FINANCEIRO","CONTA_FINANCEIRA","FECHAMENTO_CAIXA").contains(entidade))throw new IllegalArgumentException("Entidade nao autorizada");
        persistir(unidade,executor,vinculoExecutor,nome,tipo,entidade,alvo,antes,depois);
    }
    private void persistir(UUID unidade,UUID executor,UUID vinculoExecutor,String nome,String tipo,String entidade,UUID alvo,Object antes,Object depois) {
        // Somente DTOs permitidos; nunca linhas de usuarios/credenciais.
        Map<String,Object> dados=new LinkedHashMap<>();
        if(nome!=null)dados.put("executorNome",nome);
        dados.put("vinculoExecutor",vinculoExecutor); dados.put("antes",antes); dados.put("depois",depois);
        try {
            jdbc.update("INSERT INTO auditoria_eventos(estabelecimento_id,usuario_id,tipo_evento,entidade_tipo,entidade_id,dados_evento) VALUES(?,?,?,?,?,?::jsonb)",
                unidade,executor,tipo,entidade,alvo,json.writeValueAsString(dados));
        } catch(JsonProcessingException e) { throw new IllegalStateException("Falha ao registrar auditoria administrativa",e); }
    }
}
