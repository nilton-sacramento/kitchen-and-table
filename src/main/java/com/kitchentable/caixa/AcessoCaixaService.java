package com.kitchentable.caixa;

import static com.kitchentable.cardapio.CardapioRepository.erro;

import com.kitchentable.funcionarios.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AcessoCaixaService {
  private final AcessoAtendimentoService acesso;
  private final CaixaRepository repo;

  public AcessoCaixaService(AcessoAtendimentoService a, CaixaRepository r) {
    acesso = a;
    repo = r;
  }

  public AcessoAtendimentoService.Ator operador(
      UUID u, Authentication a, UUID expected, String cap, boolean activity) {
    var actor = acesso.operador(u, a, expected, cap, activity);
    if (!cap.equals("CAIXA_OPERAR")) acesso.operador(u, a, expected, "CAIXA_OPERAR");
    return actor;
  }

  public AcessoAtendimentoService.Ator consulta(UUID u, Authentication a, UUID expected) {
    if (a == null || !(a.getPrincipal() instanceof OperadorPrincipal p))
      return acesso.operador(u, a, expected, "CAIXA_OPERAR");
    if (!u.equals(p.estabelecimentoId()) || !Objects.equals(expected, p.vinculoId()))
      throw erro(403, "O contexto do operador mudou. Identifique-se novamente.");
    for (String code : List.of("CAIXA_OPERAR", "CAIXA_HISTORICO_CONSULTAR", "CAIXA_ABRIR"))
      if (acesso.vinculoPossui(u, p.vinculoId(), code))
        return acesso.operador(u, a, expected, code);
    throw erro(403, "Consulta do caixa nao autorizada");
  }

  public void candidato(UUID u, UUID id) {
    if (id == null
        || !acesso.vinculoPossui(u, id, "CAIXA_OPERAR")
        || repo.jdbc()
            .queryForList(
                "SELECT vinculo_id FROM credenciais_operacionais WHERE estabelecimento_id=? AND"
                    + " vinculo_id=? AND NOT bloqueado",
                u,
                id)
            .isEmpty())
      throw erro(
          403,
          "Operador precisa de vinculo ativo, PIN definido e permissao individual de operar caixa"
              + " nesta unidade");
  }
}
