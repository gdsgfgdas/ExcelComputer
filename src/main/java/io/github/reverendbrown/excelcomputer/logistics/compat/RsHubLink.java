package io.github.reverendbrown.excelcomputer.logistics.compat;

//? if rs {
/*import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.impl.node.SimpleNetworkNode;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.NetworkNodeActor;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.network.NetworkNodeContainerProvider;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.reverendbrown.excelcomputer.logistics.FluidStore;
import io.github.reverendbrown.excelcomputer.logistics.LogisticsHubBlockEntity;
import io.github.reverendbrown.excelcomputer.logistics.NetworkLink;
import io.github.reverendbrown.excelcomputer.logistics.NetworkStorage;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// Refined Storage 2: the hub is a network device (like an Importer), so RS cables
// connect to it. Only loaded when Refined Storage is installed (HubNetworks.init).
// The loader-specific part (how RS finds the device) is in RsNeoForge / RsFabric.
public final class RsHubLink implements NetworkLink {
    // Energy the hub uses per tick while on a network (an RS Interface uses 2).
    private static final long ENERGY_PER_TICK = 2;

    private final LogisticsHubBlockEntity hub;
    private final SimpleNetworkNode node = new SimpleNetworkNode(ENERGY_PER_TICK);
    private final NetworkNodeContainerProvider containers;

    public RsHubLink(LogisticsHubBlockEntity hub) {
        this.hub = hub;
        containers = RefinedStorageApi.INSTANCE.createNetworkNodeContainerProvider();
        containers.addContainer(RefinedStorageApi.INSTANCE.createNetworkNodeContainer(hub, node).build());
    }

    // What RS asks the hub's block entity for (through the loader's capability / API lookup).
    public NetworkNodeContainerProvider containers() {
        return containers;
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
        // Does nothing on the client.
        containers.initialize(hub.getLevel(), () -> {
        });
    }

    @Override
    public void removed() {
        containers.remove(hub.getLevel());
    }

    @Override
    public void tick() {
        Network network = node.getNetwork();
        boolean active = network != null && (!energyRequired()
                || network.getComponent(EnergyNetworkComponent.class).getStored() >= ENERGY_PER_TICK);

        if (node.isActive() != active) {
            node.setActive(active);
        }

        node.doWork();
    }

    // Whether RS's config makes devices need energy. Older RS 2 (e.g. 2.0.0, in ATM10)
    // has no such setting, so energy is always required there.
    private static Boolean energyRequired;

    private static boolean energyRequired() {
        if (energyRequired == null) {
            try {
                energyRequired = RefinedStorageApi.INSTANCE.isEnergyRequired();
            } catch (NoSuchMethodError | AbstractMethodError error) {
                energyRequired = true;
            }
        }

        return energyRequired;
    }

    @Override
    public boolean connected() {
        // On its own the hub is a network of one.
        Network network = node.getNetwork();
        return network != null && network.getComponent(GraphNetworkComponent.class).getContainers().size() > 1;
    }

    @Override
    public String problem() {
        return node.isActive() ? null : "No power";
    }

    @Override
    public NetworkStorage storage() {
        StorageNetworkComponent storage = node.getNetwork().getComponent(StorageNetworkComponent.class);
        Actor actor = new NetworkNodeActor(node);

        return new NetworkStorage() {
            @Override
            public List<Held> contents() {
                List<Held> held = new ArrayList<>();

                for (ResourceAmount amount : storage.getAll()) {
                    if (amount.resource() instanceof ItemResource item && amount.amount() > 0) {
                        held.add(new Held(item.toItemStack(1), amount.amount()));
                    }
                }

                return held;
            }

            @Override
            public long insert(ItemStack item, long amount, boolean simulate) {
                return storage.insert(ItemResource.ofItemStack(item), amount,
                        simulate ? Action.SIMULATE : Action.EXECUTE, actor);
            }

            @Override
            public long extract(ItemStack item, long amount, boolean simulate) {
                return storage.extract(ItemResource.ofItemStack(item), amount,
                        simulate ? Action.SIMULATE : Action.EXECUTE, actor);
            }
        };
    }

    // How RS fluids map to the loader's own fluid kind and unit, set by RsNeoForge / RsFabric.
    public interface FluidKinds {
        // The loader's fluid kind (FluidStack, FluidVariant) for an RS fluid.
        Object kind(FluidResource resource);

        FluidResource resource(Object kind);

        // RS amount units in one millibucket (1 on NeoForge, 81 on Fabric).
        long perMillibucket();
    }

    static FluidKinds fluidKinds;

    @Override
    public FluidStore fluidStorage() {
        FluidKinds kinds = fluidKinds;

        if (kinds == null) {
            return null;
        }

        StorageNetworkComponent storage = node.getNetwork().getComponent(StorageNetworkComponent.class);
        Actor actor = new NetworkNodeActor(node);
        long unit = kinds.perMillibucket();

        return new FluidStore() {
            @Override
            public List<Held> contents() {
                List<Held> held = new ArrayList<>();

                for (ResourceAmount amount : storage.getAll()) {
                    if (amount.resource() instanceof FluidResource fluid && amount.amount() >= unit) {
                        held.add(new Held(fluid.fluid(), kinds.kind(fluid), amount.amount() / unit));
                    }
                }

                return held;
            }

            @Override
            public long insert(Object kind, long amount, boolean simulate) {
                return whole(kinds.resource(kind), amount, simulate, true);
            }

            @Override
            public long extract(Object kind, long amount, boolean simulate) {
                return whole(kinds.resource(kind), amount, simulate, false);
            }

            // In whole millibuckets: if RS would move part of one, only the whole ones move.
            private long whole(FluidResource resource, long amount, boolean simulate, boolean insert) {
                long units = run(resource, amount * unit, Action.SIMULATE, insert) / unit * unit;

                if (units <= 0) {
                    return 0;
                }

                return (simulate ? units : run(resource, units, Action.EXECUTE, insert)) / unit;
            }

            private long run(FluidResource resource, long units, Action action, boolean insert) {
                return insert ? storage.insert(resource, units, action, actor)
                        : storage.extract(resource, units, action, actor);
            }
        };
    }
}
*///?}
