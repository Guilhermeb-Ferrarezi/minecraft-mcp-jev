package com.jevbridge.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Método extra do protocolo, registrado pelo adaptador quando uma integração
 * opcional está disponível (ex.: receitas do NEI). Aparece na lista de métodos
 * do hello só se foi registrado.
 */
public interface BridgeExtension {
    String method();

    /**
     * true: roda numa thread própria da ponte, fora do tick — para consultas
     * pesadas que não mexem no mundo (não pode tocar em entidades/blocos).
     * false: roda na thread do jogo, como os métodos normais.
     */
    boolean async();

    /** Pode lançar {@link RpcException} para erro de protocolo. */
    JsonElement handle(JsonObject params);
}
