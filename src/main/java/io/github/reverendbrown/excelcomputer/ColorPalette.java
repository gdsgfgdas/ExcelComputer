package io.github.reverendbrown.excelcomputer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** The color picker popup: Excel's theme colors (with their shades) and standard colors. */
public final class ColorPalette {
    public enum Target { FILL, TEXT, BORDER }

    /** The "No fill" / "Automatic" choice. */
    public static final int NONE = -1;
    /** A click that is not on any color. */
    public static final int OUTSIDE = -2;

    private static final int COLUMNS = 10;
    private static final int THEME_ROWS = 6;
    private static final int SWATCH = 14;
    private static final int GAP = 2;
    private static final int PAD = 6;
    private static final int STRIP = 16;
    private static final int WIDTH = PAD * 2 + COLUMNS * SWATCH + (COLUMNS - 1) * GAP;
    private static final int HEIGHT =
            PAD + STRIP + 4 + THEME_ROWS * (SWATCH + GAP) + 4 + SWATCH + PAD;

    private static final int[] THEME = {
            0xFFFFFF, 0x000000, 0xE7E6E6, 0x44546A, 0x4472C4,
            0xED7D31, 0xA5A5A5, 0xFFC000, 0x5B9BD5, 0x70AD47
    };
    private static final int[] WHITE_SHADES =
            {0xFFFFFF, 0xF2F2F2, 0xD9D9D9, 0xBFBFBF, 0xA6A6A6, 0x808080};
    private static final int[] BLACK_SHADES =
            {0x000000, 0x7F7F7F, 0x595959, 0x404040, 0x262626, 0x0D0D0D};
    private static final int[] STANDARD = {
            0xC00000, 0xFF0000, 0xFFC000, 0xFFFF00, 0x92D050,
            0x00B050, 0x00B0F0, 0x0070C0, 0x002060, 0x7030A0
    };

    private Target target;
    private int x;
    private int y;

    public boolean isOpen() {
        return target != null;
    }

    public Target target() {
        return target;
    }

    public void open(Target target, int anchorX, int anchorY, int screenWidth) {
        this.target = target;
        this.x = Math.max(4, Math.min(anchorX, screenWidth - WIDTH - 4));
        this.y = anchorY;
    }

    public void close() {
        target = null;
    }

    // Row 0 is the theme color itself; rows 1-5 are its lighter and darker shades.
    private static int shade(int column, int row) {
        if (column == 0) {
            return WHITE_SHADES[row];
        }

        if (column == 1) {
            return BLACK_SHADES[row];
        }

        int base = THEME[column];

        switch (row) {
            case 1:
                return mix(base, 255, 0.8);
            case 2:
                return mix(base, 255, 0.6);
            case 3:
                return mix(base, 255, 0.4);
            case 4:
                return mix(base, 0, 0.25);
            case 5:
                return mix(base, 0, 0.5);
            default:
                return base;
        }
    }

    private static int mix(int color, int toward, double amount) {
        int red = (int) Math.round(((color >> 16) & 0xFF) + (toward - ((color >> 16) & 0xFF)) * amount);
        int green = (int) Math.round(((color >> 8) & 0xFF) + (toward - ((color >> 8) & 0xFF)) * amount);
        int blue = (int) Math.round((color & 0xFF) + (toward - (color & 0xFF)) * amount);

        return (red << 16) | (green << 8) | blue;
    }

    private int swatchX(int column) {
        return x + PAD + column * (SWATCH + GAP);
    }

    private int themeY(int row) {
        return y + PAD + STRIP + 4 + row * (SWATCH + GAP);
    }

    private int standardY() {
        return y + PAD + STRIP + 4 + THEME_ROWS * (SWATCH + GAP) + 4;
    }

    private static boolean inside(double px, double py, int left, int top, int width, int height) {
        return px >= left && px < left + width && py >= top && py < top + height;
    }

    /** The color under the mouse (0xRRGGBB), NONE for the top strip, or OUTSIDE. */
    public int colorAt(double mouseX, double mouseY) {
        if (!isOpen()) {
            return OUTSIDE;
        }

        if (inside(mouseX, mouseY, x + PAD, y + PAD, WIDTH - PAD * 2, STRIP)) {
            return NONE;
        }

        for (int column = 0; column < COLUMNS; column++) {
            for (int row = 0; row < THEME_ROWS; row++) {
                if (inside(mouseX, mouseY, swatchX(column), themeY(row), SWATCH, SWATCH)) {
                    return shade(column, row);
                }
            }

            if (inside(mouseX, mouseY, swatchX(column), standardY(), SWATCH, SWATCH)) {
                return STANDARD[column];
            }
        }

        return OUTSIDE;
    }

    private void drawSwatch(GuiGraphics graphics, int left, int top, int color, boolean hovered) {
        if (hovered) {
            graphics.fill(left - 1, top - 1, left + SWATCH + 1, top + SWATCH + 1, 0xFFFFFFFF);
        }

        graphics.fill(left, top, left + SWATCH, top + SWATCH, 0xFF5F6368);
        graphics.fill(left + 1, top + 1, left + SWATCH - 1, top + SWATCH - 1, 0xFF000000 | color);
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!isOpen()) {
            return;
        }

        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, 0xFF5F6368);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF202124);

        boolean overStrip = colorAt(mouseX, mouseY) == NONE;
        graphics.fill(
                x + PAD, y + PAD, x + WIDTH - PAD, y + PAD + STRIP,
                overStrip ? 0xFF3C4043 : 0xFF303134
        );
        graphics.drawString(
                font,
                target == Target.FILL ? "No fill" : "Automatic",
                x + PAD + 4,
                y + PAD + 4,
                0xFFFFFFFF,
                false
        );

        for (int column = 0; column < COLUMNS; column++) {
            for (int row = 0; row < THEME_ROWS; row++) {
                int left = swatchX(column);
                int top = themeY(row);

                drawSwatch(
                        graphics, left, top, shade(column, row),
                        inside(mouseX, mouseY, left, top, SWATCH, SWATCH)
                );
            }

            int left = swatchX(column);
            int top = standardY();

            drawSwatch(
                    graphics, left, top, STANDARD[column],
                    inside(mouseX, mouseY, left, top, SWATCH, SWATCH)
            );
        }
    }
}
