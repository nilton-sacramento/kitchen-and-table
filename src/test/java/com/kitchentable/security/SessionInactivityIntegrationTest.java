package com.kitchentable.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** HTTP e Tomcat reais; nenhum acesso ao PostgreSQL ou mudança em sua configuração. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1",
    "server.servlet.session.cookie.secure=false",
    "spring.flyway.enabled=false",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@Import(SessionInactivityIntegrationTest.ShortSessions.class)
class SessionInactivityIntegrationTest {
    private static final String EMAIL = "teste-inatividade@example.com";
    @LocalServerPort int port;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @MockBean JdbcTemplate jdbc;
    @MockBean UserDetailsService users;
    private HttpClient client;

    @TestConfiguration(proxyBeanMethods = false)
    static class ShortSessions {
        @Bean
        ServletListenerRegistrationBean<HttpSessionListener> shortSessionListener() {
            return new ServletListenerRegistrationBean<>(new HttpSessionListener() {
                @Override
                public void sessionCreated(HttpSessionEvent event) {
                    // Apenas este contexto de teste: timeout real por sessão em segundos.
                    // Tomcat arredonda a configuração global para minutos inteiros.
                    event.getSession().setMaxInactiveInterval(2);
                }
            });
        }
    }

    @BeforeEach
    void setup() {
        when(users.loadUserByUsername(EMAIL)).thenReturn(User.withUsername(EMAIL)
            .password(encoder.encode("senha-exclusiva-do-teste")).authorities("ROLE_USER").build());
        doReturn(List.of(Map.of("id", UUID.randomUUID(), "nome", "Conta de teste", "email", EMAIL)))
            .when(jdbc).query(anyString(), any(RowMapper.class), eq(EMAIL));
        client = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(5)).build();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void sessaoExpiraPorInatividadeRealSemAlterarConfiguracaoNormal() throws Exception {
        var csrf = get("/api/v1/auth/csrf");
        assertEquals(200, csrf.statusCode());
        String cookie = csrf.headers().firstValue("set-cookie").orElseThrow();
        assertTrue(cookie.toLowerCase(java.util.Locale.ROOT).contains("httponly"));
        assertTrue(cookie.toLowerCase(java.util.Locale.ROOT).contains("samesite=lax"));
        var token = json.readTree(csrf.body());
        var login = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header(token.get("headerName").asText(), token.get("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString("email=teste-inatividade%40example.com&password=senha-exclusiva-do-teste"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, login.statusCode());
        var beforeIdle = get("/api/v1/auth/me");
        assertEquals(200, beforeIdle.statusCode());
        assertEquals(EMAIL, json.readTree(beforeIdle.body()).get("email").asText());

        // Não chamar invalidate(): a sessão deve vencer pelo relógio real do Tomcat.
        Thread.sleep(3200);

        var afterIdle = get("/api/v1/auth/me");
        assertEquals(401, afterIdle.statusCode());
        assertTrue(afterIdle.headers().firstValue("www-authenticate").isEmpty());
        assertFalse(afterIdle.body().contains(EMAIL));
        assertEquals(401, get("/api/v1/estabelecimentos").statusCode());
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class), eq(EMAIL));
    }
}
