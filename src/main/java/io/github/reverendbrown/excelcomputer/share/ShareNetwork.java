package io.github.reverendbrown.excelcomputer.share;

import io.github.reverendbrown.excelcomputer.ExcelComputer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The bridge between the sharing code and each loader's networking API.
 * The loader entry points fill these in; the sharing code only ever sends
 * and receives byte arrays (see {@link ShareProtocol}).
 */
public final class ShareNetwork {
    // Channel names (1.20.1 Fabric uses them directly; 1.21+ wraps them in payload types).
    public static final ResourceLocation TO_SERVER_ID = ExcelComputer.id("share_to_server");
    public static final ResourceLocation TO_CLIENT_ID = ExcelComputer.id("share_to_client");

    // Server side: sends a message to one player.
    public static BiConsumer<ServerPlayer, byte[]> sendToClient = (player, message) -> {
    };

    // Client side (filled in by the client entry point only).
    public static Consumer<byte[]> sendToServer = message -> {
    };
    public static BooleanSupplier serverSupportsSharing = () -> false;
    public static Consumer<byte[]> clientReceiver = message -> {
    };

    private ShareNetwork() {
    }
}
