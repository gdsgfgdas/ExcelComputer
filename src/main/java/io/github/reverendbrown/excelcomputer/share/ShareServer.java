package io.github.reverendbrown.excelcomputer.share;

import io.github.reverendbrown.excelcomputer.ExcelComputer;
import io.github.reverendbrown.excelcomputer.item.SharedWorkbookItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import io.github.reverendbrown.excelcomputer.item.ModItems;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * The server half of workbook sharing. Shared files are stored as plain bytes
 * in {@code <world>/excelcomputer/shared}: {@code <id>.data} holds the file
 * and {@code <id>.properties} its name and owner. The server never opens them
 * with POI.
 *
 * <p>Everything here runs on the server thread (the loader glue makes sure of it).
 */
public final class ShareServer {
    // Minimum time between two finished uploads from one player.
    private static final long UPLOAD_COOLDOWN_MS = 2_000;
    // An upload that hasn't finished after this long is dropped.
    private static final long UPLOAD_TIMEOUT_MS = 120_000;
    // Minimum time between two downloads by one player (each can be up to 4 MB).
    private static final long DOWNLOAD_COOLDOWN_MS = 3_000;
    // Minimum time between two "list my shared sheets" requests.
    private static final long LIST_COOLDOWN_MS = 500;
    // Most shared sheets one player can have at a time.
    public static final int MAX_SHARES_PER_PLAYER = 10;
    // Most disk space all shared sheets together may use, so many accounts can't fill the disk.
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
    private static final Map<UUID, Long> LAST_UPLOAD = new HashMap<>();
    private static final Map<UUID, Long> LAST_DOWNLOAD = new HashMap<>();
    private static final Map<UUID, Long> LAST_LIST = new HashMap<>();

    private static final class Upload {
        final int transfer;
        final String name;
        final byte[] data;
        final UUID target;
        final long startedAt = System.currentTimeMillis();
        int received;

        Upload(int transfer, String name, int size, UUID target) {
            this.transfer = transfer;
            this.name = name;
            this.data = new byte[size];
            this.target = target;
        }
    }

    // expiresAt is real-world time (epoch milliseconds), so it runs out even while the server is off.
    private record Meta(String name, UUID owner, String ownerName, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }

    private ShareServer() {
    }

    public static void handle(ServerPlayer player, byte[] message) {
        try {
            ShareProtocol.Reader in = new ShareProtocol.Reader(message);

            // Starting an upload, download, copy or listing also clears out expired workbooks.
            if (in.type() == ShareProtocol.UPLOAD_BEGIN
                    || in.type() == ShareProtocol.DOWNLOAD
                    || in.type() == ShareProtocol.COPY_ITEM
                    || in.type() == ShareProtocol.LIST_MINE) {
                deleteExpired(server(player));
            }

            switch (in.type()) {
                case ShareProtocol.UPLOAD_BEGIN -> beginUpload(player, in);
                case ShareProtocol.UPLOAD_CHUNK -> uploadChunk(player, in);
                case ShareProtocol.UPLOAD_END -> finishUpload(player, in.readInt());
                case ShareProtocol.DOWNLOAD -> download(player, in.readUuid());
                case ShareProtocol.COPY_ITEM -> copyItem(player, in.readUuid());
                case ShareProtocol.LIST_MINE -> listMine(player);
                case ShareProtocol.DELETE -> deleteShare(player, in.readUuid());
                default -> {
                }
            }
        } catch (IOException | RuntimeException exception) {
            ExcelComputer.LOGGER.warn("Bad workbook share message from {}: {}",
                    player.getName().getString(), exception.toString());
        }
    }

    // ----- uploads -----

