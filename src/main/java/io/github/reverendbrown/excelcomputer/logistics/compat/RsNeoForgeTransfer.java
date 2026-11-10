package io.github.reverendbrown.excelcomputer.logistics.compat;

//? if rs && neoforge && >=1.21.10 {
/*import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import io.github.reverendbrown.excelcomputer.block.ModBlocks;
import io.github.reverendbrown.excelcomputer.logistics.HubNetworks;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

// Refined Storage on NeoForge 26.x: cables find devices through a block capability.
// RS counts fluids in millibuckets; the hub's fluid kind is NeoForge's FluidResource
// (written out in full, since RS has a FluidResource class too).
public final class RsNeoForgeTransfer {
    private RsNeoForgeTransfer() {
    }

    public static void install() {
        HubNetworks.add(RsHubLink::new);
        RsHubLink.fluidKinds = new RsHubLink.FluidKinds() {
            @Override
            public Object kind(FluidResource resource) {
                return net.neoforged.neoforge.transfer.fluid.FluidResource.of(resource.fluid(), resource.components());
            }

            @Override
            public FluidResource resource(Object kind) {
                net.neoforged.neoforge.transfer.fluid.FluidResource fluid =
                        (net.neoforged.neoforge.transfer.fluid.FluidResource) kind;
                return new FluidResource(fluid.getFluid(), fluid.getComponentsPatch());
            }

            @Override
            public long perMillibucket() {
                return 1;
            }
        };
        HubNetworks.addCapabilityRegistrar(event -> ((RegisterCapabilitiesEvent) event).registerBlockEntity(
                RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
                ModBlocks.LOGISTICS_HUB_ENTITY,
                (hub, side) -> {
                    RsHubLink link = hub.link(RsHubLink.class);
                    return link == null ? null : link.containers();
                }));
    }
}
*///?}
