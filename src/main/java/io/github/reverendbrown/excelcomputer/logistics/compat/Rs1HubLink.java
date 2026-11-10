package io.github.reverendbrown.excelcomputer.logistics.compat;

//? if rs1 {
/*import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.node.INetworkNode;
import com.refinedmods.refinedstorage.api.network.node.INetworkNodeManager;
import com.refinedmods.refinedstorage.api.network.node.INetworkNodeProxy;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.network.node.NetworkNode;
import com.refinedmods.refinedstorage.capability.NetworkNodeProxyCapability;
import io.github.reverendbrown.excelcomputer.ExcelComputer;
import io.github.reverendbrown.excelcomputer.logistics.FluidStore;
import io.github.reverendbrown.excelcomputer.logistics.HubNetworks;
import io.github.reverendbrown.excelcomputer.logistics.LogisticsHubBlockEntity;
import io.github.reverendbrown.excelcomputer.logistics.NetworkLink;
import io.github.reverendbrown.excelcomputer.logistics.NetworkStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;

// Refined Storage 1 (Forge 1.20.1): the hub is a network node. RS 1 keeps its nodes
// in a per-world node manager (saved by RS, so our node type is registered with RS
// when the game starts) and finds them through the block entity's network node
// capability (see LogisticsHubBlockEntity). Only loaded when Refined Storage is installed.
public final class Rs1HubLink implements NetworkLink {
    static final ResourceLocation ID = ExcelComputer.id("logistics_hub");
    // Energy the hub uses per tick while on a network.
    private static final int ENERGY_PER_TICK = 2;

    // The hub's node, as RS 1 stores it.
    public static final class HubNode extends NetworkNode {
        HubNode(Level level, BlockPos pos) {
            super(level, pos);
        }

        @Override
        public int getEnergyUsage() {
            return ENERGY_PER_TICK;
        }

        @Override
        public ResourceLocation getId() {
            return ID;
        }
    }

    private final LogisticsHubBlockEntity hub;
    private final INetworkNodeProxy<HubNode> proxy = this::node;
    // On the client RS only needs some node to look at (like its own block entities).
    private HubNode clientNode;
    // Whether the neighbouring networks were told about the hub yet (on its first tick).
    private boolean discovered;

    public Rs1HubLink(LogisticsHubBlockEntity hub) {
        this.hub = hub;
    }

    public static void install() {
        HubNetworks.add(Rs1HubLink::new);
        // RS reads saved nodes when a world loads, so it must know how to make ours by then.
        HubNetworks.addSetup(() -> API.instance().getNetworkNodeRegistry().add(ID, (tag, level, pos) -> {
            HubNode node = new HubNode(level, pos);
            node.read(tag);
            return node;
        }));
    }

    private HubNode node() {
        Level level = hub.getLevel();

        if (!(level instanceof ServerLevel server)) {
            if (clientNode == null && level != null) {
                clientNode = new HubNode(level, hub.getBlockPos());
            }

            return clientNode;
        }

        INetworkNodeManager manager = API.instance().getNetworkNodeManager(server);
        INetworkNode node = manager.getNode(hub.getBlockPos());

        if (node instanceof HubNode hubNode) {
            return hubNode;
        }

        HubNode created = new HubNode(server, hub.getBlockPos());
        manager.setNode(hub.getBlockPos(), created);
        manager.markForSaving();
        return created;
    }

    @Override
    public Object capability(Object capability) {
        return capability == NetworkNodeProxyCapability.NETWORK_NODE_PROXY_CAPABILITY ? proxy : null;
    }

    @Override
    public String label() {
        return "RS Network";
    }

    @Override
    public String modName() {
        return "Refined Storage";
    }

    @Override
    public void added() {
        if (hub.getLevel() instanceof ServerLevel) {
            node();
        }
    }

    @Override
    public void tick() {
        // A hub that appears without being placed (an upgrade, or one from before
        // this update) joins the networks of the RS blocks next to it.
        if (!discovered) {
            discovered = true;

            for (Direction side : Direction.values()) {
                BlockEntity neighbour = hub.getLevel().getBlockEntity(hub.getBlockPos().relative(side));

                if (neighbour == null) {
                    continue;
                }

                neighbour.getCapability(NetworkNodeProxyCapability.NETWORK_NODE_PROXY_CAPABILITY, side.getOpposite())
                        .ifPresent(other -> {
                            INetwork network = other.getNode() == null ? null : other.getNode().getNetwork();

                            if (network != null) {
                                network.getNodeGraph().invalidate(Action.PERFORM, network.getLevel(),
                                        network.getPosition());
                            }
                        });
            }
        }
    }

    @Override
    public void broken() {
        if (!(hub.getLevel() instanceof ServerLevel server)) {
            return;
        }

        INetworkNodeManager manager = API.instance().getNetworkNodeManager(server);
        INetworkNode node = manager.getNode(hub.getBlockPos());

        if (node instanceof HubNode) {
            manager.removeNode(hub.getBlockPos());
            manager.markForSaving();
            INetwork network = node.getNetwork();

            if (network != null) {
                network.getNodeGraph().invalidate(Action.PERFORM, network.getLevel(), network.getPosition());
            }
        }
    }

    @Override
    public void removed() {
        // RS keeps the node while the chunk is unloaded; broken() removes it for good.
    }

    @Override
    public boolean connected() {
        return node().getNetwork() != null;
    }

    @Override
    public String problem() {
        return node().getNetwork().canRun() ? null : "No power";
    }

    @Override
    public NetworkStorage storage() {
        INetwork network = node().getNetwork();

        return new NetworkStorage() {
            @Override
            public List<Held> contents() {
                List<Held> held = new ArrayList<>();

                for (StackListEntry<ItemStack> entry : network.getItemStorageCache().getList().getStacks()) {
                    ItemStack stack = entry.getStack();

                    if (!stack.isEmpty()) {
                        held.add(new Held(stack.copyWithCount(1), stack.getCount()));
                    }
                }

                return held;
            }

            @Override
            public long insert(ItemStack item, long amount, boolean simulate) {
                int count = (int) Math.min(amount, Integer.MAX_VALUE);
                ItemStack rest = network.insertItem(item, count, simulate ? Action.SIMULATE : Action.PERFORM);
                return count - rest.getCount();
            }

            @Override
            public long extract(ItemStack item, long amount, boolean simulate) {
                return network.extractItem(item, (int) Math.min(amount, Integer.MAX_VALUE),
                        simulate ? Action.SIMULATE : Action.PERFORM).getCount();
            }
        };
    }

    // RS 1 counts fluids in millibuckets with Forge FluidStacks, like the hub.
    @Override
    public FluidStore fluidStorage() {
        INetwork network = node().getNetwork();

        return new FluidStore() {
            @Override
            public List<Held> contents() {
                List<Held> held = new ArrayList<>();

                for (StackListEntry<FluidStack> entry : network.getFluidStorageCache().getList().getStacks()) {
                    FluidStack stack = entry.getStack();

                    if (!stack.isEmpty()) {
                        held.add(new Held(stack.getFluid(), new FluidStack(stack, 1), stack.getAmount()));
                    }
                }

                return held;
            }

            @Override
            public long insert(Object kind, long amount, boolean simulate) {
                int count = (int) Math.min(amount, Integer.MAX_VALUE);
                FluidStack rest = network.insertFluid((FluidStack) kind, count,
                        simulate ? Action.SIMULATE : Action.PERFORM);
                return count - rest.getAmount();
            }

            @Override
            public long extract(Object kind, long amount, boolean simulate) {
                return network.extractFluid((FluidStack) kind, (int) Math.min(amount, Integer.MAX_VALUE),
                        simulate ? Action.SIMULATE : Action.PERFORM).getAmount();
            }
        };
    }
}
*///?}
