package io.github.reverendbrown.excelcomputer.share;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.UUID;

/**
 * The messages used to share workbooks. Every message is a plain byte array
 * (a type byte, then the fields), so each loader only has to carry bytes
 * between the client and the server, and the format is the same everywhere.
 *
 * <p>Files are sent in chunks: Minecraft limits a message from the client
 * to about 32 KB.
 */
public final class ShareProtocol {
    // Largest workbook that can be shared.
    public static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    // Bytes of file data in one message.
    public static final int CHUNK_BYTES = 24 * 1024;
    // Largest whole message (a chunk plus its small header).
    public static final int MAX_MESSAGE_BYTES = CHUNK_BYTES + 1024;

    // Client to server.
    // UPLOAD_BEGIN: transfer, name, size, has target, [target], then optionally
    // has preview, [preview: SheetPreview.BYTES map colors the client drew].
    public static final byte UPLOAD_BEGIN = 1;
    public static final byte UPLOAD_CHUNK = 2;
    public static final byte UPLOAD_END = 3;
    public static final byte DOWNLOAD = 4;
    public static final byte COPY_ITEM = 5;
    public static final byte LIST_MINE = 6;
    public static final byte DELETE = 7;
    // Redstone Connectors: open the linked workbook (request id, position),
    // and start an upload that links or updates it (transfer, name, size,
    // position; the chunks and end are the usual UPLOAD_CHUNK and UPLOAD_END).
    public static final byte CONNECTOR_OPEN = 8;
    public static final byte CONNECTOR_UPLOAD_BEGIN = 9;
    // Logistics Hubs: ask for the player's hubs (answered with HUB_LIST).
    public static final byte HUB_QUERY = 10;
    // Logistics Hubs, each naming a hub by dimension (string) and position (long):
    // make it the active one, rename it (then the name, a string), forget it.
    public static final byte HUB_CHOOSE = 21;
    public static final byte HUB_RENAME = 22;
    public static final byte HUB_FORGET = 23;
    // The owner gives an online player access to a hub (then the player's name)
    // or takes it away (then the member's id).
    public static final byte HUB_MEMBER_ADD = 24;
    public static final byte HUB_MEMBER_REMOVE = 25;
    // The player closed a linked block's workbook (position): someone else may edit it now.
    public static final byte CONNECTOR_CLOSE = 26;

    // Server to client.
    public static final byte UPLOAD_RESULT = 11;
    public static final byte DOWNLOAD_BEGIN = 12;
    public static final byte DOWNLOAD_CHUNK = 13;
    public static final byte DOWNLOAD_END = 14;
    public static final byte DOWNLOAD_FAILED = 15;
    public static final byte NOTICE = 16;
    public static final byte MY_SHARES = 17;
    // The hubs the player knows, which Excel on a Stick can open from anywhere:
    // a count, then per hub its position, dimension, name, tier and whether it is active.
    public static final byte HUB_LIST = 18;

    // A shared .xlsx may unpack to at most this much (it is a zip). Larger ones
    // could freeze or crash the game of whoever opens them, so they are refused.
    public static final long MAX_UNPACKED_BYTES = 50L * 1024 * 1024;
    public static final int MAX_ZIP_ENTRIES = 5_000;

    // A shared workbook is deleted from the server this long after it was shared.
    public static final long SHARE_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000;

    // A shared workbook's picture: one map's pixels (128 x 128 map colors).
    public static final int PREVIEW_BYTES = 128 * 128;

    private static final int MAX_NAME_LENGTH = 80;

    private ShareProtocol() {
    }

    /**
     * Describes when a shared workbook expires, e.g. "expires in 6 days (Nov 10, 17:08)",
     * in this computer's time zone.
     */
    public static String describeExpiry(long expiresAt) {
        long left = expiresAt - System.currentTimeMillis();

        if (left <= 0) {
            return "has expired";
        }

        long minutes = left / 60_000;
        long hours = minutes / 60;
        long days = hours / 24;
        String amount;

        if (days >= 1) {
            amount = days + (days == 1 ? " day" : " days");
        } else if (hours >= 1) {
            amount = hours + (hours == 1 ? " hour" : " hours");
        } else {
            amount = Math.max(1, minutes) + (minutes <= 1 ? " minute" : " minutes");
        }

        String date = java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH)
                .format(java.time.Instant.ofEpochMilli(expiresAt).atZone(java.time.ZoneId.systemDefault()));

