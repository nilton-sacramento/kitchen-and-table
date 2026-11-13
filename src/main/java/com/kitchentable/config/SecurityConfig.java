
package com.kitchentable.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(JdbcTemplate jdbc) {
        return email -> {
            var users = jdbc.query(
                "SELECT email, senha_hash, ativo FROM usuarios WHERE lower(email)=lower(?) AND senha_hash IS NOT NULL",
                (rs, row) -> User
                    .withUsername(rs.getString("email"))
                    .password(rs.getString("senha_hash"))
                    .authorities("ROLE_USER")
                    .disabled(!rs.getBoolean("ativo"))
                    .build(),
                email
            );

            if (users.isEmpty()) {
                throw new UsernameNotFoundException("Usuário não encontrado");
            }

            return users.get(0);
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,org.springframework.beans.factory.ObjectProvider<com.kitchentable.funcionarios.OperacionalService> operacional) throws Exception {
        return http
            .httpBasic(basic -> basic.disable())
            .csrf(csrf -> csrf
                // Setup usa chave fora do navegador; preservar o cliente de configuração inicial.
                .ignoringRequestMatchers("/api/v1/setup/proprietario"))
            .sessionManagement(sm ->
                sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(fixation -> fixation.changeSessionId())
            )
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> response.sendError(401))
                .accessDeniedHandler((request, response, exception) -> response.sendError(403)))
            .formLogin(login -> login
                .loginProcessingUrl("/api/v1/auth/login")
                .usernameParameter("email")
                .successHandler((request, response, authentication) -> {
                    var session=request.getSession(false);
                    if(session!=null&&session.getAttribute(com.kitchentable.funcionarios.OperacionalController.MARCADOR) instanceof com.kitchentable.funcionarios.OperadorPrincipal p){
                        var service=operacional.getIfAvailable();if(service!=null)service.encerrar(p);session.removeAttribute(com.kitchentable.funcionarios.OperacionalController.MARCADOR);
                    }
                    response.setStatus(204);
                })
                .failureHandler((request, response, exception) -> response.sendError(401))
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                .addLogoutHandler((request,response,authentication)->{
                    if(authentication!=null&&authentication.getPrincipal() instanceof com.kitchentable.funcionarios.OperadorPrincipal p){
                        try{var service=operacional.getIfAvailable();if(service!=null)service.encerrar(p);}
                        finally{var session=request.getSession(false);if(session!=null)session.invalidate();org.springframework.security.core.context.SecurityContextHolder.clearContext();}
                    }
                })
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/app.css", "/assets/app.js", "/assets/funcionarios.js", "/assets/permissoes.js", "/assets/seguranca-operacional.js", "/assets/operacional.js", "/operacional.html").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/setup/proprietario").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/operacional/entrar").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/operacional/sessao").permitAll()
                .requestMatchers("/api/v1/operacional/**").hasAuthority("ROLE_OPERADOR")
                .anyRequest().hasAuthority("ROLE_USER")
            )
            .build();
    }
}
