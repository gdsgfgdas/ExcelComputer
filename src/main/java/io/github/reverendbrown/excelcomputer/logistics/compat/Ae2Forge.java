package io.github.reverendbrown.excelcomputer.logistics.compat;

//? if ae2 && forge {
/*import appeng.api.stacks.AEFluidKey;
import io.github.reverendbrown.excelcomputer.logistics.HubNetworks;
import net.minecraftforge.fluids.FluidStack;

// AE2 on Forge 1.20.1: the hubs' block entity is Ae2HubBlockEntity (AE2 finds
// devices by their block entity's type), and the fluid kind is Forge's FluidStack
// (AE2 counts fluids in millibuckets here).
public final class Ae2Forge {
    private Ae2Forge() {
    }

    public static void install() {
        HubNetworks.add(Ae2HubLink::new);
        HubNetworks.setEntityFactory(Ae2HubBlockEntity::new);
        Ae2HubLink.fluidKinds = new Ae2HubLink.FluidKinds() {
            @Override
            public Object kind(AEFluidKey key) {
                return key.toStack(1);
            }

            @Override
            public AEFluidKey key(Object kind) {
                return AEFluidKey.of((FluidStack) kind);
            }

            @Override
            public long perMillibucket() {
                return 1;
            }
        };
    }
}
*///?}
