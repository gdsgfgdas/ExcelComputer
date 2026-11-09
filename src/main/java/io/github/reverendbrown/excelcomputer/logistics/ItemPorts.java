package io.github.reverendbrown.excelcomputer.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
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
/*import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
*///?} else {
/*import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
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

    /** One registered side of an inventory. */
    public static final class Port {
        final ServerLevel level;
        final BlockPos pos;
        final Direction side;
        //? if fabric {
        final Storage<ItemVariant> handler;
        //?} elif neoforge && >=1.21.10 {
        /*final ResourceHandler<ItemResource> handler;
        *///?} else {
        /*final IItemHandler handler;
        *///?}

        //? if fabric {
        Port(ServerLevel level, BlockPos pos, Direction side, Storage<ItemVariant> handler) {
        //?} elif neoforge && >=1.21.10 {
        /*Port(ServerLevel level, BlockPos pos, Direction side, ResourceHandler<ItemResource> handler) {
        *///?} else {
        /*Port(ServerLevel level, BlockPos pos, Direction side, IItemHandler handler) {
        *///?}
            this.level = level;
            this.pos = pos;
            this.side = side;
            this.handler = handler;
        }

        /**
         * Adds everything the block holds (item to count) to counts: the whole
         * inventory if the loader offers it without a side (a furnace's input,
         * fuel and result), otherwise what the registered side shows.
         */
        public void count(Map<Item, Long> counts) {
            var whole = handler;

            try {
                var unsided = handler(level, pos, null);

                if (unsided != null) {
                    whole = unsided;
                }
            } catch (RuntimeException exception) {
                // Some modded blocks need a side; the registered one will do.
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
    }

    /** The inventory side at pos, or null if there is none (or it offers nothing on that side). */
    public static Port find(ServerLevel level, BlockPos pos, Direction side) {
        var handler = handler(level, pos, side);
        return handler == null ? null : new Port(level, pos, side, handler);
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
        long moved = moveOnce(from.handler, from, to, filter, max);

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
