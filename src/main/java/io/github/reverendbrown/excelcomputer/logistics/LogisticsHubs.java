package io.github.reverendbrown.excelcomputer.logistics;

import io.github.reverendbrown.excelcomputer.ExcelComputer;
import io.github.reverendbrown.excelcomputer.WorkbookManager;
import io.github.reverendbrown.excelcomputer.block.ModBlocks;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import io.github.reverendbrown.excelcomputer.link.LinkedWorkbooks;
import io.github.reverendbrown.excelcomputer.ServerConfig;
import io.github.reverendbrown.excelcomputer.share.ShareProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The server side of Logistics Hubs.
 *
 * <p>Each hub has a list of registered inventories (a player adds one by
 * sneak-right-clicking it with Excel on a Stick after right-clicking the hub),
 * kept in {@code <world>/excelcomputer/hubs/inventories.txt}. Only registered
 * inventories are used, so a workbook can't reach into someone else's chest by
 * typing in coordinates.
 *
 * <p>Every cycle (a block tick, more often for better hubs) the hub counts what
 * each inventory holds, writes it to the Stock sheet, recalculates, and works
 * through the Transfers rows. A hub whose inventories haven't changed since its
 * last cycle skips the recalculation.
 *
 * <p>Everything here runs on the server thread.
 */
public final class LogisticsHubs {
    // A cycle that takes longer than this is reported in the log.
    private static final long SLOW_CYCLE_MS = 50;
    // Fluids in Stock and in the Item column: "fluid:minecraft:water"; amounts in millibuckets.
    private static final String FLUID_PREFIX = "fluid:";
    private static final String MB = " mB";

    /** An inventory side registered to a hub; the name is the one used in the sheets. */
    private static final class Entry {
        final String dimension;
        final BlockPos pos;
        // The side the hub uses: the clicked face at first, then whatever the player types in the sheet.
        Direction side;
        String name;
        // Why the Side typed in the sheet couldn't be used (shown as the status), or null.
        String sideProblem;
        // When it was registered (0: before the server started), so a save from an
        // older copy of the workbook can't unregister it.
        long addedAt;

        Entry(String dimension, BlockPos pos, Direction side, String name) {
            this.dimension = dimension;
            this.pos = pos;
            this.side = side;
            this.name = name;
        }

        boolean sameAs(String dimension, BlockPos pos, Direction side) {
            return this.dimension.equals(dimension) && this.pos.equals(pos) && this.side == side;
        }
    }

    // Hub key (LinkedWorkbooks.key) to its inventories, in the order they were added.
    private static final Map<String, List<Entry>> INVENTORIES = new LinkedHashMap<>();
    private static boolean inventoriesLoaded;
    // What each hub saw in its last cycle; the same again means nothing to do.
    private static final Map<String, List<String>> LAST_SEEN = new HashMap<>();
    // When each player (id|hub key) was last sent a hub's workbook: their save can only
    // unregister inventories that were in that copy.
    private static final Map<String, Long> DOWNLOADED = new HashMap<>();
    // How many Stock rows each hub wrote last, to clear the ones no longer needed.
    private static final Map<String, Integer> STOCK_ROWS = new HashMap<>();

    // The time budget (ServerConfig.hubTickBudgetMs) all hubs share in one server tick:
    // the tick it is for and how much of it is used. Hubs over it wait in WAITING
    // (since when they were due, and when they last tried), oldest first.
    private record Waiting(long dueSince, long lastAttempt) {
    }

    private static long budgetTick = -1;
    private static long spentNanos;
    private static final Map<String, Waiting> WAITING = new HashMap<>();
    // When each hub last ran a cycle (server ticks), for catching up after waiting.
    private static final Map<String, Long> LAST_RUN = new HashMap<>();
    // Hubs paused for being too slow, and the tick they may run again.
    private static final Map<String, Long> PAUSED = new HashMap<>();

