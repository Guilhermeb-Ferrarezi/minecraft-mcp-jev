package com.jevbridge.forge1710;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.world.ChunkPosition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jevbridge.core.BridgeCore;
import com.jevbridge.core.BridgeExtension;
import com.jevbridge.core.Json;
import com.jevbridge.core.RpcException;

/**
 * find_item: a busca de baús do FindIt (a tecla do NEI que destaca onde o item
 * está). O servidor varre os inventários em volta do jogador e devolve as
 * posições; aqui só pedimos e lemos a resposta que o FindIt guarda para
 * desenhar o contorno — uma consulta em vez de abrir baú por baú.
 *
 * <p>
 * Tudo por reflexão: sem o FindIt instalado, a classe não é registrada.
 */
final class FindIt {

    private static final String PKG = "com.gtnh.findit.";

    private FindIt() {}

    static void register(BridgeCore core) {
        core.register(new BridgeExtension() {

            @Override
            public String method() {
                return "find_item";
            }

            @Override
            public boolean async() {
                // espera a resposta do servidor sem travar o tick do jogo
                return true;
            }

            @Override
            public JsonElement handle(JsonObject p) {
                int timeout = p.has("timeoutMs") ? Json.requireInt(p, "timeoutMs") : 3000;
                return find(NeiIntegration.resolve(Json.requireString(p, "item")), timeout);
            }
        });
    }

    private static JsonObject find(ItemStack stack, int timeoutMs) {
        if (Minecraft.getMinecraft().thePlayer == null) {
            throw new RpcException("not_in_world", "o jogador não está num mundo");
        }
        try {
            Object highlighter = highlighter();
            Field expire = highlighter.getClass()
                .getDeclaredField("expireHighlight");
            Field positions = highlighter.getClass()
                .getDeclaredField("positions");
            expire.setAccessible(true);
            positions.setAccessible(true);
            long before = expire.getLong(highlighter);

            Constructor<?> req = Class.forName(PKG + "service.itemfinder.FindItemRequest")
                .getConstructor(ItemStack.class);
            Object channel = Class.forName(PKG + "FindItNetwork")
                .getField("CHANNEL")
                .get(null);
            Method send = channel.getClass()
                .getMethod("sendToServer", Class.forName("cpw.mods.fml.common.network.simpleimpl.IMessage"));
            send.invoke(channel, req.newInstance(stack.copy()));

            // A resposta chega pela rede e troca o prazo do destaque; sem troca = nada
            // achado ou o servidor recusou pelo cooldown de busca.
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end && expire.getLong(highlighter) == before) {
                Thread.sleep(50);
            }
            JsonObject o = new JsonObject();
            JsonObject it = new JsonObject();
            it.addProperty(
                "name",
                String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem())));
            it.addProperty("meta", stack.getItemDamage());
            it.addProperty("displayName", stack.getDisplayName());
            o.add("item", it);
            boolean answered = expire.getLong(highlighter) != before;
            o.addProperty("answered", answered);
            JsonArray arr = new JsonArray();
            Object list = positions.get(highlighter);
            if (answered && list instanceof List) {
                for (Object e : (List<?>) list) {
                    if (e instanceof ChunkPosition) {
                        ChunkPosition c = (ChunkPosition) e;
                        JsonObject j = new JsonObject();
                        j.addProperty("x", c.chunkPosX);
                        j.addProperty("y", c.chunkPosY);
                        j.addProperty("z", c.chunkPosZ);
                        arr.add(j);
                    }
                }
            }
            o.add("positions", arr);
            if (!answered) {
                o.addProperty(
                    "note",
                    "sem resposta: o item não está em nenhum inventário por perto, ou a busca está em cooldown");
            }
            return o;
        } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException e) {
            throw new RpcException("internal", "o FindIt mudou por dentro: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new RpcException("internal", "interrompido");
        } catch (ReflectiveOperationException e) {
            throw new RpcException("internal", "falha chamando o FindIt: " + e);
        }
    }

    private static Object highlighter() throws ReflectiveOperationException {
        Class<?> svc = Class.forName(PKG + "service.blockfinder.ClientBlockFindService");
        Object inst = svc.getMethod("getInstance")
            .invoke(null);
        Field f = svc.getDeclaredField("blockHighlighter");
        f.setAccessible(true);
        return f.get(inst);
    }
}
