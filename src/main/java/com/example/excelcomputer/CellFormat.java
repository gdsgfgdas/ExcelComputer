package com.example.excelcomputer;

/**
 * How a cell looks: its fill color, text color, bold/italic/underline, font
 * size, alignment and number format. Colors are 0xRRGGBB, or -1 for "none"
 * (fill) and "automatic" (text).
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
        boolean numeric       // the cell holds a number (numbers are right-aligned by default)
) {
    /** A cell without any formatting. */
    public static final CellFormat DEFAULT =
            new CellFormat(-1, -1, false, false, false, 11, 0, "General", false);

    /** The kinds of formatting change the Home tab can make. */
    public enum Kind {
        FILL, FONT_COLOR, BOLD, ITALIC, UNDERLINE, FONT_SIZE, ALIGN,
        NUMBER_FORMAT, DECIMALS, RESET
    }

    /**
     * One formatting change. For colors the value is 0xRRGGBB or -1; for BOLD,
     * ITALIC and UNDERLINE it is 1 or 0; for DECIMALS it is +1 or -1. A
     * NUMBER_FORMAT change carries the format code in the text.
     */
    public record Change(Kind kind, int value, String text) {
        public Change(Kind kind, int value) {
            this(kind, value, "");
        }
    }

    public CellFormat withNumeric(boolean numeric) {
        return new CellFormat(
                fill, fontColor, bold, italic, underline, fontPoints, hAlign,
                numberFormat, numeric
        );
    }
}
