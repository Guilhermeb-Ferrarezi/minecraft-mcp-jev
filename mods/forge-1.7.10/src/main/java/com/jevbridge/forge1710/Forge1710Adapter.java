package com.jevbridge.forge1710;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
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

import cpw.mods.fml.relauncher.ReflectionHelper;

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
    private boolean attackHeld;
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

    /** Single player pausa de verdade; no multiplayer o menu do ESC também conta como "pausar o Jev". */
    @Override
    public boolean isPaused() {
        return mc.isGamePaused() || mc.currentScreen instanceof GuiIngameMenu;
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
        // Reafirma os botões todo tick: abrir uma tela solta todas as teclas.
        if (useHeld) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
            holdOffRightClickRepeat();
        }
        if (attackHeld) {
            holdAttack();
        }
    }

    /**
     * O vanilla só processa o M1 segurado com {@code inGameHasFocus}; ele fica
     * false se uma tela foi fechada com a janela em segundo plano. Marcar como
     * focado não captura o mouse: isso só acontece com a janela ativa.
     */
    private void holdAttack() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), true);
        if (mc.currentScreen == null) {
            mc.inGameHasFocus = true;
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
        s.usingItem = p.isUsingItem();
        s.guiOpen = mc.currentScreen != null;
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
    public void setAttackHeld(boolean held) {
        if (held == attackHeld) {
            if (held) {
                holdAttack();
            }
            return;
        }
        attackHeld = held;
        if (held) {
            holdAttack();
        } else {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
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

    /**
     * Só o estado "segurado", sem {@code KeyBinding.onTick}: o onTick vira um
     * clique no próximo runTick, e o clique do vanilla ativa o bloco na mira
     * (bancada, baú) antes de usar o item. O uso começa em {@link #useHeldItem}.
     */
    @Override
    public void setUseHeld(boolean held) {
        if (held == useHeld) {
            return;
        }
        useHeld = held;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), held);
        if (held) {
            holdOffRightClickRepeat();
        }
    }

    /**
     * Com o botão direito segurado e o jogador sem usar item, o runTick repete o
     * clique quando {@code rightClickDelayTimer} chega a 0 — e o clique do vanilla
     * ativa o bloco na mira. O fim de "comer" chega por pacote antes da leitura
     * dos botões, então sempre sobra um tick com o botão segurado e o uso já
     * encerrado: sem isto, comer olhando para uma bancada abre a bancada no fim.
     * Mantém o contador acima de 0 enquanto o bot segura o botão.
     */
    private void holdOffRightClickRepeat() {
        try {
            ReflectionHelper.setPrivateValue(Minecraft.class, mc, 4, "rightClickDelayTimer", "field_71467_ac");
        } catch (RuntimeException e) {
            // Campo renomeado por algum mod: sem a trava, o uso ainda funciona.
        }
    }

    /** O mesmo caminho do clique direito no ar do vanilla (sem bloco nem entidade na mira). */
    @Override
    public boolean useHeldItem() {
        EntityClientPlayerMP p = mc.thePlayer;
        ItemStack held = p.inventory.getCurrentItem();
        if (held == null) {
            return false;
        }
        boolean used = mc.playerController.sendUseItem(p, mc.theWorld, held);
        if (used) {
            mc.entityRenderer.itemRenderer.resetEquippedProgress2();
        }
        return used || p.isUsingItem();
    }

    @Override
    public float breakSpeed(int slot, int x, int y, int z) {
        ItemStack stack = mc.thePlayer.inventory.mainInventory[slot];
        if (stack == null || stack.getItem() == null) {
            return 1f;
        }
        Block b = mc.theWorld.getBlock(x, y, z);
        int meta = mc.theWorld.getBlockMetadata(x, y, z);
        try {
            // getDigSpeed é do Forge: as ferramentas do GregTech respondem por ele.
            return stack.getItem()
                .getDigSpeed(stack, b, meta);
        } catch (RuntimeException e) {
            return stack.func_150997_a(b);
        }
    }

    /** Mesma regra do {@code ForgeHooks.canHarvestBlock}, mas para um slot qualquer da hotbar. */
    @Override
    public boolean canHarvest(int slot, int x, int y, int z) {
        ItemStack stack = mc.thePlayer.inventory.mainInventory[slot];
        Block b = mc.theWorld.getBlock(x, y, z);
        int meta = mc.theWorld.getBlockMetadata(x, y, z);
        try {
            if (b.getMaterial()
                .isToolNotRequired()) {
                return true;
            }
            String tool = b.getHarvestTool(meta);
            if (stack == null || stack.getItem() == null || tool == null) {
                return stack != null && stack.func_150998_b(b);
            }
            int level = stack.getItem()
                .getHarvestLevel(stack, tool);
            if (level < 0) {
                return stack.func_150998_b(b);
            }
            return level >= b.getHarvestLevel(meta);
        } catch (RuntimeException e) {
            return true; // na dúvida não impede a escolha; a quebra real decide
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

    /** O mesmo que o botão da GuiGameOver. */
    @Override
    public void respawn() {
        mc.thePlayer.respawnPlayer();
        mc.displayGuiScreen(null);
    }

    @Override
    public String closeScreen() {
        GuiScreen screen = mc.currentScreen;
        if (screen == null) {
            return null;
        }
        String name = screen.getClass()
            .getSimpleName();
        if (screen instanceof GuiContainer && mc.thePlayer != null) {
            mc.thePlayer.closeScreen(); // avisa o servidor: devolve o que estava na grade da bancada
        } else {
            mc.displayGuiScreen(null);
        }
        mc.setIngameFocus();
        return name;
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
