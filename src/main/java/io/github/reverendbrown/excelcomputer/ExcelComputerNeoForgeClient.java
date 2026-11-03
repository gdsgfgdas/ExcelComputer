package io.github.reverendbrown.excelcomputer;

//? if neoforge {
/*import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

// Runs only on the client (never on a dedicated server).
@Mod(value = ExcelComputer.MOD_ID, dist = Dist.CLIENT)
public class ExcelComputerNeoForgeClient {
    public ExcelComputerNeoForgeClient() {
        ClientHooks.openWorkbookPicker = () ->
                Minecraft.getInstance().setScreen(new WorkbookPickerScreen());
    }
}
*///?}
