package com.jevbridge.forge1710;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.client.ClientCommandHandler;

import com.jevbridge.core.BlockInfo;
import com.jevbridge.core.EntityInfo;
import com.jevbridge.core.GameAdapter;
import com.jevbridge.core.InputState;
import com.jevbridge.core.ItemInfo;
import com.jevbridge.core.PlayerSnapshot;

/**
 * Tradução do {@link GameAdapter} para o Minecraft 1.7.10 (Forge 10.13, nomes MCP).
 *
 * <p>
 * Pegadinhas da 1.7.10 tratadas aqui:
 * <ul>
 * <li>No cliente, {@code thePlayer.posY} já inclui a altura dos olhos (yOffset 1.62); os pés são
 * {@code boundingBox.minY}.</li>
 * <li>{@code ChunkProviderClient.chunkExists} sempre diz true; chunk não carregado é um {@code EmptyChunk}.</li>
 * <li>Minérios do GregTech são um único bloco com o tipo no TileEntity; o nome certo vem de
 * {@code getPickBlock}, que lê o TileEntity.</li>
 * </ul>
 */
public final class Forge1710Adapter implements GameAdapter {

    private final Minecraft mc = Minecraft.getMinecraft();
    private BotMovementInput botInput;
    private InputState input;
    private boolean useHeld;
    /** Nome de exibição por bloco+meta, para blocos sem TileEntity (o caso comum na varredura). */
    private final Map<Long, String> displayNameCache = new HashMap<>();

    @Override
    public String minecraftVersion() {
        return "1.7.10";
    }

    @Override
    public String loader() {
        return "forge";
    }

    @Override
    public boolean inWorld() {
        return mc.theWorld != null && mc.thePlayer != null;
    }

