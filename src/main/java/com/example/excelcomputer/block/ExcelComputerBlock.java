package com.example.excelcomputer.block;

import com.example.excelcomputer.ClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class ExcelComputerBlock extends Block {

    public ExcelComputerBlock(Properties properties) {
        super(properties);
    }

    //? if >=1.20.5 {
    @Override
    public InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hit
    ) {
        return openScreen(level);
    }
    //?} else {
    /*@Override
    public InteractionResult use(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit
    ) {
        return openScreen(level);
    }
    *///?}

    // Only the client opens the screen; a server just reports that the click was used.
    private static InteractionResult openScreen(Level level) {
        if (level.isClientSide()) {
            ClientHooks.openWorkbookPicker.run();
        }

        return InteractionResult.SUCCESS;
    }
}
