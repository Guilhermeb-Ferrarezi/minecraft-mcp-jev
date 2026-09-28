package com.jevbridge.core;

/** Erro de protocolo (parâmetro inválido, método desconhecido...). Vira {"ok":false,"error":{...}}. */
public class RpcException extends RuntimeException {
    public final String code;

    public RpcException(String code, String message) {
        super(message);
        this.code = code;
    }
}
