package io.github.reverendbrown.excelcomputer.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
//? if fabric {
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
//?} elif neoforge && >=1.21.10 {
/*import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
*///?} elif neoforge {
/*import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
*///?} else {
/*import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
*///?}

/**
 * Inventories as the loader sees them, so the hubs work with modded storage
 * too (anything that offers the loader's item API, e.g. Sophisticated Storage):
 * the Fabric Transfer API, NeoForge's item capability, or Forge's item handler.
 * Vanilla chests, furnaces, hoppers and so on are covered by all of them.
 *
 * <p>Putting items in uses only the side the inventory was registered with
 * (a furnace's top takes what it smelts, its sides take fuel). Taking items
 * out tries that side first and then every other side, so the player doesn't
 * have to know where a machine gives out its results (a furnace: the bottom).
 * The block's own rules still apply: a furnace never gives back its input.
 *
 * <p>Items are matched by item only: two stacks of the same item with
 * different enchantments or names both count as that item.
 */
public final class ItemPorts {
    // The sides tried after the registered one when taking items out: the bottom
    // first, where most machines (and the furnace) give out their results.
    private static final Direction[] EXTRACT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    private ItemPorts() {
    }

    //? if fabric {
    private static Storage<ItemVariant> handler(ServerLevel level, BlockPos pos, Direction side) {
        return ItemStorage.SIDED.find(level, pos, side);
    }
    //?} elif neoforge && >=1.21.10 {
    /*private static ResourceHandler<ItemResource> handler(ServerLevel level, BlockPos pos, Direction side) {
        return level.getCapability(Capabilities.Item.BLOCK, pos, side);
    }
    *///?} elif neoforge {
    /*private static IItemHandler handler(ServerLevel level, BlockPos pos, Direction side) {
        return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
    }
    *///?} else {
    /*private static IItemHandler handler(ServerLevel level, BlockPos pos, Direction side) {
        BlockEntity entity = level.getBlockEntity(pos);
        return entity == null ? null : entity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
    }
    *///?}

    /**
     * One registered side of an inventory, or a storage network the hub is
     * cabled to (then network is set, handler is null, and level and pos are
     * the hub's: where anything that can't be put back is dropped).
     */
    public static final class Port {
        final ServerLevel level;
        final BlockPos pos;
        final Direction side;
        final NetworkStorage network;
        // The fluid side (tanks), or null; for a network, networkFluids instead (null if it has none).
        final FluidStore fluids;
        final FluidStore networkFluids;
        //? if fabric {
        final Storage<ItemVariant> handler;
        //?} elif neoforge && >=1.21.10 {
        /*final ResourceHandler<ItemResource> handler;
        *///?} else {
        /*final IItemHandler handler;
        *///?}

        private Port(ServerLevel level, BlockPos pos, NetworkStorage network, FluidStore networkFluids) {
            this.level = level;
            this.pos = pos;
            this.side = null;
            this.network = network;
            this.fluids = null;
            this.networkFluids = networkFluids;
            this.handler = null;
        }

        //? if fabric {
        Port(ServerLevel level, BlockPos pos, Direction side, Storage<ItemVariant> handler, FluidStore fluids) {
        //?} elif neoforge && >=1.21.10 {
        /*Port(ServerLevel level, BlockPos pos, Direction side, ResourceHandler<ItemResource> handler, FluidStore fluids) {
        *///?} else {
        /*Port(ServerLevel level, BlockPos pos, Direction side, IItemHandler handler, FluidStore fluids) {
        *///?}
            this.level = level;
            this.pos = pos;
            this.side = side;
            this.network = null;
            this.fluids = fluids;
            this.networkFluids = null;
            this.handler = handler;
        }

