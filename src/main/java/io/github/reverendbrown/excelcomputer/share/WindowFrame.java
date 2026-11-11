package io.github.reverendbrown.excelcomputer.share;

import io.github.reverendbrown.excelcomputer.ExcelComputer;
//? if >=26.1 {
/*import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
*///?} else {
import net.minecraft.client.renderer.RenderType;
//?}

/**
 * What a held shared workbook shows instead of a map's paper border: a thin
 * window edge with a soft shadow (textures/map/window_frame.png), so the
 * sheet's picture looks like an open window. Used by ItemInHandRendererMixin.
 *
 * <p>Vanilla stretches this texture over the picture plus 7 map pixels on each
 * side, so the 142x142 texture lines up pixel for pixel; its middle is hidden
 * by the picture. Client only.
 */
public final class WindowFrame {
    private static RenderType renderType;

    private WindowFrame() {
    }

    /** The same kind of render type vanilla uses for the paper (it allows transparency). */
    public static RenderType renderType() {
        if (renderType == null) {
            //? if >=26.1 {
            /*renderType = RenderTypes.text(ExcelComputer.id("textures/map/window_frame.png"));
            *///?} else {
            renderType = RenderType.text(ExcelComputer.id("textures/map/window_frame.png"));
            //?}
        }

        return renderType;
    }
}
