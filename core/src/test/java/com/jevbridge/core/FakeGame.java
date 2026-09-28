package com.jevbridge.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mundo falso com física simplificada, para testar o núcleo sem Minecraft:
 * chão de pedra em y=63 (jogador pisa em y=64), blocos avulsos por cima,
 * andar a 0,2 bloco/tick, subir degrau de 1 bloco pulando e cair 0,5/tick.
 */
public class FakeGame implements GameAdapter {
    public static final int GROUND = 63;
    public static final int SIZE = 60;

    final Map<String, String> blocks = new HashMap<String, String>();
    final List<EntityInfo> entities = new ArrayList<EntityInfo>();
    final List<String> chat = new ArrayList<String>();
    final List<Integer> attacked = new ArrayList<Integer>();
    double x = 0.5, y = GROUND + 1, z = 0.5;
    float yaw, pitch;
    InputState input;
    boolean useHeld;
    int selected;
    /** Hotbar (slots 0-8); o item da mão é hotbar[selected]. */
    final String[] hotbar = new String[9];
    {
        hotbar[0] = "minecraft:dirt";
    }
    /** Jogo pausado (ESC no single player): nada se mexe. */
    boolean paused;
    /** Ticks restantes comendo/bebendo (usingItem do jogador). */
    int usingItemTicks;
    int eaten;
    int usedInAir;
    /** O botão direito segurado ativou o bloco na mira (abriu bancada/baú). */
    boolean activatedBlock;
    boolean inWorld = true;
    int mineProgress;
    String mining;
    boolean collided;
    boolean attackHeld;
    boolean guiOpen;
    boolean dead;
    /** Ticks segurando M1 para quebrar um bloco (o jogo real depende de dureza/ferramenta). */
    int ticksToBreak = 5;

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    public void set(int x, int y, int z, String name) {
        if (name == null) {
            blocks.remove(key(x, y, z));
        } else {
            blocks.put(key(x, y, z), name);
        }
    }

    String nameAt(int bx, int by, int bz) {
        String n = blocks.get(key(bx, by, bz));
        if (n != null) {
            return n;
        }
        if (by == 0) {
            return "minecraft:bedrock";
        }
        return by <= GROUND ? "minecraft:stone" : "minecraft:air";
    }

    /**
     * Um tick do "jogo", antes do núcleo — como o runTick do Minecraft: primeiro o
     * clique (quebra o que está na mira se M1 estiver segurado, e ZERA o progresso
     * se não estiver, igual ao vanilla), depois a física.
     */
    public void physicsTick() {
        if (paused) {
            return;
        }
        BlockInfo aim = guiOpen ? null : raytrace();
        // Como o runTick: botão direito segurado sem estar usando item clica no que
        // está na mira — numa bancada, abre a tela dela.
        if (useHeld && usingItemTicks == 0 && aim != null && !guiOpen) {
            activatedBlock = true;
            guiOpen = true;
        }
        if (usingItemTicks > 0 && --usingItemTicks == 0) {
            eaten++;
        }
        if (attackHeld && aim != null) {
            String k = key(aim.x, aim.y, aim.z);
            if (!k.equals(mining)) {
                mining = k;
                mineProgress = 0;
            }
            if (++mineProgress >= ticksToBreak) {
                set(aim.x, aim.y, aim.z, "minecraft:air");
                mining = null;
                mineProgress = 0;
            }
        } else {
            mining = null;
            mineProgress = 0;
        }
        movementTick();
    }

    /** Primeiro bloco não-ar na linha da mira, até o alcance. */
    BlockInfo raytrace() {
        double yawR = Math.toRadians(yaw), pitchR = Math.toRadians(pitch);
        double dx = -Math.sin(yawR) * Math.cos(pitchR);
        double dy = -Math.sin(pitchR);
        double dz = Math.cos(yawR) * Math.cos(pitchR);
        double ex = x, ey = y + 1.62, ez = z;
        for (double t = 0; t <= reach(); t += 0.02) {
            int bx = Geometry.floor(ex + dx * t), by = Geometry.floor(ey + dy * t), bz = Geometry.floor(ez + dz * t);
            String n = nameAt(bx, by, bz);
            if (!n.equals("minecraft:air") && !n.contains("water")) {
                return blockAt(bx, by, bz);
            }
        }
        return null;
    }

    private void movementTick() {
        int fx = Geometry.floor(x), fy = Geometry.floor(y + 1e-3), fz = Geometry.floor(z);
        boolean onGround = isSolid(fx, fy - 1, fz) && y - fy < 1e-3;
        collided = false;
        if (input != null && (input.forward != 0 || input.strafe != 0)) {
            double rad = Math.toRadians(yaw);
            double speed = input.sprint ? 0.28 : 0.2;
            double dx = (-Math.sin(rad) * input.forward + Math.cos(rad) * input.strafe) * speed;
            double dz = (Math.cos(rad) * input.forward + Math.sin(rad) * input.strafe) * speed;
            double nx = x + dx, nz = z + dz;
            int bx = Geometry.floor(nx), bz = Geometry.floor(nz);
            if (isPassable(bx, fy, bz) && isPassable(bx, fy + 1, bz)) {
                x = nx;
                z = nz;
            } else if (input.jump && onGround && isPassable(bx, fy + 1, bz) && isPassable(bx, fy + 2, bz)
                    && isPassable(fx, fy + 2, fz)) {
                x = nx;
                z = nz;
                y = fy + 1;
                return;
            } else {
                collided = true;
            }
        }
        fx = Geometry.floor(x);
        fz = Geometry.floor(z);
        boolean grounded = y == Math.floor(y) && isSolid(fx, (int) y - 1, fz);
        if (!grounded) {
            y -= 0.5;
            if (isSolid(fx, Geometry.floor(y), fz)) {
                y = Geometry.floor(y) + 1;
            }
        }
    }