        /**
         * Adds everything the block holds (item to count) to counts: the whole
         * inventory if the loader offers it without a side (a furnace's input,
         * fuel and result), otherwise what the registered side shows.
         */
        public void count(Map<Item, Long> counts) {
            if (network != null) {
                for (NetworkStorage.Held held : network.contents()) {
                    counts.merge(held.item().getItem(), held.amount(), Long::sum);
                }

                return;
            }

            var whole = handler;

            try {
                var unsided = handler(level, pos, null);

                if (unsided != null) {
                    whole = unsided;
                }
            } catch (RuntimeException exception) {
                // Some modded blocks need a side; the registered one will do.
            }

            // A tank with no item slots.
            if (whole == null) {
                return;
            }

            //? if fabric {
            for (StorageView<ItemVariant> view : whole.nonEmptyViews()) {
                counts.merge(view.getResource().getItem(), view.getAmount(), Long::sum);
            }
            //?} elif neoforge && >=1.21.10 {
            /*for (int slot = 0; slot < whole.size(); slot++) {
                ItemResource resource = whole.getResource(slot);

                if (!resource.isEmpty()) {
                    counts.merge(resource.getItem(), whole.getAmountAsLong(slot), Long::sum);
                }
            }
            *///?} else {
            /*for (int slot = 0; slot < whole.getSlots(); slot++) {
                ItemStack stack = whole.getStackInSlot(slot);

                if (!stack.isEmpty()) {
                    counts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                }
            }
            *///?}
        }

        /** Adds what the block's tanks hold (fluid to millibuckets) to counts. */
        public void countFluids(Map<Fluid, Long> counts) {
            FluidPorts.count(this, counts);
        }
    }

    /**
     * The inventory (or tank) side at pos, or null if the block offers neither
     * items nor fluids on that side.
     */
    public static Port find(ServerLevel level, BlockPos pos, Direction side) {
        var handler = handler(level, pos, side);
        FluidStore fluids = FluidPorts.find(level, pos, side);
        return handler == null && fluids == null ? null : new Port(level, pos, side, handler, fluids);
    }

    /** A storage network as a port; hubLevel and hubPos say where to drop items that can't be put back. */
    public static Port network(ServerLevel hubLevel, BlockPos hubPos, NetworkStorage network, FluidStore fluids) {
        return new Port(hubLevel, hubPos, network, fluids);
    }

    /** Like move(), for fluids: up to max millibuckets that pass the filter. */
    public static long moveFluids(Port from, Port to, Predicate<Fluid> filter, long max) {
        return FluidPorts.move(from, to, filter, max);
    }

    /**
     * Moves up to max items that pass the filter from one inventory to the
     * other, as many as fit. The items go in through the target's registered
     * side and come out of the source's registered side, then (for what's
     * still to move) out of its other sides. Nothing is lost: what doesn't fit
     * stays put.
     *
     * @return how many items moved
     */
    public static long move(Port from, Port to, Predicate<Item> filter, long max) {
        if (from.network != null || to.network != null) {
            return moveWithNetwork(from, to, filter, max);
        }

        // A tank with no item slots.
        if (to.handler == null) {
            return 0;
        }

        long moved = from.handler == null ? 0 : moveOnce(from.handler, from, to, filter, max);

        for (Direction side : EXTRACT_ORDER) {
            if (moved >= max) {
                break;
            }

            if (side == from.side) {
                continue;
            }

            var other = handler(from.level, from.pos, side);

            if (other != null) {
                moved += moveOnce(other, from, to, filter, max - moved);
            }
        }

        return moved;
    }

    // ----- moving to or from a storage network -----

    // Like move(), when either end is a network: the same sides are tried, but each
    // end is seen as a NetworkStorage, so one way of moving covers every combination.
    private static long moveWithNetwork(Port from, Port to, Predicate<Item> filter, long max) {
        if (to.network == null && to.handler == null) {
            return 0;
        }

        NetworkStorage target = to.network != null ? to.network : store(to.handler);

        if (from.network != null) {
            return moveBetween(from.network, target, filter, max, from);
        }

        long moved = from.handler == null ? 0 : moveBetween(store(from.handler), target, filter, max, from);

        for (Direction side : EXTRACT_ORDER) {
            if (moved >= max) {
                break;
            }

            if (side == from.side) {
                continue;
            }

            var other = handler(from.level, from.pos, side);

            if (other != null) {
                moved += moveBetween(store(other), target, filter, max - moved, from);
            }
        }

        return moved;
    }

