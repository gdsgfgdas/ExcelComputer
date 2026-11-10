package io.github.reverendbrown.excelcomputer.logistics.compat;

//? if ae2 {
/*import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.StorageHelper;
import io.github.reverendbrown.excelcomputer.logistics.FluidStore;
import io.github.reverendbrown.excelcomputer.logistics.LogisticsHubBlockEntity;
import io.github.reverendbrown.excelcomputer.logistics.NetworkLink;
import io.github.reverendbrown.excelcomputer.logistics.NetworkStorage;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// Applied Energistics 2: the hub is an ME device. It uses a channel and a little
// power, like an ME Interface, and AE2 cables connect to any side. Only loaded when
// AE2 is installed (HubNetworks.init). How AE2 finds the hub depends on the loader
// (Ae2NeoForge, Ae2NeoForgeTransfer, Ae2Forge, Ae2Fabric), and so does the fluid type.
public final class Ae2HubLink implements NetworkLink, IInWorldGridNodeHost, IActionHost {
    // AE energy per tick while on a network.
    private static final double IDLE_POWER = 1;

    // How AE2 fluids map to the loader's own fluid kind and unit, set by the installer.
    public interface FluidKinds {
        // The loader's fluid kind (FluidStack, FluidResource, FluidVariant) for an AE2 fluid.
        Object kind(AEFluidKey key);

        AEFluidKey key(Object kind);

        // AE2 amount units in one millibucket (1, or 81 on Fabric).
        long perMillibucket();
    }

    static FluidKinds fluidKinds;

    private static final IGridNodeListener<Ae2HubLink> LISTENER = new IGridNodeListener<>() {
        @Override
        public void onSaveChanges(Ae2HubLink link, IGridNode node) {
            link.hub.setChanged();
        }
    };

    private final LogisticsHubBlockEntity hub;
    private final IManagedGridNode node;

    public Ae2HubLink(LogisticsHubBlockEntity hub) {
        this.hub = hub;
        node = GridHelper.createManagedNode(this, LISTENER)
                .setInWorldNode(true)
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(IDLE_POWER)
                .setTagName("excelcomputer_hub")
                .setVisualRepresentation(hub.getBlockState().getBlock());
    }

    @Override
    public String label() {
        return "ME Network";
    }

    @Override
    public String modName() {
        return "Applied Energistics 2";
    }

    @Override
    public void added() {
        // AE2 joins the node to the grid on the first tick, once the neighbours are loaded.
        if (hub.getLevel() instanceof ServerLevel level) {
            GridHelper.onFirstTick(hub, entity -> node.create(level, entity.getBlockPos()));
        }
    }

    @Override
    public void removed() {
        node.destroy();
    }

    // IInWorldGridNodeHost: the same node on every side.
    @Override
    public IGridNode getGridNode(Direction side) {
        return node.getNode();
    }

    // IActionHost: who moves the items (for AE2's own checks and logs).
    @Override
    public IGridNode getActionableNode() {
        return node.getNode();
    }

    @Override
    public boolean connected() {
        // On its own the hub is a grid of one.
        IGrid grid = node.getGrid();
        return grid != null && grid.size() > 1;
    }

    @Override
    public String problem() {
        if (!node.isPowered()) {
            return "No power";
        }

        return node.isActive() ? null : "No channel";
    }

    @Override
    public NetworkStorage storage() {
        IGrid grid = node.getGrid();
        IActionSource source = IActionSource.ofMachine(this);

        return new NetworkStorage() {
            @Override
            public List<Held> contents() {
                List<Held> held = new ArrayList<>();

                for (Object2LongMap.Entry<AEKey> entry : grid.getStorageService().getCachedInventory()) {
                    if (entry.getKey() instanceof AEItemKey item && entry.getLongValue() > 0) {
                        held.add(new Held(item.toStack(1), entry.getLongValue()));
                    }
                }

                return held;
            }

            @Override
            public long insert(ItemStack item, long amount, boolean simulate) {
                return move(grid, source, AEItemKey.of(item), amount, simulate, true);
            }

            @Override
            public long extract(ItemStack item, long amount, boolean simulate) {
                return move(grid, source, AEItemKey.of(item), amount, simulate, false);
            }
        };
    }

    @Override
    public FluidStore fluidStorage() {
        FluidKinds kinds = fluidKinds;

        if (kinds == null) {
            return null;
        }

        IGrid grid = node.getGrid();
        IActionSource source = IActionSource.ofMachine(this);
        long unit = kinds.perMillibucket();

        return new FluidStore() {
            @Override
            public List<Held> contents() {
                List<Held> held = new ArrayList<>();

                for (Object2LongMap.Entry<AEKey> entry : grid.getStorageService().getCachedInventory()) {
                    if (entry.getKey() instanceof AEFluidKey fluid && entry.getLongValue() >= unit) {
                        held.add(new Held(fluid.getFluid(), kinds.kind(fluid), entry.getLongValue() / unit));
                    }
                }

                return held;
            }

            @Override
            public long insert(Object kind, long amount, boolean simulate) {
                return whole(kinds.key(kind), amount, simulate, true);
            }

            @Override
            public long extract(Object kind, long amount, boolean simulate) {
                return whole(kinds.key(kind), amount, simulate, false);
            }

            // In whole millibuckets: if AE2 would move part of one, only the whole ones move.
            private long whole(AEFluidKey key, long amount, boolean simulate, boolean insert) {
                long units = move(grid, source, key, amount * unit, true, insert) / unit * unit;

                if (units <= 0) {
                    return 0;
                }

                return (simulate ? units : move(grid, source, key, units, false, insert)) / unit;
            }
        };
    }

    // Puts in or takes out, paying AE2's energy for it.
    private static long move(IGrid grid, IActionSource source, AEKey key, long amount, boolean simulate,
                             boolean insert) {
        if (key == null || amount <= 0) {
            return 0;
        }

        Actionable mode = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
        return insert
                ? StorageHelper.poweredInsert(grid.getEnergyService(), grid.getStorageService().getInventory(),
                key, amount, source, mode)
                : StorageHelper.poweredExtraction(grid.getEnergyService(), grid.getStorageService().getInventory(),
                key, amount, source, mode);
    }
}
*///?}
