package com.kitchentable.funcionarios;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.security.core.*;
import org.springframework.security.core.context.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.kitchentable.funcionarios.OperacionalDtos.*;

@RestController
@RequestMapping("/api/v1/operacional")
public class OperacionalController {
    public static final String MARCADOR="KT_OPERADOR";
    private final OperacionalService service;
    public OperacionalController(OperacionalService service){this.service=service;}
    private OperadorPrincipal operador(Authentication a){if(a==null||!(a.getPrincipal() instanceof OperadorPrincipal p))throw FuncionarioRepository.erro(HttpStatus.UNAUTHORIZED,"Acesso operacional bloqueado");return p;}
    @PostMapping("/entrar")
    public ResponseEntity<Void> entrar(@RequestBody Entrada dto,Authentication a,HttpServletRequest request,HttpServletResponse response){
        if(a!=null&&a.getAuthorities().stream().anyMatch(r->r.getAuthority().equals("ROLE_USER")))throw FuncionarioRepository.erro(HttpStatus.CONFLICT,"Encerre a sessao administrativa antes de entrar como operador");
        var anterior=a!=null&&a.getPrincipal() instanceof OperadorPrincipal p?p:null;
        var principal=service.entrar(dto,anterior);
        if(principal==null)return ResponseEntity.status(401).build();
        request.getSession(true);request.changeSessionId();
        new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository().saveToken(null,request,response);
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of(new SimpleGrantedAuthority("ROLE_OPERADOR"))));
        SecurityContextHolder.setContext(context);new HttpSessionSecurityContextRepository().saveContext(context,request,response);
        request.getSession(false).setAttribute(MARCADOR,principal);return ResponseEntity.noContent().build();
    }
    @GetMapping("/sessao") public Sessao sessao(Authentication a){
        if(a!=null&&a.getPrincipal() instanceof OperadorPrincipal p){var c=service.contexto(p,false);return new Sessao(c!=null,false,c);}
        return new Sessao(false,a!=null&&a.getAuthorities().stream().anyMatch(r->r.getAuthority().equals("ROLE_USER")),null);
    }
    @GetMapping("/contexto") public Contexto contexto(Authentication a){return protegido(a,false);}
    @PostMapping("/atividade") public Contexto atividade(Authentication a){return protegido(a,true);}
    private Contexto protegido(Authentication a,boolean atividade){var c=service.contexto(operador(a),atividade);if(c==null)throw FuncionarioRepository.erro(HttpStatus.LOCKED,"Acesso operacional bloqueado. Identifique-se e informe seu PIN novamente.");return c;}
    @PostMapping("/bloquear") public ResponseEntity<Void> bloquear(Authentication a){service.bloquearSessao(operador(a));return ResponseEntity.noContent().build();}
}