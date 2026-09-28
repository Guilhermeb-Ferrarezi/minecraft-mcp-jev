package com.jevbridge.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Properties;

/**
 * config/jevbridge.properties — igual em todas as versões, fora do sistema de
 * config de cada loader, para o servidor MCP achar o token no mesmo lugar sempre.
 */
public final class BridgeConfig {
    public static final int DEFAULT_PORT = 25599;

    public final String bindAddress;
    public final int port;
    public final String token;
    public final boolean enabled;
    /** Deixa a IA mandar comandos (/give, /tp...) pelo chat. Desligado por padrão. */
    public final boolean allowCommands;
    public final File file;

    private BridgeConfig(String bindAddress, int port, String token, boolean enabled, boolean allowCommands, File file) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.token = token;
        this.enabled = enabled;
        this.allowCommands = allowCommands;
        this.file = file;
    }

    public static BridgeConfig forTest(int port, String token, boolean allowCommands) {
        return new BridgeConfig("127.0.0.1", port, token, true, allowCommands, null);
    }

    /** Lê (ou cria, com token aleatório) o arquivo de config dentro de gameDir/config. */
    public static BridgeConfig load(File gameDir, BridgeLog log) {
        File dir = new File(gameDir, "config");
        File file = new File(dir, "jevbridge.properties");
        Properties p = new Properties();
        if (file.isFile()) {
            InputStream in = null;
            try {
                in = new FileInputStream(file);
                p.load(in);
            } catch (IOException e) {
                log.warn("JevBridge: não consegui ler " + file, e);
            } finally {
                closeQuietly(in);
            }
        }
        boolean changed = false;
        if (p.getProperty("token", "").trim().length() < 16) {
            p.setProperty("token", randomToken());
            changed = true;
        }
        if (p.getProperty("port") == null) {
            p.setProperty("port", String.valueOf(DEFAULT_PORT));
            changed = true;
        }
        if (p.getProperty("bind") == null) {
            p.setProperty("bind", "127.0.0.1");
            changed = true;
        }
        if (p.getProperty("enabled") == null) {
            p.setProperty("enabled", "true");
            changed = true;
        }
        if (p.getProperty("allowCommands") == null) {
            p.setProperty("allowCommands", "false");
            changed = true;
        }
        if (changed) {
            dir.mkdirs();
            OutputStream out = null;
            try {
                out = new FileOutputStream(file);
                p.store(out, "JevBridge: ponte entre o Minecraft e o servidor MCP.\n"
                        + "bind=127.0.0.1 deixa a porta visivel so nesta maquina. Nao exponha na rede:\n"
                        + "quem tem o token controla o seu jogador.");
            } catch (IOException e) {
                log.warn("JevBridge: não consegui gravar " + file, e);
            } finally {
                closeQuietly(out);
            }
        }
        int port;
        try {
            port = Integer.parseInt(p.getProperty("port").trim());
        } catch (NumberFormatException e) {
            port = DEFAULT_PORT;
        }
        return new BridgeConfig(p.getProperty("bind").trim(), port, p.getProperty("token").trim(),
                !"false".equalsIgnoreCase(p.getProperty("enabled").trim()),
                "true".equalsIgnoreCase(p.getProperty("allowCommands").trim()), file);
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
