package io.github.reverendbrown.excelcomputer;

//? if fabric {
import io.github.reverendbrown.excelcomputer.share.ShareClient;
import io.github.reverendbrown.excelcomputer.share.ShareNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
//? if >=1.21 {
import io.github.reverendbrown.excelcomputer.share.SharePayloads;
//?} else {
/*import io.github.reverendbrown.excelcomputer.share.ShareProtocol;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
*///?}

// Runs only on the client (never on a dedicated server).
public class ExcelComputerFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientHooks.openWorkbookPicker = () ->
                Minecraft.getInstance().setScreen(new WorkbookPickerScreen());
        ClientHooks.openSharedWorkbook = ShareClient::openShared;
        ShareNetwork.clientReceiver = ShareClient::receive;

        //? if >=1.21 {
        // Payload handlers already run on the client thread.
        ClientPlayNetworking.registerGlobalReceiver(SharePayloads.ToClient.TYPE,
                (payload, context) -> ShareClient.receive(payload.data()));
        ShareNetwork.sendToServer = message ->
                ClientPlayNetworking.send(new SharePayloads.ToServer(message));
        ShareNetwork.serverSupportsSharing = () ->
                ClientPlayNetworking.canSend(SharePayloads.ToServer.TYPE);
        //?} else {
        /*ClientPlayNetworking.registerGlobalReceiver(ShareNetwork.TO_CLIENT_ID, (client, handler, buf, sender) -> {
            byte[] message = buf.readByteArray(ShareProtocol.MAX_MESSAGE_BYTES);
            client.execute(() -> ShareClient.receive(message));
        });
        ShareNetwork.sendToServer = message -> {
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeByteArray(message);
            ClientPlayNetworking.send(ShareNetwork.TO_SERVER_ID, buf);
        };
        ShareNetwork.serverSupportsSharing = () -> ClientPlayNetworking.canSend(ShareNetwork.TO_SERVER_ID);
        *///?}
    }
}
//?}
