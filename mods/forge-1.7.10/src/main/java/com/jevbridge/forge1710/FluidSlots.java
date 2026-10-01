package com.jevbridge.forge1710;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jevbridge.core.BridgeCore;
import com.jevbridge.core.BridgeExtension;
import com.jevbridge.core.Json;
import com.jevbridge.core.RpcException;

/**
 * fluid_slot: os tanques das GUIs do ModularUI (máquinas GT) não são Slot do
 * Container, são FluidSlotWidget — encher/esvaziar célula só clicando neles com
 * o recipiente no cursor. Sem "index" lista os tanques da tela aberta; com
 * "index" e "item" põe o recipiente no cursor, clica no tanque e devolve o que
 * sobrar no cursor ao inventário. Tudo por reflexão (sem depender do ModularUI).
 */
final class FluidSlots {

    private FluidSlots() {}

    static void register(BridgeCore core) {
        core.register(new BridgeExtension() {

            @Override
            public String method() {
                return "fluid_slot";
            }

            @Override
            public boolean async() {
                return false;
            }

            @Override
            public JsonElement handle(JsonObject p) {
                try {
                    return run(p);
                } catch (ReflectiveOperationException e) {
                    throw new RpcException("internal", "ModularUI mudou? " + e);
                }
            }
        });
    }

    private static List<Object> widgets() throws ReflectiveOperationException {
        Object screen = Minecraft.getMinecraft().currentScreen;
        if (screen == null) {
            throw new RpcException("no_container", "nenhuma GUI aberta");
        }
        Method gc;
        try {
            gc = screen.getClass()
                .getMethod("getContext");
        } catch (NoSuchMethodException e) {
            throw new RpcException("no_container", "a GUI aberta não é do ModularUI");
        }
        Object ctx = gc.invoke(screen);
        Object win = ctx.getClass()
            .getMethod("getMainWindow")
            .invoke(ctx);
        List<Object> out = new ArrayList<>();
        collect(win, out);
        return out;
    }

    private static boolean isFluidSlot(Object w) {
        for (Class<?> k = w.getClass(); k != null; k = k.getSuperclass()) {
            if (k.getName()
                .equals("com.gtnewhorizons.modularui.common.widget.FluidSlotWidget")) {
                return true;
            }
        }
        return false;
    }

    private static void collect(Object w, List<Object> out) throws ReflectiveOperationException {
        if (isFluidSlot(w)) {
            out.add(w);
        }
        Method ch;
        try {
            ch = w.getClass()
                .getMethod("getChildren");
        } catch (NoSuchMethodException e) {
            return;
        }
        Object kids = ch.invoke(w);
        if (kids instanceof List) {
            for (Object k : (List<?>) kids) {
                collect(k, out);
            }
        }
    }

    private static Field field(Object w, String name) throws NoSuchFieldException {
        for (Class<?> k = w.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // sobe
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static JsonObject describe(int i, Object w) throws ReflectiveOperationException {
        JsonObject o = new JsonObject();
        o.addProperty("index", i);
        // no cliente o fluido sincronizado fica no handler (lastStoredFluid é só do servidor)
        Object handler = field(w, "handler").get(w);
        FluidStack fs = handler == null ? null
            : (FluidStack) handler.getClass()
                .getMethod("getFluidStackInTank", int.class)
                .invoke(handler, field(w, "tank").getInt(w));
        o.addProperty("fluid", fs == null || fs.getFluid() == null ? null : fs.getLocalizedName());
        o.addProperty("amount", fs == null ? 0 : fs.amount);
        o.addProperty("canFill", field(w, "canFillSlot").getBoolean(w));
        o.addProperty("canDrain", field(w, "canDrainSlot").getBoolean(w));
        o.addProperty("phantom", field(w, "phantom").getBoolean(w));
        return o;
    }

    private static JsonObject run(JsonObject p) throws ReflectiveOperationException {
        List<Object> ws = widgets();
        if (!p.has("index")) {
            JsonArray arr = new JsonArray();
            for (int i = 0; i < ws.size(); i++) {
                arr.add(describe(i, ws.get(i)));
            }
            JsonObject o = new JsonObject();
            o.add("tanks", arr);
            return o;
        }
        int idx = Json.requireInt(p, "index");
        if (idx < 0 || idx >= ws.size()) {
            throw new RpcException("bad_params", "index fora: há " + ws.size() + " tanques");
        }
        Object w = ws.get(idx);
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP pl = mc.thePlayer;
        Container c = pl.openContainer;
        String want = Json.requireString(p, "item");
        int src = -1;
        for (int i = 0; i < c.inventorySlots.size(); i++) {
            Slot s = (Slot) c.inventorySlots.get(i);
            ItemStack st = s.getStack();
            if (s.inventory == pl.inventory && st != null
                && st.getItem() != null
                && (want.equals(Item.itemRegistry.getNameForObject(st.getItem()) + ":" + st.getItemDamage())
                    || want.equals(String.valueOf(Item.itemRegistry.getNameForObject(st.getItem()))))) {
                src = i;
                break;
            }
        }
        if (src < 0) {
            throw new RpcException("not_found", "não tenho " + want + " no inventário");
        }
        JsonObject before = describe(idx, w);
        mc.playerController.windowClick(c.windowId, src, 0, 0, pl); // recipiente no cursor
        try {
            w.getClass()
                .getMethod("onClick", int.class, boolean.class)
                .invoke(w, Json.getInt(p, "button", 0), false);
        } finally {
            ItemStack cur = pl.inventory.getItemStack();
            if (cur != null) {
                int dst = src;
                Slot s = (Slot) c.inventorySlots.get(src);
                if (s.getHasStack() && !(s.getStack()
                    .isItemEqual(cur) && ItemStack.areItemStackTagsEqual(s.getStack(), cur))) {
                    dst = -1;
                    for (int k = 0; k < c.inventorySlots.size(); k++) {
                        Slot t = (Slot) c.inventorySlots.get(k);
                        if (t.inventory == pl.inventory && t.getSlotIndex() < 36 && !t.getHasStack()) {
                            dst = k;
                            break;
                        }
                    }
                }
                if (dst >= 0) {
                    mc.playerController.windowClick(c.windowId, dst, 0, 0, pl);
                }
            }
        }
        JsonObject o = new JsonObject();
        o.add("before", before);
        ItemStack cur = pl.inventory.getItemStack();
        o.addProperty("cursorLeft", cur == null ? null : cur.stackSize + "x " + cur.getDisplayName());
        return o;
    }
}
