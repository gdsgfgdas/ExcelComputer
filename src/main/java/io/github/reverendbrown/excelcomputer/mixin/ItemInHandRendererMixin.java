package io.github.reverendbrown.excelcomputer.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.reverendbrown.excelcomputer.item.SharedWorkbookItem;
import io.github.reverendbrown.excelcomputer.share.WindowFrame;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.1 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
*///?} elif >=1.21.9 {
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
*///?}
//? if <1.21 {
/*import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Shadow;
*///?}

/**
 * Held shared workbooks look like an open window rather than a map: the map's
 * paper border is swapped for WindowFrame's thin window edge (the window's
 * title bar and buttons are part of the picture, see SheetPreview).
 *
 * <p>On 1.20.1 it also draws a shared workbook with a picture as a map at
 * all: that version only does so for Items.FILLED_MAP (1.21+ for any
 * MapItem). It uses vanilla's own map methods, two-handed when the off hand
 * is empty.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    // Set while renderMap draws a shared workbook (rendering is single-threaded).
    @Unique
    private boolean excelcomputer$window;

    //? if >=1.21.9 {
    @Inject(method = "renderMap", at = @At("HEAD"))
    private void excelcomputer$startMap(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                        ItemStack stack, CallbackInfo callback) {
        excelcomputer$window = SharedWorkbookItem.showsMap(stack);
    }

    @ModifyArg(method = "renderMap", at = @At(value = "INVOKE",
            //? if >=26.1 {
            /*target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V"),
            *///?} else {
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V"),
            //?}
            index = 1)
    private RenderType excelcomputer$windowFrame(RenderType background) {
        return excelcomputer$window ? WindowFrame.renderType() : background;
    }
    //?} else {
    /*@Inject(method = "renderMap", at = @At("HEAD"))
    private void excelcomputer$startMap(PoseStack poseStack, MultiBufferSource buffer, int light,
                                        ItemStack stack, CallbackInfo callback) {
        excelcomputer$window = SharedWorkbookItem.showsMap(stack);
    }

    @ModifyArg(method = "renderMap", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private RenderType excelcomputer$windowFrame(RenderType background) {
        return excelcomputer$window ? WindowFrame.renderType() : background;
    }
    *///?}

    //? if <1.21 {
    /*@Shadow
    private ItemStack offHandItem;

    @Shadow
    private void renderTwoHandedMap(PoseStack poseStack, MultiBufferSource buffer, int light, float pitch,
                                    float equipProgress, float swingProgress) {
        throw new AssertionError();
    }

    @Shadow
    private void renderOneHandedMap(PoseStack poseStack, MultiBufferSource buffer, int light, float equipProgress,
                                    HumanoidArm arm, float swingProgress, ItemStack stack) {
        throw new AssertionError();
    }

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void excelcomputer$renderSheetMap(AbstractClientPlayer player, float partialTick, float pitch,
                                              InteractionHand hand, float swingProgress, ItemStack stack,
                                              float equipProgress, PoseStack poseStack, MultiBufferSource buffer,
                                              int light, CallbackInfo callback) {
        if (player.isScoping() || !SharedWorkbookItem.showsMap(stack)) {
            return;
        }

        boolean mainHand = hand == InteractionHand.MAIN_HAND;
        HumanoidArm arm = mainHand ? player.getMainArm() : player.getMainArm().getOpposite();
        poseStack.pushPose();

        if (mainHand && offHandItem.isEmpty()) {
            renderTwoHandedMap(poseStack, buffer, light, pitch, equipProgress, swingProgress);
        } else {
            renderOneHandedMap(poseStack, buffer, light, equipProgress, arm, swingProgress, stack);
        }

        poseStack.popPose();
        callback.cancel();
    }
    *///?}
}
