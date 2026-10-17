package com.example.excelcomputer.item;

import com.example.excelcomputer.WorkbookPickerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
//? if >=1.21.2 {
import net.minecraft.world.InteractionResult;
//?} else {
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

    //? if >=1.21.2 {
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            Minecraft.getInstance().setScreen(new WorkbookPickerScreen());
        }

        return InteractionResult.SUCCESS;
    }
    //?} else {
    /*@Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            Minecraft.getInstance().setScreen(new WorkbookPickerScreen());
        }

        return InteractionResultHolder.success(player.getItemInHand(hand));
    }
    *///?}
}
