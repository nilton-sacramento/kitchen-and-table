package com.kitchentable.funcionarios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitchentable.KitchenTableApplication;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.github.dockerjava.api.model.*;

/** Utilitario de teste de navegador; nunca empacotado no JAR de producao. */
public final class FuncionariosBrowserFixture {
    public static void main(String[] args) throws Exception {
        var input=new BufferedReader(new InputStreamReader(System.in));
        var json=new ObjectMapper();var fixture=json.readTree(input.readLine());
        try(var pg=new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("kt_browser_test").withUsername("kt_test")
            .withPassword(UUID.randomUUID().toString()).withReuse(false)
            .withLabel("com.kitchentable.scope","discardable-browser-test-2-1")
            .withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),new ExposedPort(5432))))) {
            pg.start();
            try(var context=SpringApplication.run(KitchenTableApplication.class,
                "--server.address=127.0.0.1","--server.port=0","--server.servlet.session.cookie.secure=false","--spring.flyway.enabled=true","--debug=false","--logging.level.org.springframework=INFO",
                "--spring.datasource.url="+pg.getJdbcUrl(),"--spring.datasource.username="+pg.getUsername(),"--spring.datasource.password="+pg.getPassword())) {
                var jdbc=context.getBean(JdbcTemplate.class);var encoder=context.getBean(PasswordEncoder.class);
                UUID owner=UUID.randomUUID(),unit=UUID.randomUUID(),second=UUID.randomUUID();
                jdbc.update("INSERT INTO usuarios(id,nome,email,senha_hash) VALUES(?,'Proprietario do teste de navegador',?,?)",owner,fixture.get("email").asText(),encoder.encode(fixture.get("password").asText()));
                jdbc.update("INSERT INTO estabelecimentos(id,nome) VALUES(?,'Unidade de teste A')",unit);
                jdbc.update("INSERT INTO usuarios_estabelecimentos(usuario_id,estabelecimento_id,funcao_id) SELECT ?,?,id FROM funcoes WHERE codigo='PROPRIETARIO'",owner,unit);
                jdbc.update("INSERT INTO estabelecimentos(id,nome) VALUES(?,'Unidade de teste B')",second);
                jdbc.update("INSERT INTO usuarios_estabelecimentos(usuario_id,estabelecimento_id,funcao_id) SELECT ?,?,id FROM funcoes WHERE codigo='PROPRIETARIO'",owner,second);
                int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();
                System.out.println("KT_BROWSER_READY "+json.writeValueAsString(Map.of("url","http://127.0.0.1:"+port,"estabelecimentoId",unit,"destinoId",second)));
                input.readLine(); // Somente o controlador de teste sinaliza encerramento.
            }
        }
    }
}
