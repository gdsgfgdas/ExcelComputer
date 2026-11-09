package io.github.reverendbrown.excelcomputer;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Server settings for admins, in {@code config/excelcomputer-server.properties}.
 * The file is written with the defaults and an explanation the first time a
 * world starts, and read again every time one starts (restart to apply edits).
 * Values that are missing or can't be read fall back to the defaults.
 */
public final class ServerConfig {
    private static final String FILE_NAME = "excelcomputer-server.properties";

    // ----- Logistics Hubs -----

    /** How many linked hubs one player may own. */
    public static int maxHubsPerPlayer = 16;
    /** Largest hub workbook a player may save, in KB (as an .xlsx file). */
    public static int hubWorkbookMaxKb = 1024;
    /** Largest a hub workbook may unpack to, in MB (an .xlsx is a zip). */
    public static int hubWorkbookMaxUnpackedMb = 10;
    /** Most filled cells a hub workbook may have. */
    public static int hubWorkbookMaxCells = 50_000;
    /** Most formulas a hub workbook may have. */
    public static int hubWorkbookMaxFormulas = 5_000;
    /** Milliseconds all hubs together may spend per server tick; the rest wait for the next tick. */
    public static int hubTickBudgetMs = 20;
    /** A hub whose cycle takes longer than this (ms) is paused, so it can't lag the server. */
    public static int hubSlowCycleMs = 250;
    /** How long a slow hub stays paused, in seconds. */
    public static int hubSlowPauseSeconds = 30;
    /** A hub that had to wait moves up to this many cycles' worth of items at once to catch up. */
    public static int hubCatchUpCycles = 4;

    private ServerConfig() {
    }

    private static final String TEMPLATE = """
            # Excel on a Stick: server settings. Restart the server (or reopen the world) after editing.

            # Logistics Hubs
            # How many linked hubs one player may own (the player who placed a hub owns it).
            maxHubsPerPlayer=%d
            # Largest hub workbook a player may save: as an .xlsx file (KB), and unpacked (MB).
            hubWorkbookMaxKb=%d
            hubWorkbookMaxUnpackedMb=%d
            # Most filled cells and formulas a hub workbook may have.
            hubWorkbookMaxCells=%d
            hubWorkbookMaxFormulas=%d
            # Milliseconds all hubs together may spend recalculating per server tick (a tick is 50 ms).
            # Hubs over the budget wait for the next tick, oldest first, and catch up on items afterwards.
            hubTickBudgetMs=%d
            # A hub whose cycle takes longer than this (ms) is paused for hubSlowPauseSeconds.
            hubSlowCycleMs=%d
            hubSlowPauseSeconds=%d
            # A hub that had to wait moves up to this many cycles' worth of items at once.
            hubCatchUpCycles=%d
            """;

    /** Reads the file from the config folder, creating it with the defaults if it's missing. */
    public static void load() {
        Path file = configDirectory().resolve(FILE_NAME);

        try {
            if (!Files.isRegularFile(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, TEMPLATE.formatted(maxHubsPerPlayer, hubWorkbookMaxKb,
                        hubWorkbookMaxUnpackedMb, hubWorkbookMaxCells, hubWorkbookMaxFormulas, hubTickBudgetMs,
                        hubSlowCycleMs, hubSlowPauseSeconds, hubCatchUpCycles), StandardCharsets.UTF_8);
                return;
            }

            Properties properties = new Properties();

            try (InputStream input = Files.newInputStream(file);
                 Reader reader = new java.io.InputStreamReader(input, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }

            maxHubsPerPlayer = read(properties, "maxHubsPerPlayer", 16, 0, 10_000);
            hubWorkbookMaxKb = read(properties, "hubWorkbookMaxKb", 1024, 16, 4096);
            hubWorkbookMaxUnpackedMb = read(properties, "hubWorkbookMaxUnpackedMb", 10, 1, 50);
            hubWorkbookMaxCells = read(properties, "hubWorkbookMaxCells", 50_000, 100, 10_000_000);
            hubWorkbookMaxFormulas = read(properties, "hubWorkbookMaxFormulas", 5_000, 10, 1_000_000);
            hubTickBudgetMs = read(properties, "hubTickBudgetMs", 20, 1, 1000);
            hubSlowCycleMs = read(properties, "hubSlowCycleMs", 250, 10, 60_000);
            hubSlowPauseSeconds = read(properties, "hubSlowPauseSeconds", 30, 1, 3600);
            hubCatchUpCycles = read(properties, "hubCatchUpCycles", 4, 1, 100);
        } catch (IOException | RuntimeException exception) {
            ExcelComputer.LOGGER.warn("Could not read {}, using the defaults: {}", file, exception.toString());
        }
    }

    private static int read(Properties properties, String key, int fallback, int min, int max) {
        String value = properties.getProperty(key);

        if (value == null) {
            return fallback;
        }

        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
        } catch (NumberFormatException exception) {
            ExcelComputer.LOGGER.warn("{} in {} is not a number: {}", key, FILE_NAME, value);
            return fallback;
        }
    }

    private static Path configDirectory() {
        //? if fabric {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        //?} elif neoforge {
        /*return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
        *///?} else {
        /*return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
        *///?}
    }
}
