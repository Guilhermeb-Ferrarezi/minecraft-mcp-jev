package com.jevbridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Leitura de parâmetros e serialização dos modelos. Usa só a API do Gson 2.2.4,
 * que é a versão embutida no Minecraft 1.7.10 — o núcleo é compilado contra ela
 * justamente para não usar nada que não exista lá.
 */
public final class Json {
    private Json() {
    }

    public static boolean has(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull();
    }

    public static int requireInt(JsonObject o, String key) {
        if (!has(o, key)) {
            throw new RpcException("bad_params", "parâmetro obrigatório: " + key);
        }
        try {
            return (int) Math.floor(o.get(key).getAsDouble());
        } catch (RuntimeException e) {
            throw new RpcException("bad_params", key + " precisa ser número");
        }
    }

    public static double requireDouble(JsonObject o, String key) {
        if (!has(o, key)) {
            throw new RpcException("bad_params", "parâmetro obrigatório: " + key);
        }
        try {
            return o.get(key).getAsDouble();
        } catch (RuntimeException e) {
            throw new RpcException("bad_params", key + " precisa ser número");
        }
    }

    public static String requireString(JsonObject o, String key) {
        if (!has(o, key)) {
            throw new RpcException("bad_params", "parâmetro obrigatório: " + key);
        }
        try {
            return o.get(key).getAsString();
        } catch (RuntimeException e) {
            throw new RpcException("bad_params", key + " precisa ser texto");
        }
    }

    public static double getDouble(JsonObject o, String key, double def) {
        return has(o, key) ? requireDouble(o, key) : def;
    }

    public static int getInt(JsonObject o, String key, int def) {
        return has(o, key) ? requireInt(o, key) : def;
    }

    public static boolean getBool(JsonObject o, String key, boolean def) {
        if (!has(o, key)) {
            return def;
        }
        try {
            return o.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            throw new RpcException("bad_params", key + " precisa ser booleano");
        }
    }

    public static String getString(JsonObject o, String key, String def) {
        return has(o, key) ? requireString(o, key) : def;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static JsonObject block(BlockInfo b) {
        if (b == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("x", b.x);
        o.addProperty("y", b.y);
        o.addProperty("z", b.z);
        o.addProperty("name", b.name);
        if (b.meta >= 0) {
            o.addProperty("meta", b.meta);
        }
        o.addProperty("displayName", b.displayName);
        if (b.solid) {
            o.addProperty("solid", true);
        }
        if (b.liquid) {
            o.addProperty("liquid", true);
        }
        if (b.hardness < 0) {
            o.addProperty("unbreakable", true);
        }
        return o;
    }

    public static JsonObject item(ItemInfo i) {
        if (i == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("slot", i.slot);
        o.addProperty("name", i.name);
        if (i.meta >= 0) {
            o.addProperty("meta", i.meta);
        }
        o.addProperty("displayName", i.displayName);
        o.addProperty("count", i.count);
        if (i.maxDamage > 0) {
            o.addProperty("durability", i.maxDamage - i.damage);
            o.addProperty("maxDurability", i.maxDamage);
        }
        return o;
    }

    public static JsonObject entity(EntityInfo e, double fromX, double fromY, double fromZ) {
        if (e == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id);
        o.addProperty("type", e.type);
        o.addProperty("name", e.name);
        o.addProperty("x", round(e.x));
        o.addProperty("y", round(e.y));
        o.addProperty("z", round(e.z));
        o.addProperty("distance", round(Geometry.dist(fromX, fromY, fromZ, e.x, e.y, e.z)));
        if (!Float.isNaN(e.health)) {
            o.addProperty("health", e.health);
        }
        if (e.hostile) {
            o.addProperty("hostile", true);
        }
        if (e.player) {
            o.addProperty("player", true);
        }
        return o;
    }

    public static JsonArray items(List<ItemInfo> items) {
        JsonArray a = new JsonArray();
        for (ItemInfo i : items) {
            a.add(item(i));
        }
        return a;
    }

    public static JsonObject player(PlayerSnapshot p) {
        JsonObject o = new JsonObject();
        o.addProperty("name", p.name);
        JsonObject pos = new JsonObject();
        pos.addProperty("x", round(p.x));
        pos.addProperty("y", round(p.y));
        pos.addProperty("z", round(p.z));
        o.add("position", pos);
        JsonObject block = new JsonObject();
        block.addProperty("x", Geometry.floor(p.x));
        block.addProperty("y", Geometry.floor(p.y + 1e-3));
        block.addProperty("z", Geometry.floor(p.z));
        o.add("blockPosition", block);
        o.addProperty("yaw", round(p.yaw));
        o.addProperty("pitch", round(p.pitch));
        o.addProperty("health", p.health);
        o.addProperty("maxHealth", p.maxHealth);
        o.addProperty("food", p.food);
        o.addProperty("saturation", round(p.saturation));
        o.addProperty("xpLevel", p.xpLevel);
        o.addProperty("dimension", p.dimension);
        if (p.biome != null) {
            o.addProperty("biome", p.biome);
        }
        o.addProperty("gameMode", p.gameMode);
        o.addProperty("onGround", p.onGround);
        o.addProperty("inWater", p.inWater);
        o.addProperty("inLava", p.inLava);
        o.addProperty("dead", p.dead);
        o.addProperty("selectedSlot", p.selectedSlot);
        o.add("heldItem", item(p.heldItem));
        o.addProperty("timeOfDay", p.worldTime % 24000);
        o.addProperty("isNight", isNight(p.worldTime));
        o.addProperty("raining", p.raining);
        if (p.lookingAtBlock != null) {
            o.add("lookingAtBlock", block(p.lookingAtBlock));
        }
        if (p.lookingAtEntity != null) {
            o.add("lookingAtEntity", entity(p.lookingAtEntity, p.x, p.y, p.z));
        }
        return o;
    }

    static boolean isNight(long worldTime) {
        long t = worldTime % 24000;
        return t >= 13000 && t < 23000;
    }
}