        return "expires in " + amount + " (" + date + ")";
    }

    /**
     * Makes a received file name safe to store: no folders, only plain
     * characters, and it must end in .xlsx or .csv. Returns null if the name
     * can't be used.
     */
    public static String cleanFileName(String name) {
        if (name == null) {
            return null;
        }

        String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        String lower = base.toLowerCase(Locale.ROOT);
        String extension;

        if (lower.endsWith(".xlsx")) {
            extension = ".xlsx";
        } else if (lower.endsWith(".csv")) {
            extension = ".csv";
        } else {
            return null;
        }

        String stem = base.substring(0, base.length() - extension.length())
                .replaceAll("[^A-Za-z0-9 ._()\\-]", "_")
                .trim();

        while (stem.startsWith(".")) {
            stem = stem.substring(1);
        }

        if (stem.isEmpty()) {
            stem = "workbook";
        }

        if (stem.length() > MAX_NAME_LENGTH) {
            stem = stem.substring(0, MAX_NAME_LENGTH).trim();
        }

        return stem + extension;
    }

    /**
     * An .xlsx is a zip, and a small one can unpack into something huge
     * (a "zip bomb") that would freeze or crash whoever opens it. This
     * unpacks it once without keeping anything, counting the bytes. Used by
     * the client before opening a shared workbook and by the server before
     * opening a Redstone Connector's.
     *
     * @return null if it is safe to open, otherwise why not
     */
    public static String checkSafeToOpen(String name, byte[] data) {
        return checkSafeToOpen(name, data, MAX_UNPACKED_BYTES);
    }

    /** Like checkSafeToOpen(name, data), with a lower limit for how big it may unpack. */
    public static String checkSafeToOpen(String name, byte[] data, long maxUnpackedBytes) {
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            return null; // A CSV is plain text and already at most 4 MB.
        }

        long total = 0;
        int entries = 0;
        byte[] buffer = new byte[64 * 1024];

        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(data))) {
            java.util.zip.ZipEntry entry;

            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    return "This workbook has too many parts to open safely";
                }

                int read;

                while ((read = zip.read(buffer)) > 0) {
                    total += read;

                    if (total > maxUnpackedBytes) {
                        return "This workbook is too large to open safely (over "
                                + maxUnpackedBytes / (1024 * 1024) + " MB unpacked)";
                    }
                }
            }
        } catch (IOException exception) {
            return "This workbook is damaged and can't be opened";
        }

        return null;
    }

    public static final class Writer {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(bytes);

        public Writer(byte type) {
            writeByte(type);
        }

        public Writer writeByte(int value) {
            try {
                out.writeByte(value);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return this;
        }

        public Writer writeInt(int value) {
            try {
                out.writeInt(value);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return this;
        }

        public Writer writeLong(long value) {
            try {
                out.writeLong(value);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return this;
        }

        public Writer writeBoolean(boolean value) {
            return writeByte(value ? 1 : 0);
        }

        public Writer writeString(String value) {
            try {
                out.writeUTF(value);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return this;
        }

        public Writer writeUuid(UUID value) {
            try {
                out.writeLong(value.getMostSignificantBits());
                out.writeLong(value.getLeastSignificantBits());
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return this;
        }

        public Writer writeBytes(byte[] data, int offset, int length) {
            writeInt(length);
            try {
                out.write(data, offset, length);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return this;
        }

        public byte[] toBytes() {
            return bytes.toByteArray();
        }
    }

    public static final class Reader {
        private final DataInputStream in;
        private final byte type;

        public Reader(byte[] message) throws IOException {
            this.in = new DataInputStream(new ByteArrayInputStream(message));
            this.type = in.readByte();
        }

        public byte type() {
            return type;
        }

        /** Whether more fields follow (newer versions add optional ones at the end). */
        public boolean hasMore() throws IOException {
            return in.available() > 0;
        }

        public int readInt() throws IOException {
            return in.readInt();
        }

        public long readLong() throws IOException {
            return in.readLong();
        }

        public boolean readBoolean() throws IOException {
            return in.readByte() != 0;
        }

        public String readString() throws IOException {
            return in.readUTF();
        }

        public UUID readUuid() throws IOException {
            return new UUID(in.readLong(), in.readLong());
        }

        public byte[] readBytes(int maxLength) throws IOException {
            int length = in.readInt();

            if (length < 0 || length > maxLength) {
                throw new IOException("Chunk too large: " + length);
            }

            byte[] data = new byte[length];
            in.readFully(data);
            return data;
        }
    }
}
