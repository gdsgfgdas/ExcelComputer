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
import io.github.reverendbrown.excelcomputer.item.GuideBlockItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import io.github.reverendbrown.excelcomputer.redstone.RedstoneConnectorBlock;
import io.github.reverendbrown.excelcomputer.logistics.HubTier;
import io.github.reverendbrown.excelcomputer.logistics.LogisticsHubBlock;
import net.minecraft.world.level.block.SoundType;

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

    public static final Item EXCEL_COMPUTER_ITEM = new GuideBlockItem(
            EXCEL_COMPUTER,
            //? if >=1.21.2 {
            new Item.Properties()
                    .setId(itemKey("excel_computer"))
                    .useBlockDescriptionPrefix(),
            //?} else {
            /*new Item.Properties(),
            *///?}
            "start"
    );

    public static final ResourceKey<Block> REDSTONE_CONNECTOR_KEY =
            blockKey("redstone_connector");

    // Connects a workbook to redstone. Pistons can't move it: its link is
    // stored by position (see RedstoneConnectors).
    // Null while the connector is hidden (ExcelComputer.REDSTONE_CONNECTOR): a block
    // that is created but never registered would stop the game from starting.
    public static final Block REDSTONE_CONNECTOR = !ExcelComputer.REDSTONE_CONNECTOR ? null : new RedstoneConnectorBlock(
            //? if >=1.21.2 {
            BlockBehaviour.Properties.of()
                    .strength(1.5F)
                    .pushReaction(PushReaction.BLOCK)
                    .setId(REDSTONE_CONNECTOR_KEY)
            //?} else {
            /*BlockBehaviour.Properties.of()
                    .strength(1.5F)
                    .pushReaction(PushReaction.BLOCK)
            *///?}
    );

    public static final Item REDSTONE_CONNECTOR_ITEM = !ExcelComputer.REDSTONE_CONNECTOR ? null : new BlockItem(
            REDSTONE_CONNECTOR,
            //? if >=1.21.2 {
            new Item.Properties()
                    .setId(itemKey("redstone_connector"))
                    .useBlockDescriptionPrefix()
            //?} else {
            /*new Item.Properties()
            *///?}
    );

    // The Logistics Hubs, one per tier (in HubTier order). Like the connector,
    // pistons can't move them: their links are stored by position.
    public static final Block[] LOGISTICS_HUBS = new Block[HubTier.values().length];
    public static final Item[] LOGISTICS_HUB_ITEMS = new Item[HubTier.values().length];

    static {
        for (HubTier tier : HubTier.values()) {
            ResourceKey<Block> key = blockKey(tier.id());
            LOGISTICS_HUBS[tier.ordinal()] = new LogisticsHubBlock(
                    //? if >=1.21.2 {
                    BlockBehaviour.Properties.of()
                            .strength(tier == HubTier.NETHERITE ? 3F : 2F, tier == HubTier.NETHERITE ? 1200F : 6F)
                            .sound(tier == HubTier.NETHERITE ? SoundType.NETHERITE_BLOCK : SoundType.METAL)
                            .pushReaction(PushReaction.BLOCK)
                            .setId(key),
                    //?} else {
                    /*BlockBehaviour.Properties.of()
                            .strength(tier == HubTier.NETHERITE ? 3F : 2F, tier == HubTier.NETHERITE ? 1200F : 6F)
                            .sound(tier == HubTier.NETHERITE ? SoundType.NETHERITE_BLOCK : SoundType.METAL)
                            .pushReaction(PushReaction.BLOCK),
                    *///?}
                    tier);
            LOGISTICS_HUB_ITEMS[tier.ordinal()] = new GuideBlockItem(
                    LOGISTICS_HUBS[tier.ordinal()],
                    //? if >=1.21.2 {
                    new Item.Properties()
                            .setId(itemKey(tier.id()))
                            .useBlockDescriptionPrefix(),
                    //?} else {
                    /*new Item.Properties(),
                    *///?}
                    "hubs"
            );
        }
    }

    //? if fabric {
    public static void initialize() {
        Registry.register(BuiltInRegistries.BLOCK, EXCEL_COMPUTER_KEY, EXCEL_COMPUTER);
        Registry.register(BuiltInRegistries.ITEM, itemKey("excel_computer"), EXCEL_COMPUTER_ITEM);
        if (ExcelComputer.REDSTONE_CONNECTOR) {
            Registry.register(BuiltInRegistries.BLOCK, REDSTONE_CONNECTOR_KEY, REDSTONE_CONNECTOR);
            Registry.register(BuiltInRegistries.ITEM, itemKey("redstone_connector"), REDSTONE_CONNECTOR_ITEM);
            ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.REDSTONE_BLOCKS)
                    .register(entries -> entries.accept(REDSTONE_CONNECTOR_ITEM));
        }

        for (HubTier tier : HubTier.values()) {
            Registry.register(BuiltInRegistries.BLOCK, blockKey(tier.id()), LOGISTICS_HUBS[tier.ordinal()]);
            Registry.register(BuiltInRegistries.ITEM, itemKey(tier.id()), LOGISTICS_HUB_ITEMS[tier.ordinal()]);
        }

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                .register(entries -> entries.accept(EXCEL_COMPUTER_ITEM));
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                .register(entries -> {
                    for (Item hub : LOGISTICS_HUB_ITEMS) {
                        entries.accept(hub);
                    }
                });
    }
//?}
}
