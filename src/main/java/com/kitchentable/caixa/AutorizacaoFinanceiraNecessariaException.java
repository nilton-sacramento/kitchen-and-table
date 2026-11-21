package com.kitchentable.caixa;

/** Sinal de negocio: sessao continua valida, mas a operacao nao foi autorizada. */
public class AutorizacaoFinanceiraNecessariaException
    extends org.springframework.web.server.ResponseStatusException {
  public AutorizacaoFinanceiraNecessariaException() {
    super(
        org.springframework.http.HttpStatus.FORBIDDEN,
        "Esta operacao exige autorizacao pontual do proprietario");
  }
}
