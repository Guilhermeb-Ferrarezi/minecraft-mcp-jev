package com.jevbridge.core;

import com.google.gson.JsonObject;

/**
 * Ação que dura vários ticks (andar até um ponto, quebrar um bloco...). Só uma
 * roda por vez; uma ação nova cancela a anterior. A resposta ao pedido só sai
 * quando a ação termina, então para quem chama (o servidor MCP) cada ação é
 * uma chamada bloqueante com resultado explícito.
 */
public abstract class Action {
    final Request request;
    final int timeoutTicks;
    int ticks;

    protected Action(Request request, int timeoutTicks) {
        this.request = request;
        this.timeoutTicks = timeoutTicks;
    }

    /** @return null para continuar no próximo tick, ou o resultado final. */
    protected abstract JsonObject tick(GameAdapter game);

    /** Chamado sempre ao terminar (sucesso, falha, timeout ou cancelamento). */
    protected void cleanup(GameAdapter game) {
        game.setInput(null);
        game.setAttackHeld(false);
        game.setUseHeld(false);
    }

    /** Info extra anexada a um resultado de timeout/cancelamento. */
    protected void describeProgress(JsonObject out) {
    }

    public static JsonObject done() {
        JsonObject o = new JsonObject();
        o.addProperty("status", "done");
        return o;
    }

    public static JsonObject failed(String reason, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("status", "failed");
        o.addProperty("reason", reason);
        if (message != null) {
            o.addProperty("message", message);
        }
        return o;
    }

    static int timeoutFrom(JsonObject params, double defaultSeconds, double maxSeconds) {
        double s = Json.getDouble(params, "timeoutSeconds", defaultSeconds);
        if (s <= 0 || s > maxSeconds) {
            s = maxSeconds;
        }
        return (int) Math.ceil(s * 20);
    }
}
