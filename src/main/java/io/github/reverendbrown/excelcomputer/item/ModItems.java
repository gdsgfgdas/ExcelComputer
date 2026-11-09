package io.github.reverendbrown.excelcomputer.item;

import io.github.reverendbrown.excelcomputer.ExcelComputer;
import io.github.reverendbrown.excelcomputer.logistics.HubTier;
import java.util.Locale;
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

    public static final ResourceKey<Item> SHARED_WORKBOOK_KEY =
            ResourceKey.create(Registries.ITEM, ExcelComputer.id("shared_workbook"));

    // Only made by sharing a workbook, so it is not in a creative tab.
    public static final Item SHARED_WORKBOOK = new SharedWorkbookItem(
            //? if >=1.21.2 {
            new Item.Properties()
                    .setId(SHARED_WORKBOOK_KEY)
                    .stacksTo(1)
            //?} else {
            /*new Item.Properties()
                    .stacksTo(1)
            *///?}
    );

    public static final ResourceKey<Item> EXCEL_PAPER_KEY =
            ResourceKey.create(Registries.ITEM, ExcelComputer.id("excel_paper"));

    // Blank paper; sharing a workbook (or copying a shared one) uses one up.
    public static final Item EXCEL_PAPER = new Item(
            //? if >=1.21.2 {
            new Item.Properties()
                    .setId(EXCEL_PAPER_KEY)
            //?} else {
            /*new Item.Properties()
            *///?}
    );

    public static final ResourceKey<Item> EXCEL_GUIDE_KEY =
            ResourceKey.create(Registries.ITEM, ExcelComputer.id("excel_guide"));

    // A book that opens the step-by-step guide.
    public static final Item EXCEL_GUIDE = new GuideBookItem(
            //? if >=1.21.2 {
            new Item.Properties()
                    .setId(EXCEL_GUIDE_KEY)
                    .stacksTo(1)
            //?} else {
            /*new Item.Properties()
                    .stacksTo(1)
            *///?}
    );

    // Hub Upgrades, indexed by the tier they make (none for Copper, the lowest).
    public static final Item[] HUB_UPGRADES = new Item[HubTier.values().length];

    static {
        for (HubTier tier : HubTier.values()) {
            if (tier.ordinal() == 0) {
                continue;
            }

            HUB_UPGRADES[tier.ordinal()] = new HubUpgradeItem(
                    //? if >=1.21.2 {
                    new Item.Properties()
                            .setId(ResourceKey.create(Registries.ITEM, ExcelComputer.id(upgradeId(tier)))),
                    //?} else {
                    /*new Item.Properties(),
                    *///?}
                    tier
            );
        }
    }

    /** The registry path of the upgrade that makes this tier, e.g. iron_hub_upgrade. */
    public static String upgradeId(HubTier tier) {
        return tier.name().toLowerCase(Locale.ROOT) + "_hub_upgrade";
    }

    //? if fabric {
    public static void initialize() {
        Registry.register(
                BuiltInRegistries.ITEM,
                EXCEL_ON_A_STICK_KEY,
                EXCEL_ON_A_STICK
        );
        Registry.register(
                BuiltInRegistries.ITEM,
                SHARED_WORKBOOK_KEY,
                SHARED_WORKBOOK
        );
        Registry.register(
                BuiltInRegistries.ITEM,
                EXCEL_PAPER_KEY,
                EXCEL_PAPER
        );
        Registry.register(
                BuiltInRegistries.ITEM,
                EXCEL_GUIDE_KEY,
                EXCEL_GUIDE
        );

        for (HubTier tier : HubTier.values()) {
            if (HUB_UPGRADES[tier.ordinal()] != null) {
                Registry.register(BuiltInRegistries.ITEM, ExcelComputer.id(upgradeId(tier)),
                        HUB_UPGRADES[tier.ordinal()]);
            }
        }

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .register(entries -> {
                    entries.accept(EXCEL_ON_A_STICK);
                    entries.accept(EXCEL_PAPER);
                    entries.accept(EXCEL_GUIDE);

                    for (Item upgrade : HUB_UPGRADES) {
                        if (upgrade != null) {
                            entries.accept(upgrade);
                        }
                    }
                });
    }
//?}
}
