package com.jevbridge.forge1710;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.monster.EntityPigZombie;
import net.minecraft.entity.monster.IMob;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jevbridge.core.BridgeCore;
import com.jevbridge.core.BridgeExtension;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Defesa automática: todo tick, se houver um mob hostil ao alcance do ataque,
 * troca para a arma (Kikoku, ou a configurada), bate e volta para o item que
 * estava na mão. Neutros que ficam bravos ao apanhar (Enderman, Zombie Pigman)
 * ficam de fora. Ligado por padrão; desliga com guard {enabled:false}.
 */
public final class Guard {

    static volatile boolean enabled = true;
    static volatile double reach = 3.8;
    static volatile String weapon = "ExtraUtilities:lawSword";

    private int cooldown;
    private int restoreSlot = -1;
    private int restoreIn;
    private long hits;

    static void register(BridgeCore core) {
        core.register(new BridgeExtension() {

            @Override
            public String method() {
                return "guard";
            }

            @Override
            public boolean async() {
                return false;
            }

            @Override
            public JsonElement handle(JsonObject p) {
                if (p.has("enabled")) {
                    enabled = p.get("enabled")
                        .getAsBoolean();
                }
                if (p.has("reach")) {
                    reach = Math.max(
                        1.5,
                        Math.min(
                            5.0,
                            p.get("reach")
                                .getAsDouble()));
                }
                if (p.has("weapon")) {
                    weapon = p.get("weapon")
                        .getAsString();
                }
                JsonObject o = new JsonObject();
                o.addProperty("enabled", enabled);
                o.addProperty("reach", reach);
                o.addProperty("weapon", weapon);
                o.addProperty("hits", INSTANCE.hits);
                return o;
            }
        });
    }

    static final Guard INSTANCE = new Guard();

    private int weaponSlot(EntityClientPlayerMP p) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.inventory.mainInventory[i];
            if (s != null && s.getItem() != null
                && weapon.equals(String.valueOf(Item.itemRegistry.getNameForObject(s.getItem())))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hostile(Entity e) {
        return e instanceof IMob && e instanceof EntityLivingBase
            && !(e instanceof EntityEnderman)
            && !(e instanceof EntityPigZombie)
            && e.isEntityAlive();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP p = mc.thePlayer;
        if (p == null || mc.theWorld == null || p.isDead) {
            return;
        }
        if (restoreSlot >= 0 && --restoreIn <= 0) {
            if (p.inventory.currentItem == weaponSlot(p)) {
                p.inventory.currentItem = restoreSlot; // só volta se ninguém trocou de slot no meio
            }
            restoreSlot = -1;
        }
        if (!enabled || cooldown-- > 0) {
            return;
        }
        Entity target = null;
        double best = reach * reach;
        @SuppressWarnings("unchecked")
        List<Entity> list = mc.theWorld
            .getEntitiesWithinAABBExcludingEntity(p, p.boundingBox.expand(reach, reach, reach));
        for (Entity e : list) {
            if (!hostile(e)) {
                continue;
            }
            double d = p.getDistanceSqToEntity(e);
            if (d < best) {
                best = d;
                target = e;
            }
        }
        if (target == null) {
            return;
        }
        int slot = weaponSlot(p);
        if (slot < 0) {
            return;
        }
        if (p.inventory.currentItem != slot) {
            if (restoreSlot < 0) {
                restoreSlot = p.inventory.currentItem;
            }
            p.inventory.currentItem = slot;
            mc.playerController.updateController(); // manda a troca de slot pro servidor antes do golpe
        }
        restoreIn = 10;
        mc.playerController.attackEntity(p, target);
        p.swingItem();
        hits++;
        cooldown = 6;
    }
}
