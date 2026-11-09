package io.github.reverendbrown.excelcomputer.redstone;

import io.github.reverendbrown.excelcomputer.ClientHooks;
import io.github.reverendbrown.excelcomputer.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
//? if >=1.21.2 {
import net.minecraft.world.level.redstone.Orientation;
//?}

/**
 * The Redstone Connector: connects a workbook to redstone. Each side's
 * incoming signal goes into the workbook's Redstone sheet, and that sheet's
 * Out column sets the signal each side sends out (see {@link RedstoneConnectors}).
 *
 * <p>Right-clicking it holding Excel on a Stick links one of the player's
 * workbooks; right-clicking it otherwise opens the linked workbook to edit.
 * Like the Excel Table, only the client opens screens.
 */
public class RedstoneConnectorBlock extends Block {

    public RedstoneConnectorBlock(Properties properties) {
        super(properties);
    }

    // ----- right-click -----

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
        return use(level, pos, player);
    }
    *///?}

    private static InteractionResult use(Level level, BlockPos pos, Player player) {
        if (level.isClientSide()) {
            boolean holdingStick = player.getMainHandItem().is(ModItems.EXCEL_ON_A_STICK)
                    || player.getOffhandItem().is(ModItems.EXCEL_ON_A_STICK);

            if (holdingStick) {
                ClientHooks.linkConnector.accept(pos.immutable());
            } else {
                ClientHooks.openConnector.accept(pos.immutable());
            }
        }

        return InteractionResult.SUCCESS;
    }

    // ----- redstone -----

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    // direction points from the block asking toward this one, so the side
    // facing it is the opposite. The power is weak, like a redstone block's.
    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return level instanceof ServerLevel server
                ? RedstoneConnectors.signal(server, pos, direction.getOpposite())
                : 0;
    }

    // Any change next to it: work the sheet out again on the next tick (one
    // tick at most per block, however many changes there are).
    //? if >=1.21.2 {
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor,
                                Orientation orientation, boolean movedByPiston) {
        scheduleUpdate(level, pos);
    }
    //?} else {
    /*@Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor,
                                BlockPos neighborPos, boolean movedByPiston) {
        scheduleUpdate(level, pos);
    }
    *///?}

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!oldState.is(state.getBlock())) {
            scheduleUpdate(level, pos);
        }
    }

    private void scheduleUpdate(Level level, BlockPos pos) {
        if (!level.isClientSide()) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        RedstoneConnectors.update(level, pos);
    }

    // ----- removal: the link and the server's copy of the workbook go too -----

    //? if >=1.21.5 {
    @Override
    public void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        RedstoneConnectors.remove(level, pos);
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
    }
    //?} else {
    /*@Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server) {
            RedstoneConnectors.remove(server, pos);
        }

        super.onRemove(state, level, pos, newState, movedByPiston);
    }
    *///?}
}
