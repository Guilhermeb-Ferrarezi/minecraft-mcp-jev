package com.jevbridge.forge1710;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jevbridge.core.BridgeCore;
import com.jevbridge.core.BridgeExtension;
import com.jevbridge.core.Json;
import com.jevbridge.core.RpcException;

import codechicken.nei.ItemList;
import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiUsageRecipe;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.TemplateRecipeHandler;

/**
 * Receitas e usos pelo NEI: a mesma informação que o jogador vê apertando R/U
 * num item, incluindo máquinas do GregTech (com EU/t e duração, lidos por
 * reflexão para não depender do GT na compilação).
 *
 * <p>
 * Só é carregada se o NEI estiver instalado ({@link #registerIfPresent}); as
 * referências a classes do NEI ficam todas aqui dentro.
 */
final class NeiIntegration {

    private static final int MAX_HANDLERS_LISTED = 25;

    private NeiIntegration() {}

    static void register(BridgeCore core) {
        core.register(new Async("search_items") {

            @Override
            public JsonElement handle(JsonObject p) {
                return searchItems(p);
            }
        });
        core.register(new Async("get_recipes") {

            @Override
            public JsonElement handle(JsonObject p) {
                return recipes(p, false);
            }
        });
        core.register(new Async("get_usages") {

            @Override
            public JsonElement handle(JsonObject p) {
                return recipes(p, true);
            }
        });
    }

    private abstract static class Async implements BridgeExtension {

        private final String method;

        Async(String method) {
            this.method = method;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public boolean async() {
            return true;
        }
    }

    // ------------------------------------------------------------ itens

    private static List<ItemStack> allItems() {
        List<ItemStack> items = ItemList.items;
        if (!ItemList.loadFinished || items == null || items.isEmpty()) {
            throw new RpcException(
                "not_ready",
                "o NEI ainda está carregando a lista de itens; tente em alguns segundos");
        }
        return items;
    }

    private static String displayName(ItemStack s) {
        try {
            return s.getDisplayName();
        } catch (RuntimeException e) {
            return String.valueOf(Item.itemRegistry.getNameForObject(s.getItem()));
        }
    }

    private static String registryName(ItemStack s) {
        return String.valueOf(Item.itemRegistry.getNameForObject(s.getItem()));
    }

    /** Encontra o item: "modid:nome[:meta]" pelo registro, senão pelo nome de exibição. */
    static ItemStack resolve(String query) {
        String q = query.trim();
        if (q.isEmpty()) {
            throw new RpcException("bad_params", "item vazio");
        }
        String[] parts = q.split(":");
        if (parts.length >= 2 && !q.contains(" ")) {
            Object item = Item.itemRegistry.getObject(parts[0] + ":" + parts[1]);
            if (item instanceof Item) {
                int meta = 0;
                if (parts.length >= 3) {
                    try {
                        meta = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException ignored) {}
                }
                return new ItemStack((Item) item, 1, meta);
            }
        }
        String lower = q.toLowerCase(Locale.ROOT);
        ItemStack best = null;
        String bestName = null;
        for (ItemStack s : allItems()) {
            String name = displayName(s);
            String ln = name.toLowerCase(Locale.ROOT);
            if (ln.equals(lower)) {
                return s;
            }
            // Sem nome exato: o nome mais curto que contém o texto ("iron ingot" antes de "iron ingot mold").
            if (ln.contains(lower) && (bestName == null || name.length() < bestName.length())) {
                best = s;
                bestName = name;
            }
        }
        if (best == null) {
            throw new RpcException("not_found", "nenhum item com '" + q + "' no nome (use search_items)");
        }
        return best;
    }

    private static JsonObject stackJson(ItemStack s) {
        JsonObject o = new JsonObject();
        o.addProperty("name", registryName(s));
        o.addProperty("meta", s.getItemDamage());
        o.addProperty("displayName", displayName(s));
        o.addProperty("count", s.stackSize);
        return o;
    }

