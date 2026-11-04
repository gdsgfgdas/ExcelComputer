package io.github.reverendbrown.excelcomputer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The Borders dropdown, like Excel's: preset borders for the selection, plus
 * the line color and line style that the presets draw with.
 */
public final class BorderMenu {
    /** Excel's border presets. */
    public enum Option {
        BOTTOM("Bottom Border"),
        TOP("Top Border"),
        LEFT("Left Border"),
        RIGHT("Right Border"),
        NONE("No Border"),
        ALL("All Borders"),
        OUTSIDE("Outside Borders"),
        THICK_OUTSIDE("Thick Outside Borders"),
        BOTTOM_DOUBLE("Bottom Double Border"),
        THICK_BOTTOM("Thick Bottom Border"),
        TOP_AND_BOTTOM("Top and Bottom Border");

        private final String label;

        Option(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /**
         * The border change for one cell of the selection, or null when this
         * option leaves that cell alone.
         */
        public CellFormat.Change changeFor(
                int row, int column, int top, int bottom, int left, int right,
                int penStyle, int penColor
        ) {
            int edges = (row == top ? CellFormat.SIDE_TOP : 0)
                    | (row == bottom ? CellFormat.SIDE_BOTTOM : 0)
                    | (column == left ? CellFormat.SIDE_LEFT : 0)
                    | (column == right ? CellFormat.SIDE_RIGHT : 0);
            int sides;
            int style = penStyle;

            switch (this) {
                case BOTTOM:
                    sides = edges & CellFormat.SIDE_BOTTOM;
                    break;
                case TOP:
                    sides = edges & CellFormat.SIDE_TOP;
                    break;
                case LEFT:
                    sides = edges & CellFormat.SIDE_LEFT;
                    break;
                case RIGHT:
                    sides = edges & CellFormat.SIDE_RIGHT;
                    break;
                case NONE:
                    sides = CellFormat.SIDE_ALL;
                    style = CellFormat.LINE_NONE;
                    break;
                case ALL:
                    sides = CellFormat.SIDE_ALL;
                    break;
                case OUTSIDE:
                    sides = edges;
                    break;
                case THICK_OUTSIDE:
                    sides = edges;
                    style = CellFormat.LINE_THICK;
                    break;
                case BOTTOM_DOUBLE:
                    sides = edges & CellFormat.SIDE_BOTTOM;
                    style = CellFormat.LINE_DOUBLE;
                    break;
                case THICK_BOTTOM:
                    sides = edges & CellFormat.SIDE_BOTTOM;
                    style = CellFormat.LINE_THICK;
                    break;
                default: // TOP_AND_BOTTOM
                    sides = edges & (CellFormat.SIDE_TOP | CellFormat.SIDE_BOTTOM);
                    break;
            }

            return sides == 0 ? null : CellFormat.Change.border(sides, style, penColor);
        }

        // The sides lit up in this option's icon, and the style they are drawn in.
        private int iconSides() {
            switch (this) {
                case BOTTOM:
                case BOTTOM_DOUBLE:
                case THICK_BOTTOM:
                    return CellFormat.SIDE_BOTTOM;
                case TOP:
                    return CellFormat.SIDE_TOP;
                case LEFT:
                    return CellFormat.SIDE_LEFT;
                case RIGHT:
                    return CellFormat.SIDE_RIGHT;
                case NONE:
                    return 0;
                case TOP_AND_BOTTOM:
                    return CellFormat.SIDE_TOP | CellFormat.SIDE_BOTTOM;
                default:
                    return CellFormat.SIDE_ALL;
            }
        }

        private int iconStyle() {
            switch (this) {
                case THICK_OUTSIDE:
                case THICK_BOTTOM:
                    return CellFormat.LINE_THICK;
                case BOTTOM_DOUBLE:
                    return CellFormat.LINE_DOUBLE;
                default:
                    return CellFormat.LINE_THIN;
            }
        }
    }

    /** What a click in the menu did. */
    public record Result(Kind kind, Option option, int lineStyle) {
        public enum Kind { OUTSIDE, NOTHING, OPTION, LINE_COLOR, LINE_STYLE }

        static final Result OUTSIDE = new Result(Kind.OUTSIDE, null, 0);
        static final Result NOTHING = new Result(Kind.NOTHING, null, 0);
    }

    private static final Option[] OPTIONS = Option.values();
    private static final int[] STYLES = {
            CellFormat.LINE_THIN, CellFormat.LINE_MEDIUM, CellFormat.LINE_THICK,
            CellFormat.LINE_DASHED, CellFormat.LINE_DOTTED, CellFormat.LINE_DOUBLE
    };

