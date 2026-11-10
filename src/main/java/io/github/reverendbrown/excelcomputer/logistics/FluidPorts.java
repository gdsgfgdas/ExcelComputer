package io.github.reverendbrown.excelcomputer.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
//? if fabric {
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
//?} elif neoforge && >=1.21.10 {
/*import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
*///?} elif neoforge {
/*import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
*///?} else {
/*import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
*///?}

/**
 * Tanks and machines as the loader sees them, the fluid counterpart of
 * {@link ItemPorts}: the Fabric Transfer API, NeoForge's fluid capability, or
 * Forge's fluid handler. Fluid goes in through the registered side; coming out,
 * that side is tried first, then the others (like items).
 */
public final class FluidPorts {
    private static final Direction[] EXTRACT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    private FluidPorts() {
    }

    /** A fluid's full id, e.g. "minecraft:water". */
    public static String id(Fluid fluid) {
        return BuiltInRegistries.FLUID.getKey(fluid).toString();
    }

    /** The fluid side of the block at pos, or null if it has none there. */
    public static FluidStore find(ServerLevel level, BlockPos pos, Direction side) {
        try {
            return store(level, pos, side);
        } catch (RuntimeException exception) {
            // Some modded blocks need a side (null) or refuse the question.
            return null;
        }
    }

    /**
     * Adds what the block's tanks hold (fluid to millibuckets) to counts: all of
     * them if the loader offers them without a side, otherwise the registered side's.
     */
    static void count(ItemPorts.Port port, Map<Fluid, Long> counts) {
        FluidStore whole = port.network != null ? port.networkFluids : find(port.level, port.pos, null);

        if (whole == null) {
            whole = port.fluids;
        }

        if (whole != null) {
            for (FluidStore.Held held : whole.contents()) {
                counts.merge(held.fluid(), held.amount(), Long::sum);
            }
        }
    }

    /**
     * Moves up to max millibuckets of fluids that pass the filter. Goes in
     * through the target's registered side; comes out of the source's
     * registered side, then its other sides. Nothing is lost: what doesn't fit stays.
     */
    static long move(ItemPorts.Port from, ItemPorts.Port to, Predicate<Fluid> filter, long max) {
        FluidStore target = to.network != null ? to.networkFluids : to.fluids;

        if (target == null) {
            return 0;
        }

        if (from.network != null) {
            return from.networkFluids == null ? 0 : moveBetween(from.networkFluids, target, filter, max);
        }

        long moved = from.fluids == null ? 0 : moveBetween(from.fluids, target, filter, max);

        for (Direction side : EXTRACT_ORDER) {
            if (moved >= max) {
                break;
            }

            if (side == from.side) {
                continue;
            }

            FluidStore other = find(from.level, from.pos, side);

            if (other != null) {
                moved += moveBetween(other, target, filter, max - moved);
            }
        }

        return moved;
    }

    // Tried first without changing anything, so only what the target takes leaves the source.
    private static long moveBetween(FluidStore source, FluidStore target, Predicate<Fluid> filter, long max) {
        long moved = 0;

        for (FluidStore.Held held : source.contents()) {
            if (moved >= max) {
                break;
            }

            if (!filter.test(held.fluid())) {
                continue;
            }

            long offered = source.extract(held.kind(), Math.min(held.amount(), max - moved), true);
            long fits = offered <= 0 ? 0 : target.insert(held.kind(), offered, true);

            if (fits <= 0) {
                continue;
            }

            long taken = source.extract(held.kind(), fits, false);
            long put = taken <= 0 ? 0 : target.insert(held.kind(), taken, false);
            moved += put;

            // Took less than it said it would: put the rest back (fluid can't be dropped).
            if (put < taken) {
                source.insert(held.kind(), taken - put, false);
            }
        }

        return moved;
    }

    //? if fabric {
    // Fabric counts in droplets: 81 to a millibucket.
    private static final long DROPLETS = FluidConstants.BUCKET / 1000;

    private static FluidStore store(ServerLevel level, BlockPos pos, Direction side) {
        Storage<FluidVariant> storage = FluidStorage.SIDED.find(level, pos, side);
        return storage == null ? null : of(storage);
    }

    /** A Fabric fluid storage as a FluidStore (also used for Refined Storage on Fabric). */
    public static FluidStore of(Storage<FluidVariant> storage) {
        return new FluidStore() {
            @Override
            public List<Held> contents() {
                Map<FluidVariant, Long> amounts = new java.util.LinkedHashMap<>();

                for (StorageView<FluidVariant> view : storage.nonEmptyViews()) {
                    amounts.merge(view.getResource(), view.getAmount(), Long::sum);
                }

                List<Held> held = new ArrayList<>(amounts.size());
                amounts.forEach((variant, droplets) -> {
                    if (droplets >= DROPLETS) {
                        held.add(new Held(variant.getFluid(), variant, droplets / DROPLETS));
                    }
                });
                return held;
            }

            @Override
            public long insert(Object kind, long amount, boolean simulate) {
                return whole(amount, simulate, (droplets, transaction) ->
                        storage.insert((FluidVariant) kind, droplets, transaction));
            }

            @Override
            public long extract(Object kind, long amount, boolean simulate) {
                return whole(amount, simulate, (droplets, transaction) ->
                        storage.extract((FluidVariant) kind, droplets, transaction));
            }
        };
    }

