package io.github.reverendbrown.excelcomputer.link;

import io.github.reverendbrown.excelcomputer.ExcelComputer;
import io.github.reverendbrown.excelcomputer.WorkbookManager;
import io.github.reverendbrown.excelcomputer.logistics.HubDirectory;
import io.github.reverendbrown.excelcomputer.logistics.LogisticsHubs;
import io.github.reverendbrown.excelcomputer.redstone.RedstoneConnectors;
import io.github.reverendbrown.excelcomputer.share.ShareProtocol;
import io.github.reverendbrown.excelcomputer.share.ShareServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Workbooks linked to blocks in the world (Redstone Connectors, Logistics Hubs).
 *
 * <p>Each kind of block has one instance. Every linked block has its own copy of
 * a workbook in {@code <world>/excelcomputer/<folder>/<id>.xlsx}, and
 * {@code links.properties} records which block (dimension and position) uses
 * which copy. A player links a workbook by uploading it with Excel on a Stick,
 * and edits it by downloading the copy and saving it back.
 *
 * <p>Everything here runs on the server thread. The state belongs to one
 * server at a time (a singleplayer player can switch worlds), see {@link #forServer}.
 */
public final class LinkedWorkbooks {
    // Most workbooks of one kind kept open at once; the least recently used is closed.
    private static final int MAX_OPEN = 32;
    // How close a player must be to open or link a block.
    private static final double REACH = 8;

    public record Link(UUID id, String name) {
    }

    public record Result(boolean ok, String message) {
    }

    /** What happens around a link: called with the server's level and the block's position. */
    public interface Callback {
        void run(ServerLevel level, BlockPos pos);
    }

    /** Like Callback, for something a player did (downloaded or saved the workbook). */
    public interface PlayerCallback {
        void run(ServerPlayer player, ServerLevel level, BlockPos pos);
    }

    private final String folder;
    private final String label;
    private final Predicate<BlockState> isBlock;
    // Adds the sheets the block needs to a workbook that is being linked.
    private final Consumer<WorkbookManager> prepare;
    private Callback onStored = (level, pos) -> {
    };
    private PlayerCallback onSaved = (player, level, pos) -> {
    };
    private PlayerCallback onSent = (player, level, pos) -> {
    };
    private Runnable onReset = () -> {
    };
    private Template template;
    private java.util.function.Supplier<Limits> limits = () -> null;
    private Access access = (player, level, pos) -> null;

    /** Who may open and save a block's workbook (e.g. a hub's owner and members). */
    public interface Access {
        /** Why this player may not open or save the block's workbook, or null if they may. */
        String check(ServerPlayer player, ServerLevel level, BlockPos pos);
    }

    /**
     * For blocks whose workbook the server makes (Logistics Hubs): the first
     * time a player opens the block, a fresh workbook is made from the template
     * instead of the player uploading one, so no one can link a file they
     * prepared elsewhere.
     */
    public interface Template {
        /** Why this player can't get a new workbook here (e.g. they own too many), or null. */
        String canCreate(ServerPlayer player, ServerLevel level, BlockPos pos);

        /** A new workbook with the block's sheets. */
        WorkbookManager create();

        /** The workbook's file name, e.g. "Iron Logistics Hub.xlsx". */
        String fileName(ServerLevel level, BlockPos pos);
    }

    /** The most a saved workbook may be: file size, unpacked size, filled cells and formulas. */
    public record Limits(long maxBytes, long maxUnpackedBytes, long maxCells, long maxFormulas) {
    }

    private MinecraftServer server;
    private final Map<String, Link> links = new HashMap<>();
    // Block key to the player editing its workbook now: one at a time, so no
    // one's save overwrites another's. Released when they close the workbook
    // (CONNECTOR_CLOSE) or leave the server; never saved.
    private final Map<String, UUID> editors = new HashMap<>();
    private final Map<UUID, WorkbookManager> open = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, WorkbookManager> eldest) {
            if (size() > MAX_OPEN) {
                closeQuietly(eldest.getValue());
                return true;
            }

            return false;
        }
    };

    /**
     * @param folder  the folder (under {@code <world>/excelcomputer}) the copies are kept in
     * @param label   what players call the block, e.g. "Redstone Connector"
     * @param isBlock whether a block is one of this kind
     * @param prepare adds the block's sheets to a workbook being linked (may throw)
     */
    public LinkedWorkbooks(String folder, String label, Predicate<BlockState> isBlock,
                           Consumer<WorkbookManager> prepare) {
        this.folder = folder;
        this.label = label;
        this.isBlock = isBlock;
        this.prepare = prepare;
    }

    /** Runs after a workbook was linked or saved to a block. */
    public LinkedWorkbooks onStored(Callback callback) {
        this.onStored = callback;
        return this;
    }

    /** Runs after a player saved (or linked) a workbook, before onStored; not for a template. */
    public LinkedWorkbooks onSaved(PlayerCallback callback) {
        this.onSaved = callback;
        return this;
    }

    /** Runs after a player was sent the workbook to edit. */
    public LinkedWorkbooks onSent(PlayerCallback callback) {
        this.onSent = callback;
        return this;
    }

    /** Runs when the state is dropped because a different server started (clear caches here). */
    public LinkedWorkbooks onReset(Runnable callback) {
        this.onReset = callback;
        return this;
    }

    /** Workbooks of this kind come from a template made by the server (see {@link Template}). */
    public LinkedWorkbooks template(Template template) {
        this.template = template;
        return this;
    }

    /** Who may open and save the blocks' workbooks (by default, anyone who reaches the block). */
    public LinkedWorkbooks access(Access access) {
        this.access = access;
        return this;
    }

    /** Limits for saved workbooks (asked each time, so a config change applies); null: the defaults. */
    public LinkedWorkbooks limits(java.util.function.Supplier<Limits> limits) {
        this.limits = limits;
        return this;
    }

    public String label() {
        return label;
    }

    /** Whether the block with this key (see {@link #key}) has a workbook. */
    public boolean isLinked(String key) {
        return links.containsKey(key);
    }

    /** The kind of linked block at pos, or null if it isn't one. */
    public static LinkedWorkbooks at(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }

        BlockState state = level.getBlockState(pos);

        // Every kind of linked block (listed here so their classes are loaded).
        for (LinkedWorkbooks kind : List.of(RedstoneConnectors.LINKED, LogisticsHubs.LINKED)) {
            if (kind.isBlock.test(state)) {
                return kind;
            }
        }

        return null;
    }

    // ----- the links -----

    /** The key of a block in the links: its dimension and position. */
    public static String key(ServerLevel level, BlockPos pos) {
        return level.dimension() + "@" + pos.asLong();
    }

    /** The block's link, or null if no workbook is linked. */
    public Link link(ServerLevel level, BlockPos pos) {
        forServer(level.getServer());
        return links.get(key(level, pos));
    }

    /** The linked workbook, opened (and kept open) if needed; null if it can't be read. */
    public WorkbookManager workbook(Link link) {
        WorkbookManager workbook = open.get(link.id());

        if (workbook != null) {
            return workbook;
        }

        try {
            workbook = WorkbookManager.open(file(link.id()));
            open.put(link.id(), workbook);
            return workbook;
        } catch (IOException | RuntimeException | LinkageError exception) {
            ExcelComputer.LOGGER.warn("Could not open the {} workbook {}: {}", label, link.name(), exception.toString());
            return null;
        }
    }

    /** The block was broken: forget its link and delete its workbook. */
    public void remove(ServerLevel level, BlockPos pos) {
        forServer(level.getServer());
        editors.remove(key(level, pos));
        Link link = links.remove(key(level, pos));

        if (link == null) {
            return;
        }

        closeQuietly(open.remove(link.id()));

        try {
            Files.deleteIfExists(file(link.id()));
            saveLinks();
        } catch (IOException exception) {
            ExcelComputer.LOGGER.warn("Could not delete a {}'s workbook: {}", label, exception.toString());
        }
    }

    // ----- linking and editing (called by ShareServer for a player's messages) -----

    /** Which linked block a player's message about pos means, and where it is. */
    public record Target(LinkedWorkbooks kind, ServerLevel level, boolean remote) {
    }

    /**
     * The linked block a player means by pos: one next to them, or else the
     * Logistics Hub they know (have right-clicked), which they may use from anywhere (even
     * another dimension). Null if there is neither.
     */
    public static Target resolve(ServerPlayer player, BlockPos pos) {
        ServerLevel here = (ServerLevel) player.level();
        LinkedWorkbooks kind = at(here, pos);

        if (kind != null && player.distanceToSqr(Vec3.atCenterOf(pos)) <= REACH * REACH) {
            return new Target(kind, here, false);
        }

        ServerLevel hubLevel = HubDirectory.levelOfKnown(player, pos);

        if (hubLevel != null) {
            return new Target(LogisticsHubs.LINKED, hubLevel, true);
        }

        // Too far away: checkReach says so.
        return kind != null ? new Target(kind, here, false) : null;
    }

    /**
     * Why the player can't use the block at pos (too far, not this kind any
     * more), or null. A remote block (the player's chosen hub) may be anywhere.
     */
    public String checkReach(ServerPlayer player, ServerLevel level, BlockPos pos, boolean remote) {
        if (remote) {
            // Unloaded is fine: breaking the hub clears every player's choice of it.
            return level.isLoaded(pos) && !isBlock.test(level.getBlockState(pos))
                    ? "There is no " + label + " there any more"
                    : null;
        }

        if (!level.isLoaded(pos) || !isBlock.test(level.getBlockState(pos))) {
            return "There is no " + label + " there any more";
        }

        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            return "Move closer to the " + label;
        }

        return null;
    }

    /**
     * Why the player can't open or save the block's workbook now, or null:
     * checkReach, then whether they may use it at all, then whether someone
     * else is editing it.
     */
    public String checkUse(ServerPlayer player, ServerLevel level, BlockPos pos, boolean remote) {
        forServer(level.getServer());
        String problem = checkReach(player, level, pos, remote);

        if (problem == null) {
            problem = access.check(player, level, pos);
        }

        return problem != null ? problem : editingProblem(player, key(level, pos));
    }

    // Why the player can't edit: someone else (still online) has it open. Null if no one does.
    private String editingProblem(ServerPlayer player, String key) {
        UUID editor = editors.get(key);

        if (editor == null || editor.equals(player.getUUID())) {
            return null;
        }

        ServerPlayer other = server.getPlayerList().getPlayer(editor);

        if (other == null) {
            // They left without closing it.
            editors.remove(key);
            return null;
        }

        return other.getName().getString() + " is editing this " + label
                + " right now. Only one person can edit it at a time";
    }

    /** The player closed the workbook of the linked block at pos: someone else may edit it now. */
    public static void releaseAll(ServerPlayer player, BlockPos pos) {
        for (LinkedWorkbooks kind : List.of(RedstoneConnectors.LINKED, LogisticsHubs.LINKED)) {
            kind.release(player, pos);
        }
    }

    // Releases the player's edit of this kind's block at pos (in any dimension).
    private void release(ServerPlayer player, BlockPos pos) {
        if (server == null) {
            return;
        }

        String suffix = "@" + pos.asLong();
        editors.entrySet().removeIf(entry -> entry.getKey().endsWith(suffix)
                && entry.getValue().equals(player.getUUID()));
    }

    /** Sends the block's workbook to the player to edit (requestId names the download). */
    public void sendForEditing(ServerPlayer player, UUID requestId, ServerLevel level, BlockPos pos, boolean remote) {
        forServer(level.getServer());
        String problem = checkUse(player, level, pos, remote);

        if (problem != null) {
            ShareServer.downloadFailed(player, requestId, problem);
            return;
        }

        Link link = links.get(key(level, pos));

        if (link == null && template != null) {
            problem = template.canCreate(player, level, pos);

            if (problem != null) {
                ShareServer.downloadFailed(player, requestId, problem);
                return;
            }

            link = createFromTemplate(level, pos);

            if (link == null) {
                ShareServer.downloadFailed(player, requestId, "The server could not make the workbook");
                return;
            }
        }

        if (link == null) {
            ShareServer.downloadFailed(player, requestId,
                    "No workbook is linked yet. Right-click the " + label + " holding Excel on a Stick to link one");
            return;
        }

        byte[] data;

        try {
            // Saved first, so the copy shows what the game wrote into it last.
            WorkbookManager workbook = workbook(link);

            if (workbook != null) {
                workbook.save(file(link.id()));
            }

            data = Files.readAllBytes(file(link.id()));
        } catch (IOException | RuntimeException exception) {
            ExcelComputer.LOGGER.warn("Could not read a {}'s workbook: {}", label, exception.toString());
            ShareServer.downloadFailed(player, requestId, "The server could not read the linked workbook");
            return;
        }

        // Theirs to edit until they close it (or leave).
        editors.put(key(level, pos), player.getUUID());
        onSent.run(player, level, pos);
        ShareServer.sendWorkbook(player, requestId, link.name(), data, true, label, Long.MAX_VALUE);
    }

    /**
     * Stores a workbook the player linked or saved for the block at pos: it
     * gets the block's sheets if it has none and replaces the block's copy.
     *
     * @return the message for the player; ok says whether it worked
     */
    public Result store(ServerPlayer player, ServerLevel level, BlockPos pos, boolean remote, String name, byte[] data) {
        forServer(level.getServer());
        String problem = checkUse(player, level, pos, remote);

        if (problem != null) {
            return new Result(false, problem);
        }

        // Nobody else is editing it (checkUse): it's theirs while their copy is open.
        editors.put(key(level, pos), player.getUUID());

        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            return new Result(false, "Only .xlsx workbooks can be linked (a .csv has only one sheet)");
        }

        String key = key(level, pos);
        Link old = links.get(key);

        // Template blocks only take back the workbook the server made for them.
        if (template != null && old == null) {
            return new Result(false, "The " + label + " makes its own workbook: right-click it to open it");
        }

        Limits limit = limits.get();

        if (limit != null && data.length > limit.maxBytes()) {
            return new Result(false, "Too big to save to the " + label + " (the limit is "
                    + limit.maxBytes() / 1024 + " KB)");
        }

        // An .xlsx is a zip: make sure it can't unpack into something huge before POI opens it.
        String unsafe = limit != null
                ? ShareProtocol.checkSafeToOpen(name, data, limit.maxUnpackedBytes())
                : ShareProtocol.checkSafeToOpen(name, data);

        if (unsafe != null) {
            return new Result(false, unsafe);
        }

        // A template block keeps its workbook's name; the client's copy may be called anything.
        if (template != null) {
            name = old.name();
        }
        UUID id = old != null ? old.id() : UUID.randomUUID();
        Path target;
        Path upload;

        try {
            target = file(id);
            upload = target.resolveSibling(id + ".upload.xlsx");
            Files.write(upload, data);
        } catch (IOException exception) {
            return new Result(false, "The server could not store the workbook");
        }

        WorkbookManager workbook = null;

        try {
            workbook = WorkbookManager.open(upload);

            if (limit != null) {
                long[] content = workbook.countContent(limit.maxCells(), limit.maxFormulas());

                if (content[0] > limit.maxCells() || content[1] > limit.maxFormulas()) {
                    closeQuietly(workbook);
                    return new Result(false, "Too much for the " + label + ": at most " + limit.maxCells()
                            + " filled cells and " + limit.maxFormulas() + " formulas (this has "
                            + content[0] + " and " + content[1] + ")");
                }
            }

            prepare.accept(workbook);
            workbook.save(target);
        } catch (IOException | RuntimeException | LinkageError exception) {
            closeQuietly(workbook);
            ExcelComputer.LOGGER.warn("Could not link a workbook to a {}: {}", label, exception.toString());
            return new Result(false, "That workbook could not be opened on the server");
        } finally {
            try {
                Files.deleteIfExists(upload);
            } catch (IOException ignored) {
                // Left for next time.
            }
        }

        closeQuietly(open.remove(id));
        open.put(id, workbook);
        links.put(key, new Link(id, name));

        try {
            saveLinks();
        } catch (IOException exception) {
            ExcelComputer.LOGGER.warn("Could not save the {} links: {}", label, exception.toString());
        }

        // A player's save (not a template being made): onSaved sees what they changed.
        onSaved.run(player, level, pos);
        onStored.run(level, pos);

        return new Result(true, old == null
                ? "Linked " + name + " to the " + label
                : "Saved, and updated the " + label);
    }

    // Makes the block's workbook from the template and links it; null if it can't be written.
    private Link createFromTemplate(ServerLevel level, BlockPos pos) {
        UUID id = UUID.randomUUID();
        WorkbookManager workbook = null;

        try {
            workbook = template.create();
            workbook.save(file(id));
        } catch (IOException | RuntimeException | LinkageError exception) {
            closeQuietly(workbook);
            ExcelComputer.LOGGER.warn("Could not make a {}'s workbook: {}", label, exception.toString());
            return null;
        }

        Link link = new Link(id, template.fileName(level, pos));
        open.put(id, workbook);
        links.put(key(level, pos), link);

        try {
            saveLinks();
        } catch (IOException exception) {
            ExcelComputer.LOGGER.warn("Could not save the {} links: {}", label, exception.toString());
        }

        onStored.run(level, pos);
        return link;
    }

    // ----- storage -----

    /**
     * Makes sure the state belongs to this server; a different server (another
     * world) starts afresh. Call before using anything that depends on the world.
     */
    public void forServer(MinecraftServer current) {
        if (current == server) {
            return;
        }

        for (WorkbookManager workbook : open.values()) {
            closeQuietly(workbook);
        }

        open.clear();
        links.clear();
        editors.clear();
        server = current;
        io.github.reverendbrown.excelcomputer.ServerConfig.load();
        onReset.run();
        loadLinks();
    }

    /** The folder this kind keeps its files in (created if needed). */
    public Path directory() throws IOException {
        Path directory = server.getWorldPath(LevelResource.ROOT)
                .resolve("excelcomputer")
                .resolve(folder);
        Files.createDirectories(directory);
        return directory;
    }

    private Path file(UUID id) throws IOException {
        return directory().resolve(id + ".xlsx");
    }

    // links.properties: <dimension>@<position> = <id>|<workbook name>
    private void loadLinks() {
        try {
            Path file = directory().resolve("links.properties");

            if (!Files.isRegularFile(file)) {
                return;
            }

            Properties properties = new Properties();

            try (InputStream input = Files.newInputStream(file)) {
                properties.load(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
            }

            for (String key : properties.stringPropertyNames()) {
                String value = properties.getProperty(key);
                int bar = value.indexOf('|');

                try {
                    links.put(key, new Link(UUID.fromString(value.substring(0, bar)), value.substring(bar + 1)));
                } catch (RuntimeException exception) {
                    ExcelComputer.LOGGER.warn("Skipping a bad {} link: {}", label, key);
                }
            }
        } catch (IOException exception) {
            ExcelComputer.LOGGER.warn("Could not read the {} links: {}", label, exception.toString());
        }
    }

    private void saveLinks() throws IOException {
        Properties properties = new Properties();

        for (Map.Entry<String, Link> entry : links.entrySet()) {
            properties.setProperty(entry.getKey(), entry.getValue().id() + "|" + entry.getValue().name());
        }

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

        try (java.io.Writer writer = new java.io.OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
            properties.store(writer, "Excel on a Stick: which workbook each " + label + " uses");
        }

        writeAtomically(directory().resolve("links.properties"), bytes.toByteArray());
    }

    /** Writes a file through a temporary file, so a crash never leaves half of it. */
    public static void writeAtomically(Path path, byte[] data) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");

        try (OutputStream output = Files.newOutputStream(temp)) {
            output.write(data);
        }

        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void closeQuietly(WorkbookManager workbook) {
        if (workbook == null) {
            return;
        }

        try {
            workbook.close();
        } catch (IOException | RuntimeException ignored) {
            // Nothing to keep.
        }
    }
}
