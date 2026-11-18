package com.kitchentable.funcionarios;

import com.kitchentable.config.SecurityConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PermissoesController.class)
@Import(SecurityConfig.class)
class PermissoesPadroesWebTest {
    @Autowired MockMvc mvc;
    @MockBean PermissoesService service;
    @MockBean JdbcTemplate jdbc;
    @MockBean UserDetailsService users;
    final UUID unit=UUID.randomUUID();
    String url(){return "/api/v1/estabelecimentos/"+unit+"/permissoes/padroes";}
    @Test void anonimoNaoConsultaPadroes() throws Exception {
        mvc.perform(get(url())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void operadorNaoAcessaAdministracao() throws Exception {
        mvc.perform(get(url()).with(user("operador").authorities(()->"ROLE_OPERADOR"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void consultaEncaminhaUnidadeEIdentidadeSemOcultarCriticidade() throws Exception {
        var permission=new PermissoesDtos.Catalogo("FUTURA","Capacidade futura","Prévia","TESTE","OPERACIONAL",true,false,false);
        when(service.padroes(unit,"owner@example.invalid")).thenReturn(List.of(new PermissoesDtos.Padrao("ATENDENTE",List.of(permission))));
        mvc.perform(get(url()).with(user("owner@example.invalid").roles("USER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].funcao").value("ATENDENTE"))
            .andExpect(jsonPath("$[0].permissoes[0].critica").value(true))
            .andExpect(jsonPath("$[0].permissoes[0].disponivel").value(false));
        verify(service).padroes(unit,"owner@example.invalid");verifyNoMoreInteractions(service);
    }
    @Test void revogacaoNoServicoNaoExibePrevia() throws Exception {
        when(service.padroes(unit,"owner@example.invalid")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,"Acesso revogado"));
        mvc.perform(get(url()).with(user("owner@example.invalid").roles("USER"))).andExpect(status().isForbidden());
    }
}