    private static void beginUpload(ServerPlayer player, ShareProtocol.Reader in) throws IOException {
        int transfer = in.readInt();
        String name = ShareProtocol.cleanFileName(in.readString());
        int size = in.readInt();
        UUID target = in.readBoolean() ? in.readUuid() : null;
        long now = System.currentTimeMillis();

        UPLOADS.values().removeIf(upload -> now - upload.startedAt > UPLOAD_TIMEOUT_MS);
        LAST_UPLOAD.values().removeIf(time -> now - time > UPLOAD_COOLDOWN_MS);
        UPLOADS.remove(player.getUUID());

        if (name == null) {
            uploadResult(player, transfer, false, "Only .xlsx and .csv files can be shared");
            return;
        }

        if (size <= 0 || size > ShareProtocol.MAX_FILE_BYTES) {
            uploadResult(player, transfer, false,
                    "Too big to share (the limit is " + ShareProtocol.MAX_FILE_BYTES / (1024 * 1024) + " MB)");
            return;
        }

        if (LAST_UPLOAD.containsKey(player.getUUID())) {
            uploadResult(player, transfer, false, "Wait a moment before sharing again");
            return;
        }

        // Starting an upload counts too, so a client can't keep starting huge uploads.
        LAST_UPLOAD.put(player.getUUID(), now);

        if (target == null) {
            if (!player.isCreative() && findPaper(player) < 0) {
                uploadResult(player, transfer, false, "Sharing needs 1 Excel Paper in your inventory");
                return;
            }

            String limit = newShareLimit(server(player), player.getUUID(), size);

            if (limit != null) {
                uploadResult(player, transfer, false, limit);
                return;
            }
        } else {
            Meta meta = readMeta(server(player), target);

            if (meta == null) {
                uploadResult(player, transfer, false, "This shared workbook has expired or no longer exists");
                return;
            }

            if (!meta.owner().equals(player.getUUID())) {
                uploadResult(player, transfer, false, "Only " + meta.ownerName() + " can update this workbook");
                return;
            }
        }

        UPLOADS.put(player.getUUID(), new Upload(transfer, name, size, target));
    }

    private static void uploadChunk(ServerPlayer player, ShareProtocol.Reader in) throws IOException {
        int transfer = in.readInt();
        byte[] chunk = in.readBytes(ShareProtocol.CHUNK_BYTES);
        Upload upload = UPLOADS.get(player.getUUID());

        if (upload == null || upload.transfer != transfer) {
            return;
        }

        if (upload.received + chunk.length > upload.data.length) {
            UPLOADS.remove(player.getUUID());
            uploadResult(player, transfer, false, "Upload failed (more data than announced)");
            return;
        }

        System.arraycopy(chunk, 0, upload.data, upload.received, chunk.length);
        upload.received += chunk.length;
    }

    private static void finishUpload(ServerPlayer player, int transfer) {
        Upload upload = UPLOADS.get(player.getUUID());

        if (upload == null || upload.transfer != transfer) {
            return;
        }

        UPLOADS.remove(player.getUUID());

        if (upload.received != upload.data.length) {
            uploadResult(player, transfer, false, "Upload failed (incomplete)");
            return;
        }

        MinecraftServer server = server(player);

        try {
            if (upload.target != null) {
                Meta meta = readMeta(server, upload.target);

                if (meta == null || meta.expired() || !meta.owner().equals(player.getUUID())) {
                    uploadResult(player, transfer, false, "You can no longer update this workbook (it may have expired)");
                    return;
                }

                writeFile(dataFile(server, upload.target), upload.data);
                LAST_UPLOAD.put(player.getUUID(), System.currentTimeMillis());
                uploadResult(player, transfer, true, "Saved, and updated the shared copy");
                return;
            }

            // Checked again: another upload may have finished in the meantime.
            String limit = newShareLimit(server, player.getUUID(), upload.data.length);

            if (limit != null) {
                uploadResult(player, transfer, false, limit);
                return;
            }

            if (!player.isCreative()) {
                int paper = findPaper(player);

                if (paper < 0) {
                    uploadResult(player, transfer, false, "Sharing needs 1 Excel Paper in your inventory");
                    return;
                }

                player.getInventory().getItem(paper).shrink(1);
            }

            UUID id = UUID.randomUUID();
            String ownerName = player.getName().getString();
            long expiresAt = System.currentTimeMillis() + ShareProtocol.SHARE_LIFETIME_MS;
            writeFile(dataFile(server, id), upload.data);
            writeMeta(server, id, new Meta(upload.name, player.getUUID(), ownerName, expiresAt));
            LAST_UPLOAD.put(player.getUUID(), System.currentTimeMillis());

            give(player, SharedWorkbookItem.create(id, upload.name, ownerName, expiresAt));
            uploadResult(player, transfer, true,
                    "Shared! " + upload.name + " is in your inventory. It expires in 7 days");
        } catch (IOException exception) {
            ExcelComputer.LOGGER.error("Could not store a shared workbook", exception);
            uploadResult(player, transfer, false, "The server could not store the workbook");
        }
    }

