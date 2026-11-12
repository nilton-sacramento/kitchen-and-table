package com.kitchentable.security;

import com.kitchentable.auth.ContaController;
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
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ContaController.class)
@Import(SecurityConfig.class)
class WebAccessTest {
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @MockBean JdbcTemplate jdbc;
    @MockBean UserDetailsService users;
    private static final String EMAIL = "proprietario@example.com";
    private static final String PASSWORD = "senha-apenas-de-teste";

    @BeforeEach
    void setupUser() {
        when(users.loadUserByUsername(EMAIL)).thenReturn(User.withUsername(EMAIL)
            .password(encoder.encode(PASSWORD)).authorities("ROLE_USER").build());
    }

    @Test
    void frontendPublico() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"login-form\"")));
        mvc.perform(get("/assets/app.js")).andExpect(status().isOk());
        mvc.perform(get("/assets/app.css")).andExpect(status().isOk());
    }

    @Test
    void apiContinuaProtegida() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/estabelecimentos")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/futuro").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void senhaInvalidaNaoAcessaConta() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(csrf()).param("email", EMAIL).param("password", "incorreta"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(jdbc);
    }

    @Test
    void usuarioInexistenteNaoAcessaConta() throws Exception {
        when(users.loadUserByUsername("ausente@example.com")).thenThrow(new UsernameNotFoundException("Ausente"));
        mvc.perform(post("/api/v1/auth/login").with(csrf()).param("email", "ausente@example.com").param("password", PASSWORD))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void contaInativaNaoAcessaConta() throws Exception {
        when(users.loadUserByUsername(EMAIL)).thenReturn(User.withUsername(EMAIL)
            .password(encoder.encode(PASSWORD)).authorities("ROLE_USER").disabled(true).build());
        mvc.perform(post("/api/v1/auth/login").with(csrf()).param("email", EMAIL).param("password", PASSWORD))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void contaAutenticadaRetornaDados() throws Exception {
        doReturn(List.of(Map.of("id", UUID.randomUUID(), "nome", "Proprietária", "email", EMAIL)))
            .when(jdbc).query(anyString(), any(RowMapper.class), eq(EMAIL));
        mvc.perform(get("/api/v1/auth/me").with(user(EMAIL)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(EMAIL))
            .andExpect(jsonPath("$.nome").value("Proprietária"));
        verify(jdbc).query(contains("ativo=true"), any(RowMapper.class), eq(EMAIL));
    }

    @Test
    void retornaEstabelecimentosDoUsuario() throws Exception {
        doReturn(List.of(Map.of("id", UUID.randomUUID(), "nome", "Unidade A", "funcao", "PROPRIETARIO"),
            Map.of("id", UUID.randomUUID(), "nome", "Unidade B", "funcao", "GERENTE")))
            .when(jdbc).query(anyString(), any(RowMapper.class), eq(EMAIL));
        mvc.perform(get("/api/v1/estabelecimentos").with(user(EMAIL)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].funcao").value("PROPRIETARIO"));
        verify(jdbc).query(contains("ue.ativo=true AND e.ativo=true"), any(RowMapper.class), eq(EMAIL));
    }

    @Test
    void usuarioSemVinculosRecebeListaVazia() throws Exception {
        doReturn(List.of()).when(jdbc).query(anyString(), any(RowMapper.class), eq(EMAIL));
        mvc.perform(get("/api/v1/estabelecimentos").with(user(EMAIL)))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
    }
}
