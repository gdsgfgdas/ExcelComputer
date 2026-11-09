package io.github.reverendbrown.excelcomputer.logistics;

/**
 * The four Logistics Hubs. A better hub reaches farther, moves more items per
 * cycle, runs its workbook more often and can use more inventories.
 */
public enum HubTier {
    //    id                         label                       range  items  ticks  inventories  other dimensions
    COPPER("copper_logistics_hub", "Copper Logistics Hub", 16, 8, 40, 4, false),
    IRON("iron_logistics_hub", "Iron Logistics Hub", 32, 32, 20, 8, false),
    DIAMOND("diamond_logistics_hub", "Diamond Logistics Hub", 64, 128, 10, 16, false),
    NETHERITE("netherite_logistics_hub", "Netherite Logistics Hub", 128, 512, 5, 32, true);

    private final String id;
    private final String label;
    private final int range;
    private final int itemsPerCycle;
    private final int cycleTicks;
    private final int maxInventories;
    private final boolean otherDimensions;

    HubTier(String id, String label, int range, int itemsPerCycle, int cycleTicks, int maxInventories,
            boolean otherDimensions) {
        this.id = id;
        this.label = label;
        this.range = range;
        this.itemsPerCycle = itemsPerCycle;
        this.cycleTicks = cycleTicks;
        this.maxInventories = maxInventories;
        this.otherDimensions = otherDimensions;
    }

    /** The block's registry path. */
    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** How far (in blocks, straight-line) an inventory may be from the hub. */
    public int range() {
        return range;
    }

    /** The most items all the Transfers rows together move in one cycle. */
    public int itemsPerCycle() {
        return itemsPerCycle;
    }

    /** Game ticks between cycles (20 ticks = 1 second). */
    public int cycleTicks() {
        return cycleTicks;
    }

    public int maxInventories() {
        return maxInventories;
    }

    /** Whether inventories may be in another dimension (measured from the same coordinates). */
    public boolean otherDimensions() {
        return otherDimensions;
    }

    /** One line about the tier, e.g. for the Inventories sheet. */
    public String describe() {
        String seconds = cycleTicks % 20 == 0
                ? Integer.toString(cycleTicks / 20)
                : Double.toString(cycleTicks / 20.0);

        return label + ": moves up to " + itemsPerCycle + " items every " + seconds + " s, reaches "
                + range + " blocks, holds " + maxInventories + " inventories"
                + (otherDimensions ? ", works across dimensions" : "");
    }
}
