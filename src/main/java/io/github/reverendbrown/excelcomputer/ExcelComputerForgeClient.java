package io.github.reverendbrown.excelcomputer;

//? if forge {
/*import net.minecraft.client.Minecraft;
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
    }
}
*///?}
