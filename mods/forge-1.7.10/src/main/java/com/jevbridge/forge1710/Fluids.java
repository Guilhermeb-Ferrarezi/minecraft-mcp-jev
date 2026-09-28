package com.jevbridge.forge1710;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jevbridge.core.BridgeCore;
import com.jevbridge.core.BridgeExtension;
import com.jevbridge.core.Json;
import com.jevbridge.core.RpcException;

/**
 * Tanques de fluido de um bloco (válvula de Iron Tank do Railcraft, máquinas do
 * GT, caldeiras): o que o Forge expõe por {@link IFluidHandler#getTankInfo}.
 *
 * <p>
 * Lê o TileEntity do mundo do cliente, então mostra o que o servidor sincronizou
 * com o cliente — para Iron Tank e máquinas do GT isso é o conteúdo real, mas
 * algum bloco que não sincroniza fluido pode aparecer vazio.
 */
final class Fluids {

    private Fluids() {}

    static void register(BridgeCore core) {
        core.register(new BridgeExtension() {

            @Override
            public String method() {
                return "get_fluids";
            }

            @Override
            public boolean async() {
                return false;
            }

            @Override
            public JsonElement handle(JsonObject p) {
                return fluids(Json.requireInt(p, "x"), Json.requireInt(p, "y"), Json.requireInt(p, "z"));
            }
        });
    }

    private static JsonObject fluids(int x, int y, int z) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            throw new RpcException("not_in_world", "o jogador não está num mundo");
        }
        TileEntity te = mc.theWorld.getTileEntity(x, y, z);
        JsonObject o = new JsonObject();
        o.addProperty(
            "block",
            mc.theWorld.getBlock(x, y, z)
                .getLocalizedName());
        if (!(te instanceof IFluidHandler)) {
            o.addProperty("fluidHandler", false);
            o.add("tanks", new JsonArray());
            return o;
        }
        o.addProperty("fluidHandler", true);
        JsonArray tanks = new JsonArray();
        FluidTankInfo[] info = null;
        try {
            info = ((IFluidHandler) te).getTankInfo(ForgeDirection.UNKNOWN);
        } catch (RuntimeException e) {
            o.addProperty("error", String.valueOf(e));
        }
        if (info != null) {
            for (FluidTankInfo t : info) {
                if (t == null) {
                    continue;
                }
                JsonObject j = new JsonObject();
                FluidStack f = t.fluid;
                j.addProperty(
                    "fluid",
                    f == null || f.getFluid() == null ? null
                        : f.getFluid()
                            .getName());
                j.addProperty("displayName", f == null || f.getFluid() == null ? null : f.getLocalizedName());
                j.addProperty("amount", f == null ? 0 : f.amount);
                j.addProperty("capacity", t.capacity);
                tanks.add(j);
            }
        }
        o.add("tanks", tanks);
        return o;
    }
}
