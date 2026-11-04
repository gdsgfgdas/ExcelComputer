package io.github.reverendbrown.excelcomputer.share;

import io.github.reverendbrown.excelcomputer.SpreadsheetScreen;
import io.github.reverendbrown.excelcomputer.WorkbookFiles;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.function.Consumer;

/**
 * The client half of workbook sharing: uploads a workbook to the server,
 * downloads a shared one into a local cache folder, and opens it.
 * Client only; everything runs on the game thread.
 */
public final class ShareClient {
    private static int nextTransfer = 1;
    private static int pendingTransfer;
    private static Consumer<String> uploadListener;
    private static Consumer<String> noticeListener;

    private static SharedWorkbookLoadingScreen loadingScreen;
    private static MySharesScreen mySharesScreen;
    private static Download download;

    private static final class Download {
        final UUID id;
        String name;
        boolean owner;
        String ownerName;
        long expiresAt;
        byte[] data;
        int received;

        Download(UUID id) {
            this.id = id;
        }
    }

    private ShareClient() {
    }

    // True when connected to a server (or a singleplayer world) that has the mod.
    public static boolean canShare() {
        return Minecraft.getInstance().getConnection() != null
                && ShareNetwork.serverSupportsSharing.getAsBoolean();
    }

    /**
     * Sends a workbook file to the server. With a target, it replaces that
     * shared workbook (only its owner may); without one, the server stores a
     * new one and gives the player an item for it. The listener receives the
     * server's answer.
     *
     * @return null if the upload started, otherwise why it could not
     */
    public static String upload(Path file, UUID target, Consumer<String> listener) {
        if (!canShare()) {
            return "Sharing needs the server to have Excel on a Stick installed";
        }

        String name = ShareProtocol.cleanFileName(file.getFileName().toString());

        if (name == null) {
            return "Only .xlsx and .csv files can be shared";
        }

        byte[] data;

        try {
            if (Files.size(file) > ShareProtocol.MAX_FILE_BYTES) {
                return "Too big to share (the limit is "
                        + ShareProtocol.MAX_FILE_BYTES / (1024 * 1024) + " MB)";
            }

            data = Files.readAllBytes(file);
        } catch (IOException exception) {
            return "Could not read " + file.getFileName();
        }

        if (data.length == 0) {
            return "That file is empty";
        }

        int transfer = nextTransfer++;
        pendingTransfer = transfer;
        uploadListener = listener;

        ShareProtocol.Writer begin = new ShareProtocol.Writer(ShareProtocol.UPLOAD_BEGIN)
                .writeInt(transfer)
                .writeString(name)
                .writeInt(data.length)
                .writeBoolean(target != null);

        if (target != null) {
            begin.writeUuid(target);
        }

        ShareNetwork.sendToServer.accept(begin.toBytes());

        for (int offset = 0; offset < data.length; offset += ShareProtocol.CHUNK_BYTES) {
            int length = Math.min(ShareProtocol.CHUNK_BYTES, data.length - offset);
            ShareNetwork.sendToServer.accept(new ShareProtocol.Writer(ShareProtocol.UPLOAD_CHUNK)
                    .writeInt(transfer)
                    .writeBytes(data, offset, length)
                    .toBytes());
        }

        ShareNetwork.sendToServer.accept(new ShareProtocol.Writer(ShareProtocol.UPLOAD_END)
                .writeInt(transfer)
                .toBytes());

        return null;
    }

    // Called when a player uses a shared workbook item.
    public static void openShared(UUID id, String name) {
        SharedWorkbookLoadingScreen screen = new SharedWorkbookLoadingScreen(id, name);
        loadingScreen = screen;
        Minecraft.getInstance().setScreen(screen);

        if (!canShare()) {
            screen.fail("This server does not have Excel on a Stick installed");
            return;
        }

        download = new Download(id);
        ShareNetwork.sendToServer.accept(new ShareProtocol.Writer(ShareProtocol.DOWNLOAD)
                .writeUuid(id)
                .toBytes());
    }

    // Asks the server for another item for this shared workbook (costs 1 Excel Paper).
    public static void requestCopy(UUID id, Consumer<String> listener) {
        if (!canShare()) {
            listener.accept("This server does not have Excel on a Stick installed");
            return;
        }

        noticeListener = listener;
        ShareNetwork.sendToServer.accept(new ShareProtocol.Writer(ShareProtocol.COPY_ITEM)
                .writeUuid(id)
                .toBytes());
    }

    // One of this player's shared sheets, as listed by the server.
    public record MyShare(UUID id, String name, long expiresAt, long sizeBytes) {
    }

    // Asks the server for this player's shared sheets; MySharesScreen shows the answer.
    public static void requestMyShares(MySharesScreen screen) {
        mySharesScreen = screen;
        noticeListener = screen::setStatus;
        ShareNetwork.sendToServer.accept(new ShareProtocol.Writer(ShareProtocol.LIST_MINE).toBytes());
    }

    // Deletes one of this player's shared sheets; the server answers with a new list.
    public static void deleteShare(UUID id) {
        ShareNetwork.sendToServer.accept(new ShareProtocol.Writer(ShareProtocol.DELETE)
                .writeUuid(id)
                .toBytes());
    }

    private static void receiveMyShares(ShareProtocol.Reader in) throws IOException {
        int limit = in.readInt();
        int count = in.readInt();

        if (count < 0 || count > 1_000) {
            throw new IOException("Bad share count: " + count);
        }

        List<MyShare> shares = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            shares.add(new MyShare(in.readUuid(), in.readString(), in.readLong(), in.readLong()));
        }

