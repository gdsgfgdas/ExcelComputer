package io.github.reverendbrown.excelcomputer;

/**
 * How a cell looks: its fill color, text color, bold/italic/underline, font
 * size, alignment, number format and borders. Colors are 0xRRGGBB, or -1 for
 * "none" (fill) and "automatic" (text and border lines).
 */
public record CellFormat(
        int fill,
        int fontColor,
        boolean bold,
        boolean italic,
        boolean underline,
        int fontPoints,
        int hAlign,           // 0 = general, 1 = left, 2 = center, 3 = right
        String numberFormat,  // Excel's format code, for example "General" or "0.00%"
        boolean numeric,      // the cell holds a number (numbers are right-aligned by default)
        Borders borders
) {
    /** A cell without any formatting. */
    public static final CellFormat DEFAULT =
            new CellFormat(-1, -1, false, false, false, 11, 0, "General", false, Borders.NONE);

    // Border line styles (the subset of Excel's that this mod draws).
    public static final int LINE_NONE = 0;
    public static final int LINE_THIN = 1;
    public static final int LINE_MEDIUM = 2;
    public static final int LINE_THICK = 3;
    public static final int LINE_DASHED = 4;
    public static final int LINE_DOTTED = 5;
    public static final int LINE_DOUBLE = 6;

    // Sides of a cell, combined as a bit mask in a BORDER change.
    public static final int SIDE_TOP = 1;
    public static final int SIDE_BOTTOM = 2;
    public static final int SIDE_LEFT = 4;
    public static final int SIDE_RIGHT = 8;
    public static final int SIDE_ALL = SIDE_TOP | SIDE_BOTTOM | SIDE_LEFT | SIDE_RIGHT;

    /** One side of a cell's border: a LINE_* style and a color (-1 = automatic). */
    public record Edge(int style, int color) {
        public static final Edge NONE = new Edge(LINE_NONE, -1);

        public boolean visible() {
            return style != LINE_NONE;
        }
    }

    /** The four sides of a cell's border. */
    public record Borders(Edge top, Edge bottom, Edge left, Edge right) {
        public static final Borders NONE = new Borders(Edge.NONE, Edge.NONE, Edge.NONE, Edge.NONE);

        public boolean any() {
            return top.visible() || bottom.visible() || left.visible() || right.visible();
        }
    }

    /** The kinds of formatting change the Home tab can make. */
    public enum Kind {
        FILL, FONT_COLOR, BOLD, ITALIC, UNDERLINE, FONT_SIZE, ALIGN,
        NUMBER_FORMAT, DECIMALS, BORDER, RESET
    }

    /**
     * One formatting change. For FILL and FONT_COLOR the value is 0xRRGGBB or
     * -1; for BOLD, ITALIC and UNDERLINE it is 1 or 0; for DECIMALS it is +1
     * or -1. A NUMBER_FORMAT change carries the format code in the text. A
     * BORDER change sets the sides in {@code value & 0xF} (SIDE_* bits) to the
     * line style {@code value >> 4} in {@code color}.
     */
    public record Change(Kind kind, int value, String text, int color) {
        public Change(Kind kind, int value) {
            this(kind, value, "", -1);
        }

        public Change(Kind kind, int value, String text) {
            this(kind, value, text, -1);
        }

        public static Change border(int sides, int lineStyle, int color) {
            return new Change(Kind.BORDER, (lineStyle << 4) | (sides & SIDE_ALL), "", color);
        }

        public int borderSides() {
            return value & SIDE_ALL;
        }

        public int borderStyle() {
            return value >> 4;
        }
    }

    public CellFormat withNumeric(boolean numeric) {
        return new CellFormat(
                fill, fontColor, bold, italic, underline, fontPoints, hAlign,
                numberFormat, numeric, borders
        );
    }
}
