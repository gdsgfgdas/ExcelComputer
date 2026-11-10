package io.github.reverendbrown.excelcomputer.logistics.compat;

//? if rs && neoforge && <1.21.10 {
/*import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import net.neoforged.neoforge.fluids.FluidStack;
import io.github.reverendbrown.excelcomputer.block.ModBlocks;
import io.github.reverendbrown.excelcomputer.logistics.HubNetworks;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

// Refined Storage on NeoForge: cables find devices through a block capability.
public final class RsNeoForge {
    private RsNeoForge() {
    }

    public static void install() {
        HubNetworks.add(RsHubLink::new);
        // RS on NeoForge counts fluids in millibuckets; the hub's fluid kind is a FluidStack.
        RsHubLink.fluidKinds = new RsHubLink.FluidKinds() {
            @Override
            public Object kind(FluidResource resource) {
                return new FluidStack(resource.fluid().builtInRegistryHolder(), 1, resource.components());
            }

            @Override
            public FluidResource resource(Object kind) {
                FluidStack stack = (FluidStack) kind;
                return new FluidResource(stack.getFluid(), stack.getComponentsPatch());
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
