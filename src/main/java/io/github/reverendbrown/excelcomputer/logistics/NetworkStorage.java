package io.github.reverendbrown.excelcomputer.logistics;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Items a hub can count, take and put, as one store: a storage network the hub
 * is cabled to (see {@link NetworkLink}), or one side of an ordinary inventory
 * (made by {@link ItemPorts} so both can be moved between the same way).
 */
public interface NetworkStorage {
    /** One kind of item held, and how many. item is a single item (count 1) with its components. */
    record Held(ItemStack item, long amount) {
    }

    /** What it holds. */
    List<Held> contents();

    /** Puts up to amount of item in; returns how many went (or would go, when simulating). */
    long insert(ItemStack item, long amount, boolean simulate);

    /** Takes up to amount of item out; returns how many came (or would come, when simulating). */
    long extract(ItemStack item, long amount, boolean simulate);
}
