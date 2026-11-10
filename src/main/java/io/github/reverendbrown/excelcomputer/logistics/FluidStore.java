package io.github.reverendbrown.excelcomputer.logistics;

import net.minecraft.world.level.material.Fluid;

import java.util.List;

/**
 * Fluids a hub can count, take and put: one side of a tank or machine (see
 * {@link FluidPorts}) or a storage network's fluids. Amounts are always in
 * millibuckets (1000 = one bucket), whatever unit the loader uses.
 *
 * <p>A fluid's exact kind (with its components) is the loader's own type:
 * Fabric's FluidVariant, NeoForge's FluidResource (1.21.10+) or FluidStack
 * (1.21.1, Forge). Every store in one build uses the same type, so a kind
 * taken from one store can be put in any other.
 */
public interface FluidStore {
    /** One fluid held: the fluid (for names and filters), its exact kind, and how many millibuckets. */
    record Held(Fluid fluid, Object kind, long amount) {
    }

    /** What it holds. */
    List<Held> contents();

    /** Puts up to amount millibuckets of kind in; returns how many went (or would go, when simulating). */
    long insert(Object kind, long amount, boolean simulate);

    /** Takes up to amount millibuckets of kind out; returns how many came (or would come, when simulating). */
    long extract(Object kind, long amount, boolean simulate);
}