    private static void uploadResult(ServerPlayer player, int transfer, boolean ok, String message) {
        ShareNetwork.sendToClient.accept(player, new ShareProtocol.Writer(ShareProtocol.UPLOAD_RESULT)
                .writeInt(transfer)
                .writeBoolean(ok)
                .writeString(message)
                .toBytes());
    }

    // ----- downloads and copies -----

    private static void download(ServerPlayer player, UUID id) {
        if (!isHolding(player, id)) {
            downloadFailed(player, id, "Hold the shared workbook to open it");
            return;
        }

        long now = System.currentTimeMillis();
        LAST_DOWNLOAD.values().removeIf(time -> now - time > DOWNLOAD_COOLDOWN_MS);

        if (LAST_DOWNLOAD.containsKey(player.getUUID())) {
            downloadFailed(player, id, "Wait a moment before opening it again");
            return;
        }

        LAST_DOWNLOAD.put(player.getUUID(), now);

        MinecraftServer server = server(player);
        Meta meta = readMeta(server, id);
        byte[] data;

        try {
            data = meta == null ? null : Files.readAllBytes(dataFile(server, id));
        } catch (IOException exception) {
            data = null;
        }

        if (data == null || meta.expired()) {
            downloadFailed(player, id, goneMessage(player, id));
            return;
        }

        ShareNetwork.sendToClient.accept(player, new ShareProtocol.Writer(ShareProtocol.DOWNLOAD_BEGIN)
                .writeUuid(id)
                .writeString(meta.name())
                .writeInt(data.length)
                .writeBoolean(meta.owner().equals(player.getUUID()))
                .writeString(meta.ownerName())
                .writeLong(meta.expiresAt())
                .toBytes());

        for (int offset = 0; offset < data.length; offset += ShareProtocol.CHUNK_BYTES) {
            int length = Math.min(ShareProtocol.CHUNK_BYTES, data.length - offset);
            ShareNetwork.sendToClient.accept(player, new ShareProtocol.Writer(ShareProtocol.DOWNLOAD_CHUNK)
                    .writeUuid(id)
                    .writeBytes(data, offset, length)
                    .toBytes());
        }

        ShareNetwork.sendToClient.accept(player, new ShareProtocol.Writer(ShareProtocol.DOWNLOAD_END)
                .writeUuid(id)
                .toBytes());
    }

    private static void downloadFailed(ServerPlayer player, UUID id, String message) {
        ShareNetwork.sendToClient.accept(player, new ShareProtocol.Writer(ShareProtocol.DOWNLOAD_FAILED)
                .writeUuid(id)
                .writeString(message)
                .toBytes());
    }

    // Gives the player another item for the same shared workbook, for 1 Excel Paper.
    private static void copyItem(ServerPlayer player, UUID id) {
        ItemStack held = heldStack(player, id);

        if (held == null) {
            notice(player, "Hold the shared workbook to copy it");
            return;
        }

        Meta meta = readMeta(server(player), id);

        if (meta == null || meta.expired()) {
            notice(player, goneMessage(player, id));
            return;
        }

        if (!player.isCreative()) {
            int paper = findPaper(player);

            if (paper < 0) {
                notice(player, "A copy needs 1 Excel Paper in your inventory");
                return;
            }

            player.getInventory().getItem(paper).shrink(1);
        }

        give(player, held.copyWithCount(1));
        notice(player, "Made a copy of the item, you can give it to someone");
    }

    // ----- the owner's list of shared sheets -----

