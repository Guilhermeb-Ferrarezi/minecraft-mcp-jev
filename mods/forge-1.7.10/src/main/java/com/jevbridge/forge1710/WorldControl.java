package com.jevbridge.forge1710;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.SaveFormatComparator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jevbridge.core.BridgeCore;
import com.jevbridge.core.BridgeExtension;
import com.jevbridge.core.Json;
import com.jevbridge.core.RpcException;

/**
 * Mundos do single player: listar e abrir sem passar pelo menu. Sem isso, a IA
 * no menu principal dependeria de alguém clicar em "Singleplayer".
 *
 * <p>
 * Abrir um mundo trava a thread do jogo até o servidor integrado subir (no GTNH,
 * dezenas de segundos), então {@code open_world} responde na hora e o
 * carregamento roda no começo do tick seguinte ({@link #runPending}). Quem chamou
 * acompanha pelo {@code status} (inWorld) ou pelo evento joined_world.
 */
final class WorldControl {

    private static volatile Runnable pending;

    private WorldControl() {}

    static void register(BridgeCore core) {
        core.register(new Sync("list_worlds") {

            @Override
            public JsonElement handle(JsonObject p) {
                return listWorlds();
            }
        });
        core.register(new Sync("open_world") {

            @Override
            public JsonElement handle(JsonObject p) {
                return openWorld(Json.requireString(p, "name"));
            }
        });
    }

    /** Chamado pelo mod no começo do tick, fora do processamento de pedidos. */
    static void runPending() {
        Runnable r = pending;
        if (r != null) {
            pending = null;
            r.run();
        }
    }

    private abstract static class Sync implements BridgeExtension {

        private final String method;

        Sync(String method) {
            this.method = method;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public boolean async() {
            return false; // mexe no Minecraft: só na thread do jogo
        }
    }

    @SuppressWarnings("unchecked")
    private static List<SaveFormatComparator> saves() {
        try {
            return Minecraft.getMinecraft()
                .getSaveLoader()
                .getSaveList();
        } catch (Exception e) {
            throw new RpcException("internal", "não consegui ler a pasta saves: " + e);
        }
    }

    private static JsonObject listWorlds() {
        Minecraft mc = Minecraft.getMinecraft();
        JsonArray arr = new JsonArray();
        for (SaveFormatComparator s : saves()) {
            JsonObject o = new JsonObject();
            o.addProperty("folder", s.getFileName());
            o.addProperty("name", s.getDisplayName());
            o.addProperty("lastPlayed", s.getLastTimePlayed());
            o.addProperty(
                "gameMode",
                s.getEnumGameType() == null ? null
                    : s.getEnumGameType()
                        .getName());
            o.addProperty("hardcore", s.isHardcoreModeEnabled());
            arr.add(o);
        }
        JsonObject o = new JsonObject();
        o.addProperty("inWorld", mc.theWorld != null);
        o.add("worlds", arr);
        return o;
    }

    private static JsonObject openWorld(String query) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null) {
            throw new RpcException("already_in_world", "já está num mundo; saia dele antes");
        }
        if (pending != null) {
            throw new RpcException("busy", "já tem um mundo abrindo");
        }
        String q = query.trim()
            .toLowerCase(Locale.ROOT);
        SaveFormatComparator found = null;
        for (SaveFormatComparator s : saves()) {
            if (s.getFileName()
                .toLowerCase(Locale.ROOT)
                .equals(q)
                || s.getDisplayName()
                    .toLowerCase(Locale.ROOT)
                    .equals(q)) {
                found = s;
                break;
            }
        }
        if (found == null) {
            throw new RpcException("not_found", "nenhum mundo chamado '" + query + "' (veja list_worlds)");
        }
        ISaveFormat loader = mc.getSaveLoader();
        if (!loader.canLoadWorld(found.getFileName())) {
            throw new RpcException("cannot_load", "o Minecraft não consegue abrir esse mundo");
        }
        final String folder = found.getFileName();
        final String name = found.getDisplayName();
        pending = new Runnable() {

            @Override
            public void run() {
                if (mc.theWorld == null) {
                    mc.launchIntegratedServer(folder, name, null);
                }
            }
        };
        JsonObject o = new JsonObject();
        o.addProperty("status", "loading");
        o.addProperty("folder", folder);
        o.addProperty("name", name);
        return o;
    }
}