    /** The hubs' linked workbooks, in {@code <world>/excelcomputer/hubs}. */
    public static final LinkedWorkbooks LINKED = new LinkedWorkbooks(
            "hubs", "Logistics Hub",
            state -> state.getBlock() instanceof LogisticsHubBlock,
            LogisticsSheets::ensure)
            // Only the owner and the members they added may open or save a hub's workbook.
            .access(HubDirectory::checkAccess)
            // A player saved the workbook: rows they deleted from Inventories unregister those inventories.
            .onSaved(LogisticsHubs::applySavedInventories)
            .onSent((player, level, pos) -> DOWNLOADED.put(player.getUUID() + "|" + LinkedWorkbooks.key(level, pos),
                    System.currentTimeMillis()))
            .onStored((level, pos) -> {
                // Starts (or restarts) the cycle with the new workbook.
                String key = LinkedWorkbooks.key(level, pos);
                LAST_SEEN.remove(key);
                STOCK_ROWS.remove(key);
                // An unloaded hub (linked from far away) carries on when its chunk loads.
                if (level.isLoaded(pos)) {
                    level.scheduleTick(pos, level.getBlockState(pos).getBlock(), 1);
                }
            })
            .template(new LinkedWorkbooks.Template() {
                @Override
                public String canCreate(ServerPlayer player, ServerLevel level, BlockPos pos) {
                    return HubDirectory.canCreate(player, level, pos);
                }

                @Override
                public WorkbookManager create() {
                    WorkbookManager workbook = WorkbookManager.createEmpty();
                    LogisticsSheets.ensure(workbook);
                    return workbook;
                }

                @Override
                public String fileName(ServerLevel level, BlockPos pos) {
                    String name = level.getBlockState(pos).getBlock() instanceof LogisticsHubBlock hub
                            ? HubDirectory.name(LinkedWorkbooks.key(level, pos), hub.tier())
                            : "Logistics Hub";
                    String clean = ShareProtocol.cleanFileName(name + ".xlsx");
                    return clean != null ? clean : "Logistics Hub.xlsx";
                }
            })
            .limits(() -> new LinkedWorkbooks.Limits(
                    ServerConfig.hubWorkbookMaxKb * 1024L,
                    ServerConfig.hubWorkbookMaxUnpackedMb * 1024L * 1024L,
                    ServerConfig.hubWorkbookMaxCells,
                    ServerConfig.hubWorkbookMaxFormulas))
            .onReset(() -> {
                INVENTORIES.clear();
                inventoriesLoaded = false;
                HubDirectory.reset();
                LAST_SEEN.clear();
                STOCK_ROWS.clear();
                WAITING.clear();
                DOWNLOADED.clear();
                LAST_RUN.clear();
                PAUSED.clear();
                budgetTick = -1;
            });

    private LogisticsHubs() {
    }

    // ----- upgrading -----

    /**
     * A player used a Hub Upgrade on the hub at pos: if it is the tier just
     * below the upgrade's and theirs to break, the block becomes the next tier.
     * Everything stored by position (the workbook, owner, members, name and
     * registered inventories) stays.
     *
     * @return whether the hub was upgraded (and the item should be used up)
     */
    public static boolean upgrade(ServerPlayer player, ServerLevel level, BlockPos pos, HubTier from, HubTier to) {
        LINKED.forServer(level.getServer());

        if (to.ordinal() <= from.ordinal()) {
            tell(player, "This hub is already a " + from.label());
            return false;
        }

        if (to.ordinal() != from.ordinal() + 1) {
            tell(player, "Upgrade it to a " + HubTier.values()[from.ordinal() + 1].label() + " first");
            return false;
        }

        if (!HubDirectory.mayBreak(player, level, pos)) {
            String owner = HubDirectory.ownerName(level, pos);
            tell(player, "Only " + (owner != null ? owner : "its owner") + " can upgrade this hub");
            return false;
        }

        LogisticsHubBlock.upgrading = true;

        try {
            level.setBlock(pos, ModBlocks.LOGISTICS_HUBS[to.ordinal()].defaultBlockState(), Block.UPDATE_ALL);
        } finally {
            LogisticsHubBlock.upgrading = false;
        }

        // A hub paused for being slow gets another go at its new pace.
        PAUSED.remove(LinkedWorkbooks.key(level, pos));
        HubDirectory.retiered(level.getServer(), level.dimension().toString(), pos, to);
        level.playSound(null, pos, SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 1F, 1F);
        tell(player, "Upgraded. " + to.describe());
        return true;
    }

    // ----- choosing a hub and registering inventories -----

    /**
     * The player right-clicked a hub: it joins their list of hubs (which they
     * can open from anywhere) and inventories they register next join it.
     */
    public static void select(ServerPlayer player, ServerLevel level, BlockPos pos, HubTier tier) {
        LINKED.forServer(level.getServer());
        int count = inventories(LinkedWorkbooks.key(level, pos)).size();
        HubDirectory.Hub hub = HubDirectory.use(player, level, pos, tier);

        if (hub == null) {
            // Not theirs (the workbook download that the click also started says so too).
            tell(player, HubDirectory.checkAccess(player, level, pos));
            return;
        }

        tell(player, HubDirectory.name(hub) + " selected (" + count + "/" + tier.maxInventories()
                + " inventories). Sneak-right-click inventories with Excel on a Stick to add them,"
                + " sneak-use the stick anywhere to open its workbook");
    }

