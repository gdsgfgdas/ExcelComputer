package io.github.reverendbrown.excelcomputer;

import net.minecraft.core.BlockPos;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Lets code that runs on both client and server (the blocks and the items)
 * open screens without mentioning any client-only class. The client entry
 * point fills the hooks in; a dedicated server never does, and never needs to.
 */
public final class ClientHooks {
    public static Runnable openWorkbookPicker = () -> {
    };

    // Opens a shared workbook item (its id and file name).
    public static BiConsumer<UUID, String> openSharedWorkbook = (id, name) -> {
    };

    // Redstone Connector at a position: pick a workbook to link to it, or
    // open the workbook linked to it.
    public static Consumer<BlockPos> linkConnector = pos -> {
    };
    public static Consumer<BlockPos> openConnector = pos -> {
    };

    // Opens the workbook of the Logistics Hub the player chose last, from
    // anywhere. False if there is none to open (the picker opens instead).
    public static java.util.function.BooleanSupplier openSelectedHub = () -> false;

    // Adds "Hold [W] for the guide" to an item's tooltip (the guide chapter's id,
    // the tooltip's lines); holding W opens that chapter. See GuideScreen.tooltip.
    public static BiConsumer<String, Consumer<net.minecraft.network.chat.Component>> guideTooltip = (chapter, lines) -> {
    };

    // Opens the guide at its first chapter (the Excel Guide book).
    public static Runnable openGuide = () -> {
    };

    private ClientHooks() {
    }
}
