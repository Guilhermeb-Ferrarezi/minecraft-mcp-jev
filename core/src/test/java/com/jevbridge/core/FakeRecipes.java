package com.jevbridge.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Imita a integração com o NEI no mundo falso, no mesmo formato de resposta,
 * para testar o servidor MCP e o agente sem o jogo.
 */
final class FakeRecipes implements BridgeExtension {
    private static final String PICKAXE = "{\"item\":{\"name\":\"minecraft:wooden_pickaxe\",\"meta\":0,\"displayName\":\"Wooden Pickaxe\",\"count\":1},"
            + "\"totalRecipes\":1,\"byHandler\":[{\"handler\":\"Shaped Crafting\",\"recipes\":1}],"
            + "\"recipes\":[{\"handler\":\"Shaped Crafting\",\"ingredients\":["
            + "{\"name\":\"minecraft:planks\",\"meta\":0,\"displayName\":\"Oak Wood Planks\",\"count\":3},"
            + "{\"name\":\"minecraft:stick\",\"meta\":0,\"displayName\":\"Stick\",\"count\":2}],"
            + "\"outputs\":[{\"name\":\"minecraft:wooden_pickaxe\",\"meta\":0,\"displayName\":\"Wooden Pickaxe\",\"count\":1}]}]}";
    private static final String PLANKS = "{\"item\":{\"name\":\"minecraft:planks\",\"meta\":0,\"displayName\":\"Oak Wood Planks\",\"count\":1},"
            + "\"totalRecipes\":1,\"byHandler\":[{\"handler\":\"Shapeless Crafting\",\"recipes\":1}],"
            + "\"recipes\":[{\"handler\":\"Shapeless Crafting\",\"ingredients\":["
            + "{\"name\":\"minecraft:log\",\"meta\":0,\"displayName\":\"Oak Wood\",\"count\":1}],"
            + "\"outputs\":[{\"name\":\"minecraft:planks\",\"meta\":0,\"displayName\":\"Oak Wood Planks\",\"count\":4}]}]}";

    private final String method;

    FakeRecipes(String method) {
        this.method = method;
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public boolean async() {
        return true;
    }

    @Override
    public JsonElement handle(JsonObject params) {
        if (method.equals("search_items")) {
            return new JsonParser().parse("{\"totalMatches\":1,\"items\":[{\"name\":\"minecraft:wooden_pickaxe\",\"meta\":0,\"displayName\":\"Wooden Pickaxe\"}]}");
        }
        String item = Json.requireString(params, "item").toLowerCase();
        if (item.contains("pickaxe")) {
            return new JsonParser().parse(PICKAXE);
        }
        if (item.contains("plank")) {
            return new JsonParser().parse(PLANKS);
        }
        throw new RpcException("not_found", "nenhum item com '" + item + "' no nome (use search_items)");
    }
}
