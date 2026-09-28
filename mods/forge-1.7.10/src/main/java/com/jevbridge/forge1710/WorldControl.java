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
        core.register(new Sync("leave_world") {

            @Override
            public JsonElement handle(JsonObject p) {
                return leaveWorld();
            }
        });
        core.register(new Sync("list_servers") {

            @Override
            public JsonElement handle(JsonObject p) {
                return listServers();
            }
        });
        core.register(new Sync("join_server") {

            @Override
            public JsonElement handle(JsonObject p) {
                return joinServer(Json.requireString(p, "name"));
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
            try {
                r.run();
            } catch (RuntimeException | LinkageError e) {
                // Nunca derrubar o jogo por causa de uma troca de mundo pedida pela IA.
                JevBridgeMod.LOG.warn("JevBridge: troca de mundo falhou", e);
            }
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

    /** Sai do mundo/servidor e volta ao menu principal (o "Save and Quit"/"Disconnect" do ESC). */
    private static JsonObject leaveWorld() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            throw new RpcException("not_in_world", "já está no menu");
        }
        if (pending != null) {
            throw new RpcException("busy", "já tem uma troca de mundo em andamento");
        }
        final boolean single = mc.isIntegratedServerRunning();
        pending = new Runnable() {

            @Override
            public void run() {
                if (mc.theWorld != null) {
                    mc.theWorld.sendQuittingDisconnectingPacket();
                    mc.loadWorld((net.minecraft.client.multiplayer.WorldClient) null);
                }
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiMainMenu());
            }
        };
        JsonObject o = new JsonObject();
        o.addProperty("status", "leaving");
        o.addProperty("singleplayer", single);
        return o;
    }

    private static net.minecraft.client.multiplayer.ServerList servers() {
        net.minecraft.client.multiplayer.ServerList list = new net.minecraft.client.multiplayer.ServerList(
            Minecraft.getMinecraft());
        list.loadServerList();
        return list;
    }

    /** Servidores salvos na lista do Multiplayer (servers.dat). */
    private static JsonObject listServers() {
        net.minecraft.client.multiplayer.ServerList list = servers();
        JsonArray arr = new JsonArray();
        for (int i = 0; i < list.countServers(); i++) {
            net.minecraft.client.multiplayer.ServerData d = list.getServerData(i);
            JsonObject o = new JsonObject();
            o.addProperty("name", d.serverName);
            o.addProperty("address", d.serverIP);
            arr.add(o);
        }
        JsonObject o = new JsonObject();
        o.add("servers", arr);
        return o;
    }

    /** Conecta num servidor da lista pelo nome ou endereço (como clicar em "Join Server"). */
    private static JsonObject joinServer(String query) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null) {
            throw new RpcException("already_in_world", "saia do mundo antes (leave_world)");
        }
        if (pending != null) {
            throw new RpcException("busy", "já tem uma troca de mundo em andamento");
        }
        net.minecraft.client.multiplayer.ServerList list = servers();
        net.minecraft.client.multiplayer.ServerData found = null;
        String q = query.trim()
            .toLowerCase(Locale.ROOT);
        for (int i = 0; i < list.countServers(); i++) {
            net.minecraft.client.multiplayer.ServerData d = list.getServerData(i);
            if (d.serverName.toLowerCase(Locale.ROOT)
                .equals(q)
                || d.serverIP.toLowerCase(Locale.ROOT)
                    .equals(q)) {
                found = d;
                break;
            }
        }
        if (found == null) {
            throw new RpcException("not_found", "nenhum servidor '" + query + "' na lista (veja list_servers)");
        }
        final net.minecraft.client.multiplayer.ServerData target = found;
        pending = new Runnable() {

            @Override
            public void run() {
                if (mc.theWorld == null) {
                    // Mesma sequência da tela de Multiplayer: setupServerList cria o mapa
                    // que connectToServer lê (sem ele, NPE), e connectToServer arma o
                    // trinco do handshake do FML (abrir GuiConnecting direto deixa o
                    // handshake preso até o servidor dar timeout).
                    cpw.mods.fml.client.FMLClientHandler fml = cpw.mods.fml.client.FMLClientHandler.instance();
                    fml.setupServerList();
                    fml.connectToServer(new net.minecraft.client.gui.GuiMainMenu(), target);
                }
            }
        };
        JsonObject o = new JsonObject();
        o.addProperty("status", "connecting");
        o.addProperty("name", target.serverName);
        o.addProperty("address", target.serverIP);
        return o;
    }
}
