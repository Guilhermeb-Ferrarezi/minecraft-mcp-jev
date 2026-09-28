package com.jevbridge.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Servidor TCP local com protocolo JSON por linha (docs/PROTOCOL.md).
 *
 * <p>Threads próprias para aceitar, ler e escrever: a thread do jogo nunca faz
 * I/O de rede, só enfileira mensagens. Um cliente autenticado por vez; um novo
 * "hello" válido derruba o anterior (útil quando o servidor MCP reinicia).
 */
public final class BridgeServer {

    public interface Handler {
        JsonObject hello(JsonObject params);

        /** Chamado na thread de leitura; a implementação deve só enfileirar. */
        void onRequest(Request request);

        void onDisconnect(Connection connection);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_LINE_BYTES = 1 << 20;
    private static final int MAX_QUEUED_MESSAGES = 2000;

    private final BridgeConfig config;
    private final BridgeLog log;
    private final Handler handler;
    private volatile ServerSocket serverSocket;
    private volatile Connection active;
    private volatile boolean running;

    public BridgeServer(BridgeConfig config, BridgeLog log, Handler handler) {
        this.config = config;
        this.log = log;
        this.handler = handler;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(config.port, 4, InetAddress.getByName(config.bindAddress));
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "JevBridge-Accept");
        t.setDaemon(true);
        t.start();
        log.info("JevBridge ouvindo em " + config.bindAddress + ":" + serverSocket.getLocalPort());
    }

    public int localPort() {
        return serverSocket.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        Connection c = active;
        if (c != null) {
            c.close();
        }
    }

    /** Evento assíncrono para o cliente conectado (chat, morte...). Sem cliente, é descartado. */
    public void emit(String event, JsonElement data) {
        Connection c = active;
        if (c == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("event", event);
        o.add("data", data == null ? new JsonObject() : data);
        c.send(o);
    }

    public boolean hasClient() {
        return active != null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                new Connection(s).start();
            } catch (IOException e) {
                if (running) {
                    log.warn("JevBridge: erro aceitando conexão", e);
                }
            }
        }
    }

    public final class Connection {
        private final Socket socket;
        private final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<String>();
        private volatile boolean open = true;
        private boolean authenticated;

        Connection(Socket socket) {
            this.socket = socket;
        }

        void start() {
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop();
                }
            }, "JevBridge-Read");
            reader.setDaemon(true);
            reader.start();
            Thread writer = new Thread(new Runnable() {
                @Override
                public void run() {
                    writeLoop();
                }
            }, "JevBridge-Write");
            writer.setDaemon(true);
            writer.start();
        }

        public void send(JsonObject message) {
            if (!open) {
                return;
            }
            if (outbox.size() > MAX_QUEUED_MESSAGES) {
                // Cliente parou de ler; melhor derrubar do que acumular memória no jogo.
                log.warn("JevBridge: cliente não está lendo, desconectando", null);
                close();
                return;
            }
            outbox.offer(message.toString());
        }

        void close() {
            if (!open) {
                return;
            }
            open = false;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            if (active == this) {
                active = null;
                handler.onDisconnect(this);
                log.info("JevBridge: cliente desconectado");
            }
        }

        private void readLoop() {
            try {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                String line;
                while (open && (line = readLine(in)) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    handleLine(line);
                }
            } catch (IOException e) {
                // conexão caiu
            } finally {
                close();
            }
        }

        private void handleLine(String line) {
            JsonObject msg;
            try {
                JsonElement parsed = new JsonParser().parse(line);
                if (!parsed.isJsonObject()) {
                    throw new IllegalArgumentException("mensagem precisa ser um objeto JSON");
                }
                msg = parsed.getAsJsonObject();
            } catch (RuntimeException e) {
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                JsonObject detail = new JsonObject();
                detail.addProperty("code", "bad_json");
                detail.addProperty("message", String.valueOf(e.getMessage()));
                err.add("error", detail);
                send(err);
                return;
            }
            JsonElement id = msg.get("id");
            String method = Json.getString(msg, "method", "");
            JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
                    ? msg.getAsJsonObject("params") : new JsonObject();
            Request req = new Request(id, method, params, this);

            if (!authenticated) {
                if (!"hello".equals(method) || !constantTimeEquals(Json.getString(params, "token", ""), config.token)) {
                    req.fail("unauthorized", "a primeira mensagem precisa ser hello com o token certo");
                    // Dá tempo do erro sair antes de fechar.
                    outbox.offer("");
                    open = false;
                    return;
                }
                authenticated = true;
                Connection previous = active;
                active = this;
                if (previous != null && previous != this) {
                    // close() só avisa o handler se ainda for a conexão ativa; aqui já
                    // não é, então avisa na mão para cancelar a ação do cliente antigo.
                    previous.close();
                    handler.onDisconnect(previous);
                }
                log.info("JevBridge: cliente autenticado (" + Json.getString(params, "client", "?") + ")");
                req.respond(handler.hello(params));
                return;
            }
            if ("hello".equals(method)) {
                req.respond(handler.hello(params));
                return;
            }
            handler.onRequest(req);
        }

        private void writeLoop() {
            try {
                OutputStream out = socket.getOutputStream();
                while (open || !outbox.isEmpty()) {
                    String msg = outbox.poll(500, TimeUnit.MILLISECONDS);
                    if (msg == null) {
                        continue;
                    }
                    if (msg.isEmpty()) {
                        out.flush();
                        break;
                    }
                    out.write(msg.getBytes(UTF8));
                    out.write('\n');
                    if (outbox.isEmpty()) {
                        out.flush();
                    }
                }
            } catch (IOException e) {
                // conexão caiu
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (!authenticated) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                } else {
                    close();
                }
            }
        }
    }

    /** readLine com limite de tamanho: antes de autenticar, qualquer processo local pode mandar lixo. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return new String(buf.toByteArray(), UTF8);
            }
            if (b != '\r') {
                buf.write(b);
            }
            if (buf.size() > MAX_LINE_BYTES) {
                throw new IOException("linha grande demais");
            }
        }
        return buf.size() > 0 ? new String(buf.toByteArray(), UTF8) : null;
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(UTF8);
        byte[] y = b.getBytes(UTF8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            byte bx = i < x.length ? x[i] : 0;
            byte by = i < y.length ? y[i] : 0;
            diff |= bx ^ by;
        }
        return diff == 0 && x.length == y.length;
    }
}
