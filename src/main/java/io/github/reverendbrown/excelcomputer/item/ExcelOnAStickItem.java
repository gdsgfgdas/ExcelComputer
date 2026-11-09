package io.github.reverendbrown.excelcomputer.item;

import io.github.reverendbrown.excelcomputer.ClientHooks;
import io.github.reverendbrown.excelcomputer.logistics.LogisticsHubBlock;
import io.github.reverendbrown.excelcomputer.logistics.LogisticsHubs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
//? if <1.21.2 {
/*import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
*///?}
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

public class ExcelOnAStickItem extends Item {

    public ExcelOnAStickItem(Properties properties) {
        super(properties);
    }

    // Sneak-right-clicking a block: a Logistics Hub is chosen, and an inventory
    // is added to (or removed from) the chosen hub. Anything else falls through
    // to use(), which opens the workbook picker.
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();

        if (player == null || !player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }

        Level level = context.getLevel();
        BlockState state = level.getBlockState(context.getClickedPos());
        boolean hub = state.getBlock() instanceof LogisticsHubBlock;

        // The client can only guess which blocks are inventories; the server checks.
        if (!hub && level.getBlockEntity(context.getClickedPos()) == null) {
            return InteractionResult.PASS;
        }

        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel server) {
            if (hub) {
                LogisticsHubs.select(serverPlayer, server, context.getClickedPos().immutable(),
                        ((LogisticsHubBlock) state.getBlock()).tier());
            } else {
                LogisticsHubs.toggleInventory(serverPlayer, server, context.getClickedPos().immutable(),
                        context.getClickedFace());
            }
        }

        return InteractionResult.SUCCESS;
    }

    //? if >=1.21.2 {
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            // Sneaking: straight to the chosen Logistics Hub's workbook, if there is one.
            if (!player.isSecondaryUseActive() || !ClientHooks.openSelectedHub.getAsBoolean()) {
                ClientHooks.openWorkbookPicker.run();
            }
        }

        return InteractionResult.SUCCESS;
    }
    //?} else {
    /*@Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            // Sneaking: straight to the chosen Logistics Hub's workbook, if there is one.
            if (!player.isSecondaryUseActive() || !ClientHooks.openSelectedHub.getAsBoolean()) {
                ClientHooks.openWorkbookPicker.run();
            }
        }

        return InteractionResultHolder.success(player.getItemInHand(hand));
    }
    *///?}

    // "Hold [W] for the guide" (see GuideScreen).
    //? if >=1.21.5 {
    @Override
    public void appendHoverText(net.minecraft.world.item.ItemStack stack, TooltipContext context,
                                net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<net.minecraft.network.chat.Component> tooltip,
                                net.minecraft.world.item.TooltipFlag flag) {
        ClientHooks.guideTooltip.accept("start", tooltip);
    }
    //?} elif >=1.21 {
    /*@Override
    public void appendHoverText(net.minecraft.world.item.ItemStack stack, TooltipContext context,
                                java.util.List<net.minecraft.network.chat.Component> tooltip,
                                net.minecraft.world.item.TooltipFlag flag) {
        ClientHooks.guideTooltip.accept("start", tooltip::add);
    }
    *///?} else {
    /*@Override
    public void appendHoverText(net.minecraft.world.item.ItemStack stack, @org.jetbrains.annotations.Nullable Level level,
                                java.util.List<net.minecraft.network.chat.Component> tooltip,
                                net.minecraft.world.item.TooltipFlag flag) {
        ClientHooks.guideTooltip.accept("start", tooltip::add);
    }
    *///?}
}
