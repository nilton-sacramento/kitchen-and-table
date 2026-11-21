package com.kitchentable.caixa;

import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(
    assignableTypes = {
      CaixaAdminController.class,
      CaixaOperacionalController.class,
      com.kitchentable.pagamentos.PagamentoController.class,
      com.kitchentable.relatorios.RelatorioFinanceiroController.class
    })
public class FinanceiroExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> status(ResponseStatusException e) {
    var problem =
        ProblemDetail.forStatusAndDetail(
            e.getStatusCode(),
            e.getReason() == null ? "Operacao financeira indisponivel" : e.getReason());
    if (e instanceof AutorizacaoFinanceiraNecessariaException)
      problem.setProperty("requerAutorizacao", true);
    return ResponseEntity.status(e.getStatusCode()).body(problem);
  }

  @ExceptionHandler({
    DataIntegrityViolationException.class,
    ConcurrencyFailureException.class,
    ArithmeticException.class
  })
  public ResponseEntity<ProblemDetail> conflito() {
    return ResponseEntity.status(409)
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Operacao financeira conflitante. Consulte o historico e atualize os"
                    + " valores antes de tentar novamente."));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ProblemDetail> formato() {
    return ResponseEntity.badRequest()
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Confira os campos e os valores informados; nao informe dados"
                    + " bancarios sensiveis."));
  }
}
