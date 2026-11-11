package io.github.reverendbrown.excelcomputer.mixin;

import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
//? if <1.21 {
/*import io.github.reverendbrown.excelcomputer.item.SharedWorkbookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;
*///?}

/**
 * 1.20.1 only shows Items.FILLED_MAP in an item frame as a map (1.21+ goes by
 * the map id). A shared workbook with a picture counts too, so a frame draws
 * its picture full size. The server already sends a framed item's map to
 * players whatever the item is. Empty on 1.21+ (the mixin config is the same
 * for every version).
 */
@Mixin(ItemFrame.class)
public abstract class ItemFrameMixin {
    //? if <1.21 {
    /*@Shadow
    public abstract ItemStack getItem();

    @Inject(method = "getFramedMapId", at = @At("HEAD"), cancellable = true)
    private void excelcomputer$sheetMapId(CallbackInfoReturnable<OptionalInt> callback) {
        ItemStack stack = getItem();

        if (SharedWorkbookItem.showsMap(stack)) {
            callback.setReturnValue(OptionalInt.of(MapItem.getMapId(stack)));
        }
    }
    *///?}
}
