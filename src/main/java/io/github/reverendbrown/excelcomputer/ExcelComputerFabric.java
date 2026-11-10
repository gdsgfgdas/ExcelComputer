package io.github.reverendbrown.excelcomputer;

//? if fabric {
import io.github.reverendbrown.excelcomputer.block.ModBlocks;
import io.github.reverendbrown.excelcomputer.item.ModItems;
import io.github.reverendbrown.excelcomputer.share.ShareNetwork;
import io.github.reverendbrown.excelcomputer.share.ShareServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
//? if >=1.21 {
import io.github.reverendbrown.excelcomputer.share.SharePayloads;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
//?} else {
/*import io.github.reverendbrown.excelcomputer.share.ShareProtocol;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
*///?}

public class ExcelComputerFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ModBlocks.initialize();
        ModItems.initialize();
        // Storage networks (Refined Storage) the hubs can be cabled to.
        io.github.reverendbrown.excelcomputer.logistics.HubNetworks.init();
        registerNetworking();
        ExcelComputer.LOGGER.info("Columns and rows, delicious.");
    }

    // Workbook sharing: one channel each way that carries ShareProtocol messages.
    private static void registerNetworking() {
        //? if >=26.1 {
        /*PayloadTypeRegistry.serverboundPlay().register(SharePayloads.ToServer.TYPE, SharePayloads.ToServer.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SharePayloads.ToClient.TYPE, SharePayloads.ToClient.CODEC);
        *///?} elif >=1.21 {
        PayloadTypeRegistry.playC2S().register(SharePayloads.ToServer.TYPE, SharePayloads.ToServer.CODEC);
        PayloadTypeRegistry.playS2C().register(SharePayloads.ToClient.TYPE, SharePayloads.ToClient.CODEC);
        //?}

        //? if >=1.21 {
        // Payload handlers already run on the server thread.
        ServerPlayNetworking.registerGlobalReceiver(SharePayloads.ToServer.TYPE,
                (payload, context) -> ShareServer.handle(context.player(), payload.data()));
        ShareNetwork.sendToClient = (player, message) ->
                ServerPlayNetworking.send(player, new SharePayloads.ToClient(message));
        //?} else {
        /*ServerPlayNetworking.registerGlobalReceiver(ShareNetwork.TO_SERVER_ID, (server, player, handler, buf, sender) -> {
            byte[] message = buf.readByteArray(ShareProtocol.MAX_MESSAGE_BYTES);
            server.execute(() -> ShareServer.handle(player, message));
        });
        ShareNetwork.sendToClient = (player, message) -> {
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeByteArray(message);
            ServerPlayNetworking.send(player, ShareNetwork.TO_CLIENT_ID, buf);
        };
        *///?}
    }
}
//?}
