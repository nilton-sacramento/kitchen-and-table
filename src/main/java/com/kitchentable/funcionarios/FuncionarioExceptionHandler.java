package com.kitchentable.funcionarios;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes={FuncionarioController.class,PermissoesController.class,OperacionalController.class,SegurancaOperacionalController.class})
public class FuncionarioExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(ProblemDetail.forStatusAndDetail(e.getStatusCode(),e.getReason()==null?"Operação indisponível":e.getReason()));
    }
    // Nao encaminhar mensagens de parse que possam conter o PIN recebido.
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> formato(){return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Formato de requisicao invalido"));}
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> integridade() {
        return ResponseEntity.status(409).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"Operação conflitante; atualize os dados e tente novamente"));
    }
}