    /**
     * The player sneak-right-clicked a block's side holding Excel on a Stick:
     * adds that side to their active hub, or removes it if it was added.
     */
    public static void toggleInventory(ServerPlayer player, ServerLevel level, BlockPos pos, Direction side) {
        LINKED.forServer(level.getServer());
        HubDirectory.Hub hub = HubDirectory.active(player);

        if (hub == null) {
            tell(player, "Right-click a Logistics Hub first (or choose one in the workbook picker),"
                    + " to choose the hub this inventory joins");
            return;
        }

        // (An unloaded hub is fine: breaking a hub takes it off every list.)
        ServerLevel hubLevel = level(level.getServer(), hub.dimension());

        if (hubLevel == null) {
            tell(player, "The hub's dimension isn't there any more");
            return;
        }

        HubTier tier = hub.tier();
        String hubName = HubDirectory.name(hub);
        String hubKey = hub.key();
        List<Entry> entries = inventories(hubKey);
        String dimension = level.dimension().toString();

        for (Entry entry : entries) {
            if (entry.sameAs(dimension, pos, side)) {
                entries.remove(entry);
                changed(hubKey);
                tell(player, "Removed " + entry.name + " from " + hubName);
                return;
            }
        }

        String problem = reachProblem(tier, hubLevel, hub.pos(), level, pos);

        if (problem != null) {
            tell(player, problem);
            return;
        }

        if (entries.size() >= tier.maxInventories()) {
            tell(player, hubName + " already has " + tier.maxInventories()
                    + " inventories. Remove one, or use a better hub");
            return;
        }

        if (ItemPorts.find(level, pos, side) == null) {
            tell(player, "That block has no inventory or tank on this side");
            return;
        }

        // Another side of a block that's already registered is named after it
        // ("Furnace 1 Bottom"): Stock lists the block once, under the first name.
        Entry sameBlock = null;

        for (Entry entry : entries) {
            if (sameBlock == null && entry.dimension.equals(dimension) && entry.pos.equals(pos)) {
                sameBlock = entry;
            }
        }

        String name = sameBlock != null
                ? uniqueSideName(entries, sameBlock.name + " " + sideName(side))
                : uniqueName(entries, blockName(level.getBlockState(pos)));
        Entry added = new Entry(dimension, pos.immutable(), side, name);
        added.addedAt = System.currentTimeMillis();
        entries.add(added);
        changed(hubKey);
        tell(player, "Added " + name + " (" + sideName(side) + " side) to " + hubName
                + (sameBlock != null
                ? ". Move items with this name; its contents are in Stock as " + sameBlock.name
                : ". Use that name in the Transfers sheet"));
    }

    /** A hub was renamed: its next cycle runs in full, so its sheet shows the new name. */
    static void renamed(String hubKey) {
        LAST_SEEN.remove(hubKey);
    }

