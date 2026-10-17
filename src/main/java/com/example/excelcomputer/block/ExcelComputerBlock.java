
package com.example.excelcomputer.block;

import com.example.excelcomputer.WorkbookPickerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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

    @Override
    public InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hit
    ) {
        if (level.isClientSide()) {
            Minecraft.getInstance().setScreen(
                    new WorkbookPickerScreen()
            );
        }

        return InteractionResult.SUCCESS;
    }
}
