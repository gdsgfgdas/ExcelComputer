package io.github.reverendbrown.excelcomputer;

//? if neoforge {
/*import io.github.reverendbrown.excelcomputer.block.ModBlocks;
import io.github.reverendbrown.excelcomputer.item.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(ExcelComputer.MOD_ID)
public class ExcelComputerNeoForge {
    public ExcelComputerNeoForge(IEventBus modEventBus) {
        modEventBus.addListener(ExcelComputerNeoForge::register);
        modEventBus.addListener(ExcelComputerNeoForge::addToCreativeTabs);

        ExcelComputer.LOGGER.info("Columns and rows, delicious.");
    }

    // NeoForge only allows registering inside RegisterEvent.
    private static void register(RegisterEvent event) {
        event.register(Registries.BLOCK, helper ->
                helper.register(ExcelComputer.id("excel_computer"), ModBlocks.EXCEL_COMPUTER));

        event.register(Registries.ITEM, helper -> {
            helper.register(ExcelComputer.id("excel_computer"), ModBlocks.EXCEL_COMPUTER_ITEM);
            helper.register(ExcelComputer.id("excel_on_a_stick"), ModItems.EXCEL_ON_A_STICK);
        });
    }

    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModBlocks.EXCEL_COMPUTER_ITEM);
        } else if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.EXCEL_ON_A_STICK);
        }
    }
}
*///?}
