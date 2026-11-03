package io.github.reverendbrown.excelcomputer;

//? if fabric {
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

// Runs only on the client (never on a dedicated server).
public class ExcelComputerFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientHooks.openWorkbookPicker = () ->
                Minecraft.getInstance().setScreen(new WorkbookPickerScreen());
    }
}
//?}
