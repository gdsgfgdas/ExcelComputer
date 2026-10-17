package com.example.excelcomputer;

//? if fabric {
import com.example.excelcomputer.block.ModBlocks;
import com.example.excelcomputer.item.ModItems;
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
