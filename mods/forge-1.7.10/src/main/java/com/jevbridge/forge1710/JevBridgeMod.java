package com.jevbridge.forge1710;

import java.io.IOException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.jevbridge.core.BridgeConfig;
import com.jevbridge.core.BridgeCore;
import com.jevbridge.core.BridgeLog;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Mod só de cliente: não faz nada num servidor dedicado e não exige que o
 * servidor tenha o mod ({@code acceptableRemoteVersions = "*"}).
 */
@Mod(
    modid = "jevbridge",
    name = "JevBridge",
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public class JevBridgeMod {

    public static final Logger LOG = LogManager.getLogger("JevBridge");

    private BridgeCore core;
    private Forge1710Adapter adapter;
    private boolean hadClient;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (!event.getSide()
            .isClient()) {
            return;
        }
        BridgeLog log = new BridgeLog() {

            @Override
            public void info(String message) {
                LOG.info(message);
            }

            @Override
            public void warn(String message, Throwable error) {
                LOG.warn(message, error);
            }
        };
        BridgeConfig config = BridgeConfig.load(Minecraft.getMinecraft().mcDataDir, log);
        if (!config.enabled) {
            LOG.info("JevBridge desativado em {}", config.file);
            return;
        }
        adapter = new Forge1710Adapter();
        core = new BridgeCore(adapter, log, Tags.VERSION);
        WorldControl.register(core);
        Crafting.register(core);
        Fluids.register(core);
        Guard.register(core);
        if (Loader.isModLoaded("gregtech")) {
            GtInfo.register(core);
            RecipeDump.register(core);
        }
        if (Loader.isModLoaded("NotEnoughItems")) {
            // Classe separada: sem NEI instalado, nenhuma classe do NEI é carregada.
            NeiIntegration.register(core);
            LOG.info("JevBridge: integração com o NEI ativa (search_items, get_recipes, get_usages)");
            if (Loader.isModLoaded("findit")) {
                FindIt.register(core);
                LOG.info("JevBridge: busca de baús do FindIt ativa (find_item)");
            }
        }
        try {
            core.start(config);
            LOG.info("JevBridge: token em {}", config.file);
        } catch (IOException e) {
            LOG.error("JevBridge: não consegui abrir a porta " + config.port + " (outra instância aberta?)", e);
            core = null;
            return;
        }
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance()
            .bus()
            .register(Guard.INSTANCE);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || core == null) {
            return;
        }
        // Fora de core.tick(): abrir um mundo trava a thread até o servidor subir.
        WorldControl.runPending();
        Minecraft mc = Minecraft.getMinecraft();
        boolean hasClient = core.hasClient();
        if (mc.thePlayer != null) {
            adapter.ensureInputHook();
            // Com a IA no controle, o jogo não pode pausar quando a janela perde o foco.
            if (hasClient && mc.gameSettings.pauseOnLostFocus) {
                mc.gameSettings.pauseOnLostFocus = false;
            }
            // Menu de pausa aberto antes do agente conectar (ex.: trocou de janela
            // antes) congelaria o single player: fecha uma vez, na conexão. Depois
            // disso o ESC do jogador é respeitado e funciona como "pausar o Jev".
            if (hasClient && !hadClient && mc.currentScreen instanceof GuiIngameMenu) {
                mc.displayGuiScreen(null);
            }
        }
        hadClient = hasClient;
        core.tick();
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (core != null && event.message != null) {
            core.onChat(event.message.getUnformattedText());
        }
    }
}
