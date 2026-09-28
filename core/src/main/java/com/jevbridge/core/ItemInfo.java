package com.jevbridge.core;

public final class ItemInfo {
    /** Índice no inventário do jogador: 0-8 hotbar, 9-35 inventário, 36-39 armadura. */
    public final int slot;
    public final String name;
    public final int meta;
    public final String displayName;
    public final int count;
    public final int damage;
    public final int maxDamage;

    public ItemInfo(int slot, String name, int meta, String displayName, int count, int damage, int maxDamage) {
        this.slot = slot;
        this.name = name;
        this.meta = meta;
        this.displayName = displayName;
        this.count = count;
        this.damage = damage;
        this.maxDamage = maxDamage;
    }
}
