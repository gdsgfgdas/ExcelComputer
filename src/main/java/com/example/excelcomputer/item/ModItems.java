package com.example.excelcomputer.item;

import com.example.excelcomputer.ExcelComputer;
//? if fabric {
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
//?}
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

public class ModItems {

    public static final ResourceKey<Item> EXCEL_ON_A_STICK_KEY =
            ResourceKey.create(Registries.ITEM, ExcelComputer.id("excel_on_a_stick"));

    // The item is only created here. Each loader registers it itself.
    public static final Item EXCEL_ON_A_STICK = new ExcelOnAStickItem(
            //? if >=1.21.2 {
            new Item.Properties()
                    .setId(EXCEL_ON_A_STICK_KEY)
                    .stacksTo(1)
            //?} else {
            /*new Item.Properties()
                    .stacksTo(1)
            *///?}
    );

    //? if fabric {
    public static void initialize() {
        Registry.register(
                BuiltInRegistries.ITEM,
                EXCEL_ON_A_STICK_KEY,
                EXCEL_ON_A_STICK
        );

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .register(entries -> entries.accept(EXCEL_ON_A_STICK));
    }
//?}
}
