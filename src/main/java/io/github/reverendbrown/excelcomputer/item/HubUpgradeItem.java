package io.github.reverendbrown.excelcomputer.item;

import io.github.reverendbrown.excelcomputer.ClientHooks;
import io.github.reverendbrown.excelcomputer.logistics.HubTier;
import io.github.reverendbrown.excelcomputer.logistics.LogisticsHubBlock;
import io.github.reverendbrown.excelcomputer.logistics.LogisticsHubs;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
//? if >=1.21.5 {
import net.minecraft.world.item.component.TooltipDisplay;
import java.util.function.Consumer;
//?} else {
/*import java.util.List;
*///?}
//? if <1.21 {
/*import org.jetbrains.annotations.Nullable;
*///?}

/**
 * A Hub Upgrade: used on the Logistics Hub one tier below its own, it turns
 * that hub into its tier in place. The hub keeps its workbook, owner, members,
 * name and registered inventories, since all of those are stored by position.
 *
 * <p>Right-clicking a hub with it reaches {@link #upgrade} through
 * {@code LogisticsHubBlock.useItemOn} (the block is asked first), and
 * sneak-right-clicking through {@link #useOn}.
 */
public class HubUpgradeItem extends Item {
    private final HubTier tier;

    public HubUpgradeItem(Properties properties, HubTier tier) {
        super(properties);
        this.tier = tier;
    }

    /** The tier this upgrade turns a hub into. */
    public HubTier tier() {
        return tier;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        return player != null && upgrade(context.getItemInHand(), context.getLevel(), context.getClickedPos(), player)
                ? InteractionResult.SUCCESS
                : InteractionResult.PASS;
    }

    /**
     * Upgrades the hub at pos with the stack (an upgrade item), using one up.
     *
     * @return whether pos holds a Logistics Hub (so the click is used up, even
     *         when the server then says the upgrade doesn't fit)
     */
    public static boolean upgrade(ItemStack stack, Level level, BlockPos pos, Player player) {
        if (!(stack.getItem() instanceof HubUpgradeItem upgrade)
                || !(level.getBlockState(pos).getBlock() instanceof LogisticsHubBlock hub)) {
            return false;
        }

        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel server
                && LogisticsHubs.upgrade(serverPlayer, server, pos.immutable(), hub.tier(), upgrade.tier())
                && !player.getAbilities().instabuild) {
            stack.shrink(1);
        }

        return true;
    }

    private Component usage() {
        return Component.literal("Right-click a " + HubTier.values()[tier.ordinal() - 1].label()
                + " to make it a " + tier.label()).withStyle(ChatFormatting.GRAY);
    }

    //? if >=1.21.5 {
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        tooltip.accept(usage());
        ClientHooks.guideTooltip.accept("hubs", tooltip);
    }
    //?} elif >=1.21 {
    /*@Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(usage());
        ClientHooks.guideTooltip.accept("hubs", tooltip::add);
    }
    *///?} else {
    /*@Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(usage());
        ClientHooks.guideTooltip.accept("hubs", tooltip::add);
    }
    *///?}
}
