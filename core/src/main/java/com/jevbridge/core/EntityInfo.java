package com.jevbridge.core;

public final class EntityInfo {
    public final int id;
    /** Tipo da entidade, ex.: "Zombie", "Item", "minecraft:zombie" (varia por versão). */
    public final String type;
    public final String name;
    public final double x, y, z;
    /** Vida atual; NaN se não for entidade viva. */
    public final float health;
    public final boolean hostile;
    public final boolean player;
    /** Altura da hitbox, para mirar no meio do corpo. */
    public final float height;

    public EntityInfo(int id, String type, String name, double x, double y, double z,
                      float health, boolean hostile, boolean player, float height) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.health = health;
        this.hostile = hostile;
        this.player = player;
        this.height = height;
    }
}