    static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message), true);
    }

    // Why the hub can't use an inventory at pos, or null if it can.
    private static String reachProblem(HubTier tier, ServerLevel hubLevel, BlockPos hubPos,
                                       ServerLevel level, BlockPos pos) {
        if (level != hubLevel && !tier.otherDimensions()) {
            return "Only a Netherite Logistics Hub reaches other dimensions";
        }

        if (hubPos.distSqr(pos) > (double) tier.range() * tier.range()) {
            return "Too far: the " + tier.label() + " reaches " + tier.range() + " blocks";
        }

        return null;
    }

    // "Furnace" for minecraft:furnace, "Oak Chest" for somemod:oak_chest. Taken
    // from the id, not the translated name, which a dedicated server may not know.
    private static String blockName(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        StringBuilder name = new StringBuilder();

        for (String word : id.substring(id.indexOf(':') + 1).split("_")) {
            if (word.isEmpty()) {
                continue;
            }

            if (name.length() > 0) {
                name.append(' ');
            }

            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }

        return name.length() == 0 ? "Inventory" : name.toString();
    }

    // name itself if no inventory has it, else name 2, name 3...
    private static String uniqueSideName(List<Entry> entries, String name) {
        for (int number = 1; ; number++) {
            String candidate = number == 1 ? name : name + " " + number;
            boolean taken = false;

            for (Entry entry : entries) {
                taken |= entry.name.equalsIgnoreCase(candidate);
            }

            if (!taken) {
                return candidate;
            }
        }
    }

    private static String uniqueName(List<Entry> entries, String base) {
        for (int number = 1; ; number++) {
            String name = base + " " + number;
            boolean taken = false;

            for (Entry entry : entries) {
                taken |= entry.name.equalsIgnoreCase(name);
            }

            if (!taken) {
                return name;
            }
        }
    }

    // How a side is written in the sheet: Top, Bottom, North, South, East, West.
    private static String sideName(Direction side) {
        return switch (side) {
            case UP -> "Top";
            case DOWN -> "Bottom";
            default -> {
                String name = side.getName();
                yield Character.toUpperCase(name.charAt(0)) + name.substring(1);
            }
        };
    }

    // A side as typed in the sheet, or null if it isn't one. Besides the six sides it
    // takes Up/Down and the furnace's layout: Input (top), Fuel or Side (a side), Output (bottom).
    private static Direction parseSide(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "top", "up", "input", "in" -> Direction.UP;
            case "bottom", "down", "output", "out" -> Direction.DOWN;
            case "north", "fuel", "side" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    // A hub's inventories changed: save them, and run its next cycle in full.
    private static void changed(String hubKey) {
        LAST_SEEN.remove(hubKey);
        saveInventories();
    }

    /** The hub was broken: forget its inventories, link and workbook. */
    static void remove(ServerLevel level, BlockPos pos) {
        LINKED.forServer(level.getServer());
        String key = LinkedWorkbooks.key(level, pos);
        WAITING.remove(key);
        LAST_RUN.remove(key);
        PAUSED.remove(key);
        LAST_SEEN.remove(key);
        STOCK_ROWS.remove(key);
        HubDirectory.removed(level.getServer(), level.dimension().toString(), pos);

        if (!inventories(key).isEmpty()) {
            INVENTORIES.remove(key);
            saveInventories();
        }

        LINKED.remove(level, pos);
    }

    // ----- the cycle -----

    /**
     * Runs one cycle of the hub at pos.
     *
     * @return whether the hub should keep cycling (false once nothing is linked)
     */
    static int cycle(ServerLevel level, BlockPos pos, HubTier tier) {
        LINKED.forServer(level.getServer());
        LinkedWorkbooks.Link link = LINKED.link(level, pos);

        if (link == null) {
            return -1;
        }

        WorkbookManager workbook = LINKED.workbook(link);

        if (workbook == null) {
            return tier.cycleTicks();
        }

        String key = LinkedWorkbooks.key(level, pos);
        long now = level.getServer().getTickCount();

        // A hub paused for being too slow waits it out, then carries on in full.
        Long pausedUntil = PAUSED.get(key);

        if (pausedUntil != null) {
            if (now < pausedUntil) {
                return (int) Math.min(pausedUntil - now, 200);
            }

            PAUSED.remove(key);
            LAST_SEEN.remove(key);
            LAST_RUN.remove(key);
            LogisticsSheets.writeNotice(workbook, LogisticsSheets.ensure(workbook).inventories(), "");
        }

        // The time budget all hubs share in one server tick.
        if (budgetTick != now) {
            budgetTick = now;
            spentNanos = 0;
            WAITING.values().removeIf(waiting -> waiting.lastAttempt() < now - 2);
        }

        Waiting waiting = WAITING.get(key);
        long dueSince = waiting != null ? waiting.dueSince() : now;

        if (spentNanos >= ServerConfig.hubTickBudgetMs * 1_000_000L || olderWaiting(key, dueSince)) {
            // Over budget (or a hub that has waited longer goes first): try again next tick.
            WAITING.put(key, new Waiting(dueSince, now));
            return 1;
        }

        WAITING.remove(key);

        // A hub that had to wait catches up: it may move several cycles' worth of items.
        long lastRun = LAST_RUN.getOrDefault(key, now - tier.cycleTicks());
        int cycles = (int) Math.max(1, Math.min(ServerConfig.hubCatchUpCycles, (now - lastRun) / tier.cycleTicks()));
        LAST_RUN.put(key, now);

        long start = System.nanoTime();

        try {
            runCycle(workbook, key, level, pos, tier, cycles);
        } catch (RuntimeException | LinkageError exception) {
            ExcelComputer.LOGGER.warn("{} at {} could not run {}: {}",
                    tier.label(), pos.toShortString(), link.name(), exception.toString());
        }

        long nanos = System.nanoTime() - start;
        spentNanos += nanos;
        long millis = nanos / 1_000_000;

        if (millis > ServerConfig.hubSlowCycleMs) {
            PAUSED.put(key, now + ServerConfig.hubSlowPauseSeconds * 20L);
            ExcelComputer.LOGGER.warn("{} at {} took {} ms to run {}; paused for {} s",
                    tier.label(), pos.toShortString(), millis, link.name(), ServerConfig.hubSlowPauseSeconds);
            LogisticsSheets.writeNotice(workbook, LogisticsSheets.ensure(workbook).inventories(),
                    "Paused for " + ServerConfig.hubSlowPauseSeconds + " s: one cycle took " + millis
                            + " ms (the server allows " + ServerConfig.hubSlowCycleMs
                            + "). Use fewer or simpler formulas.");
        } else if (millis > SLOW_CYCLE_MS) {
            ExcelComputer.LOGGER.warn("{} at {} took {} ms to run {}",
                    tier.label(), pos.toShortString(), millis, link.name());
        }

        return tier.cycleTicks();
    }

    // Whether another hub, still trying, has waited longer than one due since dueSince.
    private static boolean olderWaiting(String key, long dueSince) {
        for (Map.Entry<String, Waiting> entry : WAITING.entrySet()) {
            if (!entry.getKey().equals(key) && entry.getValue().dueSince() < dueSince) {
                return true;
            }
        }

        return false;
    }

    private static void runCycle(WorkbookManager workbook, String key, ServerLevel level, BlockPos pos,
                                 HubTier tier, int cycles) {
        LogisticsSheets.Sheets sheets = LogisticsSheets.ensure(workbook);
        List<Entry> entries = inventories(key);
        readNames(workbook, sheets.inventories(), entries);

        // Find each inventory and count what it holds.
        Map<String, ItemPorts.Port> ports = new HashMap<>();
        List<String> statuses = new ArrayList<>();
        List<String[]> stock = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        // Each block's contents go into Stock once, under the name of its first
        // registered side: several sides of one block (a furnace's top and bottom)
        // would otherwise each list everything it holds. Block (dimension@pos) to that name.
        Map<String, String> counted = new HashMap<>();

        for (Entry entry : entries) {
            ServerLevel target = level(level.getServer(), entry.dimension);
            String status;

            if (target == null) {
                status = "Dimension not found";
            } else if (reachProblem(tier, level, pos, target, entry.pos) != null) {
                status = target != level && !tier.otherDimensions() ? "Other dimension" : "Out of range";
            } else if (!target.isLoaded(entry.pos)) {
                status = "Not loaded";
            } else {
                ItemPorts.Port port = ItemPorts.find(target, entry.pos, entry.side);
                status = port == null ? "No inventory or tank on the " + sideName(entry.side) + " side"
                        : entry.sideProblem != null ? entry.sideProblem : "OK";

                String block = entry.dimension + "@" + entry.pos.asLong();
                String countedAs = port == null ? null : counted.get(block);

                if (port != null) {
                    ports.putIfAbsent(entry.name.toLowerCase(Locale.ROOT), port);
                }

                if (countedAs != null) {
                    if (entry.sideProblem == null) {
                        status = "OK (in Stock as " + countedAs + ")";
                    }
                } else if (port != null) {
                    counted.put(block, entry.name);
                    Map<Item, Long> counts = new HashMap<>();
                    port.count(counts);
                    TreeMap<String, Long> sorted = new TreeMap<>();

                    for (Map.Entry<Item, Long> count : counts.entrySet()) {
                        sorted.merge(ItemFilter.id(count.getKey()), count.getValue(), Long::sum);
                    }

                    for (Map.Entry<String, Long> count : sorted.entrySet()) {
                        stock.add(new String[]{entry.name, count.getKey(), Long.toString(count.getValue())});
                    }

                    // Fluids, in millibuckets, after the items: "fluid:minecraft:water".
                    Map<Fluid, Long> fluids = new HashMap<>();
                    port.countFluids(fluids);
                    TreeMap<String, Long> sortedFluids = new TreeMap<>();

                    for (Map.Entry<Fluid, Long> count : fluids.entrySet()) {
                        sortedFluids.merge(FLUID_PREFIX + FluidPorts.id(count.getKey()), count.getValue(), Long::sum);
                    }

                    for (Map.Entry<String, Long> count : sortedFluids.entrySet()) {
                        stock.add(new String[]{entry.name, count.getKey(), Long.toString(count.getValue())});
                    }
                }
            }

            statuses.add(status);
            seen.add(entry.name + "|" + status);
        }

        // Storage networks (AE2, Refined Storage) cabled to the hub: a row each, after the inventories.
        List<String[]> networks = new ArrayList<>();

        if (level.getBlockEntity(pos) instanceof LogisticsHubBlockEntity hub) {
            for (NetworkLink link : hub.links()) {
                if (!link.connected()) {
                    continue;
                }

                String problem = link.problem();
                String status = problem != null ? problem : "OK";

                if (problem == null) {
                    NetworkStorage storage = link.storage();
                    FluidStore fluids = link.fluidStorage();
                    ports.putIfAbsent(link.label().toLowerCase(Locale.ROOT),
                            ItemPorts.network(level, pos, storage, fluids));
                    int listed = networkStock(link.label(), storage, fluids, stock);

                    if (listed >= 0) {
                        status = "OK (Stock lists its " + listed + " most common items)";
                    }
                }

                networks.add(new String[]{link.label(), status, link.modName()});
                seen.add(link.label() + "|" + status);
            }
        }

        for (String[] row : stock) {
            seen.add(String.join("|", row));
        }

        // Nothing changed since the last cycle, so the sheet would ask for the same moves,
        // and those moved nothing (otherwise the counts would differ).
        if (seen.equals(LAST_SEEN.get(key))) {
            return;
        }

        writeInventories(workbook, sheets.inventories(), entries, statuses, networks, level.getServer(), tier,
                HubDirectory.name(key, tier));
        int previousStock = STOCK_ROWS.getOrDefault(key, workbook.getLastUsedRow(sheets.stock()));
        LogisticsSheets.writeStock(workbook, sheets.stock(), stock, previousStock);
        STOCK_ROWS.put(key, stock.size());
        workbook.recalculate();

        long moved = runTransfers(workbook, sheets.transfers(), ports, (long) tier.itemsPerCycle() * cycles,
                (long) tier.fluidPerCycle() * cycles);

        // After moving items the counts are out of date: the next cycle runs in full.
        if (moved > 0) {
            LAST_SEEN.remove(key);
        } else {
            LAST_SEEN.put(key, seen);
        }
    }

    // Picks up names the player changed in the Inventories sheet (matched by position and side).
    /**
     * A player saved the hub's workbook: inventories whose row they deleted (or
     * cleared) from the Inventories sheet are unregistered, and the rest take
     * the order of the rows. Only on a player's save, so a sheet that is new or
     * damaged never unregisters anything by itself, and only inventories that
     * were in the copy the player downloaded: one registered since (by them or
     * another player) has no row in their copy, but wasn't deleted.
     */
    static void applySavedInventories(ServerPlayer player, ServerLevel level, BlockPos pos) {
        String key = LinkedWorkbooks.key(level, pos);
        Long downloadedAt = DOWNLOADED.get(player.getUUID() + "|" + key);

        // No record of their download (e.g. the server restarted since): don't guess.
        if (downloadedAt == null) {
            return;
        }

        LinkedWorkbooks.Link link = LINKED.link(level, pos);
        WorkbookManager workbook = link == null ? null : LINKED.workbook(link);

        if (workbook == null) {
            return;
        }

        int sheet = workbook.findSheet(LogisticsSheets.INVENTORIES);

        // No Inventories sheet at all: more likely a mistake than "remove everything".
        if (sheet < 0) {
            return;
        }

        List<Entry> entries = inventories(key);
        List<Entry> kept = new ArrayList<>(matchRows(workbook, sheet, entries).values());

        // Registered after their copy was downloaded: not in it, so not deleted from it.
        for (Entry entry : entries) {
            if (!kept.contains(entry) && entry.addedAt > downloadedAt) {
                kept.add(entry);
            }
        }

        if (kept.size() != entries.size() || !kept.equals(entries)) {
            entries.clear();
            entries.addAll(kept);
            changed(key);
        }
    }

    // Which inventory each row of the Inventories sheet is (row to inventory, in
    // row order), matched by position and dimension. When one block is registered
    // more than once, the row's name decides, then its side, then the order.
    private static Map<Integer, Entry> matchRows(WorkbookManager workbook, int sheet, List<Entry> entries) {
        Map<Integer, Entry> matched = new TreeMap<>();
        List<Entry> left = new ArrayList<>(entries);

        for (int row = 1; row <= workbook.getLastUsedRow(sheet) && !left.isEmpty(); row++) {
            Double x = workbook.getCellNumber(sheet, row, LogisticsSheets.X);
            Double y = workbook.getCellNumber(sheet, row, LogisticsSheets.Y);
            Double z = workbook.getCellNumber(sheet, row, LogisticsSheets.Z);

            if (x == null || y == null || z == null) {
                continue;
            }

            BlockPos pos = new BlockPos((int) Math.round(x), (int) Math.round(y), (int) Math.round(z));
            String dimension = workbook.getCellText(sheet, row, LogisticsSheets.DIMENSION).trim();
            String name = workbook.getCellText(sheet, row, LogisticsSheets.NAME).trim();
            Direction side = parseSide(workbook.getCellText(sheet, row, LogisticsSheets.SIDE));
            Entry best = null;
            int bestScore = -1;

            for (Entry entry : left) {
                if (!entry.pos.equals(pos) || !dimensionName(entry.dimension).equals(dimension)) {
                    continue;
                }

                int score = (entry.name.equals(name) ? 2 : 0) + (entry.side == side ? 1 : 0);

                if (score > bestScore) {
                    best = entry;
                    bestScore = score;
                }
            }

            if (best != null) {
                matched.put(row, best);
                left.remove(best);
            }
        }

        return matched;
    }

    // Picks up what the player changed in the Inventories sheet: names and sides.
    // Rows are matched to inventories by position (see matchRows); an inventory
    // without a row keeps its settings (only a player's save removes it).
    private static void readNames(WorkbookManager workbook, int sheet, List<Entry> entries) {
        boolean changed = false;

        for (Entry entry : entries) {
            entry.sideProblem = null;
        }

        for (Map.Entry<Integer, Entry> match : matchRows(workbook, sheet, entries).entrySet()) {
            Entry entry = match.getValue();
            int row = match.getKey();
            String name = workbook.getCellText(sheet, row, LogisticsSheets.NAME).trim();

            if (!name.isEmpty() && !entry.name.equals(name)) {
                entry.name = name;
                changed = true;
            }

            String typed = workbook.getCellText(sheet, row, LogisticsSheets.SIDE).trim();

            if (typed.isEmpty()) {
                continue;
            }

            Direction side = parseSide(typed);

            if (side == null) {
                entry.sideProblem = "Side \"" + typed + "\"? Use Top, Bottom, North, South, East, West,"
                        + " or Input, Fuel, Output";
            } else if (side != entry.side) {
                entry.side = side;
                changed = true;
            }
        }

        if (changed) {
            saveInventories();
        }
    }

    // Adds a network's items to Stock: the most common ones (ServerConfig.hubNetworkStockRows),
    // since a network can hold thousands of kinds. Returns how many were listed if
    // that cut any off, or -1 if all were.
    private static int networkStock(String name, NetworkStorage storage, FluidStore fluids, List<String[]> stock) {
        Map<String, Long> counts = new HashMap<>();

        for (NetworkStorage.Held held : storage.contents()) {
            counts.merge(ItemFilter.id(held.item().getItem()), held.amount(), Long::sum);
        }

        if (fluids != null) {
            for (FluidStore.Held held : fluids.contents()) {
                counts.merge(FLUID_PREFIX + FluidPorts.id(held.fluid()), held.amount(), Long::sum);
            }
        }

        List<Map.Entry<String, Long>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        int listed = Math.min(sorted.size(), ServerConfig.hubNetworkStockRows);
        TreeMap<String, Long> kept = new TreeMap<>();

        for (Map.Entry<String, Long> count : sorted.subList(0, listed)) {
            kept.put(count.getKey(), count.getValue());
        }

        for (Map.Entry<String, Long> count : kept.entrySet()) {
            stock.add(new String[]{name, count.getKey(), Long.toString(count.getValue())});
        }

        return listed < sorted.size() ? listed : -1;
    }

    // networks: name, status and mod of each storage network, written after the inventories.
    private static void writeInventories(WorkbookManager workbook, int sheet, List<Entry> entries,
                                         List<String> statuses, List<String[]> networks, MinecraftServer server,
                                         HubTier tier, String name) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int row = i + 1;
            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.NAME, entry.name);
            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.X, Integer.toString(entry.pos.getX()));
            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.Y, Integer.toString(entry.pos.getY()));
            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.Z, Integer.toString(entry.pos.getZ()));
            // A side the player mistyped stays as typed (so they can fix it); the Status says why.
            if (entry.sideProblem == null) {
                LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.SIDE, sideName(entry.side));
            }
            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.DIMENSION, dimensionName(entry.dimension));
            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.INVENTORY_STATUS, statuses.get(i));

            ServerLevel level = level(server, entry.dimension);

            if (level != null && level.isLoaded(entry.pos)) {
                LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.BLOCK,
                        blockName(level.getBlockState(entry.pos)));
            }
        }

        // Networks have no position or side: those cells stay blank, so a network row
        // never matches an inventory (see matchRows) and saving never unregisters one.
        for (int i = 0; i < networks.size(); i++) {
            String[] network = networks.get(i);
            int row = entries.size() + i + 1;
            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.NAME, network[0]);
            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.X, "");
            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.Y, "");
            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.Z, "");
            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.SIDE, "");
            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.DIMENSION, "");
            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.BLOCK, network[2]);
            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.INVENTORY_STATUS, network[1]);
        }

        // Rows left over from inventories that were removed.
        int last = workbook.getLastUsedRow(sheet);
        LogisticsSheets.clearRows(workbook, sheet, entries.size() + networks.size() + 1, last + 1,
                LogisticsSheets.INVENTORY_STATUS + 1);
        LogisticsSheets.writeGuide(workbook, sheet, name, tier);
    }

    // Works through the Transfers rows; returns how many items and millibuckets moved in all.
    // Items and fluids each have their own limit per cycle (budget, fluidBudget).
    private static long runTransfers(WorkbookManager workbook, int sheet, Map<String, ItemPorts.Port> ports,
                                     long budget, long fluidBudget) {
        long total = 0;

        for (int row = 1; row <= workbook.getLastUsedRow(sheet); row++) {
            String from = workbook.getCellText(sheet, row, LogisticsSheets.FROM).trim();
            String to = workbook.getCellText(sheet, row, LogisticsSheets.TO).trim();
            String items = workbook.getCellText(sheet, row, LogisticsSheets.ITEM).trim();

            if (from.isEmpty() && to.isEmpty() && items.isEmpty()) {
                continue;
            }

            String status;

            try {
                status = ItemFilter.isFluid(items)
                        ? transferFluid(workbook, sheet, row, from, to, items, ports, fluidBudget)
                        : transfer(workbook, sheet, row, from, to, items, ports, budget);
            } catch (ItemFilter.BadFilter exception) {
                status = exception.getMessage();
            }

            if (status.startsWith("Moved ") && status.endsWith(MB)) {
                long moved = Long.parseLong(status.substring("Moved ".length(), status.length() - MB.length()));
                fluidBudget -= moved;
                total += moved;
            } else if (status.startsWith("Moved ")) {
                long moved = Long.parseLong(status.substring("Moved ".length()));
                budget -= moved;
                total += moved;
            }

            LogisticsSheets.put(workbook, sheet, row, LogisticsSheets.TRANSFER_STATUS, status);
        }

        return total;
    }

    // One Transfers row; returns its status ("Moved 16", or why nothing moved).
    private static String transfer(WorkbookManager workbook, int sheet, int row, String from, String to,
                                   String items, Map<String, ItemPorts.Port> ports, long budget)
            throws ItemFilter.BadFilter {
        long amount = amount(workbook, sheet, row);

        if (amount < 0) {
            return "Amount must be a number, ALL or blank";
        }

        if (amount == 0) {
            return "Skipped (amount 0)";
        }

        if (from.isEmpty() || to.isEmpty()) {
            return "Needs both From and To";
        }

        ItemPorts.Port source = ports.get(from.toLowerCase(Locale.ROOT));
        ItemPorts.Port target = ports.get(to.toLowerCase(Locale.ROOT));

        if (source == null || target == null) {
            return "Can't use " + (source == null ? from : to) + " (see the Inventories sheet)";
        }

        ItemFilter<Item> filter = ItemFilter.parse(items);

        if (budget <= 0) {
            return "Waiting: the hub moved its limit this cycle";
        }

        long moved = ItemPorts.move(source, target, filter, Math.min(amount, budget));

        if (moved > 0) {
            return "Moved " + moved;
        }

        // Say why: nothing there, or something there that couldn't go.
        Map<Item, Long> counts = new HashMap<>();
        source.count(counts);
        long matching = 0;

        for (Map.Entry<Item, Long> count : counts.entrySet()) {
            if (filter.test(count.getKey())) {
                matching += count.getValue();
            }
        }

        return matching == 0
                ? "Nothing to move"
                : "Stuck: " + to + " is full or won't take these on its side, or " + from + " won't give them out";
    }

    // One Transfers row whose Item starts with "fluid:"; Amount is in millibuckets.
    // Returns its status ("Moved 1000 mB", or why nothing moved).
    private static String transferFluid(WorkbookManager workbook, int sheet, int row, String from, String to,
                                        String items, Map<String, ItemPorts.Port> ports, long budget)
            throws ItemFilter.BadFilter {
        long amount = amount(workbook, sheet, row);

        if (amount < 0) {
            return "Amount must be a number (mB), ALL or blank";
        }

        if (amount == 0) {
            return "Skipped (amount 0)";
        }

        if (from.isEmpty() || to.isEmpty()) {
            return "Needs both From and To";
        }

        ItemPorts.Port source = ports.get(from.toLowerCase(Locale.ROOT));
        ItemPorts.Port target = ports.get(to.toLowerCase(Locale.ROOT));

        if (source == null || target == null) {
            return "Can't use " + (source == null ? from : to) + " (see the Inventories sheet)";
        }

        ItemFilter<Fluid> filter = ItemFilter.parseFluids(items);

        if (budget <= 0) {
            return "Waiting: the hub moved its fluid limit this cycle";
        }

        long moved = ItemPorts.moveFluids(source, target, filter, Math.min(amount, budget));

        if (moved > 0) {
            return "Moved " + moved + MB;
        }

        Map<Fluid, Long> counts = new HashMap<>();
        source.countFluids(counts);
        long matching = 0;

        for (Map.Entry<Fluid, Long> count : counts.entrySet()) {
            if (filter.test(count.getKey())) {
                matching += count.getValue();
            }
        }

        return matching == 0
                ? "Nothing to move"
                : "Stuck: " + to + " is full or has no tank on its side, or " + from + " won't give it out";
    }

    // The Amount cell: a number (rounded down), Long.MAX_VALUE for blank/ALL/TRUE,
    // 0 for FALSE, -1 for anything else.
    private static long amount(WorkbookManager workbook, int sheet, int row) {
        Double number = workbook.getCellNumber(sheet, row, LogisticsSheets.AMOUNT);

        if (number != null && !number.isNaN()) {
            return number <= 0 ? 0 : (long) Math.min(Math.floor(number), Long.MAX_VALUE);
        }

        String text = workbook.getCellText(sheet, row, LogisticsSheets.AMOUNT).trim();

        if (text.isEmpty() || text.equalsIgnoreCase("ALL") || text.equalsIgnoreCase("TRUE")) {
            return Long.MAX_VALUE;
        }

        return text.equalsIgnoreCase("FALSE") ? 0 : -1;
    }

    // ----- dimensions -----

    // The level whose dimension().toString() is this, or null.
    static ServerLevel level(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().toString().equals(dimension)) {
                return level;
            }
        }

        return null;
    }

    // "minecraft:overworld" from "ResourceKey[minecraft:dimension / minecraft:overworld]".
    private static String dimensionName(String dimension) {
        int slash = dimension.lastIndexOf('/');
        String name = slash >= 0 ? dimension.substring(slash + 1) : dimension;
        return name.replace("]", "").trim();
    }

    // ----- inventories.txt: one line per inventory -----
    // <hub key> TAB <dimension> TAB <x> TAB <y> TAB <z> TAB <side> TAB <name>

    private static List<Entry> inventories(String hubKey) {
        ensureLoaded();
        return INVENTORIES.computeIfAbsent(hubKey, key -> new ArrayList<>());
    }

    // Reads inventories.txt the first time they are needed on a server.
    private static void ensureLoaded() {
        if (!inventoriesLoaded) {
            inventoriesLoaded = true;
            loadInventories();
        }
    }

    private static void loadInventories() {
        try {
            Path file = LINKED.directory().resolve("inventories.txt");

            if (!Files.isRegularFile(file)) {
                return;
            }

            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", -1);

                if (parts.length != 7) {
                    continue;
                }

                try {
                    Direction side = Direction.byName(parts[5]);
                    BlockPos pos = new BlockPos(Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                            Integer.parseInt(parts[4]));

                    if (side != null) {
                        INVENTORIES.computeIfAbsent(parts[0], key -> new ArrayList<>())
                                .add(new Entry(parts[1], pos, side, parts[6]));
                    }
                } catch (NumberFormatException exception) {
                    ExcelComputer.LOGGER.warn("Skipping a bad Logistics Hub inventory: {}", line);
                }
            }
        } catch (IOException exception) {
            ExcelComputer.LOGGER.warn("Could not read the Logistics Hub inventories: {}", exception.toString());
        }
    }

    private static void saveInventories() {
        StringBuilder text = new StringBuilder();

        for (Map.Entry<String, List<Entry>> hub : INVENTORIES.entrySet()) {
            for (Entry entry : hub.getValue()) {
                text.append(hub.getKey()).append('\t')
                        .append(entry.dimension).append('\t')
                        .append(entry.pos.getX()).append('\t')
                        .append(entry.pos.getY()).append('\t')
                        .append(entry.pos.getZ()).append('\t')
                        .append(entry.side.getName()).append('\t')
                        .append(entry.name.replaceAll("[\\t\\r\\n]", " ")).append('\n');
            }
        }

        try {
            LinkedWorkbooks.writeAtomically(LINKED.directory().resolve("inventories.txt"),
                    text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            ExcelComputer.LOGGER.warn("Could not save the Logistics Hub inventories: {}", exception.toString());
        }
    }
}
