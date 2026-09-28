package com.jevbridge.core;

/** Log fornecido pelo adaptador (log4j no Forge/Fabric, stdout nos testes). */
public interface BridgeLog {
    void info(String message);

    void warn(String message, Throwable error);
}