    // Sends the player the sheets they have shared that haven't expired.
    private static void listMine(ServerPlayer player) {
        long now = System.currentTimeMillis();
        LAST_LIST.values().removeIf(time -> now - time > LIST_COOLDOWN_MS);

        if (LAST_LIST.containsKey(player.getUUID())) {
            return;
        }

        LAST_LIST.put(player.getUUID(), now);
        sendList(player);
    }

    private static void sendList(ServerPlayer player) {
        MinecraftServer server = server(player);
        List<Map.Entry<UUID, Meta>> mine = new ArrayList<>();

        for (Map.Entry<UUID, Meta> entry : allShares(server).entrySet()) {
            if (entry.getValue().owner().equals(player.getUUID())) {
                mine.add(entry);
            }
        }

        mine.sort(Comparator.comparingLong(entry -> entry.getValue().expiresAt()));

        ShareProtocol.Writer out = new ShareProtocol.Writer(ShareProtocol.MY_SHARES)
                .writeInt(MAX_SHARES_PER_PLAYER)
                .writeInt(mine.size());

        for (Map.Entry<UUID, Meta> entry : mine) {
            long size;

            try {
                size = Files.size(dataFile(server, entry.getKey()));
            } catch (IOException exception) {
                size = 0;
            }

            out.writeUuid(entry.getKey())
                    .writeString(entry.getValue().name())
                    .writeLong(entry.getValue().expiresAt())
                    .writeLong(size);
        }

        ShareNetwork.sendToClient.accept(player, out.toBytes());
    }

    // Deletes one of the player's own shared sheets. Items for it stop working.
    private static void deleteShare(ServerPlayer player, UUID id) {
        MinecraftServer server = server(player);
        Meta meta = readMeta(server, id);

        if (meta == null) {
            notice(player, "That sheet was already deleted");
        } else if (!meta.owner().equals(player.getUUID())) {
            notice(player, "You can only delete sheets you shared");
            return;
        } else {
            try {
                Files.deleteIfExists(dataFile(server, id));
                Files.deleteIfExists(directory(server).resolve(id + ".properties"));
                notice(player, "Deleted " + meta.name() + ". Items for it no longer open");
            } catch (IOException exception) {
                ExcelComputer.LOGGER.warn("Could not delete shared workbook {}: {}", id, exception.toString());
                notice(player, "The server could not delete " + meta.name());
            }
        }

        sendList(player);
    }

    private static void notice(ServerPlayer player, String message) {
        ShareNetwork.sendToClient.accept(player, new ShareProtocol.Writer(ShareProtocol.NOTICE)
                .writeString(message)
                .toBytes());
    }

    // ----- helpers -----

    private static MinecraftServer server(ServerPlayer player) {
        return player.level().getServer();
    }

    private static ItemStack heldStack(ServerPlayer player, UUID id) {
        if (id.equals(SharedWorkbookItem.getSharedId(player.getMainHandItem()))) {
            return player.getMainHandItem();
        }

        if (id.equals(SharedWorkbookItem.getSharedId(player.getOffhandItem()))) {
            return player.getOffhandItem();
        }

        return null;
    }

    private static boolean isHolding(ServerPlayer player, UUID id) {
        return heldStack(player, id) != null;
    }

    // Why a held shared workbook can't be used any more.
    private static String goneMessage(ServerPlayer player, UUID id) {
        ItemStack held = heldStack(player, id);
        long expiresAt = held == null ? 0 : SharedWorkbookItem.getExpiresAt(held);

        return expiresAt > 0 && System.currentTimeMillis() >= expiresAt
                ? "This shared workbook has expired (they last one week)"
                : "This shared workbook no longer exists on this server";
    }

    // Every shared workbook that hasn't expired, by id.
    private static Map<UUID, Meta> allShares(MinecraftServer server) {
        Map<UUID, Meta> shares = new HashMap<>();

        try (java.util.stream.Stream<Path> files = Files.list(directory(server))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String fileName = file.getFileName().toString();

                if (!fileName.endsWith(".properties")) {
                    continue;
                }

                try {
                    UUID id = UUID.fromString(fileName.substring(0, fileName.length() - ".properties".length()));
                    Meta meta = readMeta(server, id);

                    if (meta != null && !meta.expired()) {
                        shares.put(id, meta);
                    }
                } catch (IllegalArgumentException exception) {
                    // Not one of ours.
                }
            }
        } catch (IOException exception) {
            ExcelComputer.LOGGER.warn("Could not list shared workbooks: {}", exception.toString());
        }

