package io.github.reverendbrown.excelcomputer.share;

import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * A workbook that was downloaded from the server: a shared workbook item's,
 * or a Redstone Connector's.
 *
 * @param owner     true if this player shared it, so saving updates the server's copy
 * @param expiresAt when the server deletes it (epoch milliseconds)
 * @param connector the Redstone Connector it belongs to (saving updates it), or null
 */
public record SharedWorkbook(UUID id, String name, boolean owner, String ownerName, long expiresAt,
                             BlockPos connector) {
    public SharedWorkbook(UUID id, String name, boolean owner, String ownerName, long expiresAt) {
        this(id, name, owner, ownerName, expiresAt, null);
    }

    public boolean isConnector() {
        return connector != null;
    }
}
