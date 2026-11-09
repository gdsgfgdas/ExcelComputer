package io.github.reverendbrown.excelcomputer.item;

import io.github.reverendbrown.excelcomputer.ClientHooks;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
//? if >=1.21.5 {
import net.minecraft.world.item.component.TooltipDisplay;
import java.util.function.Consumer;
//?} else {
/*import java.util.List;
*///?}
//? if <1.21 {
/*import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
*///?}

/**
 * A block's item whose tooltip says "Hold [W] for the guide", which opens the
 * guide chapter about it (the Excel Table, the Logistics Hubs).
 */
public class GuideBlockItem extends BlockItem {
    private final String guideChapter;

    public GuideBlockItem(Block block, Properties properties, String guideChapter) {
        super(block, properties);
        this.guideChapter = guideChapter;
    }

    //? if >=1.21.5 {
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        ClientHooks.guideTooltip.accept(guideChapter, tooltip);
    }
    //?} elif >=1.21 {
    /*@Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        ClientHooks.guideTooltip.accept(guideChapter, tooltip::add);
    }
    *///?} else {
    /*@Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        ClientHooks.guideTooltip.accept(guideChapter, tooltip::add);
    }
    *///?}
}