    // Moves up to max items that pass the filter, a stack at a time: tried first
    // without changing anything, so only what the target takes leaves the source.
    private static long moveBetween(NetworkStorage source, NetworkStorage target, Predicate<Item> filter, long max,
                                    Port from) {
        long moved = 0;

        for (NetworkStorage.Held held : source.contents()) {
            if (moved >= max) {
                break;
            }

            ItemStack item = held.item();

            if (!filter.test(item.getItem())) {
                continue;
            }

            long left = Math.min(held.amount(), max - moved);
            int stack = Math.max(1, item.getMaxStackSize());

            while (left > 0) {
                long offered = source.extract(item, Math.min(left, stack), true);
                long fits = offered <= 0 ? 0 : target.insert(item, offered, true);

                if (fits <= 0) {
                    break;
                }

                long taken = source.extract(item, fits, false);
                long put = taken <= 0 ? 0 : target.insert(item, taken, false);
                moved += put;
                left -= put;

                if (put < taken) {
                    // Took less than it said it would: put the rest back, or drop it.
                    long rest = taken - put;
                    rest -= source.insert(item, rest, false);
                    drop(from, item, rest);
                    break;
                }

                if (put <= 0) {
                    break;
                }
            }
        }

        return moved;
    }

    private static void drop(Port at, ItemStack item, long amount) {
        while (amount > 0) {
            int count = (int) Math.min(amount, Math.max(1, item.getMaxStackSize()));
            Block.popResource(at.level, at.pos, item.copyWithCount(count));
            amount -= count;
        }
    }

    // One side of an ordinary inventory, seen as a NetworkStorage.
    //? if fabric {
    private static NetworkStorage store(Storage<ItemVariant> handler) {
        return new NetworkStorage() {
            @Override
            public List<Held> contents() {
                Map<ItemVariant, Long> amounts = new LinkedHashMap<>();

                for (StorageView<ItemVariant> view : handler.nonEmptyViews()) {
                    amounts.merge(view.getResource(), view.getAmount(), Long::sum);
                }

                List<Held> held = new ArrayList<>(amounts.size());
                amounts.forEach((variant, amount) -> held.add(new Held(variant.toStack(1), amount)));
                return held;
            }

            @Override
            public long insert(ItemStack item, long amount, boolean simulate) {
                try (Transaction transaction = Transaction.openOuter()) {
                    long done = handler.insert(ItemVariant.of(item), amount, transaction);

                    if (!simulate) {
                        transaction.commit();
                    }

                    return done;
                }
            }

            @Override
            public long extract(ItemStack item, long amount, boolean simulate) {
                try (Transaction transaction = Transaction.openOuter()) {
                    long done = handler.extract(ItemVariant.of(item), amount, transaction);

                    if (!simulate) {
                        transaction.commit();
                    }

                    return done;
                }
            }
        };
    }
    //?} elif neoforge && >=1.21.10 {
    /*private static NetworkStorage store(ResourceHandler<ItemResource> handler) {
        return new NetworkStorage() {
            @Override
            public List<Held> contents() {
                Map<ItemResource, Long> amounts = new LinkedHashMap<>();

                for (int slot = 0; slot < handler.size(); slot++) {
                    ItemResource resource = handler.getResource(slot);

                    if (!resource.isEmpty()) {
                        amounts.merge(resource, handler.getAmountAsLong(slot), Long::sum);
                    }
                }

                List<Held> held = new ArrayList<>(amounts.size());
                amounts.forEach((resource, amount) -> held.add(new Held(resource.toStack(1), amount)));
                return held;
            }

            @Override
            public long insert(ItemStack item, long amount, boolean simulate) {
                try (Transaction transaction = Transaction.openRoot()) {
                    int done = ResourceHandlerUtil.insertStacking(handler, ItemResource.of(item),
                            (int) Math.min(amount, Integer.MAX_VALUE), transaction);

                    if (!simulate) {
                        transaction.commit();
                    }

                    return done;
                }
            }

            @Override
            public long extract(ItemStack item, long amount, boolean simulate) {
                try (Transaction transaction = Transaction.openRoot()) {
                    int done = handler.extract(ItemResource.of(item), (int) Math.min(amount, Integer.MAX_VALUE),
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
    /*private static NetworkStorage store(IItemHandler handler) {
        return new NetworkStorage() {
            @Override
            public List<Held> contents() {
                List<Held> held = new ArrayList<>();

                slots:
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    ItemStack stack = handler.getStackInSlot(slot);

                    if (stack.isEmpty()) {
                        continue;
                    }

                    for (int i = 0; i < held.size(); i++) {
                        if (same(held.get(i).item(), stack)) {
                            held.set(i, new Held(held.get(i).item(), held.get(i).amount() + stack.getCount()));
                            continue slots;
                        }
                    }

                    held.add(new Held(stack.copyWithCount(1), stack.getCount()));
                }

                return held;
            }

            @Override
            public long insert(ItemStack item, long amount, boolean simulate) {
                long done = 0;

                // A stack at a time; a simulation only says what the first stack would do.
                while (done < amount) {
                    int count = (int) Math.min(amount - done, Math.max(1, item.getMaxStackSize()));
                    ItemStack rest = ItemHandlerHelper.insertItemStacked(handler, item.copyWithCount(count), simulate);
                    done += count - rest.getCount();

                    if (!rest.isEmpty() || simulate) {
                        break;
                    }
                }

                return done;
            }

            @Override
            public long extract(ItemStack item, long amount, boolean simulate) {
                long done = 0;

                for (int slot = 0; slot < handler.getSlots() && done < amount; slot++) {
                    ItemStack stack = handler.getStackInSlot(slot);

                    if (!stack.isEmpty() && same(stack, item)) {
                        done += handler.extractItem(slot, (int) Math.min(amount - done, stack.getCount()), simulate)
                                .getCount();
                    }
                }

                return done;
            }
        };
    }

    private static boolean same(ItemStack a, ItemStack b) {
        //? if >=1.20.5 {
        return ItemStack.isSameItemSameComponents(a, b);
        //?} else {
        /^return ItemStack.isSameItemSameTags(a, b);
        ^///?}
    }
    *///?}

