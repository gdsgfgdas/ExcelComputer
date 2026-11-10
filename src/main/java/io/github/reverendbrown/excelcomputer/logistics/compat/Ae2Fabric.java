package io.github.reverendbrown.excelcomputer.logistics.compat;

//? if ae2 && fabric {
/*import appeng.api.stacks.AEFluidKey;
import io.github.reverendbrown.excelcomputer.logistics.HubNetworks;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;

// AE2 on Fabric 1.20.1: the hubs' block entity is Ae2HubBlockEntity (AE2 finds
// devices by their block entity's type), and the fluid kind is a FluidVariant
// (AE2 counts fluids in droplets here, 81 to a millibucket).
public final class Ae2Fabric {
    private Ae2Fabric() {
    }

    public static void install() {
        HubNetworks.add(Ae2HubLink::new);
        HubNetworks.setEntityFactory(Ae2HubBlockEntity::new);
        Ae2HubLink.fluidKinds = new Ae2HubLink.FluidKinds() {
            @Override
            public Object kind(AEFluidKey key) {
                return key.toVariant();
            }

            @Override
            public AEFluidKey key(Object kind) {
                return AEFluidKey.of((FluidVariant) kind);
            }

            @Override
            public long perMillibucket() {
                return FluidConstants.BUCKET / 1000;
            }
        };
    }
}
*///?}
