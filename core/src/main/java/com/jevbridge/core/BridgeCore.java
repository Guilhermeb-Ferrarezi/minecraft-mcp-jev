package com.jevbridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Coração do mod: recebe pedidos da rede, executa na thread do jogo e mantém a
 * ação de vários ticks em andamento. O adaptador de cada versão só precisa
 * chamar {@link #tick()} no fim de todo tick do cliente e repassar eventos
 * (chat) — o resto está aqui.
 */
public final class BridgeCore implements BridgeServer.Handler {
    public static final int PROTOCOL_VERSION = 1;

    static final List<String> METHODS = Collections.unmodifiableList(Arrays.asList(
            "hello", "status", "get_state", "get_inventory", "get_block", "get_blocks", "find_blocks",
            "get_entities", "look", "look_at", "select_slot", "chat", "attack", "stop",
            "walk_to", "mine_block", "place_block", "use_item", "move"));

    private final GameAdapter game;
    private final BridgeLog log;
    private final String modVersion;
    private final ConcurrentLinkedQueue<Request> inbox = new ConcurrentLinkedQueue<Request>();
    private final Map<String, BridgeExtension> extensions = new ConcurrentHashMap<String, BridgeExtension>();
    private ExecutorService asyncWorker;
    private BridgeServer server;
    private BridgeConfig config;
    private volatile boolean disconnected;
    private Action current;
    private boolean wasDead;
    private boolean wasInWorld;

    public BridgeCore(GameAdapter game, BridgeLog log, String modVersion) {
        this.game = game;
        this.log = log;
        this.modVersion = modVersion;
    }

    /** Registra um método extra (antes de {@link #start}). */
    public void register(BridgeExtension extension) {
        if (METHODS.contains(extension.method())) {
            throw new IllegalArgumentException("método já existe: " + extension.method());
        }
        extensions.put(extension.method(), extension);
    }

    public void start(BridgeConfig config) throws IOException {
        this.config = config;
        server = new BridgeServer(config, log, this);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop();
        }
        if (asyncWorker != null) {
            asyncWorker.shutdownNow();
        }
    }

    public int port() {
        return server.localPort();
    }

    public boolean hasClient() {
        return server != null && server.hasClient();
    }

    // ---------------------------------------------------------------- rede

    @Override
    public JsonObject hello(JsonObject params) {
        JsonObject o = new JsonObject();
        o.addProperty("protocol", PROTOCOL_VERSION);
        o.addProperty("minecraftVersion", game.minecraftVersion());
        o.addProperty("loader", game.loader());
        o.addProperty("modVersion", modVersion);
        JsonArray methods = new JsonArray();
        for (String m : METHODS) {
            methods.add(new JsonPrimitive(m));
        }
        for (String m : extensions.keySet()) {
            methods.add(new JsonPrimitive(m));
        }
        o.add("methods", methods);
        return o;
    }

    @Override
    public void onRequest(Request request) {
        inbox.add(request);
    }

    @Override
    public void onDisconnect(BridgeServer.Connection connection) {
        disconnected = true;
    }

    // ---------------------------------------------------------------- eventos

    /** Mensagem de chat recebida (o adaptador chama isto do handler de chat). */
    public void onChat(String text) {
        if (server == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        server.emit("chat", o);
    }

    public void emit(String event, JsonElement data) {
        if (server != null) {
            server.emit(event, data);
        }
    }

    // ---------------------------------------------------------------- tick

    /** Chamado pelo adaptador no fim de cada tick do cliente, na thread do jogo. */
    public void tick() {
        try {
            tickUnsafe();
        } catch (RuntimeException | LinkageError e) {
            // Nunca derrubar o jogo por causa da ponte (LinkageError: algum mod
            // do pack com método ausente/alterado).
            log.warn("JevBridge: erro no tick", e);
            if (current != null) {
                finish(Action.failed("internal", String.valueOf(e)));
            }
        }
    }

    private void tickUnsafe() {
        if (disconnected) {
            disconnected = false;
            if (current != null) {
                current.cleanup(game);
                current = null;
            }
        }
        boolean inWorld = game.inWorld();
        if (inWorld != wasInWorld) {
            wasInWorld = inWorld;
            JsonObject o = new JsonObject();
            o.addProperty("inWorld", inWorld);
            emit(inWorld ? "joined_world" : "left_world", o);
        }
        if (!inWorld && current != null) {
            finish(Action.failed("left_world", "saiu do mundo"));
        }

        Request r;
        while ((r = inbox.poll()) != null) {
            try {
                dispatch(r, inWorld);
            } catch (RpcException e) {
                r.fail(e.code, e.getMessage());
            } catch (RuntimeException | LinkageError e) {
                log.warn("JevBridge: erro executando " + r.method, e);
                r.fail("internal", String.valueOf(e));
            }
        }

        if (inWorld) {
            PlayerSnapshot p = game.player();
            if (p.dead != wasDead) {
                wasDead = p.dead;
                if (p.dead) {
                    JsonObject o = new JsonObject();
                    o.add("position", Actions.position(p));
                    emit("death", o);
                }
            }
        }

        if (current != null) {
            Action a = current;
            JsonObject result = a.tick(game);
            a.ticks++;
            if (result != null) {
                finish(result);
            } else if (a.ticks >= a.timeoutTicks) {
                JsonObject o = Action.failed("timeout", "tempo esgotado depois de " + a.ticks + " ticks");
                o.addProperty("status", "timeout");
                a.describeProgress(o);
                if (game.inWorld()) {
                    o.add("position", Actions.position(game.player()));
                }
                finish(o);
            }
        }
    }

    private void finish(JsonObject result) {
        Action a = current;
        current = null;
        if (a == null) {
            return;
        }
        a.cleanup(game);
        a.request.respond(result);
    }

    private void startAction(Action action) {
        if (current != null) {
            JsonObject o = new JsonObject();
            o.addProperty("status", "cancelled");
            o.addProperty("reason", "substituída por " + action.request.method);
            current.describeProgress(o);
            finish(o);
        }
        current = action;
    }

    // ---------------------------------------------------------------- métodos

    private void dispatch(Request r, boolean inWorld) {
        String m = r.method;
        JsonObject p = r.params;
        if ("status".equals(m)) {
            JsonObject o = new JsonObject();
            o.addProperty("inWorld", inWorld);
            if (current != null) {
                o.addProperty("busyWith", current.request.method);
            }
            r.respond(o);
            return;
        }
        if ("stop".equals(m)) {
            JsonObject o = new JsonObject();
            if (current != null) {
                o.addProperty("stopped", current.request.method);
                JsonObject cancelled = new JsonObject();
                cancelled.addProperty("status", "cancelled");
                cancelled.addProperty("reason", "stop");
                current.describeProgress(cancelled);
                finish(cancelled);
            }
            game.setInput(null);
            r.respond(o);
            return;
        }
        BridgeExtension ext = extensions.get(m);
        if (ext != null) {
            runExtension(ext, r);
            return;
        }
        if (!METHODS.contains(m)) {
            throw new RpcException("unknown_method", "método desconhecido: " + m);
        }
        if (!inWorld) {
            throw new RpcException("not_in_world", "o jogador não está num mundo");
        }
        PlayerSnapshot me = game.player();

        if ("get_state".equals(m)) {
            JsonObject o = Json.player(me);
            if (current != null) {
                o.addProperty("busyWith", current.request.method);
            }
            r.respond(o);
        } else if ("get_inventory".equals(m)) {
            JsonObject o = new JsonObject();
            o.addProperty("selectedSlot", me.selectedSlot);
            o.add("items", Json.items(game.inventory()));
            r.respond(o);
        } else if ("get_block".equals(m)) {
            BlockInfo b = game.blockAt(Json.requireInt(p, "x"), Json.requireInt(p, "y"), Json.requireInt(p, "z"));
            if (b == null) {
                throw new RpcException("not_loaded", "chunk não carregado");
            }
            r.respond(Json.block(b));
        } else if ("get_blocks".equals(m)) {
            r.respond(getBlocks(me, Math.max(1, Math.min(6, Json.getInt(p, "radius", 3)))));
        } else if ("find_blocks".equals(m)) {
            r.respond(findBlocks(me, p));
        } else if ("get_entities".equals(m)) {
            r.respond(getEntities(me, p));
        } else if ("look".equals(m)) {
            float pitch = (float) Math.max(-90, Math.min(90, Json.requireDouble(p, "pitch")));
            game.setLook((float) Json.requireDouble(p, "yaw"), pitch);
            r.respond(new JsonObject());
        } else if ("look_at".equals(m)) {
            Actions.lookAt(game, me, Json.requireDouble(p, "x"), Json.requireDouble(p, "y"), Json.requireDouble(p, "z"));
            r.respond(new JsonObject());
        } else if ("select_slot".equals(m)) {
            int slot = Json.requireInt(p, "slot");
            if (slot < 0 || slot > 8) {
                throw new RpcException("bad_params", "slot vai de 0 a 8 (hotbar)");
            }
            game.selectSlot(slot);
            JsonObject o = new JsonObject();
            o.add("heldItem", Json.item(game.player().heldItem));
            r.respond(o);
        } else if ("chat".equals(m)) {
            chat(r, Json.requireString(p, "message"));
        } else if ("attack".equals(m)) {
            r.respond(attack(me, Json.requireInt(p, "entityId")));
        } else if ("walk_to".equals(m)) {
            double range = Math.max(0, Json.getDouble(p, "range", 1));
            startAction(new Actions.WalkTo(r, game, Json.requireInt(p, "x"), Json.requireInt(p, "y"),
                    Json.requireInt(p, "z"), range, Json.getBool(p, "sprint", false), Action.timeoutFrom(p, 60, 300)));
        } else if ("mine_block".equals(m)) {
            startAction(new Actions.MineBlock(r, Json.requireInt(p, "x"), Json.requireInt(p, "y"),
                    Json.requireInt(p, "z"), Action.timeoutFrom(p, 30, 120)));
        } else if ("place_block".equals(m)) {
            startAction(new Actions.PlaceBlock(r, Json.requireInt(p, "x"), Json.requireInt(p, "y"), Json.requireInt(p, "z")));
        } else if ("use_item".equals(m)) {
            startAction(new Actions.UseItem(r, Math.max(1, Math.min(200, Json.getInt(p, "ticks", 40)))));
        } else if ("move".equals(m)) {
            InputState in = new InputState((float) Json.getDouble(p, "forward", 0), (float) Json.getDouble(p, "strafe", 0),
                    Json.getBool(p, "jump", false), Json.getBool(p, "sneak", false), Json.getBool(p, "sprint", false));
            startAction(new Actions.Move(r, in, Math.max(1, Math.min(200, Json.getInt(p, "ticks", 10)))));
        }
    }

    private void runExtension(final BridgeExtension ext, final Request r) {
        if (!ext.async()) {
            r.respond(ext.handle(r.params));
            return;
        }
        if (asyncWorker == null) {
            // Uma thread só: consultas pesadas em fila, sem competir entre si.
            asyncWorker = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread t = new Thread(runnable, "JevBridge-Async");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        asyncWorker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    r.respond(ext.handle(r.params));
                } catch (RpcException e) {
                    r.fail(e.code, e.getMessage());
                } catch (RuntimeException | LinkageError e) {
                    log.warn("JevBridge: erro executando " + r.method, e);
                    r.fail("internal", String.valueOf(e));
                }
            }
        });
    }

    private void chat(Request r, String message) {
        message = message.trim();
        if (message.isEmpty()) {
            throw new RpcException("bad_params", "mensagem vazia");
        }
        // 1.7.10 derruba o cliente do servidor com mensagem > 100 caracteres.
        if (message.length() > 100) {
            throw new RpcException("bad_params", "mensagem passa de 100 caracteres (limite do Minecraft)");
        }
        if (message.startsWith("/") && !config.allowCommands) {
            throw new RpcException("forbidden", "comandos (/...) estão desativados; habilite allowCommands no config/jevbridge.properties");
        }
        game.sendChat(message);
        r.respond(new JsonObject());
    }

    private JsonObject attack(PlayerSnapshot me, int entityId) {
        for (EntityInfo e : game.entities(64)) {
            if (e.id != entityId) {
                continue;
            }
            double cy = e.y + e.height / 2;
            double d = Actions.eyeDistance(me, e.x, cy, e.z);
            if (d > 3.5) {
                JsonObject o = Action.failed("too_far", "chegue mais perto (walk_to até a entidade, range 2)");
                o.addProperty("distance", Math.round(d * 10) / 10.0);
                return o;
            }
            Actions.lookAt(game, me, e.x, cy, e.z);
            game.attack(entityId);
            JsonObject o = Action.done();
            o.add("target", Json.entity(e, me.x, me.y, me.z));
            return o;
        }
        return Action.failed("not_found", "nenhuma entidade com esse id por perto");
    }

    private JsonObject getBlocks(PlayerSnapshot me, int radius) {
        int cx = Geometry.floor(me.x), cy = Geometry.floor(me.y + 0.01), cz = Geometry.floor(me.z);
        JsonArray arr = new JsonArray();
        for (int y = cy + radius; y >= cy - radius; y--) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    BlockInfo b = game.blockAt(x, y, z);
                    if (b != null && !b.isAir()) {
                        arr.add(Json.block(b));
                    }
                }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("center", cx + "," + cy + "," + cz);
        o.addProperty("radius", radius);
        o.add("blocks", arr);
        return o;
    }

    private JsonObject findBlocks(PlayerSnapshot me, JsonObject p) {
        final String query = Json.requireString(p, "query").toLowerCase(Locale.ROOT).trim();
        if (query.isEmpty()) {
            throw new RpcException("bad_params", "query vazia");
        }
        int radius = Math.max(1, Math.min(32, Json.getInt(p, "radius", 16)));
        int vertical = Math.max(1, Math.min(32, Json.getInt(p, "verticalRadius", 8)));
        int limit = Math.max(1, Math.min(50, Json.getInt(p, "limit", 10)));
        final double ex = me.x, ey = me.y + me.eyeHeight, ez = me.z;
        int cx = Geometry.floor(me.x), cy = Geometry.floor(me.y), cz = Geometry.floor(me.z);
        List<BlockInfo> found = new ArrayList<BlockInfo>();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (!game.isLoaded(x, z)) {
                    continue;
                }
                for (int y = Math.max(0, cy - vertical); y <= cy + vertical; y++) {
                    BlockInfo b = game.blockAt(x, y, z);
                    if (b == null || b.isAir()) {
                        continue;
                    }
                    if (b.name.toLowerCase(Locale.ROOT).contains(query)
                            || (b.displayName != null && b.displayName.toLowerCase(Locale.ROOT).contains(query))) {
                        found.add(b);
                    }
                }
            }
        }
        Collections.sort(found, new Comparator<BlockInfo>() {
            @Override
            public int compare(BlockInfo a, BlockInfo b) {
                return Double.compare(Geometry.dist(ex, ey, ez, a.x + 0.5, a.y + 0.5, a.z + 0.5),
                        Geometry.dist(ex, ey, ez, b.x + 0.5, b.y + 0.5, b.z + 0.5));
            }
        });
        JsonArray arr = new JsonArray();
        for (int i = 0; i < found.size() && i < limit; i++) {
            BlockInfo b = found.get(i);
            JsonObject o = Json.block(b);
            o.addProperty("distance", Math.round(Geometry.dist(ex, ey, ez, b.x + 0.5, b.y + 0.5, b.z + 0.5) * 10) / 10.0);
            arr.add(o);
        }
        JsonObject o = new JsonObject();
        o.addProperty("totalMatches", found.size());
        o.add("blocks", arr);
        return o;
    }

    private JsonObject getEntities(final PlayerSnapshot me, JsonObject p) {
        double radius = Math.max(1, Math.min(64, Json.getDouble(p, "radius", 16)));
        int limit = Math.max(1, Math.min(100, Json.getInt(p, "limit", 30)));
        List<EntityInfo> list = new ArrayList<EntityInfo>(game.entities(radius));
        Collections.sort(list, new Comparator<EntityInfo>() {
            @Override
            public int compare(EntityInfo a, EntityInfo b) {
                return Double.compare(Geometry.dist(me.x, me.y, me.z, a.x, a.y, a.z),
                        Geometry.dist(me.x, me.y, me.z, b.x, b.y, b.z));
            }
        });
        JsonArray arr = new JsonArray();
        for (int i = 0; i < list.size() && i < limit; i++) {
            arr.add(Json.entity(list.get(i), me.x, me.y, me.z));
        }
        JsonObject o = new JsonObject();
        o.add("entities", arr);
        return o;
    }
}
