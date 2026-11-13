package com.kitchentable.funcionarios;

import java.util.UUID;
import java.io.Serializable;
import java.security.Principal;
/** Identidade de sessao; nao contem PIN, hash ou permissoes congeladas. */
public record OperadorPrincipal(UUID usuarioId,UUID vinculoId,UUID estabelecimentoId,UUID sessaoId) implements Principal,Serializable {
    @Override public String toString(){return "OperadorPrincipal[identidade de sessao omitida]";}
    @Override public String getName(){return usuarioId.toString();}
}