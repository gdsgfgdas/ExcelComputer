package io.github.reverendbrown.excelcomputer.logistics.compat;

//? if rs && fabric {
/*import com.refinedmods.refinedstorage.common.api.support.network.NetworkNodeContainerProvider;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import io.github.reverendbrown.excelcomputer.block.ModBlocks;
import io.github.reverendbrown.excelcomputer.logistics.HubNetworks;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

// Refined Storage on Fabric: cables find devices through a block API lookup. It is
// fetched by its id (the same lookup RS made), so this works whichever mod starts first.
public final class RsFabric {
    private RsFabric() {
    }

    public static void install() {
        HubNetworks.add(RsHubLink::new);
        // RS on Fabric counts fluids in droplets (81 to a millibucket); the hub's fluid kind is a FluidVariant.
        RsHubLink.fluidKinds = new RsHubLink.FluidKinds() {
            @Override
            public Object kind(FluidResource resource) {
                return FluidVariant.of(resource.fluid(), resource.components());
            }

            @Override
            public FluidResource resource(Object kind) {
                FluidVariant variant = (FluidVariant) kind;
                // Fabric API on 26.x calls it getComponentsPatch.
                //? if >=26.1 {
                /^return new FluidResource(variant.getFluid(), variant.getComponentsPatch());
                ^///?} else {
                return new FluidResource(variant.getFluid(), variant.getComponents());
                //?}
            }

            @Override
            public long perMillibucket() {
                return FluidConstants.BUCKET / 1000;
            }
        };
        BlockApiLookup.get(ResourceLocation.fromNamespaceAndPath("refinedstorage", "network_node_container_provider"),
                        NetworkNodeContainerProvider.class, Direction.class)
                .registerForBlockEntity((hub, side) -> {
                    RsHubLink link = hub.link(RsHubLink.class);
                    return link == null ? null : link.containers();
                }, ModBlocks.LOGISTICS_HUB_ENTITY);
    }
}
*///?}