    private interface Operation {
        long run(long droplets, Transaction transaction);
    }

    // Runs an insert or extract in whole millibuckets: if the storage would take
    // part of one, it's done again with the whole ones only.
    private static long whole(long amount, boolean simulate, Operation operation) {
        long droplets = amount * DROPLETS;

        try (Transaction transaction = Transaction.openOuter()) {
            long done = operation.run(droplets, transaction);

            if (done % DROPLETS == 0) {
                if (!simulate) {
                    transaction.commit();
                }

                return done / DROPLETS;
            }

            droplets = done / DROPLETS * DROPLETS;
        }

        if (droplets <= 0) {
            return 0;
        }

        try (Transaction transaction = Transaction.openOuter()) {
            long done = operation.run(droplets, transaction);

            if (done != droplets) {
                return 0;
            }

            if (!simulate) {
                transaction.commit();
            }

            return done / DROPLETS;
        }
    }
    //?} elif neoforge && >=1.21.10 {
    /*private static FluidStore store(ServerLevel level, BlockPos pos, Direction side) {
        ResourceHandler<FluidResource> handler = level.getCapability(Capabilities.Fluid.BLOCK, pos, side);
        return handler == null ? null : of(handler);
    }

    private static FluidStore of(ResourceHandler<FluidResource> handler) {
        return new FluidStore() {
            @Override
            public List<Held> contents() {
                Map<FluidResource, Long> amounts = new java.util.LinkedHashMap<>();

                for (int tank = 0; tank < handler.size(); tank++) {
                    FluidResource resource = handler.getResource(tank);

                    if (!resource.isEmpty()) {
                        amounts.merge(resource, handler.getAmountAsLong(tank), Long::sum);
                    }
                }

                List<Held> held = new ArrayList<>(amounts.size());
                amounts.forEach((resource, amount) -> held.add(new Held(resource.getFluid(), resource, amount)));
                return held;
            }

            @Override
            public long insert(Object kind, long amount, boolean simulate) {
                try (Transaction transaction = Transaction.openRoot()) {
                    int done = ResourceHandlerUtil.insertStacking(handler, (FluidResource) kind,
                            (int) Math.min(amount, Integer.MAX_VALUE), transaction);

                    if (!simulate) {
                        transaction.commit();
                    }

                    return done;
                }
            }

            @Override
            public long extract(Object kind, long amount, boolean simulate) {
                try (Transaction transaction = Transaction.openRoot()) {
                    int done = handler.extract((FluidResource) kind, (int) Math.min(amount, Integer.MAX_VALUE),
                            transaction);

                    if (!simulate) {
                        transaction.commit();
                    }

                    return done;
                }
            }
        };
    }
    *///?} else {
    /*private static FluidStore store(ServerLevel level, BlockPos pos, Direction side) {
        //? if neoforge {
        /^IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
        ^///?} else {
        BlockEntity entity = level.getBlockEntity(pos);
        IFluidHandler handler = entity == null ? null
                : entity.getCapability(ForgeCapabilities.FLUID_HANDLER, side).orElse(null);
        //?}
        return handler == null ? null : of(handler);
    }

    /^* A fluid handler as a FluidStore (also used for AE2 / Refined Storage on NeoForge). ^/
    public static FluidStore of(IFluidHandler handler) {
        return new FluidStore() {
            @Override
            public List<Held> contents() {
                List<Held> held = new ArrayList<>();

                tanks:
                for (int tank = 0; tank < handler.getTanks(); tank++) {
                    FluidStack stack = handler.getFluidInTank(tank);

                    if (stack.isEmpty()) {
                        continue;
                    }

                    for (int i = 0; i < held.size(); i++) {
                        if (same((FluidStack) held.get(i).kind(), stack)) {
                            held.set(i, new Held(stack.getFluid(), held.get(i).kind(),
                                    held.get(i).amount() + stack.getAmount()));
                            continue tanks;
                        }
                    }

                    held.add(new Held(stack.getFluid(), withAmount(stack, 1), stack.getAmount()));
                }

                return held;
            }

            @Override
            public long insert(Object kind, long amount, boolean simulate) {
                return handler.fill(withAmount((FluidStack) kind, (int) Math.min(amount, Integer.MAX_VALUE)),
                        simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE);
            }

            @Override
            public long extract(Object kind, long amount, boolean simulate) {
                return handler.drain(withAmount((FluidStack) kind, (int) Math.min(amount, Integer.MAX_VALUE)),
                        simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE).getAmount();
            }
        };
    }

    private static FluidStack withAmount(FluidStack stack, int amount) {
        //? if neoforge {
        /^return stack.copyWithAmount(amount);
        ^///?} else {
        return new FluidStack(stack, amount);
        //?}
    }

    private static boolean same(FluidStack a, FluidStack b) {
        //? if neoforge {
        /^return FluidStack.isSameFluidSameComponents(a, b);
        ^///?} else {
        return a.isFluidEqual(b);
        //?}
    }
    *///?}
}