        MySharesScreen screen = mySharesScreen;

        if (screen != null && screen.isShowing()) {
            screen.setShares(shares, limit);
        }
    }

    public static void receive(byte[] message) {
        try {
            ShareProtocol.Reader in = new ShareProtocol.Reader(message);

            switch (in.type()) {
                case ShareProtocol.UPLOAD_RESULT -> {
                    int transfer = in.readInt();
                    in.readBoolean();
                    String text = in.readString();

                    if (transfer == pendingTransfer && uploadListener != null) {
                        uploadListener.accept(text);
                    }
                }
                case ShareProtocol.DOWNLOAD_BEGIN -> downloadBegin(in);
                case ShareProtocol.DOWNLOAD_CHUNK -> downloadChunk(in);
                case ShareProtocol.DOWNLOAD_END -> downloadEnd(in.readUuid());
                case ShareProtocol.DOWNLOAD_FAILED -> {
                    UUID id = in.readUuid();
                    String text = in.readString();

                    if (download != null && download.id.equals(id)) {
                        download = null;
                        screenFor(id, screen -> screen.fail(text));
                    }
                }
                case ShareProtocol.MY_SHARES -> receiveMyShares(in);
                case ShareProtocol.NOTICE -> {
                    String text = in.readString();

                    if (noticeListener != null) {
                        noticeListener.accept(text);
                    }
                }
                default -> {
                }
            }
        } catch (IOException | RuntimeException exception) {
            io.github.reverendbrown.excelcomputer.ExcelComputer.LOGGER.warn(
                    "Bad workbook share message from the server: {}", exception.toString());
        }
    }

    private static void downloadBegin(ShareProtocol.Reader in) throws IOException {
        UUID id = in.readUuid();
        String name = ShareProtocol.cleanFileName(in.readString());
        int size = in.readInt();
        boolean owner = in.readBoolean();
        String ownerName = in.readString();
        long expiresAt = in.readLong();

        if (download == null || !download.id.equals(id)) {
            return;
        }

        if (name == null || size <= 0 || size > ShareProtocol.MAX_FILE_BYTES) {
            download = null;
            screenFor(id, screen -> screen.fail("The server sent a workbook that can't be opened"));
            return;
        }

        download.name = name;
        download.owner = owner;
        download.ownerName = ownerName;
        download.expiresAt = expiresAt;
        download.data = new byte[size];
    }

    private static void downloadChunk(ShareProtocol.Reader in) throws IOException {
        UUID id = in.readUuid();
        byte[] chunk = in.readBytes(ShareProtocol.CHUNK_BYTES);
        Download current = download;

        if (current == null || !current.id.equals(id) || current.data == null) {
            return;
        }

        if (current.received + chunk.length > current.data.length) {
            download = null;
            screenFor(id, screen -> screen.fail("The download was corrupted"));
            return;
        }

        System.arraycopy(chunk, 0, current.data, current.received, chunk.length);
        current.received += chunk.length;
        screenFor(id, screen -> screen.progress(current.received, current.data.length));
    }

    private static void downloadEnd(UUID id) {
        Download current = download;

        if (current == null || !current.id.equals(id) || current.data == null) {
            return;
        }

        download = null;

        if (current.received != current.data.length) {
            screenFor(id, screen -> screen.fail("The download was incomplete"));
            return;
        }

        String unsafe = checkSafeToOpen(current.name, current.data);

        if (unsafe != null) {
            screenFor(id, screen -> screen.fail(unsafe));
            return;
        }

        Path file;

        try {
            // One folder per shared workbook keeps its real file name (shown in the title bar).
            Path folder = WorkbookFiles.getSharedCacheDirectory().resolve(id.toString());
            Files.createDirectories(folder);
            file = folder.resolve(current.name);
            Files.write(file, current.data);
        } catch (IOException exception) {
            screenFor(id, screen -> screen.fail("Could not save the download: " + exception.getMessage()));
            return;
        }

        SharedWorkbook shared = new SharedWorkbook(
                id, current.name, current.owner, current.ownerName, current.expiresAt);
        screenFor(id, screen -> Minecraft.getInstance().setScreen(new SpreadsheetScreen(file, shared)));
    }

    /**
     * An .xlsx is a zip, and a small one can unpack into something huge
     * (a "zip bomb") that would freeze or crash the game while it opens.
     * This unpacks it once without keeping anything, counting the bytes.
     *
     * @return null if it is safe to open, otherwise why not
     */
    private static String checkSafeToOpen(String name, byte[] data) {
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            return null; // A CSV is plain text and already at most 4 MB.
        }

        long total = 0;
        int entries = 0;
        byte[] buffer = new byte[64 * 1024];

        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry entry;

            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > ShareProtocol.MAX_ZIP_ENTRIES) {
                    return "This workbook has too many parts to open safely";
                }

                int read;

                while ((read = zip.read(buffer)) > 0) {
                    total += read;

                    if (total > ShareProtocol.MAX_UNPACKED_BYTES) {
                        return "This workbook is too large to open safely (over "
                                + ShareProtocol.MAX_UNPACKED_BYTES / (1024 * 1024) + " MB unpacked)";
                    }
                }
            }
        } catch (IOException exception) {
            return "This workbook is damaged and can't be opened";
        }

        return null;
    }

    // Runs the action only if the loading screen for this workbook is still open.
    private static void screenFor(UUID id, Consumer<SharedWorkbookLoadingScreen> action) {
        SharedWorkbookLoadingScreen screen = loadingScreen;

        if (screen != null && screen.isShowing() && screen.id().equals(id)) {
            action.accept(screen);
        }
    }
}
