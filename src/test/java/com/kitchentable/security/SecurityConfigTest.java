package com.kitchentable.security;
import com.kitchentable.config.HealthController;
import com.kitchentable.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {
 @Autowired MockMvc mvc;
 @MockBean JdbcTemplate jdbc;
 @Test void healthPublico() throws Exception { mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP")); }
 @Test void demaisRotasExigemAutenticacao() throws Exception { mvc.perform(get("/api/v1/estabelecimentos")).andExpect(status().isUnauthorized()); }
}
