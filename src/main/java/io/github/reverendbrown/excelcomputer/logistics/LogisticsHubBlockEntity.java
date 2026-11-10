package io.github.reverendbrown.excelcomputer.logistics;

import io.github.reverendbrown.excelcomputer.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * A Logistics Hub's block entity. It holds no data (the hub's link, owner and
 * workbook are kept by position, see {@link LogisticsHubs}); it exists so the
 * hub can be a device on storage networks: Applied Energistics 2 and Refined
 * Storage attach their network nodes to block entities (see {@link NetworkLink}).
 * Made by {@link HubNetworks#newEntity}, which may make a subclass.
 *
 * <p>Hubs placed before this existed get theirs the first time the game looks
 * for it (the hub's cycle does, every few seconds).
 */
public class LogisticsHubBlockEntity extends BlockEntity {
    private final List<NetworkLink> links;
    private boolean added;
    // Set when the chunk unloads (Forge tells us), so removal there isn't taken as breaking.
    private boolean unloading;

    public LogisticsHubBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.LOGISTICS_HUB_ENTITY, pos, state);
        links = HubNetworks.create(this);
    }

    /** One per storage mod installed. */
    public List<NetworkLink> links() {
        return links;
    }

    /** This hub's link of the given kind, or null (that mod isn't installed). */
    public <T> T link(Class<T> type) {
        for (NetworkLink link : links) {
            if (type.isInstance(link)) {
                return type.cast(link);
            }
        }

        return null;
    }

    // Called when the block entity joins the level: placed, or its chunk loaded.
    @Override
    public void clearRemoved() {
        super.clearRemoved();

        if (!added && getLevel() != null) {
            added = true;
            unloading = false;

            for (NetworkLink link : links) {
                link.added();
            }
        }
    }

    // Called when the block is broken (or upgraded to the next tier) and when its chunk unloads.
    @Override
    public void setRemoved() {
        if (added) {
            added = false;

            for (NetworkLink link : links) {
                if (!unloading) {
                    link.broken();
                }

                link.removed();
            }
        }

        super.setRemoved();
    }

    void serverTick() {
        for (NetworkLink link : links) {
            link.tick();
        }
    }

    //? if forge {
    /*// Forge: the links' capabilities (Refined Storage finds its devices through one).
    private final java.util.Map<Object, net.minecraftforge.common.util.LazyOptional<?>> capabilities =
            new java.util.HashMap<>();

    @Override
    public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(
            net.minecraftforge.common.capabilities.Capability<T> capability, net.minecraft.core.Direction side) {
        for (NetworkLink link : links) {
            Object value = link.capability(capability);

            if (value != null) {
                return capabilities.computeIfAbsent(capability,
                        key -> net.minecraftforge.common.util.LazyOptional.of(() -> value)).cast();
            }
        }

        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();

        for (net.minecraftforge.common.util.LazyOptional<?> optional : capabilities.values()) {
            optional.invalidate();
        }

        capabilities.clear();
    }

    @Override
    public void onChunkUnloaded() {
        unloading = true;
        super.onChunkUnloaded();
    }
    *///?}
}
