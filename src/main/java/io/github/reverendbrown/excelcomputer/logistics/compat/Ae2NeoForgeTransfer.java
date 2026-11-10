package io.github.reverendbrown.excelcomputer.logistics.compat;

//? if ae2 && neoforge && >=1.21.10 {
/*import appeng.api.AECapabilities;
import appeng.api.stacks.AEFluidKey;
import io.github.reverendbrown.excelcomputer.block.ModBlocks;
import io.github.reverendbrown.excelcomputer.logistics.HubNetworks;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

// AE2 on NeoForge 26.x: cables find devices through a block capability, and the
// hub's fluid kind is NeoForge's FluidResource (AE2 counts fluids in millibuckets).
public final class Ae2NeoForgeTransfer {
    private Ae2NeoForgeTransfer() {
    }

    public static void install() {
        HubNetworks.add(Ae2HubLink::new);
        Ae2HubLink.fluidKinds = new Ae2HubLink.FluidKinds() {
            @Override
            public Object kind(AEFluidKey key) {
                return FluidResource.of(key.toStack(1));
            }

            @Override
            public AEFluidKey key(Object kind) {
                return AEFluidKey.of((FluidResource) kind);
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
