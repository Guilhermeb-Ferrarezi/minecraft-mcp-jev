package com.jevbridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Testa o caminho completo: TCP -> fila -> tick do jogo -> ação -> resposta. */
public class BridgeCoreTest {
    private static final String TOKEN = "test-token-1234567890";
    private static final int Y = FakeGame.GROUND + 1;

    private FakeGame game;
    private BridgeCore core;
    private Thread loop;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Socket socket;
    private BufferedReader in;
    private OutputStream out;
    private int nextId = 1;

    @Before
    public void setUp() throws Exception {
        game = new FakeGame();
        core = new BridgeCore(game, new BridgeLog() {
            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message, Throwable error) {
                System.err.println(message);
                if (error != null) {
                    error.printStackTrace();
                }
            }
        }, "test");
        core.register(new BridgeExtension() {
            @Override
            public String method() {
                return "echo";
            }

            @Override
            public boolean async() {
                return false;
            }

            @Override
            public JsonElement handle(JsonObject params) {
                return params;
            }
        });
        core.register(new BridgeExtension() {
            @Override
            public String method() {
                return "slow_lookup";
            }

            @Override
            public boolean async() {
                return true;
            }

            @Override
            public JsonElement handle(JsonObject params) {
                if (!params.has("q")) {
                    throw new RpcException("bad_params", "falta q");
                }
                JsonObject o = new JsonObject();
                o.addProperty("thread", Thread.currentThread().getName());
                return o;
            }
        });
        core.start(BridgeConfig.forTest(0, TOKEN, false));
        loop = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running.get()) {
                    game.physicsTick();
                    core.tick();
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        });
        loop.start();
    }

    @After
    public void tearDown() throws Exception {
        running.set(false);
        loop.join();
        if (socket != null) {
            socket.close();
        }
        core.stop();
    }

    private void connect() throws Exception {
        socket = new Socket("127.0.0.1", core.port());
        socket.setSoTimeout(20000);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        out = socket.getOutputStream();
    }

    private JsonObject call(String method, String paramsJson) throws Exception {
        int id = nextId++;
        String line = "{\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}\n";
        out.write(line.getBytes("UTF-8"));
        out.flush();
        while (true) {
            String resp = in.readLine();
            if (resp == null) {
                throw new AssertionError("conexão fechada");
            }
            JsonObject o = new JsonParser().parse(resp).getAsJsonObject();
            if (o.has("id") && o.get("id").getAsInt() == id) {
                return o;
            }
        }
    }

    private JsonObject ok(String method, String paramsJson) throws Exception {
        JsonObject o = call(method, paramsJson);
        assertTrue(method + " falhou: " + o, o.get("ok").getAsBoolean());
        return o.getAsJsonObject("result");
    }

    private void login() throws Exception {
        connect();
        JsonObject hello = ok("hello", "{\"token\":\"" + TOKEN + "\",\"client\":\"test\"}");
        assertEquals(BridgeCore.PROTOCOL_VERSION, hello.get("protocol").getAsInt());
    }

    @Test
    public void rejectsWrongToken() throws Exception {
        connect();
        JsonObject o = call("hello", "{\"token\":\"errado\"}");
        assertFalse(o.get("ok").getAsBoolean());
        assertEquals("unauthorized", o.getAsJsonObject("error").get("code").getAsString());
        assertEquals(null, in.readLine());
    }

    @Test
    public void rejectsRequestsBeforeHello() throws Exception {
        connect();
        JsonObject o = call("get_state", "{}");
        assertFalse(o.get("ok").getAsBoolean());
    }

    @Test
    public void getState() throws Exception {
        login();
        JsonObject s = ok("get_state", "{}");
        assertEquals(Y, s.getAsJsonObject("blockPosition").get("y").getAsInt());
        assertEquals("Jev", s.get("name").getAsString());
    }

    @Test
    public void walkToClimbsStep() throws Exception {
        login();
        for (int x = 4; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) {
                game.set(x, Y, z, "minecraft:dirt");
            }
        }
        JsonObject r = ok("walk_to", "{\"x\":8,\"y\":" + (Y + 1) + ",\"z\":0,\"range\":0}");
        assertEquals("walk_to: " + r, "done", r.get("status").getAsString());
        assertEquals(Y + 1, (int) Math.floor(game.y));
        assertEquals(null, game.input);
    }

    @Test
    public void walkToUnreachableFails() throws Exception {
        login();
        JsonObject r = ok("walk_to", "{\"x\":500,\"y\":" + Y + ",\"z\":0,\"timeoutSeconds\":20}");
        assertEquals("failed", r.get("status").getAsString());
        assertEquals("unreachable", r.get("reason").getAsString());
    }

    @Test
    public void mineAndPlace() throws Exception {
        login();
        game.set(2, Y, 0, "minecraft:log");
        JsonObject found = ok("find_blocks", "{\"query\":\"log\"}");
        assertEquals(1, found.get("totalMatches").getAsInt());

        JsonObject mined = ok("mine_block", "{\"x\":2,\"y\":" + Y + ",\"z\":0}");
        assertEquals("done", mined.get("status").getAsString());
        assertEquals("minecraft:air", game.nameAt(2, Y, 0));

        JsonObject placed = ok("place_block", "{\"x\":2,\"y\":" + Y + ",\"z\":0}");
        assertEquals("place: " + placed, "done", placed.get("status").getAsString());
        assertEquals("minecraft:dirt", game.nameAt(2, Y, 0));
    }

    @Test
    public void mineTooFar() throws Exception {
        login();
        game.set(20, Y, 0, "minecraft:log");
        JsonObject r = ok("mine_block", "{\"x\":20,\"y\":" + Y + ",\"z\":0}");
        assertEquals("too_far", r.get("reason").getAsString());
    }

    @Test
    public void placeInsidePlayerRefused() throws Exception {
        login();
        JsonObject r = ok("place_block", "{\"x\":0,\"y\":" + Y + ",\"z\":0}");
        assertEquals("inside_player", r.get("reason").getAsString());
    }

    @Test
    public void newActionCancelsPrevious() throws Exception {
        login();
        int first = nextId++;
        out.write(("{\"id\":" + first + ",\"method\":\"move\",\"params\":{\"forward\":1,\"ticks\":200}}\n").getBytes("UTF-8"));
        out.flush();
        JsonObject stop = ok("stop", "{}");
        assertEquals("move", stop.get("stopped").getAsString());
    }

    @Test
    public void newClientCancelsOldClientsAction() throws Exception {
        login();
        out.write("{\"id\":99,\"method\":\"move\",\"params\":{\"forward\":1,\"ticks\":200}}\n".getBytes("UTF-8"));
        out.flush();
        long deadline = System.currentTimeMillis() + 5000;
        while (game.input == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("a ação não começou", game.input != null);
        Socket old = socket;
        login();
        // O aviso de desconexão é processado no tick antes de qualquer pedido do
        // cliente novo, então o primeiro status já não pode mostrar a ação antiga.
        JsonObject status = ok("status", "{}");
        assertFalse("o cliente novo herdou a ação do antigo: " + status, status.has("busyWith"));
        old.close();
    }

    @Test
    public void chatAndCommandsGate() throws Exception {
        login();
        ok("chat", "{\"message\":\"oi\"}");
        assertEquals("oi", game.chat.get(0));
        JsonObject cmd = call("chat", "{\"message\":\"/give @p diamond\"}");
        assertFalse(cmd.get("ok").getAsBoolean());
        assertEquals("forbidden", cmd.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    public void attackChecksDistance() throws Exception {
        login();
        game.entities.add(new EntityInfo(7, "Zombie", "Zombie", 2.5, Y, 0.5, 20, true, false, 1.8f));
        game.entities.add(new EntityInfo(8, "Zombie", "Zombie", 9.5, Y, 0.5, 20, true, false, 1.8f));
        assertEquals("done", ok("attack", "{\"entityId\":7}").get("status").getAsString());
        assertEquals("too_far", ok("attack", "{\"entityId\":8}").get("reason").getAsString());
        assertEquals(1, game.attacked.size());
    }

    @Test
    public void notInWorld() throws Exception {
        login();
        game.inWorld = false;
        JsonObject r = call("get_state", "{}");
        assertEquals("not_in_world", r.getAsJsonObject("error").get("code").getAsString());
        assertFalse(ok("status", "{}").get("inWorld").getAsBoolean());
    }

    @Test
    public void extensions() throws Exception {
        connect();
        JsonObject hello = ok("hello", "{\"token\":\"" + TOKEN + "\"}");
        assertTrue(hello.get("methods").toString().contains("slow_lookup"));
        assertEquals(3, ok("echo", "{\"a\":3}").get("a").getAsInt());
        assertEquals("JevBridge-Async", ok("slow_lookup", "{\"q\":1}").get("thread").getAsString());
        JsonObject bad = call("slow_lookup", "{}");
        assertEquals("bad_params", bad.getAsJsonObject("error").get("code").getAsString());
        // Consulta de extensão não depende de estar num mundo.
        game.inWorld = false;
        ok("echo", "{}");
    }

    @Test
    public void unknownMethod() throws Exception {
        login();
        JsonObject r = call("fly_to_moon", "{}");
        assertEquals("unknown_method", r.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    public void badParams() throws Exception {
        login();
        JsonObject r = call("walk_to", "{\"x\":1}");
        assertEquals("bad_params", r.getAsJsonObject("error").get("code").getAsString());
    }
}
