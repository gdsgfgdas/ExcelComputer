package io.github.reverendbrown.excelcomputer.share;

import io.github.reverendbrown.excelcomputer.SpreadsheetScreen;
import io.github.reverendbrown.excelcomputer.WorkbookFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
    private static Runnable uploadSuccess;
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
        // The Redstone Connector this is the workbook of, or null for a shared workbook.
        BlockPos connector;
        byte[] data;
        int received;

        Download(UUID id) {
            this.id = id;
        }
    }

    /** A player the owner let use a hub. */
    public record HubMember(UUID id, String name) {
    }

    /**
     * A Logistics Hub the player knows (the server says, see HUB_LIST).
     * mayRename: this player owns it (renames it, manages its members);
     * members is only filled in for the owner.
     */
    public record KnownHub(BlockPos pos, String dimension, String name, String tier, boolean active,
                           String owner, boolean mayRename, List<HubMember> members) {
        /** "minecraft:the_nether" from "ResourceKey[minecraft:dimension / minecraft:the_nether]". */
        public String dimensionName() {
            int slash = dimension.lastIndexOf('/');
            return (slash >= 0 ? dimension.substring(slash + 1) : dimension).replace("]", "").trim();
        }
    }

    // The hubs the player knows, which Excel on a Stick opens from anywhere.
    // Empty if none or not known yet.
    private static List<KnownHub> knownHubs = List.of();
    // Set when the stick asked to open the active hub before the server said which it is.
    private static boolean openHubWhenKnown;
    // The connection knownHubs came from: another world or server forgets them.
    private static Object knownHubsConnection;
    // Runs when the server sends the hubs (the open workbook picker refreshes).
    public static Runnable hubListener;

    private ShareClient() {
    }

    /** The hubs the player knows, in the order they were first used. */
    public static List<KnownHub> knownHubs() {
        if (knownHubsConnection != Minecraft.getInstance().getConnection()) {
            knownHubs = List.of();
        }

        return knownHubs;
    }

    /** The active hub (the one sneak-right-clicked inventories join), or null. */
    public static KnownHub activeHub() {
        for (KnownHub hub : knownHubs()) {
            if (hub.active()) {
                return hub;
            }
        }

        return null;
    }

    /** Asks the server for the player's hubs (the answer arrives as HUB_LIST). */
    public static void queryHubs() {
        if (canShare()) {
            ShareNetwork.sendToServer.accept(new ShareProtocol.Writer(ShareProtocol.HUB_QUERY).toBytes());
        }
    }

    /** Makes a hub the active one (the server answers with the new list). */
    public static void chooseHub(KnownHub hub) {
        sendHubMessage(ShareProtocol.HUB_CHOOSE, hub, null);
    }

    /** Renames a hub for everyone (blank: back to its tier's name). */
    public static void renameHub(KnownHub hub, String name) {
        sendHubMessage(ShareProtocol.HUB_RENAME, hub, name);
    }

    /** Takes a hub off the player's list; the hub keeps working. */
    public static void forgetHub(KnownHub hub) {
        sendHubMessage(ShareProtocol.HUB_FORGET, hub, null);
    }

    /**
     * Lets an online player use one of the player's hubs (owner only). The
     * listener gets the server's answer, then the new list arrives.
     */
    public static void addHubMember(KnownHub hub, String playerName, Consumer<String> listener) {
        noticeListener = listener;
        sendHubMessage(ShareProtocol.HUB_MEMBER_ADD, hub, playerName);
    }

    /** Takes a member's access to one of the player's hubs away (the listener as for addHubMember). */
    public static void removeHubMember(KnownHub hub, UUID member, Consumer<String> listener) {
        if (!canShare()) {
            return;
        }

        noticeListener = listener;
        ShareNetwork.sendToServer.accept(new ShareProtocol.Writer(ShareProtocol.HUB_MEMBER_REMOVE)
                .writeString(hub.dimension())
                .writeLong(hub.pos().asLong())
                .writeUuid(member)
                .toBytes());
    }

    /** Tells the server the player closed the linked block's workbook, so someone else may edit it. */
    public static void closeConnector(BlockPos pos) {
        if (canShare()) {
            ShareNetwork.sendToServer.accept(new ShareProtocol.Writer(ShareProtocol.CONNECTOR_CLOSE)
                    .writeLong(pos.asLong())
                    .toBytes());
        }
    }

    // The loading screen closed: a hub's or connector's download that won't
    // become an open workbook gives up its editing turn.
    static void loadingScreenClosed(SharedWorkbookLoadingScreen screen) {
        Download current = download;

        if (current != null && current.id.equals(screen.id()) && current.connector != null) {
            download = null;
            closeConnector(current.connector);
        }
    }

    private static void sendHubMessage(byte type, KnownHub hub, String name) {
        if (!canShare()) {
            return;
        }

        ShareProtocol.Writer message = new ShareProtocol.Writer(type)
                .writeString(hub.dimension())
                .writeLong(hub.pos().asLong());

        if (name != null) {
            message.writeString(name);
        }

        ShareNetwork.sendToServer.accept(message.toBytes());
    }

    /**
     * Opens the active hub's workbook; if the hubs aren't known yet, asks the
     * server and opens it when the answer comes.
     *
     * @return false if there's nothing to open (no server support)
     */
    public static boolean openSelectedHub() {
        KnownHub active = activeHub();

        if (active != null) {
            openConnector(active.pos());
            return true;
        }

        if (!canShare()) {
            return false;
        }

        openHubWhenKnown = true;
        queryHubs();
        return true;
    }

    private static void receiveHubs(ShareProtocol.Reader in) throws IOException {
        int count = in.readInt();
        List<KnownHub> hubs = new java.util.ArrayList<>();

        for (int i = 0; i < count && i < 256; i++) {
            BlockPos pos = BlockPos.of(in.readLong());
            String dimension = in.readString();
            String name = in.readString();
            String tier = in.readString();
            boolean active = in.readBoolean();
            String owner = in.readString();
            boolean mayRename = in.readBoolean();
            int memberCount = in.readInt();
            List<HubMember> members = new java.util.ArrayList<>();

            for (int m = 0; m < memberCount && m < 256; m++) {
                members.add(new HubMember(in.readUuid(), in.readString()));
            }

            hubs.add(new KnownHub(pos, dimension, name, tier, active, owner, mayRename, List.copyOf(members)));
        }

        knownHubs = List.copyOf(hubs);
        knownHubsConnection = Minecraft.getInstance().getConnection();

        boolean open = openHubWhenKnown;
        openHubWhenKnown = false;
        KnownHub active = activeHub();

        if (open) {
            if (active != null) {
                openConnector(active.pos());
            } else {
                Minecraft.getInstance().setScreen(new io.github.reverendbrown.excelcomputer.WorkbookPickerScreen());
            }
        } else if (hubListener != null) {
            hubListener.run();
        }
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
        return upload(file, listener, null, (transfer, name, size) -> {
            ShareProtocol.Writer begin = new ShareProtocol.Writer(ShareProtocol.UPLOAD_BEGIN)
                    .writeInt(transfer)
                    .writeString(name)
                    .writeInt(size)
                    .writeBoolean(target != null);

            if (target != null) {
                begin.writeUuid(target);
            }

            return begin;
        });
    }

    /**
     * Links a workbook to the Redstone Connector at pos (the server adds the
     * Redstone sheet), then opens the connector's copy to set it up.
     *
     * @return null if the upload started, otherwise why it could not
     */
    public static String linkConnector(Path file, BlockPos pos, Consumer<String> listener) {
        return upload(file, listener, () -> openConnector(pos), connectorBegin(pos));
    }

    /** Sends the edited copy of a Redstone Connector's workbook back to it. */
    public static String saveConnector(Path file, BlockPos pos, Consumer<String> listener) {
        return upload(file, listener, null, connectorBegin(pos));
    }

    private static UploadBegin connectorBegin(BlockPos pos) {
        return (transfer, name, size) -> new ShareProtocol.Writer(ShareProtocol.CONNECTOR_UPLOAD_BEGIN)
                .writeInt(transfer)
                .writeString(name)
                .writeInt(size)
                .writeLong(pos.asLong());
    }

    // The first message of an upload, which says what the file is for.
    private interface UploadBegin {
        ShareProtocol.Writer create(int transfer, String name, int size);
    }

    // Sends a file in chunks after its begin message. onSuccess (may be null)
    // runs if the server accepts it.
    private static String upload(Path file, Consumer<String> listener, Runnable onSuccess, UploadBegin beginMessage) {
        if (!canShare()) {
            return "This needs the server to have Excel on a Stick installed";
        }

        String name = ShareProtocol.cleanFileName(file.getFileName().toString());

        if (name == null) {
            return "Only .xlsx and .csv files can be sent";
        }

        byte[] data;

        try {
            if (Files.size(file) > ShareProtocol.MAX_FILE_BYTES) {
                return "Too big to send (the limit is "
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
        uploadSuccess = onSuccess;

        ShareNetwork.sendToServer.accept(beginMessage.create(transfer, name, data.length).toBytes());

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

    /**
     * Opens the workbook linked to the Redstone Connector at pos (the server
     * answers with the file, or says why not, e.g. nothing is linked yet).
     */
    public static void openConnector(BlockPos pos) {
        UUID request = UUID.randomUUID();
        SharedWorkbookLoadingScreen screen = new SharedWorkbookLoadingScreen(request, linkedLabel(pos));
        loadingScreen = screen;
        Minecraft.getInstance().setScreen(screen);

        if (!canShare()) {
            screen.fail("This server does not have Excel on a Stick installed");
            return;
        }

        download = new Download(request);
        download.connector = pos;
        ShareNetwork.sendToServer.accept(new ShareProtocol.Writer(ShareProtocol.CONNECTOR_OPEN)
                .writeUuid(request)
                .writeLong(pos.asLong())
                .toBytes());
    }

    /** What the linked block at pos is called: "Redstone Connector", "Iron Logistics Hub"... */
    public static String linkedLabel(BlockPos pos) {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;

        if (level != null
                && level.getBlockState(pos).getBlock() instanceof io.github.reverendbrown.excelcomputer.logistics.LogisticsHubBlock hub) {
            return hub.tier().label();
        }

        // The chosen hub may be far away, where this client knows no blocks.
        for (KnownHub hub : knownHubs()) {
            if (hub.pos().equals(pos)) {
                return hub.name();
            }
        }

        return "Redstone Connector";
    }

    /** Whether the linked block at pos is a Logistics Hub (otherwise it's a Redstone Connector). */
    public static boolean isHub(BlockPos pos) {
        return !"Redstone Connector".equals(linkedLabel(pos));
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
                    boolean ok = in.readBoolean();
                    String text = in.readString();

                    if (transfer == pendingTransfer) {
                        if (uploadListener != null) {
                            uploadListener.accept(text);
                        }

                        Runnable success = uploadSuccess;
                        uploadSuccess = null;

                        if (ok && success != null) {
                            success.run();
                        }
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
                case ShareProtocol.HUB_LIST -> receiveHubs(in);
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

        // download stays set until the workbook opens: if it fails, closing the
        // loading screen gives a hub's editing turn back (loadingScreenClosed).
        if (current.received != current.data.length) {
            screenFor(id, screen -> screen.fail("The download was incomplete"));
            return;
        }

        String unsafe = ShareProtocol.checkSafeToOpen(current.name, current.data);

        if (unsafe != null) {
            screenFor(id, screen -> screen.fail(unsafe));
            return;
        }

        Path file;

        try {
            // One folder per shared workbook (or connector) keeps its real
            // file name, shown in the title bar.
            Path folder = current.connector != null
                    ? WorkbookFiles.getSharedCacheDirectory().resolve("connector")
                            .resolve(current.connector.getX() + "_" + current.connector.getY() + "_" + current.connector.getZ())
                    : WorkbookFiles.getSharedCacheDirectory().resolve(id.toString());
            Files.createDirectories(folder);
            file = folder.resolve(current.name);
            Files.write(file, current.data);
        } catch (IOException exception) {
            screenFor(id, screen -> screen.fail("Could not save the download: " + exception.getMessage()));
            return;
        }

        download = null;
        SharedWorkbook shared = new SharedWorkbook(
                id, current.name, current.owner, current.ownerName, current.expiresAt, current.connector);
        screenFor(id, screen -> Minecraft.getInstance().setScreen(new SpreadsheetScreen(file, shared)));
    }

    // Runs the action only if the loading screen for this workbook is still open.
    private static void screenFor(UUID id, Consumer<SharedWorkbookLoadingScreen> action) {
        SharedWorkbookLoadingScreen screen = loadingScreen;

        if (screen != null && screen.isShowing() && screen.id().equals(id)) {
            action.accept(screen);
        }
    }
}
