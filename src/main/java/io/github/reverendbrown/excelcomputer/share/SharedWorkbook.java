package io.github.reverendbrown.excelcomputer.share;

import java.util.UUID;

/**
 * A workbook that was downloaded from a shared workbook item.
 *
 * @param owner     true if this player shared it, so saving updates the server's copy
 * @param expiresAt when the server deletes it (epoch milliseconds)
 */
public record SharedWorkbook(UUID id, String name, boolean owner, String ownerName, long expiresAt) {
}