        return shares;
    }

    // Why this player can't share another sheet of this size right now, or null if they can.
    private static String newShareLimit(MinecraftServer server, UUID player, long size) {
        Map<UUID, Meta> shares = allShares(server);
        int mine = 0;
        long total = size;

        for (Map.Entry<UUID, Meta> entry : shares.entrySet()) {
            if (entry.getValue().owner().equals(player)) {
                mine++;
            }

            try {
                total += Files.size(dataFile(server, entry.getKey()));
            } catch (IOException exception) {
                // A missing file takes no room.
            }
        }

        if (mine >= MAX_SHARES_PER_PLAYER) {
            return "You already have " + MAX_SHARES_PER_PLAYER
                    + " shared sheets. Delete one in My shared sheets, or wait for one to expire";
        }

        if (total > MAX_TOTAL_BYTES) {
            return "The server's space for shared sheets is full. Try again after some expire";
        }

        return null;
    }

    // Deletes every shared workbook whose week is up.
    private static void deleteExpired(MinecraftServer server) {
        try (java.util.stream.Stream<Path> files = Files.list(directory(server))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String fileName = file.getFileName().toString();

                if (!fileName.endsWith(".properties")) {
                    continue;
                }

                UUID id;

                try {
                    id = UUID.fromString(fileName.substring(0, fileName.length() - ".properties".length()));
                } catch (IllegalArgumentException exception) {
                    continue;
                }

                Meta meta = readMeta(server, id);

                if (meta == null || meta.expired()) {
                    Files.deleteIfExists(dataFile(server, id));
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException exception) {
            ExcelComputer.LOGGER.warn("Could not clean up expired shared workbooks: {}", exception.toString());
        }
    }

    private static int findPaper(ServerPlayer player) {
        Inventory inventory = player.getInventory();

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(ModItems.EXCEL_PAPER)) {
                return slot;
            }
        }

        return -1;
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static Path directory(MinecraftServer server) throws IOException {
        Path directory = server.getWorldPath(LevelResource.ROOT)
                .resolve("excelcomputer")
                .resolve("shared");
        Files.createDirectories(directory);
        return directory;
    }

    private static Path dataFile(MinecraftServer server, UUID id) throws IOException {
        return directory(server).resolve(id + ".data");
    }

    private static Meta readMeta(MinecraftServer server, UUID id) {
        try {
            Path file = directory(server).resolve(id + ".properties");

            if (!Files.isRegularFile(file)) {
                return null;
            }

            Properties properties = new Properties();

            try (InputStream input = Files.newInputStream(file)) {
                properties.load(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
            }

            return new Meta(
                    properties.getProperty("name", "workbook.xlsx"),
                    UUID.fromString(properties.getProperty("owner", "")),
                    properties.getProperty("ownerName", "?"),
                    // Workbooks shared before expiry existed get a week from their last change.
                    Long.parseLong(properties.getProperty("expiresAt",
                            String.valueOf(Files.getLastModifiedTime(file).toMillis()
                                    + ShareProtocol.SHARE_LIFETIME_MS)))
            );
        } catch (IOException | IllegalArgumentException exception) {
            return null;
        }
    }

    private static void writeMeta(MinecraftServer server, UUID id, Meta meta) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("name", meta.name());
        properties.setProperty("owner", meta.owner().toString());
        properties.setProperty("ownerName", meta.ownerName());
        properties.setProperty("expiresAt", String.valueOf(meta.expiresAt()));

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

        try (java.io.Writer writer = new java.io.OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
            properties.store(writer, "Excel on a Stick shared workbook");
        }

        writeFile(directory(server).resolve(id + ".properties"), bytes.toByteArray());
    }

    // Writes to a temporary file, then moves it into place, so a crash can't leave half a file.
    private static void writeFile(Path path, byte[] data) throws IOException {
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
}
