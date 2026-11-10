package io.github.reverendbrown.excelcomputer.logistics;

/**
 * A hub's connection to one storage mod's network (Applied Energistics 2,
 * Refined Storage): the hub block entity is a device on that network, so a
 * cable laid up to the hub joins it. One per mod installed, made by
 * {@link HubNetworks}; the classes behind it are the only ones that use the
 * mod's API, and they're only loaded when the mod is.
 */
public interface NetworkLink {
    /** The name the network gets in the hub's sheets, e.g. "ME Network". */
    String label();

    /** The mod, shown in the Inventories sheet's Block column. */
    String modName();

    /** The block entity was added to a level (both sides; joins the network on the server). */
    void added();

    /** The block entity was removed or unloaded: leave the network. */
    void removed();

    /** The hub was broken or replaced (not just unloaded with its chunk). Called before removed(). */
    default void broken() {
    }

    /** What this link exposes for a Forge capability (the capability object), or null. */
    default Object capability(Object capability) {
        return null;
    }

    /** Every server tick (pays the network's energy where the mod wants that). */
    default void tick() {
    }

    /** Whether a cable connects the hub to a network at all. */
    boolean connected();

    /** Why the network can't be used right now (no power, no channel), or null if it can. */
    String problem();

    /** The network's items. Only called when connected and problem() is null. */
    NetworkStorage storage();

    /** The network's fluids, or null if it can't hold any. Called like storage(). */
    default FluidStore fluidStorage() {
        return null;
    }
}
