package io.github.reverendbrown.excelcomputer.logistics;

import io.github.reverendbrown.excelcomputer.ClientHooks;
import io.github.reverendbrown.excelcomputer.item.HubUpgradeItem;
import net.minecraft.core.BlockPos;
//? if >=1.20.5 && <1.21.2 {
/*import net.minecraft.world.ItemInteractionResult;
*///?}
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A Logistics Hub: moves items between the inventories registered to it, as
 * its workbook's Transfers sheet says (see {@link LogisticsHubs}).
 *
 * <p>Right-clicking it opens its workbook (made by the server from a template
 * the first time) and makes it the hub the player's next sneak-right-clicked
 * inventories join. Whoever places it owns it.
 */
public class LogisticsHubBlock extends Block {
    // How often a hub with no workbook checks whether one was linked.
    private static final int UNLINKED_TICKS = 100;

    private final HubTier tier;

    // True while LogisticsHubs.upgrade swaps this block for the next tier, so the
    // removal below keeps the hub's link, workbook and inventories. Server thread only.
    static boolean upgrading;

    public LogisticsHubBlock(Properties properties, HubTier tier) {
        super(properties);
        this.tier = tier;
    }

    public HubTier tier() {
        return tier;
    }

    // ----- right-click -----

    // Holding a Hub Upgrade upgrades the hub instead of opening its workbook.
    //? if >=1.21.2 {
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        return HubUpgradeItem.upgrade(stack, level, pos, player)
                ? InteractionResult.SUCCESS
                : super.useItemOn(stack, state, level, pos, player, hand, hit);
    }
    //?} elif >=1.20.5 {
    /*@Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        return HubUpgradeItem.upgrade(stack, level, pos, player)
                ? ItemInteractionResult.sidedSuccess(level.isClientSide())
                : super.useItemOn(stack, state, level, pos, player, hand, hit);
    }
    *///?}

    //? if >=1.20.5 {
    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                            BlockHitResult hit) {
        return use(level, pos, player);
    }
    //?} else {
    /*@Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (HubUpgradeItem.upgrade(player.getItemInHand(hand), level, pos, player)) {
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        return use(level, pos, player);
    }
    *///?}

    private InteractionResult use(Level level, BlockPos pos, Player player) {
        if (level.isClientSide()) {
            // The server makes the workbook from its template the first time.
            ClientHooks.openConnector.accept(pos.immutable());
        } else if (player instanceof ServerPlayer serverPlayer) {
            LogisticsHubs.select(serverPlayer, (ServerLevel) level, pos.immutable(), tier);
        }

        return InteractionResult.SUCCESS;
    }

    // ----- the cycle: a block tick that schedules the next one -----

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // Keeps ticking when nothing is linked (slowly), so a workbook linked from
        // far away while this chunk was unloaded starts running once it loads.
        int next = LogisticsHubs.cycle(level, pos, tier);
        level.scheduleTick(pos, this, next > 0 ? next : UNLINKED_TICKS);
    }

    // Whoever places a hub owns it (see HubDirectory).
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (placer instanceof ServerPlayer player && level instanceof ServerLevel server) {
            HubDirectory.placed(player, server, pos.immutable());
        }
    }

    // Only the owner can mine a hub (breaking it deletes its workbook). Checked
    // on the server, which decides; creative mode breaks blocks without this.
    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel server
                && !HubDirectory.mayBreak(serverPlayer, server, pos)) {
            String owner = HubDirectory.ownerName(server, pos);
            LogisticsHubs.tell(serverPlayer, "Only " + (owner != null ? owner : "its owner") + " can break this hub");
            return 0;
        }

        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!oldState.is(state.getBlock()) && !level.isClientSide()) {
            // An upgraded hub keeps its workbook, so it carries on at its new pace.
            level.scheduleTick(pos, this, upgrading ? tier.cycleTicks() : UNLINKED_TICKS);
        }
    }

    // ----- removal: the link, the workbook and the registered inventories go too -----

    //? if >=1.21.5 {
    @Override
    public void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        if (!upgrading) {
            LogisticsHubs.remove(level, pos);
        }

        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
    }
    //?} else {
    /*@Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !upgrading && level instanceof ServerLevel server) {
            LogisticsHubs.remove(server, pos);
        }

        super.onRemove(state, level, pos, newState, movedByPiston);
    }
    *///?}
}
