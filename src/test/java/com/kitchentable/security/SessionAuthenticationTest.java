package com.kitchentable.security;

import com.kitchentable.auth.ContaController;
import com.kitchentable.auth.SessionController;
import com.kitchentable.config.SecurityConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({ContaController.class, SessionController.class})
@Import(SecurityConfig.class)
class SessionAuthenticationTest {
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @MockBean JdbcTemplate jdbc;
    @MockBean UserDetailsService users;
    private static final String EMAIL = "proprietario@example.com";
    private static final String PASSWORD = "senha-apenas-de-teste";

    @BeforeEach
    void setup() {
        when(users.loadUserByUsername(EMAIL)).thenReturn(User.withUsername(EMAIL)
            .password(encoder.encode(PASSWORD)).authorities("ROLE_USER").build());
        doReturn(List.of(Map.of("id", UUID.randomUUID(), "nome", "Proprietário", "email", EMAIL)))
            .when(jdbc).query(anyString(), any(RowMapper.class), eq(EMAIL));
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrf())
            .param("email", EMAIL).param("password", PASSWORD))
            .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    @Test
    void cookieDeSessaoMantemAutenticacaoEmNovasRequisicoes() throws Exception {
        MockHttpSession session = login();
        assertNotNull(session);
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(EMAIL));
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void logoutInvalidaSessaoEProximoAcessoExigeLogin() throws Exception {
        MockHttpSession session = login();
        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf()))
            .andExpect(status().isNoContent()).andExpect(cookie().maxAge("JSESSIONID", 0));
        assertTrue(session.isInvalid());
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginELogoutExigemCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/login").param("email", EMAIL).param("password", PASSWORD))
            .andExpect(status().isForbidden());
        MockHttpSession session = login();
        mvc.perform(post("/api/v1/auth/logout").session(session)).andExpect(status().isForbidden());
        assertFalse(session.isInvalid());
    }

    @Test
    void tokenCsrfRealPermiteLoginERotacaoDeId() throws Exception {
        var tokenResult = mvc.perform(get("/api/v1/auth/csrf"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
            .andReturn();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(tokenResult.getResponse().getContentAsString());
        MockHttpSession session = (MockHttpSession) tokenResult.getRequest().getSession(false);
        String oldId = session.getId();
        mvc.perform(post("/api/v1/auth/login").session(session)
            .header(json.get("headerName").asText(), json.get("token").asText())
            .param("email", EMAIL).param("password", PASSWORD)).andExpect(status().isNoContent());
        assertNotEquals(oldId, session.getId());
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void basicNaoSubstituiSessao() throws Exception {
        mvc.perform(get("/api/v1/auth/me").with(httpBasic(EMAIL, PASSWORD)))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void respostasNaoDisparamJanelaDeAutenticacaoDoNavegador() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Accept", "text/html"))
            .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/api/v1/auth/csrf"))
            .andExpect(status().isOk()).andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(post("/api/v1/auth/login").with(csrf())
            .param("email", EMAIL).param("password", "senha-incorreta"))
            .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(post("/api/v1/auth/login").param("email", EMAIL).param("password", PASSWORD))
            .andExpect(status().isForbidden()).andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void sessaoInvalidadaPorExpiracaoNaoPodeAcessarConta() throws Exception {
        MockHttpSession session = login();
        session.invalidate(); // Simula invalidação pelo contêiner, sem esperar timeout real.
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }
}
