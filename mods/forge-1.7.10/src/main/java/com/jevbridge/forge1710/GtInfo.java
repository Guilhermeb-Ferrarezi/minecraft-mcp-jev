package com.jevbridge.forge1710;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jevbridge.core.BridgeCore;
import com.jevbridge.core.BridgeExtension;
import com.jevbridge.core.Json;
import com.jevbridge.core.RpcException;

/**
 * gt_info: o que o cliente sabe de um bloco do GregTech — para onde a máquina
 * está virada (front: a face de saída de um Battery Buffer, a entrada de um
 * transformador) e para quais lados um cabo/cano está ligado. O cliente
 * recebe isso do servidor para desenhar, então vale no multiplayer.
 *
 * <p>
 * Por reflexão: sem GregTech, responde not_gregtech.
 */
final class GtInfo {

    private GtInfo() {}

    static void register(BridgeCore core) {
        core.register(new BridgeExtension() {

            @Override
            public String method() {
                return "gt_info";
            }

            @Override
            public boolean async() {
                return false;
            }

            @Override
            public JsonElement handle(JsonObject p) {
                return info(Json.requireInt(p, "x"), Json.requireInt(p, "y"), Json.requireInt(p, "z"));
            }
        });
    }

    private static String side(int s) {
        ForgeDirection d = ForgeDirection.getOrientation(s);
        switch (d) {
            case DOWN:
                return "down(-y)";
            case UP:
                return "up(+y)";
            case NORTH:
                return "north(-z)";
            case SOUTH:
                return "south(+z)";
            case WEST:
                return "west(-x)";
            case EAST:
                return "east(+x)";
            default:
                return "unknown";
        }
    }

    private static int sideIndex(Object facing) {
        if (facing instanceof ForgeDirection) {
            return ((ForgeDirection) facing).ordinal();
        }
        if (facing instanceof Number) {
            return ((Number) facing).intValue();
        }
        return -1;
    }

    private static JsonObject info(int x, int y, int z) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            throw new RpcException("not_in_world", "o jogador não está num mundo");
        }
        TileEntity te = mc.theWorld.getTileEntity(x, y, z);
        JsonObject o = new JsonObject();
        o.addProperty(
            "block",
            mc.theWorld.getBlock(x, y, z)
                .getLocalizedName());
        if (te == null || !te.getClass()
            .getName()
            .startsWith("gregtech.")) {
            throw new RpcException("not_gregtech", "não é um bloco do GregTech");
        }
        o.addProperty(
            "tileClass",
            te.getClass()
                .getSimpleName());
        try {
            Method mte = te.getClass()
                .getMethod("getMetaTileEntity");
            Object meta = mte.invoke(te);
            if (meta != null) {
                o.addProperty(
                    "metaClass",
                    meta.getClass()
                        .getSimpleName());
            }
        } catch (ReflectiveOperationException ignored) {}
        try {
            Method ff = te.getClass()
                .getMethod("getFrontFacing");
            int f = sideIndex(ff.invoke(te));
            if (f >= 0) {
                o.addProperty("front", side(f));
            }
        } catch (ReflectiveOperationException ignored) {}
        Field conn = findField(te.getClass(), "mConnections");
        if (conn != null) {
            try {
                conn.setAccessible(true);
                int mask = ((Number) conn.get(te)).intValue();
                JsonArray arr = new JsonArray();
                for (int s = 0; s < 6; s++) {
                    if ((mask & (1 << s)) != 0) {
                        arr.add(new com.google.gson.JsonPrimitive(side(s)));
                    }
                }
                o.add("connections", arr);
                o.addProperty("connectionMask", mask);
            } catch (IllegalAccessException ignored) {}
        }
        return o;
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }
}