    @Override
    public String minecraftVersion() {
        return "fake";
    }

    @Override
    public String loader() {
        return "test";
    }

    @Override
    public boolean inWorld() {
        return inWorld;
    }

    @Override
    public PlayerSnapshot player() {
        PlayerSnapshot s = new PlayerSnapshot();
        s.name = "Jev";
        s.x = x;
        s.y = y;
        s.z = z;
        s.yaw = yaw;
        s.pitch = pitch;
        s.health = 20;
        s.maxHealth = 20;
        s.food = 20;
        s.dimension = "Overworld";
        s.gameMode = "survival";
        int fx = Geometry.floor(x), fy = Geometry.floor(y + 1e-3), fz = Geometry.floor(z);
        s.onGround = isSolid(fx, fy - 1, fz) && y - fy < 1e-3;
        s.collidedHorizontally = collided;
        s.selectedSlot = selected;
        s.guiOpen = guiOpen;
        s.dead = dead;
        s.lookingAtBlock = guiOpen ? null : raytrace();
        String held = held();
        s.heldItem = held == null ? null : new ItemInfo(selected, held, -1, held, 64, 0, 0);
        s.usingItem = usingItemTicks > 0;
        return s;
    }

    @Override
    public List<ItemInfo> inventory() {
        List<ItemInfo> l = new ArrayList<ItemInfo>();
        for (int i = 0; i < hotbar.length; i++) {
            if (hotbar[i] != null) {
                l.add(new ItemInfo(i, hotbar[i], -1, hotbar[i], 64, 0, 0));
            }
        }
        return l;
    }

    @Override
    public BlockInfo blockAt(int bx, int by, int bz) {
        if (!isLoaded(bx, bz)) {
            return null;
        }
        String n = nameAt(bx, by, bz);
        boolean liquid = n.contains("water") || n.contains("lava");
        float hardness = n.contains("bedrock") ? -1 : 1.5f;
        return new BlockInfo(bx, by, bz, n, -1, n.replace("minecraft:", ""), isSolid(bx, by, bz), liquid, hardness);
    }

    @Override
    public boolean isSolid(int bx, int by, int bz) {
        String n = nameAt(bx, by, bz);
        return !n.equals("minecraft:air") && !n.contains("water") && !n.contains("lava") && !n.contains("tallgrass");
    }

    @Override
    public boolean isPassable(int bx, int by, int bz) {
        String n = nameAt(bx, by, bz);
        return n.equals("minecraft:air") || n.contains("water") || n.contains("tallgrass");
    }

    @Override
    public boolean isWater(int bx, int by, int bz) {
        return nameAt(bx, by, bz).contains("water");
    }

    @Override
    public boolean isDangerous(int bx, int by, int bz) {
        return nameAt(bx, by, bz).contains("lava");
    }

    @Override
    public boolean isLoaded(int bx, int bz) {
        return Math.abs(bx) <= SIZE && Math.abs(bz) <= SIZE;
    }

    @Override
    public List<EntityInfo> entities(double radius) {
        List<EntityInfo> l = new ArrayList<EntityInfo>();
        for (EntityInfo e : entities) {
            if (Geometry.dist(x, y, z, e.x, e.y, e.z) <= radius) {
                l.add(e);
            }
        }
        return l;
    }

    @Override
    public void setLook(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    @Override
    public void setInput(InputState input) {
        this.input = input;
    }

    @Override
    public void setAttackHeld(boolean held) {
        attackHeld = held;
    }

    @Override
    public boolean useOnBlock(int bx, int by, int bz, int face, double hitX, double hitY, double hitZ) {
        String held = held();
        if (held == null) {
            return false;
        }
        int[] d = Geometry.FACE_DIR[face];
        set(bx + d[0], by + d[1], bz + d[2], held);
        return true;
    }

    @Override
    public void setUseHeld(boolean held) {
        useHeld = held;
    }

    String held() {
        return hotbar[selected];
    }

    @Override
    public boolean useHeldItem() {
        String held = held();
        if (held == null) {
            return false;
        }
        usedInAir++;
        if (held.contains("bread") || held.contains("apple")) {
            usingItemTicks = 32;
        }
        return true;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    /** Machado quebra tronco 4x mais rápido; picareta, pedra. */
    @Override
    public float breakSpeed(int slot, int bx, int by, int bz) {
        String item = hotbar[slot], block = nameAt(bx, by, bz);
        if (item == null) {
            return 1f;
        }
        if (item.contains("_axe") && block.contains("log")) {
            return 4f;
        }
        if (item.contains("pickaxe") && (block.contains("stone") || block.contains("ore"))) {
            return 4f;
        }
        return 1f;
    }

    /** Pedra e minério só dropam com picareta. */
    @Override
    public boolean canHarvest(int slot, int bx, int by, int bz) {
        String block = nameAt(bx, by, bz);
        if (block.contains("stone") || block.contains("ore")) {
            return hotbar[slot] != null && hotbar[slot].contains("pickaxe");
        }
        return true;
    }

    @Override
    public boolean attack(int entityId) {
        attacked.add(entityId);
        return true;
    }

    @Override
    public void selectSlot(int slot) {
        selected = slot;
    }

    @Override
    public void sendChat(String message) {
        chat.add(message);
    }

    @Override
    public void respawn() {
        dead = false;
        guiOpen = false;
        x = 0.5;
        y = GROUND + 1;
        z = 0.5;
    }

    @Override
    public String closeScreen() {
        if (!guiOpen) {
            return null;
        }
        guiOpen = false;
        return "FakeScreen";
    }

    @Override
    public double reach() {
        return 4.5;
    }
}
