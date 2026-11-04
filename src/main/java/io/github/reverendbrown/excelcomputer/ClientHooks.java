package io.github.reverendbrown.excelcomputer;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Lets code that runs on both client and server (the block and the item) open
 * the spreadsheet screen without mentioning any client-only class. The client
 * entry point fills the hook in; a dedicated server never does, and never
 * needs to.
 */
public final class ClientHooks {
    public static Runnable openWorkbookPicker = () -> {
    };

    // Opens a shared workbook item (its id and file name).
    public static BiConsumer<UUID, String> openSharedWorkbook = (id, name) -> {
    };

    private ClientHooks() {
    }
}
