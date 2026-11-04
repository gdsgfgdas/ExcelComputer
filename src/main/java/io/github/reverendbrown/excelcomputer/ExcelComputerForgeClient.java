package io.github.reverendbrown.excelcomputer;

//? if forge {
/*import io.github.reverendbrown.excelcomputer.share.ShareClient;
import io.github.reverendbrown.excelcomputer.share.ShareNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

// Registered for the client only (never on a dedicated server).
@Mod.EventBusSubscriber(
        modid = ExcelComputer.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public class ExcelComputerForgeClient {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ClientHooks.openWorkbookPicker = () ->
                Minecraft.getInstance().setScreen(new WorkbookPickerScreen());
        ClientHooks.openSharedWorkbook = ShareClient::openShared;
        ShareNetwork.clientReceiver = ShareClient::receive;
        ShareNetwork.sendToServer = ExcelComputerForge::sendToServer;
        ShareNetwork.serverSupportsSharing = () -> {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            return connection != null && ExcelComputerForge.serverHasChannel(connection.getConnection());
        };
    }
}
*///?}
