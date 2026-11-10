package io.github.reverendbrown.excelcomputer.logistics.compat;

//? if ae2 && neoforge && <1.21.10 {
/*import appeng.api.AECapabilities;
import appeng.api.stacks.AEFluidKey;
import io.github.reverendbrown.excelcomputer.block.ModBlocks;
import io.github.reverendbrown.excelcomputer.logistics.HubNetworks;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;

// AE2 on NeoForge 1.21.1: cables find devices through a block capability, and the
// hub's fluid kind is a FluidStack (AE2 counts fluids in millibuckets here).
public final class Ae2NeoForge {
    private Ae2NeoForge() {
    }

    public static void install() {
        HubNetworks.add(Ae2HubLink::new);
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
        HubNetworks.addCapabilityRegistrar(event -> ((RegisterCapabilitiesEvent) event).registerBlockEntity(
                AECapabilities.IN_WORLD_GRID_NODE_HOST, ModBlocks.LOGISTICS_HUB_ENTITY,
                (hub, context) -> hub.link(Ae2HubLink.class)));
    }
}
*///?}
