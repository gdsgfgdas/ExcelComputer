package io.github.reverendbrown.excelcomputer;

//? if neoforge {
/*import io.github.reverendbrown.excelcomputer.block.ModBlocks;
import io.github.reverendbrown.excelcomputer.logistics.HubTier;
import io.github.reverendbrown.excelcomputer.item.ModItems;
import io.github.reverendbrown.excelcomputer.share.ShareNetwork;
import io.github.reverendbrown.excelcomputer.share.SharePayloads;
import io.github.reverendbrown.excelcomputer.share.ShareServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(ExcelComputer.MOD_ID)
public class ExcelComputerNeoForge {
    public ExcelComputerNeoForge(IEventBus modEventBus) {
        modEventBus.addListener(ExcelComputerNeoForge::register);
        modEventBus.addListener(ExcelComputerNeoForge::addToCreativeTabs);
        modEventBus.addListener(ExcelComputerNeoForge::registerPayloads);

        ExcelComputer.LOGGER.info("Columns and rows, delicious.");
    }

    // NeoForge only allows registering inside RegisterEvent.
    private static void register(RegisterEvent event) {
        event.register(Registries.BLOCK, helper ->
                helper.register(ExcelComputer.id("excel_computer"), ModBlocks.EXCEL_COMPUTER));
        if (ExcelComputer.REDSTONE_CONNECTOR) {
            event.register(Registries.BLOCK, helper ->
                    helper.register(ExcelComputer.id("redstone_connector"), ModBlocks.REDSTONE_CONNECTOR));
        }
        event.register(Registries.BLOCK, helper -> {
            for (HubTier tier : HubTier.values()) {
                helper.register(ExcelComputer.id(tier.id()), ModBlocks.LOGISTICS_HUBS[tier.ordinal()]);
            }
        });

        event.register(Registries.ITEM, helper -> {
            helper.register(ExcelComputer.id("excel_computer"), ModBlocks.EXCEL_COMPUTER_ITEM);
            if (ExcelComputer.REDSTONE_CONNECTOR) {
                helper.register(ExcelComputer.id("redstone_connector"), ModBlocks.REDSTONE_CONNECTOR_ITEM);
            }

            for (HubTier tier : HubTier.values()) {
                helper.register(ExcelComputer.id(tier.id()), ModBlocks.LOGISTICS_HUB_ITEMS[tier.ordinal()]);
            }

            helper.register(ExcelComputer.id("excel_on_a_stick"), ModItems.EXCEL_ON_A_STICK);
            helper.register(ExcelComputer.id("shared_workbook"), ModItems.SHARED_WORKBOOK);
            helper.register(ExcelComputer.id("excel_paper"), ModItems.EXCEL_PAPER);
            helper.register(ExcelComputer.id("excel_guide"), ModItems.EXCEL_GUIDE);

            for (HubTier tier : HubTier.values()) {
                if (ModItems.HUB_UPGRADES[tier.ordinal()] != null) {
                    helper.register(ExcelComputer.id(ModItems.upgradeId(tier)), ModItems.HUB_UPGRADES[tier.ordinal()]);
                }
            }
        });
    }

    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModBlocks.EXCEL_COMPUTER_ITEM);

            for (net.minecraft.world.item.Item hub : ModBlocks.LOGISTICS_HUB_ITEMS) {
                event.accept(hub);
            }
        } else if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS && ExcelComputer.REDSTONE_CONNECTOR) {
            event.accept(ModBlocks.REDSTONE_CONNECTOR_ITEM);
        } else if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.EXCEL_ON_A_STICK);
            event.accept(ModItems.EXCEL_PAPER);
            event.accept(ModItems.EXCEL_GUIDE);

            for (net.minecraft.world.item.Item upgrade : ModItems.HUB_UPGRADES) {
                if (upgrade != null) {
                    event.accept(upgrade);
                }
            }
        }
    }

    // Workbook sharing. Optional, so players can still join servers without the mod.
    // Handlers run on the game thread by default.
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();

        registrar.playToServer(SharePayloads.ToServer.TYPE, SharePayloads.ToServer.CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        ShareServer.handle(player, payload.data());
                    }
                });
        registrar.playToClient(SharePayloads.ToClient.TYPE, SharePayloads.ToClient.CODEC,
                (payload, context) -> ShareNetwork.clientReceiver.accept(payload.data()));

        ShareNetwork.sendToClient = (player, message) ->
                PacketDistributor.sendToPlayer(player, new SharePayloads.ToClient(message));
    }
}
*///?}
