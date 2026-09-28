package com.jevbridge.core;

/** Estado do jogador local num tick. Preenchido pelo adaptador de cada versão. */
public final class PlayerSnapshot {
    public String name;
    public double x, y, z;
    public float yaw, pitch;
    public float health, maxHealth;
    public int food;
    public float saturation;
    public int xpLevel;
    public String dimension;
    public String biome;
    public String gameMode;
    public boolean onGround, inWater, inLava, collidedHorizontally, dead;
    public int selectedSlot;
    public ItemInfo heldItem;
    public long worldTime;
    public boolean raining;
    /** Bloco na mira (ou null). */
    public BlockInfo lookingAtBlock;
    /** Entidade na mira (ou null). */
    public EntityInfo lookingAtEntity;
    public double eyeHeight = 1.62;
}
