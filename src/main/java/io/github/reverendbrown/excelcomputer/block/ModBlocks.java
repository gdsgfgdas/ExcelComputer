package io.github.reverendbrown.excelcomputer.block;

import io.github.reverendbrown.excelcomputer.ExcelComputer;
//? if fabric {
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
//?}
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class ModBlocks {

    private static ResourceKey<Block> blockKey(String name) {
        return ResourceKey.create(Registries.BLOCK, ExcelComputer.id(name));
    }

    private static ResourceKey<Item> itemKey(String name) {
        return ResourceKey.create(Registries.ITEM, ExcelComputer.id(name));
    }

    public static final ResourceKey<Block> EXCEL_COMPUTER_KEY =
            blockKey("excel_computer");

    // The objects are only created here. Each loader registers them itself.
    public static final Block EXCEL_COMPUTER = new ExcelComputerBlock(
            //? if >=1.21.2 {
            BlockBehaviour.Properties.of()
                    .strength(1F)
                    .setId(EXCEL_COMPUTER_KEY)
            //?} else {
            /*BlockBehaviour.Properties.of()
                    .strength(1F)
            *///?}
    );

    public static final Item EXCEL_COMPUTER_ITEM = new BlockItem(
            EXCEL_COMPUTER,
            //? if >=1.21.2 {
            new Item.Properties()
                    .setId(itemKey("excel_computer"))
                    .useBlockDescriptionPrefix()
            //?} else {
            /*new Item.Properties()
            *///?}
    );

    //? if fabric {
    public static void initialize() {
        Registry.register(BuiltInRegistries.BLOCK, EXCEL_COMPUTER_KEY, EXCEL_COMPUTER);
        Registry.register(BuiltInRegistries.ITEM, itemKey("excel_computer"), EXCEL_COMPUTER_ITEM);

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                .register(entries -> entries.accept(EXCEL_COMPUTER_ITEM));
    }
//?}
}
