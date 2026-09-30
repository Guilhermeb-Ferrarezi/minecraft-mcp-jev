package com.jevbridge.forge1710;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Vec3;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jevbridge.core.BridgeCore;
import com.jevbridge.core.BridgeExtension;
import com.jevbridge.core.Json;
import com.jevbridge.core.RpcException;

/**
 * Crafting como um jogador: clica nos slots do contêiner aberto (a grade 2x2 do
 * inventário, que não precisa de tela, ou a 3x3 de uma bancada aberta com
 * {@code use_block}). Pega a pilha, põe um item por clique direito em cada casa,
 * devolve o resto e tira o resultado com um clique por craft. O servidor valida cada
 * clique, então vale qualquer receita do modpack (GTNH incluído).
 *
 * <p>
 * Slots: 0 = resultado, 1..4 (inventário) ou 1..9 (bancada) = grade em ordem de
 * linha; as casas do jogador são achadas pelo índice no inventário.
 */
final class Crafting {

    private Crafting() {}

    static void register(BridgeCore core) {
        core.register(new Sync("craft") {

            @Override
            public JsonElement handle(JsonObject p) {
                return craft(p);
            }
        });
        core.register(new Sync("move_to_hotbar") {

            @Override
            public JsonElement handle(JsonObject p) {
                return withBogoFlag(
                    () -> moveToHotbar(Json.requireString(p, "item"), Json.getInt(p, "hotbarSlot", -1)));
            }
        });
        core.register(new Sync("container_list") {

            @Override
            public JsonElement handle(JsonObject p) {
                return containerList();
            }
        });
        core.register(new Sync("container_take") {

            @Override
            public JsonElement handle(JsonObject p) {
                return withBogoFlag(
                    () -> containerTake(Json.requireString(p, "item"), Math.max(1, Json.getInt(p, "count", 64))));
            }
        });
        core.register(new Sync("container_put") {

            @Override
            public JsonElement handle(JsonObject p) {
                return withBogoFlag(
                    () -> containerPut(Json.requireString(p, "item"), Math.max(1, Json.getInt(p, "count", 2304))));
            }
        });
        core.register(new Sync("drop_item") {

            @Override
            public JsonElement handle(JsonObject p) {
                return withBogoFlag(() -> dropItem(Json.requireString(p, "item"), Json.getInt(p, "stacks", 1)));
            }
        });
        core.register(new Sync("use_block") {

            @Override
            public JsonElement handle(JsonObject p) {
                int face = p.has("face") ? Json.requireInt(p, "face") : 1;
                if (face < 0 || face > 5) {
                    throw new RpcException(
                        "bad_params",
                        "face vai de 0 a 5 (0 baixo, 1 cima, 2 norte, 3 sul, 4 oeste, 5 leste)");
                }
                double[] def = FACE_CENTER[face];
                return useBlock(
                    Json.requireInt(p, "x"),
                    Json.requireInt(p, "y"),
                    Json.requireInt(p, "z"),
                    face,
                    p.has("hx") ? p.get("hx")
                        .getAsDouble() : def[0],
                    p.has("hy") ? p.get("hy")
                        .getAsDouble() : def[1],
                    p.has("hz") ? p.get("hz")
                        .getAsDouble() : def[2]);
            }
        });
        core.register(new Sync("dig_block") {

            @Override
            public JsonElement handle(JsonObject p) {
                return digBlock(
                    Json.requireInt(p, "x"),
                    Json.requireInt(p, "y"),
                    Json.requireInt(p, "z"),
                    p.has("face") ? Json.requireInt(p, "face") : 1,
                    p.has("stage") ? Json.requireString(p, "stage") : "start");
            }
        });
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
            return false;
        }
    }

    /**
     * Bancada 3x3: slot 0 é resultado de crafting e 1..9 uma grade de crafting de
     * 9 casas. Cobre a vanilla e as de mods (Crafting Station do Tinkers...).
     */
    static boolean isTable(Container c) {
        if (c instanceof ContainerWorkbench) {
            return true;
        }
        if (c == null || c.inventorySlots.size() < 10
            || !(c.getSlot(0) instanceof net.minecraft.inventory.SlotCrafting)) {
            return false;
        }
        net.minecraft.inventory.IInventory grid = c.getSlot(1).inventory;
        if (!(grid instanceof net.minecraft.inventory.InventoryCrafting) || grid.getSizeInventory() != 9) {
            return false;
        }
        for (int i = 1; i <= 9; i++) {
            if (c.getSlot(i).inventory != grid) {
                return false;
            }
        }
        return true;
    }

    private static EntityClientPlayerMP player() {
        EntityClientPlayerMP p = Minecraft.getMinecraft().thePlayer;
        if (p == null || Minecraft.getMinecraft().theWorld == null) {
            throw new RpcException("not_in_world", "o jogador não está num mundo");
        }
        return p;
    }

    /** Clique direito no bloco (abrir bancada, fornalha, baú), sem precisar mirar. */
    /** Ponto do clique (dentro do bloco, 0..1) no centro de cada face. */
    private static final double[][] FACE_CENTER = { { 0.5, 0, 0.5 }, { 0.5, 1, 0.5 }, { 0.5, 0.5, 0 }, { 0.5, 0.5, 1 },
        { 0, 0.5, 0.5 }, { 1, 0.5, 0.5 } };

    /**
     * Mineração direta por pacote, sem mira (bloco cercado por outros): stage
     * "start" manda o início da quebra, "finish" o fim. O servidor só confere a
     * distância e se passou tempo suficiente para a ferramenta da mão; se foi
     * cedo demais, o bloco fica e é só mandar start/finish de novo esperando mais.
     */
    private static JsonObject digBlock(int x, int y, int z, int face, String stage) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP p = player();
        double dist = Math.sqrt(p.getDistanceSq(x + 0.5, y + 0.5, z + 0.5));
        if (dist > mc.playerController.getBlockReachDistance() + 1) {
            throw new RpcException("too_far", "chegue mais perto (a " + Math.round(dist) + " blocos)");
        }
        JsonObject o = new JsonObject();
        o.addProperty(
            "block",
            mc.theWorld.getBlock(x, y, z)
                .getLocalizedName());
        if (mc.theWorld.isAirBlock(x, y, z)) {
            o.addProperty("broken", true);
            return o;
        }
        int status = "finish".equals(stage) ? 2 : 0;
        mc.getNetHandler()
            .addToSendQueue(new net.minecraft.network.play.client.C07PacketPlayerDigging(status, x, y, z, face));
        p.swingItem();
        // velocidade da ferramenta da mão no bloco (fração da quebra por tick)
        float rel = mc.theWorld.getBlock(x, y, z)
            .getPlayerRelativeBlockHardness(p, mc.theWorld, x, y, z);
        o.addProperty("progressPerTick", rel);
        o.addProperty("ticksNeeded", rel > 0 ? (int) Math.ceil(1.0 / rel) : -1);
        o.addProperty("broken", false);
        return o;
    }

    private static JsonObject useBlock(int x, int y, int z, int face, double hx, double hy, double hz) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP p = player();
        double dist = Math.sqrt(p.getDistanceSq(x + 0.5, y + 0.5, z + 0.5));
        if (dist > mc.playerController.getBlockReachDistance() + 1) {
            throw new RpcException("too_far", "chegue mais perto (a " + Math.round(dist) + " blocos)");
        }
        boolean used = mc.playerController.onPlayerRightClick(
            p,
            mc.theWorld,
            p.inventory.getCurrentItem(),
            x,
            y,
            z,
            face,
            Vec3.createVectorHelper(x + hx, y + hy, z + hz));
        if (used) {
            p.swingItem();
        }
        JsonObject o = new JsonObject();
        o.addProperty("used", used);
        o.addProperty(
            "block",
            mc.theWorld.getBlock(x, y, z)
                .getLocalizedName());
        o.addProperty("note", "a tela abre no tick seguinte; confira com get_state (guiOpen)");
        return o;
    }

    /**
     * Traz um item do inventário para a hotbar (a troca da tecla numérica no
     * vanilla: clique modo 2). hotbarSlot -1 = primeiro slot vazio da hotbar, ou o
     * selecionado se não houver vazio. Já na hotbar: só informa o slot.
     */
    private static JsonObject moveToHotbar(String want, int hotbarSlot) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP p = player();
        Container c = p.inventoryContainer;
        if (p.openContainer != c) {
            throw new RpcException("gui_open", "feche a tela antes (close_screen)");
        }
        for (int i = 0; i < 9; i++) {
            if (matches(p.inventory.mainInventory[i], want)) {
                JsonObject o = new JsonObject();
                o.addProperty("slot", i);
                o.addProperty("moved", false);
                return o;
            }
        }
        int from = -1;
        for (int i = 0; i < c.inventorySlots.size(); i++) {
            Slot s = (Slot) c.inventorySlots.get(i);
            if (s.inventory == p.inventory && s.getSlotIndex() >= 9
                && s.getSlotIndex() < 36
                && matches(s.getStack(), want)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            throw new RpcException("missing_items", "não tenho " + want);
        }
        int target = hotbarSlot;
        if (target < 0 || target > 8) {
            target = p.inventory.currentItem;
            for (int i = 0; i < 9; i++) {
                if (p.inventory.mainInventory[i] == null) {
                    target = i;
                    break;
                }
            }
        }
        mc.playerController.windowClick(c.windowId, from, target, 2, p);
        JsonObject o = new JsonObject();
        o.addProperty("slot", target);
        o.addProperty("moved", true);
        return o;
    }

    private static Container openNonPlayerContainer(EntityClientPlayerMP p) {
        Container c = p.openContainer;
        if (c == null || c == p.inventoryContainer) {
            throw new RpcException("no_container", "nenhum contêiner aberto; abra um baú/gaveta com use_block");
        }
        return c;
    }

    private static String id(ItemStack s) {
        return Item.itemRegistry.getNameForObject(s.getItem()) + ":" + s.getItemDamage();
    }

    /** Itens do contêiner aberto (só os slots que não são do jogador), somados por item. */
    private static JsonObject containerList() {
        EntityClientPlayerMP p = player();
        Container c = openNonPlayerContainer(p);
        java.util.Map<String, JsonObject> merged = new java.util.LinkedHashMap<>();
        int slots = 0;
        for (Object o : c.inventorySlots) {
            Slot s = (Slot) o;
            if (s.inventory == p.inventory) {
                continue;
            }
            slots++;
            ItemStack st = s.getStack();
            if (st == null || st.getItem() == null) {
                continue;
            }
            String key = id(st);
            JsonObject e = merged.get(key);
            if (e == null) {
                e = new JsonObject();
                e.addProperty("item", key);
                e.addProperty("displayName", st.getDisplayName());
                e.addProperty("count", 0);
                merged.put(key, e);
            }
            e.addProperty(
                "count",
                e.get("count")
                    .getAsInt() + st.stackSize);
        }
        JsonArray arr = new JsonArray();
        for (JsonObject e : merged.values()) {
            arr.add(e);
        }
        JsonObject out = new JsonObject();
        out.addProperty(
            "container",
            c.getClass()
                .getSimpleName());
        out.addProperty("slots", slots);
        out.add("items", arr);
        return out;
    }

    /** Tira até count do item do contêiner aberto para o inventário (shift-clique por pilha). */
    private static JsonObject containerTake(String want, int count) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP p = player();
        Container c = openNonPlayerContainer(p);
        int taken = 0;
        for (int i = 0; i < c.inventorySlots.size() && taken < count; i++) {
            Slot s = (Slot) c.inventorySlots.get(i);
            if (s.inventory == p.inventory || !matches(s.getStack(), want)) {
                continue;
            }
            int before = s.getStack().stackSize;
            if (before <= count - taken) {
                mc.playerController.windowClick(c.windowId, i, 0, 1, p); // pilha inteira
            } else {
                // Só parte: pega a pilha, devolve o excedente um a um... simples: clique esquerdo,
                // solta (count - taken) no inventário com clique direito em slots vazios.
                mc.playerController.windowClick(c.windowId, i, 0, 0, p);
                int need = count - taken;
                for (int k = 0; k < c.inventorySlots.size() && need > 0 && p.inventory.getItemStack() != null; k++) {
                    Slot t = (Slot) c.inventorySlots.get(k);
                    if (t.inventory == p.inventory && t.getSlotIndex() < 36 && !t.getHasStack()) {
                        while (need > 0 && p.inventory.getItemStack() != null) {
                            mc.playerController.windowClick(c.windowId, k, 1, 0, p);
                            need--;
                        }
                    }
                }
                if (p.inventory.getItemStack() != null) {
                    // O resto volta por shift-clique a partir de um slot vazio do jogador:
                    // clicar de volta no slot de origem não funciona em contêiner de mod
                    // (o compartment do Binnie ignora e a pilha inteira vinha junto).
                    int park = emptyPlayerSlot(c, p);
                    if (park >= 0) {
                        mc.playerController.windowClick(c.windowId, park, 0, 0, p);
                        mc.playerController.windowClick(c.windowId, park, 0, 1, p);
                    }
                }
            }
            ItemStack after = s.getStack();
            taken += before - (after == null ? 0 : after.stackSize);
        }
        JsonObject o = new JsonObject();
        o.addProperty("taken", taken);
        o.addProperty("item", want);
        return o;
    }

    /**
     * Solta pilhas inteiras do item na direção em que o jogador olha (o Ctrl+Q do
     * vanilla: clique modo 4, botão 1, no inventário). Serve para jogar pó no
     * caldeirão com água, dar item pra alguém etc.
     */
    private static JsonObject dropItem(String want, int stacks) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP p = player();
        Container c = p.inventoryContainer;
        if (p.openContainer != c) {
            throw new RpcException("gui_open", "feche a tela antes (close_screen)");
        }
        int dropped = 0, items = 0;
        for (int i = 0; i < c.inventorySlots.size() && dropped < stacks; i++) {
            Slot s = (Slot) c.inventorySlots.get(i);
            if (s.inventory != p.inventory || s.getSlotIndex() >= 36 || !matches(s.getStack(), want)) {
                continue;
            }
            int n = s.getStack().stackSize;
            mc.playerController.windowClick(c.windowId, i, 1, 4, p);
            if (!s.getHasStack()) {
                dropped++;
                items += n;
            }
        }
        if (dropped == 0) {
            throw new RpcException("missing_items", "não tenho " + want);
        }
        JsonObject o = new JsonObject();
        o.addProperty("stacks", dropped);
        o.addProperty("items", items);
        return o;
    }

    private static int emptyPlayerSlot(Container c, EntityClientPlayerMP p) {
        for (int k = 0; k < c.inventorySlots.size(); k++) {
            Slot t = (Slot) c.inventorySlots.get(k);
            if (t.inventory == p.inventory && t.getSlotIndex() < 36 && !t.getHasStack()) {
                return k;
            }
        }
        return -1;
    }

    /** Guarda até count do item do inventário no contêiner aberto (shift-clique por pilha). */
    private static JsonObject containerPut(String want, int count) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP p = player();
        Container c = openNonPlayerContainer(p);
        int put = 0;
        for (int i = 0; i < c.inventorySlots.size() && put < count; i++) {
            Slot s = (Slot) c.inventorySlots.get(i);
            if (s.inventory != p.inventory || s.getSlotIndex() >= 36 || !matches(s.getStack(), want)) {
                continue;
            }
            int before = s.getStack().stackSize;
            if (before > count - put) {
                continue; // pilha maior que o pedido: não divide (evita sobra no cursor)
            }
            mc.playerController.windowClick(c.windowId, i, 0, 1, p);
            ItemStack after = s.getStack();
            put += before - (after == null ? 0 : after.stackSize);
        }
        JsonObject o = new JsonObject();
        o.addProperty("put", put);
        o.addProperty("item", want);
        return o;
    }

    /** "modid:nome", "modid:nome:meta" ou o nome de exibição. */
    private static boolean matches(ItemStack s, String query) {
        if (s == null || s.getItem() == null) {
            return false;
        }
        String q = query.trim()
            .toLowerCase(Locale.ROOT);
        String reg = String.valueOf(Item.itemRegistry.getNameForObject(s.getItem()))
            .toLowerCase(Locale.ROOT);
        if (q.equals(reg) || q.equals(reg + ":" + s.getItemDamage())) {
            return true;
        }
        try {
            return q.equals(
                s.getDisplayName()
                    .toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * O BogoSorter (no GTNH) faz {@code Slot.canTakeStack} devolver false no
     * cliente, a não ser que {@code ShortcutHandler.SetCanTakeStack} esteja ligado
     * — ele liga só durante os cliques das próprias telas. Sem isto, nenhum clique
     * de pegar item funciona fora de uma GUI. O servidor não tem essa trava.
     */
    private static java.lang.reflect.Field bogoFlag() {
        try {
            java.lang.reflect.Field f = Class.forName("com.cleanroommc.bogosorter.ShortcutHandler")
                .getField("SetCanTakeStack");
            return f;
        } catch (Throwable t) {
            return null; // sem BogoSorter
        }
    }

    private static JsonObject craft(JsonObject params) {
        return withBogoFlag(() -> craftUnsafe(params));
    }

    private static JsonObject withBogoFlag(java.util.function.Supplier<JsonObject> body) {
        java.lang.reflect.Field flag = bogoFlag();
        boolean previous = false;
        try {
            if (flag != null) {
                previous = flag.getBoolean(null);
                flag.setBoolean(null, true);
            }
        } catch (IllegalAccessException e) {
            flag = null;
        }
        try {
            return body.get();
        } finally {
            if (flag != null) {
                try {
                    flag.setBoolean(null, previous);
                } catch (IllegalAccessException ignored) {}
            }
        }
    }

    private static JsonObject craftUnsafe(JsonObject params) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP p = player();
        JsonArray grid = params.getAsJsonArray("grid");
        if (grid == null || (grid.size() != 4 && grid.size() != 9)) {
            throw new RpcException(
                "bad_params",
                "grid = 4 casas (2x2) ou 9 (3x3), em ordem de linha, nome do item ou null");
        }
        int times = Math.max(1, Math.min(64, Json.getInt(params, "times", 1)));
        if (grid.size() == 9 && !isTable(p.openContainer)) {
            grid = shrinkTo2x2(grid); // receita do NEI (3x3) que cabe no inventário
        }
        Container c = p.openContainer;
        boolean table = isTable(c);
        if (!table && c != p.inventoryContainer) {
            throw new RpcException("gui_open", "tem outra tela aberta; feche (close_screen) ou abra uma bancada");
        }
        if (grid.size() == 9 && !table) {
            throw new RpcException("needs_table", "receita 3x3 precisa de bancada aberta: use_block na bancada antes");
        }
        int width = table ? 3 : 2;
        int cols = grid.size() == 9 ? 3 : 2;
        if (Json.getBool(params, "clearGrid", false)) {
            returnGrid(mc, c, p, width); // tira o que já estava na grade (ex.: Crafting Station) pro inventário
        }
        for (int i = 1; i <= width * width; i++) {
            if (c.getSlot(i)
                .getHasStack()) {
                throw new RpcException(
                    "grid_not_empty",
                    "a grade já tem itens; use clearGrid=true para tirá-los pro inventário");
            }
        }

        for (int i = 0; i < grid.size(); i++) {
            if (grid.get(i)
                .isJsonNull()) {
                continue;
            }
            String want = grid.get(i)
                .getAsString();
            if (want.isEmpty()) {
                continue;
            }
            int gridSlot = 1 + (i / cols) * width + (i % cols);
            int placed = 0;
            // Ferramenta (não empilha: martelo, lima, chave...) volta pra grade depois
            // de cada craft, então vai uma vez só, não uma por craft.
            int src0 = findSource(c, p, want);
            int perCell = src0 >= 0 && c.getSlot(src0)
                .getStack()
                .getMaxStackSize() == 1 ? 1 : times;
            for (int guard = 0; placed < perCell; guard++) {
                int from = findSource(c, p, want);
                if (from < 0) {
                    returnGrid(mc, c, p, width);
                    throw new RpcException(
                        "missing_items",
                        "faltou " + want + " (precisa de " + perCell + " por casa em que aparece)");
                }
                ItemStack clicked = mc.playerController.windowClick(c.windowId, from, 0, 0, p); // pega a pilha
                if (p.inventory.getItemStack() == null || guard > 64) {
                    Slot src = c.getSlot(from);
                    returnGrid(mc, c, p, width);
                    throw new RpcException(
                        "click_failed",
                        "o clique não pegou " + want
                            + " do slot "
                            + from
                            + " [container="
                            + c.getClass()
                                .getName()
                            + " window="
                            + c.windowId
                            + " slot="
                            + src.getClass()
                                .getName()
                            + " hasStack="
                            + src.getHasStack()
                            + " canTake="
                            + src.canTakeStack(p)
                            + " returned="
                            + clicked
                            + " controller="
                            + mc.playerController.getClass()
                                .getName()
                            + "]");
                }
                while (placed < perCell && p.inventory.getItemStack() != null) {
                    mc.playerController.windowClick(c.windowId, gridSlot, 1, 0, p); // põe 1
                    placed++;
                }
                if (p.inventory.getItemStack() != null) {
                    mc.playerController.windowClick(c.windowId, from, 0, 0, p); // devolve o resto
                }
            }
        }

        ItemStack result = c.getSlot(0)
            .getStack();
        if (result == null) {
            returnGrid(mc, c, p, width);
            throw new RpcException("no_recipe", "essa grade não forma nenhuma receita (veja get_recipes)");
        }
        String resultName = result.getDisplayName();
        String resultId = String.valueOf(Item.itemRegistry.getNameForObject(result.getItem()));
        int perCraft = result.stackSize;
        int before = count(p, resultId);
        // Um clique normal por craft (shift-clique no resultado repete em laço no
        // vanilla enquanto o resultado "parece" igual — já travou o jogo aqui).
        // O cursor junta os resultados; quando encher, descarrega no inventário.
        for (int n = 0; n < times; n++) {
            ItemStack r = c.getSlot(0)
                .getStack();
            if (r == null) {
                break;
            }
            ItemStack cursor = p.inventory.getItemStack();
            if (cursor != null && cursor.stackSize + r.stackSize > cursor.getMaxStackSize()) {
                dropCursorIntoInventory(mc, c, p);
            }
            mc.playerController.windowClick(c.windowId, 0, 0, 0, p);
        }
        dropCursorIntoInventory(mc, c, p);
        returnGrid(mc, c, p, width); // sobra (receita que não consome tudo igual)
        JsonObject o = new JsonObject();
        o.addProperty("status", "done");
        o.addProperty("crafted", resultName);
        o.addProperty("item", resultId);
        o.addProperty("perCraft", perCraft);
        o.addProperty("gained", count(p, resultId) - before);
        o.addProperty("grid", table ? "3x3 (bancada)" : "2x2 (inventário)");
        return o;
    }

    /** 3x3 cujos itens cabem num quadrado 2x2 vira 2x2 (sem bancada); senão fica 3x3. */
    private static JsonArray shrinkTo2x2(JsonArray g) {
        int minR = 3, minC = 3, maxR = -1, maxC = -1;
        for (int i = 0; i < 9; i++) {
            if (!g.get(i)
                .isJsonNull()) {
                minR = Math.min(minR, i / 3);
                maxR = Math.max(maxR, i / 3);
                minC = Math.min(minC, i % 3);
                maxC = Math.max(maxC, i % 3);
            }
        }
        if (maxR < 0 || maxR - minR > 1 || maxC - minC > 1) {
            return g;
        }
        JsonArray out = new JsonArray();
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                int rr = minR + r, cc = minC + c;
                out.add(rr > 2 || cc > 2 ? com.google.gson.JsonNull.INSTANCE : g.get(rr * 3 + cc));
            }
        }
        return out;
    }

    private static int count(EntityClientPlayerMP p, String id) {
        int n = 0;
        for (ItemStack s : p.inventory.mainInventory) {
            if (s != null && id.equals(String.valueOf(Item.itemRegistry.getNameForObject(s.getItem())))) {
                n += s.stackSize;
            }
        }
        return n;
    }

    /** Slot do contêiner com o item, só entre as casas do inventário do jogador. */
    private static int findSource(Container c, EntityClientPlayerMP p, String want) {
        for (int i = 0; i < c.inventorySlots.size(); i++) {
            Slot s = (Slot) c.inventorySlots.get(i);
            if (s.inventory == p.inventory && s.getSlotIndex() < 36 && matches(s.getStack(), want)) {
                return i;
            }
        }
        return -1;
    }

    /** Solta o que está no cursor: primeiro em pilha igual com espaço, senão em slot vazio. */
    private static void dropCursorIntoInventory(Minecraft mc, Container c, EntityClientPlayerMP p) {
        for (int pass = 0; pass < 2 && p.inventory.getItemStack() != null; pass++) {
            for (int i = 0; i < c.inventorySlots.size() && p.inventory.getItemStack() != null; i++) {
                Slot s = (Slot) c.inventorySlots.get(i);
                if (s.inventory != p.inventory || s.getSlotIndex() >= 36) {
                    continue;
                }
                ItemStack cur = p.inventory.getItemStack();
                ItemStack in = s.getStack();
                boolean fits = pass == 0
                    ? in != null && in.isItemEqual(cur)
                        && ItemStack.areItemStackTagsEqual(in, cur)
                        && in.stackSize < in.getMaxStackSize()
                    : in == null;
                if (fits) {
                    mc.playerController.windowClick(c.windowId, i, 0, 0, p);
                }
            }
        }
    }

    private static void returnGrid(Minecraft mc, Container c, EntityClientPlayerMP p, int width) {
        for (int i = 1; i <= width * width; i++) {
            if (c.getSlot(i)
                .getHasStack()) {
                mc.playerController.windowClick(c.windowId, i, 0, 1, p);
            }
        }
    }
}
