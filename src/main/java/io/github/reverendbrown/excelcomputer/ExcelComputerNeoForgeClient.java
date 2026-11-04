package io.github.reverendbrown.excelcomputer;

//? if neoforge {
/*import io.github.reverendbrown.excelcomputer.share.ShareClient;
import io.github.reverendbrown.excelcomputer.share.ShareNetwork;
import io.github.reverendbrown.excelcomputer.share.SharePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
//? if >=1.21.9 {
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
//?} else {
/^import net.neoforged.neoforge.network.PacketDistributor;
^///?}

// Runs only on the client (never on a dedicated server).
@Mod(value = ExcelComputer.MOD_ID, dist = Dist.CLIENT)
public class ExcelComputerNeoForgeClient {
    public ExcelComputerNeoForgeClient() {
        ClientHooks.openWorkbookPicker = () ->
                Minecraft.getInstance().setScreen(new WorkbookPickerScreen());
        ClientHooks.openSharedWorkbook = ShareClient::openShared;
        ShareNetwork.clientReceiver = ShareClient::receive;

        ShareNetwork.sendToServer = message ->
                //? if >=1.21.9 {
                ClientPacketDistributor.sendToServer(new SharePayloads.ToServer(message));
                //?} else {
                /^PacketDistributor.sendToServer(new SharePayloads.ToServer(message));
                ^///?}
        ShareNetwork.serverSupportsSharing = () -> {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            return connection != null && connection.hasChannel(SharePayloads.ToServer.TYPE);
        };
    }
}
*///?}