    public static final int WIDTH = 168;
    private static final int ROW = 14;
    private static final int PAD = 4;
    private static final int SEPARATOR = 5;
    private static final int STYLE_BOX = (WIDTH - PAD * 2) / STYLES.length;
    private static final int STYLE_ROW = 14;

    // A separator line is drawn after these options, like Excel's groups.
    private static boolean separatorAfter(int option) {
        return option == 3 || option == 7 || option == 10;
    }

    public static final int HEIGHT;
    private static final int[] OPTION_Y = new int[OPTIONS.length];
    private static final int COLOR_Y;
    private static final int STYLE_LABEL_Y;
    private static final int STYLE_Y;

    static {
        int y = PAD;

        for (int i = 0; i < OPTIONS.length; i++) {
            OPTION_Y[i] = y;
            y += ROW;

            if (separatorAfter(i)) {
                y += SEPARATOR;
            }
        }

        COLOR_Y = y;
        y += ROW;
        STYLE_LABEL_Y = y;
        y += 12;
        STYLE_Y = y;
        y += STYLE_ROW + PAD;
        HEIGHT = y;
    }

    private boolean open;
    private int x;
    private int y;

    public boolean isOpen() {
        return open;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public void open(int anchorX, int anchorY, int screenWidth) {
        open = true;
        x = Math.max(4, Math.min(anchorX, screenWidth - WIDTH - 4));
        y = anchorY;
    }

    public void close() {
        open = false;
    }

    private static boolean inside(double px, double py, int left, int top, int width, int height) {
        return px >= left && px < left + width && py >= top && py < top + height;
    }

    private boolean inRow(double mouseX, double mouseY, int rowTop) {
        return inside(mouseX, mouseY, x + PAD, y + rowTop, WIDTH - PAD * 2, ROW);
    }

    private int styleAt(double mouseX, double mouseY) {
        for (int i = 0; i < STYLES.length; i++) {
            if (inside(mouseX, mouseY, x + PAD + i * STYLE_BOX, y + STYLE_Y, STYLE_BOX, STYLE_ROW)) {
                return i;
            }
        }

        return -1;
    }

    public Result click(double mouseX, double mouseY) {
        if (!open || !inside(mouseX, mouseY, x, y, WIDTH, HEIGHT)) {
            return Result.OUTSIDE;
        }

        for (int i = 0; i < OPTIONS.length; i++) {
            if (inRow(mouseX, mouseY, OPTION_Y[i])) {
                return new Result(Result.Kind.OPTION, OPTIONS[i], 0);
            }
        }

        if (inRow(mouseX, mouseY, COLOR_Y)) {
            return new Result(Result.Kind.LINE_COLOR, null, 0);
        }

        int style = styleAt(mouseX, mouseY);

        if (style >= 0) {
            return new Result(Result.Kind.LINE_STYLE, null, STYLES[style]);
        }

        return Result.NOTHING;
    }

    public void render(
            GuiGraphics graphics, Font font, int mouseX, int mouseY,
            int penStyle, int penColor
    ) {
        if (!open) {
            return;
        }

        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, 0xFF5F6368);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF202124);

        for (int i = 0; i < OPTIONS.length; i++) {
            int rowY = y + OPTION_Y[i];

            if (inRow(mouseX, mouseY, OPTION_Y[i])) {
                graphics.fill(x + PAD, rowY, x + WIDTH - PAD, rowY + ROW, 0xFF3C4043);
            }

            drawIcon(graphics, x + PAD + 3, rowY + 2, OPTIONS[i]);
            graphics.drawString(font, OPTIONS[i].label(), x + PAD + 18, rowY + 3, 0xFFFFFFFF, false);

            if (separatorAfter(i)) {
                int lineY = rowY + ROW + SEPARATOR / 2;
                graphics.fill(x + PAD, lineY, x + WIDTH - PAD, lineY + 1, 0xFF5F6368);
            }
        }

        // Line color: a swatch of the color the presets draw with.
        int colorY = y + COLOR_Y;

        if (inRow(mouseX, mouseY, COLOR_Y)) {
            graphics.fill(x + PAD, colorY, x + WIDTH - PAD, colorY + ROW, 0xFF3C4043);
        }

        graphics.fill(x + PAD + 3, colorY + 2, x + PAD + 13, colorY + 12, 0xFF9AA0A6);
        graphics.fill(x + PAD + 4, colorY + 3, x + PAD + 12, colorY + 11, lineColor(penColor));
        graphics.drawString(
                font,
                "Line Color" + (penColor < 0 ? " (Automatic)" : "") + "  >",
                x + PAD + 18, colorY + 3, 0xFFFFFFFF, false
        );

        // Line style: one sample per style, the active one outlined in green.
        graphics.drawString(font, "Line Style", x + PAD + 3, y + STYLE_LABEL_Y + 2, 0xFF9AA0A6, false);

