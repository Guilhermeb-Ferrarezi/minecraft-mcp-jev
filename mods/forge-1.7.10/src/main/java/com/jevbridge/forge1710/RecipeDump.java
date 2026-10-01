package com.jevbridge.forge1710;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jevbridge.core.BridgeCore;
import com.jevbridge.core.BridgeExtension;
import com.jevbridge.core.Json;
import com.jevbridge.core.RpcException;

/**
 * dump_recipes: grava num JSONL todas as receitas de máquina do GregTech
 * (RecipeMap.ALL_RECIPE_MAPS, com EU/t, tempo, chances e calor) e as de bancada
 * (vanilla + OreDictionary), com nomes de exibição — para quem não tem o jogo
 * (o proxy do chat) responder "como faz X". Só leitura.
 */
final class RecipeDump {

    private RecipeDump() {}

    static void register(BridgeCore core) {
        core.register(new BridgeExtension() {

            @Override
            public String method() {
                return "dump_recipes";
            }

            @Override
            public boolean async() {
                return true;
            }

            @Override
            public JsonElement handle(JsonObject p) {
                return dump(Json.requireString(p, "path"));
            }
        });
    }

    private static String name(ItemStack s) {
        if (s == null || s.getItem() == null) {
            return null;
        }
        try {
            ItemStack c = s.copy();
            if (c.getItemDamage() == 32767) {
                c.setItemDamage(0);
            }
            return c.getDisplayName();
        } catch (RuntimeException e) {
            return String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(s.getItem()));
        }
    }

    private static void addItem(JsonArray arr, Object o, int chance) {
        ItemStack s = null;
        if (o instanceof ItemStack) {
            s = (ItemStack) o;
        } else if (o instanceof List && !((List<?>) o).isEmpty() && ((List<?>) o).get(0) instanceof ItemStack) {
            s = (ItemStack) ((List<?>) o).get(0); // OreDictionary: o primeiro da lista representa
        }
        String n = name(s);
        if (n == null) {
            return;
        }
        JsonArray e = new JsonArray();
        e.add(new com.google.gson.JsonPrimitive(n));
        e.add(new com.google.gson.JsonPrimitive(s.stackSize));
        if (chance > 0 && chance < 10000) {
            e.add(new com.google.gson.JsonPrimitive(chance / 100.0));
        }
        arr.add(e);
    }

    private static JsonArray fluids(FluidStack[] fs) {
        JsonArray arr = new JsonArray();
        if (fs != null) {
            for (FluidStack f : fs) {
                if (f == null || f.getFluid() == null) {
                    continue;
                }
                JsonArray e = new JsonArray();
                e.add(new com.google.gson.JsonPrimitive(f.getLocalizedName()));
                e.add(new com.google.gson.JsonPrimitive(f.amount));
                arr.add(e);
            }
        }
        return arr;
    }

    private static JsonObject dump(String path) {
        int gt = 0, crafting = 0, maps = 0;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8)) {
            // GregTech
            try {
                Class<?> rm = Class.forName("gregtech.api.recipe.RecipeMap");
                Map<?, ?> all = (Map<?, ?>) rm.getField("ALL_RECIPE_MAPS")
                    .get(null);
                Method getAll = rm.getMethod("getAllRecipes");
                Field un = rm.getField("unlocalizedName");
                Class<?> r = Class.forName("gregtech.api.util.GTRecipe");
                Field fIn = r.getField("mInputs"), fOut = r.getField("mOutputs"), fFi = r.getField("mFluidInputs"),
                    fFo = r.getField("mFluidOutputs"), fCh = r.getField("mChances"), fT = r.getField("mDuration"),
                    fE = r.getField("mEUt"), fS = r.getField("mSpecialValue"), fH = r.getField("mHidden");
                for (Object map : all.values()) {
                    maps++;
                    String id = String.valueOf(un.get(map));
                    String machine = StatCollector.translateToLocal(id);
                    for (Object rec : (Collection<?>) getAll.invoke(map)) {
                        if (fH.getBoolean(rec)) {
                            continue;
                        }
                        JsonObject o = new JsonObject();
                        o.addProperty("m", machine);
                        JsonArray in = new JsonArray(), out = new JsonArray();
                        ItemStack[] ins = (ItemStack[]) fIn.get(rec), outs = (ItemStack[]) fOut.get(rec);
                        int[] ch = (int[]) fCh.get(rec);
                        if (ins != null) {
                            for (ItemStack s : ins) {
                                addItem(in, s, 0);
                            }
                        }
                        if (outs != null) {
                            for (int i = 0; i < outs.length; i++) {
                                addItem(out, outs[i], ch != null && i < ch.length ? ch[i] : 0);
                            }
                        }
                        o.add("in", in);
                        o.add("out", out);
                        JsonArray fi = fluids((FluidStack[]) fFi.get(rec)), fo = fluids((FluidStack[]) fFo.get(rec));
                        if (fi.size() > 0) {
                            o.add("fin", fi);
                        }
                        if (fo.size() > 0) {
                            o.add("fout", fo);
                        }
                        o.addProperty("eut", fE.getInt(rec));
                        o.addProperty("t", fT.getInt(rec));
                        int sv = fS.getInt(rec);
                        if (sv > 0) {
                            o.addProperty("sv", sv);
                        }
                        w.write(o.toString());
                        w.write('\n');
                        gt++;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new RpcException("internal", "falha lendo receitas do GregTech: " + e);
            }
            // bancada
            for (Object o : CraftingManager.getInstance()
                .getRecipeList()) {
                IRecipe rec = (IRecipe) o;
                Object[] ins = null;
                if (rec instanceof ShapedRecipes) {
                    ins = ((ShapedRecipes) rec).recipeItems;
                } else if (rec instanceof ShapelessRecipes) {
                    ins = ((ShapelessRecipes) rec).recipeItems.toArray();
                } else if (rec instanceof ShapedOreRecipe) {
                    ins = ((ShapedOreRecipe) rec).getInput();
                } else if (rec instanceof ShapelessOreRecipe) {
                    ins = ((ShapelessOreRecipe) rec).getInput()
                        .toArray();
                }
                ItemStack res = rec.getRecipeOutput();
                if (ins == null || res == null) {
                    continue;
                }
                JsonObject j = new JsonObject();
                j.addProperty("m", "Crafting");
                JsonArray in = new JsonArray(), out = new JsonArray();
                for (Object x : ins) {
                    addItem(in, x, 0);
                }
                addItem(out, res, 0);
                if (out.size() == 0) {
                    continue;
                }
                j.add("in", in);
                j.add("out", out);
                w.write(j.toString());
                w.write('\n');
                crafting++;
            }
        } catch (java.io.IOException e) {
            throw new RpcException("io_error", "não consegui gravar " + path + ": " + e.getMessage());
        }
        JsonObject o = new JsonObject();
        o.addProperty("path", path);
        o.addProperty("recipeMaps", maps);
        o.addProperty("gregtech", gt);
        o.addProperty("crafting", crafting);
        return o;
    }
}
