package io.github.reverendbrown.excelcomputer;

//? if forge {
/*import io.github.reverendbrown.excelcomputer.block.ModBlocks;
import io.github.reverendbrown.excelcomputer.logistics.HubTier;
import io.github.reverendbrown.excelcomputer.item.ModItems;
import io.github.reverendbrown.excelcomputer.share.ShareNetwork;
import io.github.reverendbrown.excelcomputer.share.ShareProtocol;
import io.github.reverendbrown.excelcomputer.share.ShareServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.RegisterEvent;

@Mod(ExcelComputer.MOD_ID)
public class ExcelComputerForge {
    // Workbook sharing. acceptMissingOr keeps the mod optional on either side.
    private static final String PROTOCOL = "1";
    private static final SimpleChannel SHARE_CHANNEL = NetworkRegistry.newSimpleChannel(
            ExcelComputer.id("share"),
            () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL),
            NetworkRegistry.acceptMissingOr(PROTOCOL)
    );

    // ShareProtocol messages, one class per direction.
    public record ToServer(byte[] data) {
    }

    public record ToClient(byte[] data) {
    }

    public ExcelComputerForge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(ExcelComputerForge::register);
        modEventBus.addListener(ExcelComputerForge::addToCreativeTabs);
        registerNetworking();

        ExcelComputer.LOGGER.info("Columns and rows, delicious.");
    }

    // Forge only allows registering inside RegisterEvent.
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

    // consumerMainThread runs the handlers on the game thread.
    private static void registerNetworking() {
        SHARE_CHANNEL.messageBuilder(ToServer.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((message, buf) -> buf.writeByteArray(message.data()))
                .decoder(buf -> new ToServer(buf.readByteArray(ShareProtocol.MAX_MESSAGE_BYTES)))
                .consumerMainThread((message, context) -> {
                    ServerPlayer player = context.get().getSender();

                    if (player != null) {
                        ShareServer.handle(player, message.data());
                    }
                })
                .add();

        SHARE_CHANNEL.messageBuilder(ToClient.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((message, buf) -> buf.writeByteArray(message.data()))
                .decoder(buf -> new ToClient(buf.readByteArray(ShareProtocol.MAX_MESSAGE_BYTES)))
                .consumerMainThread((message, context) -> ShareNetwork.clientReceiver.accept(message.data()))
                .add();

        ShareNetwork.sendToClient = (player, message) ->
                SHARE_CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ToClient(message));
    }

    // Used by ExcelComputerForgeClient.
    static void sendToServer(byte[] message) {
        SHARE_CHANNEL.sendToServer(new ToServer(message));
    }

    static boolean serverHasChannel(Connection connection) {
        return SHARE_CHANNEL.isRemotePresent(connection);
    }
}
*///?}