    /** Chamado a cada tick: o objeto do jogador é recriado ao morrer/trocar de dimensão. */
    void ensureInputHook() {
        EntityClientPlayerMP p = mc.thePlayer;
        if (p == null) {
            return;
        }
        if (!(p.movementInput instanceof BotMovementInput)) {
            botInput = new BotMovementInput(p.movementInput);
            p.movementInput = botInput;
        }
        botInput.bot = input;
        if (input != null && input.sprint
            && input.forward > 0
            && p.getFoodStats()
                .getFoodLevel() > 6) {
            p.setSprinting(true);
        }
        // Reafirma o botão direito todo tick: perder o foco da janela solta todas as teclas.
        if (useHeld) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
        }
    }

    // ------------------------------------------------------------ jogador

    @Override
    public PlayerSnapshot player() {
        EntityClientPlayerMP p = mc.thePlayer;
        WorldClient w = mc.theWorld;
        PlayerSnapshot s = new PlayerSnapshot();
        s.name = p.getCommandSenderName();
        s.x = p.posX;
        s.y = p.boundingBox.minY;
        s.z = p.posZ;
        s.eyeHeight = 1.62;
        s.yaw = p.rotationYaw;
        s.pitch = p.rotationPitch;
        s.health = p.getHealth();
        s.maxHealth = p.getMaxHealth();
        s.food = p.getFoodStats()
            .getFoodLevel();
        s.saturation = p.getFoodStats()
            .getSaturationLevel();
        s.xpLevel = p.experienceLevel;
        s.dimension = w.provider.getDimensionName();
        try {
            s.biome = w.getBiomeGenForCoords((int) Math.floor(p.posX), (int) Math.floor(p.posZ)).biomeName;
        } catch (RuntimeException ignored) {
            s.biome = null;
        }
        s.gameMode = mc.playerController.isInCreativeMode() ? "creative" : "survival";
        s.onGround = p.onGround;
        s.inWater = p.isInWater();
        s.inLava = p.handleLavaMovement();
        s.collidedHorizontally = p.isCollidedHorizontally;
        s.dead = p.isDead || p.getHealth() <= 0;
        s.selectedSlot = p.inventory.currentItem;
        s.heldItem = item(p.inventory.currentItem, p.getHeldItem());
        s.worldTime = w.getWorldTime();
        s.raining = w.isRaining();
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop != null) {
            if (mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                s.lookingAtBlock = blockAt(mop.blockX, mop.blockY, mop.blockZ);
            } else if (mop.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && mop.entityHit != null) {
                s.lookingAtEntity = entity(mop.entityHit);
            }
        }
        return s;
    }

    @Override
    public List<ItemInfo> inventory() {
        List<ItemInfo> out = new ArrayList<>();
        ItemStack[] main = mc.thePlayer.inventory.mainInventory;
        for (int i = 0; i < main.length; i++) {
            ItemInfo info = item(i, main[i]);
            if (info != null) {
                out.add(info);
            }
        }
        ItemStack[] armor = mc.thePlayer.inventory.armorInventory;
        for (int i = 0; i < armor.length; i++) {
            ItemInfo info = item(36 + i, armor[i]);
            if (info != null) {
                out.add(info);
            }
        }
        return out;
    }

    private static ItemInfo item(int slot, ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        String name = Item.itemRegistry.getNameForObject(stack.getItem());
        String display;
        try {
            display = stack.getDisplayName();
        } catch (RuntimeException e) {
            display = name;
        }
        int maxDamage = stack.isItemStackDamageable() ? stack.getMaxDamage() : 0;
        return new ItemInfo(
            slot,
            name,
            stack.getHasSubtypes() ? stack.getItemDamage() : -1,
            display,
            stack.stackSize,
            stack.isItemStackDamageable() ? stack.getItemDamage() : 0,
            maxDamage);
    }

    // ------------------------------------------------------------ blocos

    @Override
    public BlockInfo blockAt(int x, int y, int z) {
        WorldClient w = mc.theWorld;
        if (y < 0 || y > 255) {
            return new BlockInfo(x, y, z, "minecraft:air", -1, "Air", false, false, 0);
        }
        if (!isLoaded(x, z)) {
            return null;
        }
        Block b = w.getBlock(x, y, z);
        int meta = w.getBlockMetadata(x, y, z);
        String name = Block.blockRegistry.getNameForObject(b);
        if (b.getMaterial() == Material.air) {
            return new BlockInfo(x, y, z, name == null ? "minecraft:air" : name, -1, "Air", false, false, 0);
        }
        float hardness;
        try {
            hardness = b.getBlockHardness(w, x, y, z);
        } catch (RuntimeException e) {
            hardness = 1;
        }
        return new BlockInfo(
            x,
            y,
            z,
            name,
            meta,
            displayName(b, meta, x, y, z),
            isSolid(x, y, z),
            b.getMaterial()
                .isLiquid(),
            hardness);
    }

    private String displayName(Block b, int meta, int x, int y, int z) {
        boolean hasTile = b.hasTileEntity(meta);
        long key = ((long) Block.getIdFromBlock(b) << 16) | (meta & 0xFFFF);
        if (!hasTile) {
            String cached = displayNameCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        String name;
        try {
            MovingObjectPosition target = new MovingObjectPosition(
                x,
                y,
                z,
                1,
                Vec3.createVectorHelper(x + 0.5, y + 1, z + 0.5));
            ItemStack pick = b.getPickBlock(target, mc.theWorld, x, y, z);
            name = pick != null && pick.getItem() != null ? pick.getDisplayName() : b.getLocalizedName();
        } catch (Throwable t) {
            // Mods fazem de tudo em getPickBlock; nunca deixar a varredura quebrar.
            name = b.getLocalizedName();
        }
        if (!hasTile) {
            displayNameCache.put(key, name);
        }
        return name;
    }

    private AxisAlignedBB collision(int x, int y, int z) {
        Block b = mc.theWorld.getBlock(x, y, z);
        try {
            return b.getCollisionBoundingBoxFromPool(mc.theWorld, x, y, z);
        } catch (RuntimeException e) {
            return AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + 1, z + 1);
        }
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        if (y < 0 || y > 255 || !isLoaded(x, z)) {
            return false;
        }
        AxisAlignedBB box = collision(x, y, z);
        return box != null && box.maxY - y >= 0.5;
    }

    @Override
    public boolean isPassable(int x, int y, int z) {
        if (y > 255) {
            return true;
        }
        if (y < 0 || !isLoaded(x, z)) {
            return false;
        }
        Block b = mc.theWorld.getBlock(x, y, z);
        if (b.getMaterial() == Material.lava) {
            return false;
        }
        AxisAlignedBB box = collision(x, y, z);
        // Tapete e neve fina têm caixa de colisão, mas dá pra ficar em cima deles.
        return box == null || box.maxY - y <= 0.2;
    }

    @Override
    public boolean isWater(int x, int y, int z) {
        return y >= 0 && y <= 255
            && isLoaded(x, z)
            && mc.theWorld.getBlock(x, y, z)
                .getMaterial() == Material.water;
    }

    @Override
    public boolean isDangerous(int x, int y, int z) {
        if (y < 0 || y > 255 || !isLoaded(x, z)) {
            return false;
        }
        Block b = mc.theWorld.getBlock(x, y, z);
        Material m = b.getMaterial();
        return m == Material.lava || m == Material.fire || b == Blocks.cactus || b == Blocks.web;
    }

    @Override
    public boolean isLoaded(int x, int z) {
        Chunk c = mc.theWorld.getChunkFromChunkCoords(x >> 4, z >> 4);
        return c != null && !c.isEmpty();
    }

    // ------------------------------------------------------------ entidades

    @Override
    public List<EntityInfo> entities(double radius) {
        EntityClientPlayerMP p = mc.thePlayer;
        List<?> raw = mc.theWorld.getEntitiesWithinAABBExcludingEntity(p, p.boundingBox.expand(radius, radius, radius));
        List<EntityInfo> out = new ArrayList<>();
        for (Object o : raw) {
            Entity e = (Entity) o;
            if (e.getDistanceToEntity(p) <= radius) {
                out.add(entity(e));
            }
        }
        return out;
    }

    private static EntityInfo entity(Entity e) {
        String type = EntityList.getEntityString(e);
        if (type == null) {
            type = e instanceof EntityPlayer ? "Player"
                : e.getClass()
                    .getSimpleName();
        }
        String name = e.getCommandSenderName();
        if (e instanceof EntityItem) {
            ItemStack s = ((EntityItem) e).getEntityItem();
            if (s != null) {
                name = s.getDisplayName() + " x" + s.stackSize;
            }
        }
        float health = e instanceof EntityLivingBase ? ((EntityLivingBase) e).getHealth() : Float.NaN;
        return new EntityInfo(
            e.getEntityId(),
            type,
            name,
            e.posX,
            e.boundingBox.minY,
            e.posZ,
            health,
            e instanceof IMob,
            e instanceof EntityPlayer,
            e.height);
    }

    // ------------------------------------------------------------ ações

    @Override
    public void setLook(float yaw, float pitch) {
        mc.thePlayer.rotationYaw = yaw;
        mc.thePlayer.rotationPitch = Math.max(-90f, Math.min(90f, pitch));
    }

    @Override
    public void setInput(InputState state) {
        this.input = state;
        if (botInput != null) {
            botInput.bot = state;
        }
        if (state == null && mc.thePlayer != null) {
            mc.thePlayer.setSprinting(false);
        }
    }

    @Override
    public void mineTick(int x, int y, int z, int face) {
        mc.playerController.onPlayerDamageBlock(x, y, z, face);
        mc.thePlayer.swingItem();
    }

    @Override
    public void stopMining() {
        if (mc.playerController != null) {
            mc.playerController.resetBlockRemoving();
        }
    }

    @Override
    public boolean useOnBlock(int x, int y, int z, int face, double hitX, double hitY, double hitZ) {
        EntityClientPlayerMP p = mc.thePlayer;
        ItemStack held = p.inventory.getCurrentItem();
        boolean used = mc.playerController
            .onPlayerRightClick(p, mc.theWorld, held, x, y, z, face, Vec3.createVectorHelper(hitX, hitY, hitZ));
        if (used) {
            p.swingItem();
        }
        if (held != null && held.stackSize == 0) {
            p.inventory.mainInventory[p.inventory.currentItem] = null;
        }
        return used;
    }

    @Override
    public void setUseHeld(boolean held) {
        if (held == useHeld) {
            return;
        }
        useHeld = held;
        int key = mc.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(key, held);
        if (held) {
            KeyBinding.onTick(key);
        }
    }

    @Override
    public boolean attack(int entityId) {
        Entity e = mc.theWorld.getEntityByID(entityId);
        if (e == null) {
            return false;
        }
        mc.playerController.attackEntity(mc.thePlayer, e);
        mc.thePlayer.swingItem();
        return true;
    }

    @Override
    public void selectSlot(int slot) {
        mc.thePlayer.inventory.currentItem = slot;
    }

    @Override
    public void sendChat(String message) {
        if (message.startsWith("/") && ClientCommandHandler.instance.executeCommand(mc.thePlayer, message) != 0) {
            return;
        }
        mc.thePlayer.sendChatMessage(message);
    }

    @Override
    public double reach() {
        return mc.playerController.getBlockReachDistance();
    }

    /**
     * Substitui o MovementInput do jogador. Sem ação do bot, repassa o teclado
     * real; com ação, ignora o teclado. Funciona com a janela sem foco, ao
     * contrário de simular teclas.
     */
    static final class BotMovementInput extends MovementInput {

        private final MovementInput keyboard;
        volatile InputState bot;

        BotMovementInput(MovementInput keyboard) {
            this.keyboard = keyboard;
        }

        @Override
        public void updatePlayerMoveState() {
            InputState s = bot;
            if (s == null) {
                keyboard.updatePlayerMoveState();
                moveForward = keyboard.moveForward;
                moveStrafe = keyboard.moveStrafe;
                jump = keyboard.jump;
                sneak = keyboard.sneak;
                return;
            }
            moveForward = s.forward;
            moveStrafe = s.strafe;
            jump = s.jump;
            sneak = s.sneak;
            if (sneak) {
                moveForward *= 0.3f;
                moveStrafe *= 0.3f;
            }
        }
    }
}
