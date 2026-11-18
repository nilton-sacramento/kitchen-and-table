package com.kitchentable.funcionarios;

import java.util.*;
import java.time.Instant;
import jakarta.validation.constraints.*;
public final class OperacionalDtos {
    private OperacionalDtos(){}
    // PIN nao usa Bean Validation para impedir rejected value em logs de validacao.
    public record Entrada(String estabelecimentoId,String identificacao,String pin){@Override public String toString(){return "Entrada[credenciais omitidas]";}}
    public record Definicao(String pin,Long versao){@Override public String toString(){return "Definicao[PIN omitido]";}}
    public record Bloqueio(@NotNull Boolean bloqueado,@NotNull @PositiveOrZero Long versao){}
    public record Politica(@Min(30) @Max(3600) int segundos,@NotNull @PositiveOrZero Long versao){}
    public record Configuracao(int segundos,long versao){}
    public record Estado(UUID vinculoId,String nome,boolean configurado,boolean bloqueado,String motivo,int tentativasRestantes,long versao){}
    public record Capacidades(boolean gerenciarPin,boolean desbloquear,boolean configurar,boolean eventos){}
    public record Contexto(UUID usuarioId,UUID vinculoId,UUID estabelecimentoId,String nome,String estabelecimento,Instant expiraEm,long validadeMs,List<PermissoesDtos.Efetiva> permissoes){}
    public record Sessao(boolean desbloqueado,boolean administrativo,Contexto contexto){}
    public record Evento(UUID id,String tipo,UUID funcionario,UUID executor,Instant ocorridoEm){}
}