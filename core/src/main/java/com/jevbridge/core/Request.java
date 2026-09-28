package com.jevbridge.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Um pedido recebido do cliente, respondido exatamente uma vez. */
public final class Request {
    public final JsonElement id;
    public final String method;
    public final JsonObject params;
    final BridgeServer.Connection connection;
    private boolean answered;

    Request(JsonElement id, String method, JsonObject params, BridgeServer.Connection connection) {
        this.id = id;
        this.method = method;
        this.params = params == null ? new JsonObject() : params;
        this.connection = connection;
    }

    public synchronized void respond(JsonElement result) {
        if (answered) {
            return;
        }
        answered = true;
        JsonObject o = new JsonObject();
        o.add("id", id);
        o.addProperty("ok", true);
        o.add("result", result == null ? new JsonObject() : result);
        connection.send(o);
    }

    public synchronized void fail(String code, String message) {
        if (answered) {
            return;
        }
        answered = true;
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject o = new JsonObject();
        o.add("id", id);
        o.addProperty("ok", false);
        o.add("error", err);
        connection.send(o);
    }
}