    //? if fabric {
    private static long moveOnce(Storage<ItemVariant> source, Port from, Port to, Predicate<Item> filter, long max) {
    //?} elif neoforge && >=1.21.10 {
    /*private static long moveOnce(ResourceHandler<ItemResource> source, Port from, Port to, Predicate<Item> filter,
                                 long max) {
    *///?} else {
    /*private static long moveOnce(IItemHandler source, Port from, Port to, Predicate<Item> filter, long max) {
    *///?}
        if (max <= 0) {
            return 0;
        }

        //? if fabric {
        try (Transaction transaction = Transaction.openOuter()) {
            long moved = StorageUtil.move(source, to.handler, variant -> filter.test(variant.getItem()),
                    max, transaction);
            transaction.commit();
            return moved;
        }
        //?} elif neoforge && >=1.21.10 {
        /*try (Transaction transaction = Transaction.openRoot()) {
            int moved = ResourceHandlerUtil.move(source, to.handler,
                    resource -> filter.test(resource.getItem()), (int) Math.min(max, Integer.MAX_VALUE), transaction);
            transaction.commit();
            return moved;
        }
        *///?} else {
        /*long moved = 0;

        for (int slot = 0; slot < source.getSlots() && moved < max; slot++) {
            ItemStack stack = source.getStackInSlot(slot);

            if (stack.isEmpty() || !filter.test(stack.getItem())) {
                continue;
            }

            // Tried first without changing anything, so only what fits is taken out.
            int wanted = (int) Math.min(max - moved, stack.getCount());
            ItemStack offered = source.extractItem(slot, wanted, true);

            if (offered.isEmpty()) {
                continue;
            }

            int fits = offered.getCount() - ItemHandlerHelper.insertItemStacked(to.handler, offered, true).getCount();

            if (fits <= 0) {
                continue;
            }

            ItemStack taken = source.extractItem(slot, fits, false);
            ItemStack rest = ItemHandlerHelper.insertItemStacked(to.handler, taken, false);
            moved += taken.getCount() - rest.getCount();

            // An inventory that took less than it said it would: put the rest back.
            if (!rest.isEmpty()) {
                rest = source.insertItem(slot, rest, false);
                rest = rest.isEmpty() ? rest : ItemHandlerHelper.insertItemStacked(source, rest, false);

                if (!rest.isEmpty()) {
                    Block.popResource(from.level, from.pos, rest);
                }
            }
        }

        return moved;
        *///?}
    }
}
