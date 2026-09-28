package com.jevbridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;

import org.junit.Test;

public class BridgeConfigTest {
    private static final BridgeLog SILENT = new BridgeLog() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message, Throwable error) {
        }
    };

    private static File gameDirWith(String properties) throws Exception {
        File dir = Files.createTempDirectory("jevbridge").toFile();
        File config = new File(dir, "config");
        config.mkdirs();
        OutputStream out = new FileOutputStream(new File(config, "jevbridge.properties"));
        out.write(properties.getBytes("UTF-8"));
        out.close();
        return dir;
    }

    /** O exemplo do README tinha comentário na mesma linha: a porta não abria. */
    @Test
    public void ignoresCommentsAfterTheValue() throws Exception {
        File dir = gameDirWith("bind=127.0.0.1        # só esta máquina\n"
                + "port=25599 # porta\n"
                + "token=abcdefghijklmnopqrstuvwxyz0123456789\t# quem tem o token controla o jogador\n"
                + "allowCommands=false   # true deixa a IA usar comandos\n"
                + "enabled=true\n");
        BridgeConfig c = BridgeConfig.load(dir, SILENT);
        assertEquals("127.0.0.1", c.bindAddress);
        assertEquals(25599, c.port);
        assertEquals("abcdefghijklmnopqrstuvwxyz0123456789", c.token);
        assertFalse(c.allowCommands);
        assertTrue(c.enabled);
    }

    @Test
    public void createsTokenWhenMissing() throws Exception {
        File dir = gameDirWith("");
        BridgeConfig c = BridgeConfig.load(dir, SILENT);
        assertEquals(48, c.token.length());
        assertEquals(BridgeConfig.DEFAULT_PORT, c.port);
    }
}