    private static JsonObject searchItems(JsonObject p) {
        String q = Json.requireString(p, "query")
            .toLowerCase(Locale.ROOT)
            .trim();
        int limit = Math.max(1, Math.min(50, Json.getInt(p, "limit", 20)));
        JsonArray arr = new JsonArray();
        int total = 0;
        for (ItemStack s : allItems()) {
            String name = displayName(s);
            if (name.toLowerCase(Locale.ROOT)
                .contains(q)
                || registryName(s).toLowerCase(Locale.ROOT)
                    .contains(q)) {
                total++;
                if (arr.size() < limit) {
                    JsonObject o = stackJson(s);
                    o.remove("count");
                    arr.add(o);
                }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("totalMatches", total);
        o.add("items", arr);
        return o;
    }

    // ------------------------------------------------------------ receitas

    private static JsonObject recipes(JsonObject p, boolean usages) {
        // Sem a lista do NEI carregada (menu principal, logo ao entrar no mundo), os
        // handlers de receita respondem vazio: "0 receitas" levaria a IA a concluir
        // que o item não tem receita.
        allItems();
        ItemStack target = resolve(Json.requireString(p, "item"));
        int limit = Math.max(1, Math.min(20, Json.getInt(p, "limit", 5)));
        String handlerFilter = Json.getString(p, "handler", "")
            .toLowerCase(Locale.ROOT)
            .trim();

        List<? extends IRecipeHandler> handlers = usages ? GuiUsageRecipe.getUsageHandlers("item", target)
            : GuiCraftingRecipe.getCraftingHandlers("item", target);

        JsonArray out = new JsonArray();
        JsonArray summary = new JsonArray();
        int total = 0;
        for (IRecipeHandler h : handlers) {
            int n = h.numRecipes();
            if (n <= 0) {
                continue;
            }
            String handlerName = safeName(h);
            total += n;
            if (summary.size() < MAX_HANDLERS_LISTED) {
                JsonObject s = new JsonObject();
                s.addProperty("handler", handlerName);
                s.addProperty("recipes", n);
                summary.add(s);
            }
            if (!handlerFilter.isEmpty() && !handlerName.toLowerCase(Locale.ROOT)
                .contains(handlerFilter)) {
                continue;
            }
            for (int i = 0; i < n && out.size() < limit; i++) {
                try {
                    out.add(recipe(h, handlerName, i));
                } catch (RuntimeException e) {
                    // Handler de algum mod que não gosta de ser lido fora da GUI: pula a receita.
                }
            }
        }
        JsonObject o = new JsonObject();
        o.add("item", stackJson(target));
        o.addProperty("totalRecipes", total);
        o.add("byHandler", summary);
        o.add("recipes", out);
        return o;
    }

    private static String safeName(IRecipeHandler h) {
        try {
            String n = h.getRecipeName();
            return n == null ? h.getClass()
                .getSimpleName() : n;
        } catch (RuntimeException e) {
            return h.getClass()
                .getSimpleName();
        }
    }

    private static JsonObject recipe(IRecipeHandler h, String handlerName, int i) {
        JsonObject r = new JsonObject();
        r.addProperty("handler", handlerName);
        r.add("ingredients", stacks(h.getIngredientStacks(i)));
        Object cached = null;
        if (h instanceof TemplateRecipeHandler) {
            List<TemplateRecipeHandler.CachedRecipe> list = ((TemplateRecipeHandler) h).arecipes;
            if (list != null && i < list.size()) {
                cached = list.get(i);
            }
        }
        // Máquinas do GregTech guardam as saídas em mOutputs (o "result" do NEI fica vazio).
        Object gtOutputs = cached == null ? null : field(cached, "mOutputs");
        if (gtOutputs instanceof List) {
            @SuppressWarnings("unchecked")
            List<PositionedStack> outs = (List<PositionedStack>) gtOutputs;
            r.add("outputs", stacks(outs));
        } else {
            JsonArray outputs = new JsonArray();
            PositionedStack result = h.getResultStack(i);
            if (result != null && result.item != null) {
                outputs.add(stackJson(result.item));
            }
            r.add("outputs", outputs);
            JsonArray other = stacks(h.getOtherStacks(i));
            if (other.size() > 0) {
                // Ex.: na fornalha é o combustível.
                r.add("otherStacks", other);
            }
        }
        if (cached != null) {
            addGregTechInfo(r, cached);
        }
        return r;
    }

    /** Agrupa ingredientes iguais e soma quantidades; guarda alternativas do OreDictionary. */
    private static JsonArray stacks(List<PositionedStack> list) {
        Map<String, JsonObject> merged = new LinkedHashMap<>();
        if (list != null) {
            for (PositionedStack ps : list) {
                if (ps == null || ps.item == null) {
                    continue;
                }
                ItemStack s = ps.item;
                String key = registryName(s) + ":" + s.getItemDamage();
                JsonObject o = merged.get(key);
                if (o == null) {
                    o = stackJson(s);
                    if (ps.items != null && ps.items.length > 1) {
                        JsonArray alts = new JsonArray();
                        for (int k = 0; k < ps.items.length && alts.size() < 5; k++) {
                            if (ps.items[k] != null) {
                                alts.add(new com.google.gson.JsonPrimitive(displayName(ps.items[k])));
                            }
                        }
                        o.add("alternatives", alts);
                    }
                    merged.put(key, o);
                } else {
                    o.addProperty(
                        "count",
                        o.get("count")
                            .getAsInt() + s.stackSize);
                }
            }
        }
        JsonArray arr = new JsonArray();
        for (JsonObject o : new ArrayList<>(merged.values())) {
            arr.add(o);
        }
        return arr;
    }

    /** Receitas de máquina do GregTech: EU/t e duração ficam no objeto mRecipe do cache do NEI. */
    private static void addGregTechInfo(JsonObject r, Object cachedRecipe) {
        Object gtRecipe = field(cachedRecipe, "mRecipe");
        if (gtRecipe == null) {
            return;
        }
        Object eut = field(gtRecipe, "mEUt");
        Object duration = field(gtRecipe, "mDuration");
        if (eut instanceof Number) {
            r.addProperty("euPerTick", ((Number) eut).intValue());
        }
        if (duration instanceof Number) {
            r.addProperty("durationTicks", ((Number) duration).intValue());
        }
    }

    private static Object field(Object obj, String name) {
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                // tenta a superclasse
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
