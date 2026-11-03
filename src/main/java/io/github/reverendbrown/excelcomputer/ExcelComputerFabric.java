package io.github.reverendbrown.excelcomputer;

//? if fabric {
import io.github.reverendbrown.excelcomputer.block.ModBlocks;
import io.github.reverendbrown.excelcomputer.item.ModItems;
import net.fabricmc.api.ModInitializer;

public class ExcelComputerFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ModBlocks.initialize();
        ModItems.initialize();
        ExcelComputer.LOGGER.info("Columns and rows, delicious.");
    }
}
//?}