        int hoveredStyle = styleAt(mouseX, mouseY);

        for (int i = 0; i < STYLES.length; i++) {
            int left = x + PAD + i * STYLE_BOX;
            int top = y + STYLE_Y;

            if (STYLES[i] == penStyle) {
                graphics.fill(left, top, left + STYLE_BOX - 2, top + STYLE_ROW, 0xFF217346);
                graphics.fill(left + 1, top + 1, left + STYLE_BOX - 3, top + STYLE_ROW - 1, 0xFF303134);
            } else if (i == hoveredStyle) {
                graphics.fill(left, top, left + STYLE_BOX - 2, top + STYLE_ROW, 0xFF3C4043);
            }

            int lineY = top + (STYLE_ROW - thickness(STYLES[i])) / 2;
            drawHorizontal(graphics, left + 3, left + STYLE_BOX - 5, lineY, STYLES[i], 0xFFFFFFFF);
        }
    }

    // A 10x10 picture of a cell, with this option's sides drawn solid.
    private static void drawIcon(GuiGraphics graphics, int left, int top, Option option) {
        int size = 10;
        int faint = 0xFF5F6368;

        drawHorizontal(graphics, left, left + size, top, CellFormat.LINE_DOTTED, faint);
        drawHorizontal(graphics, left, left + size, top + size - 1, CellFormat.LINE_DOTTED, faint);
        drawVertical(graphics, left, top, top + size, CellFormat.LINE_DOTTED, faint);
        drawVertical(graphics, left + size - 1, top, top + size, CellFormat.LINE_DOTTED, faint);

        int sides = option.iconSides();
        int style = option.iconStyle();
        int t = thickness(style);
        int white = 0xFFFFFFFF;

        if ((sides & CellFormat.SIDE_TOP) != 0) {
            drawHorizontal(graphics, left, left + size, top, style, white);
        }
        if ((sides & CellFormat.SIDE_BOTTOM) != 0) {
            drawHorizontal(graphics, left, left + size, top + size - t, style, white);
        }
        if ((sides & CellFormat.SIDE_LEFT) != 0) {
            drawVertical(graphics, left, top, top + size, style, white);
        }
        if ((sides & CellFormat.SIDE_RIGHT) != 0) {
            drawVertical(graphics, left + size - t, top, top + size, style, white);
        }
    }

    // ----- line drawing, shared with the grid -----

    /** The on-screen color of a border line (automatic is black). */
    public static int lineColor(int color) {
        return 0xFF000000 | (color < 0 ? 0 : color);
    }

    /** How many pixels thick a line style is drawn. */
    public static int thickness(int style) {
        switch (style) {
            case CellFormat.LINE_MEDIUM:
                return 2;
            case CellFormat.LINE_THICK:
            case CellFormat.LINE_DOUBLE:
                return 3;
            case CellFormat.LINE_NONE:
                return 0;
            default:
                return 1;
        }
    }

    /** A horizontal line from x1 to x2 whose top edge is at y. */
    public static void drawHorizontal(GuiGraphics graphics, int x1, int x2, int y, int style, int color) {
        switch (style) {
            case CellFormat.LINE_NONE:
                return;
            case CellFormat.LINE_DOUBLE:
                graphics.fill(x1, y, x2, y + 1, color);
                graphics.fill(x1, y + 2, x2, y + 3, color);
                return;
            case CellFormat.LINE_DASHED:
            case CellFormat.LINE_DOTTED:
                int on = style == CellFormat.LINE_DASHED ? 3 : 1;
                int step = style == CellFormat.LINE_DASHED ? 5 : 2;

                for (int x = x1; x < x2; x += step) {
                    graphics.fill(x, y, Math.min(x + on, x2), y + 1, color);
                }
                return;
            default:
                graphics.fill(x1, y, x2, y + thickness(style), color);
        }
    }

    /** A vertical line from y1 to y2 whose left edge is at x. */
    public static void drawVertical(GuiGraphics graphics, int x, int y1, int y2, int style, int color) {
        switch (style) {
            case CellFormat.LINE_NONE:
                return;
            case CellFormat.LINE_DOUBLE:
                graphics.fill(x, y1, x + 1, y2, color);
                graphics.fill(x + 2, y1, x + 3, y2, color);
                return;
            case CellFormat.LINE_DASHED:
            case CellFormat.LINE_DOTTED:
                int on = style == CellFormat.LINE_DASHED ? 3 : 1;
                int step = style == CellFormat.LINE_DASHED ? 5 : 2;

                for (int y = y1; y < y2; y += step) {
                    graphics.fill(x, y, x + 1, Math.min(y + on, y2), color);
                }
                return;
            default:
                graphics.fill(x, y1, x + thickness(style), y2, color);
        }
    }
}
