package com.jevbridge.core;

public final class BlockInfo {
    public final int x, y, z;
    /** Id de registro, ex.: "minecraft:stone". Em versões < 1.13 o tipo exato também depende de {@link #meta}. */
    public final String name;
    /** Metadata/dano do bloco em versões < 1.13; -1 quando a versão não usa metadata. */
    public final int meta;
    /** Nome traduzido, ex.: "Oak Wood". Mais estável entre versões que o id. */
    public final String displayName;
    public final boolean solid;
    public final boolean liquid;
    /** Dureza; negativa = inquebrável (bedrock). */
    public final float hardness;

    public BlockInfo(int x, int y, int z, String name, int meta, String displayName,
                     boolean solid, boolean liquid, float hardness) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.name = name;
        this.meta = meta;
        this.displayName = displayName;
        this.solid = solid;
        this.liquid = liquid;
        this.hardness = hardness;
    }

    public boolean isAir() {
        return name == null || name.endsWith(":air") || name.equals("air");
    }
}
