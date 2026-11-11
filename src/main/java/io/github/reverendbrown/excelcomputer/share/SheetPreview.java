package io.github.reverendbrown.excelcomputer.share;

import io.github.reverendbrown.excelcomputer.CellFormat;
import io.github.reverendbrown.excelcomputer.ConditionalFormats;
import io.github.reverendbrown.excelcomputer.ViewStateStore;
import io.github.reverendbrown.excelcomputer.WorkbookManager;
import net.minecraft.world.level.material.MapColor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Paints a small picture of a sheet, the size of a Minecraft map (128 x 128),
 * in map colors. It is sent along with a shared workbook; the server puts it
 * on a locked map that the shared workbook item carries, so holding the item
 * or putting it in an item frame shows the sheet like a map.
 *
 * <p>The picture is a tiny, zoomed-out Excel window: a green title bar with
 * the file name, the column letters and row numbers in a readable 3x5 pixel
 * font, then the cells (about a third of their size: a standard cell is 21 x 7,
 * so about 5 columns by 16 rows) with their fills, borders, and their text in
 * the same font, starting at the given row and column. Rows too short for the
 * font show their text as one-pixel strokes instead.
 *
 * <p>Client only, but it uses only WorkbookManager and MapColor. Call it on
 * the thread that owns the workbook (POI workbooks are not thread-safe).
 */
public final class SheetPreview {
    public static final int SIZE = 128;
    public static final int BYTES = ShareProtocol.PREVIEW_BYTES;

    private static final int TITLE_HEIGHT = 7;
    private static final int HEADER_HEIGHT = 7;
    // A standard row (20 pixels) is drawn 7 tall: 1 above the text, 5 for the
    // text, 1 gridline. A standard column (64 pixels) is drawn 21 wide.
    private static final float ROW_SCALE = 20f / 7f;
    private static final float COLUMN_SCALE = 3f;
    private static final int MIN_ROW_HEIGHT = 3;
    // The readable 3x5 font (title, column letters, row numbers).
    private static final int GLYPH_WIDTH = 3;
    private static final int GLYPH_HEIGHT = 5;
    private static final int ADVANCE = GLYPH_WIDTH + 1;
    // Cell text in rows too short for the font (see smallText).
    private static final int SMALL_HEIGHT = 3;
    private static final int SMALL_ADVANCE = 2;

    private static final int EXCEL_GREEN = 0x217346;
    private static final int CLOSE_RED = 0xC42B1C;
    private static final int WHITE = 0xFFFFFF;
    private static final int TEXT = 0x202020;
    private static final int GRID = 0xD4D4D4;
    private static final int HEADER_FILL = 0xE6E6E6;
    private static final int HEADER_TEXT = 0x505050;
    private static final int HEADER_LINE = 0xB0B0B0;

    // Workbooks opened only to draw their picture (shared from the picker).
    private static final ExecutorService PREVIEW_IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Excel on a Stick sheet preview");
        thread.setDaemon(true);
        return thread;
    });

    private final int[] pixels = new int[BYTES];

    private SheetPreview() {
    }

    /**
     * Opens a workbook file in the background and draws where the player was
     * in it last time (ViewStateStore), or the top of the first sheet.
     * Completes with null if it can't be drawn.
     */
    public static CompletableFuture<byte[]> renderFileAsync(Path file) {
        String name = file.getFileName().toString();
        ViewStateStore.ViewState view = ViewStateStore.load(name);

        return CompletableFuture.supplyAsync(() -> {
            try {
                WorkbookManager workbook = WorkbookManager.open(file);
                int sheet = view == null ? 0 : view.sheet();

                if (sheet < 0 || sheet >= workbook.getSheetCount()) {
                    sheet = 0;
                }

                return render(workbook, name, sheet,
                        view == null ? 0 : view.firstRow(), view == null ? 0 : view.firstColumn());
            } catch (Exception | LinkageError exception) {
                return null;
            }
        }, PREVIEW_IO);
    }

    /**
     * Draws a sheet from the given top-left cell. Returns the picture as map
     * color ids (row by row), or null if it can't be drawn.
     */
    public static byte[] render(WorkbookManager workbook, String title, int sheet, int firstRow, int firstColumn) {
        try {
            SheetPreview preview = new SheetPreview();
            preview.draw(workbook, title, sheet, Math.max(0, firstRow), Math.max(0, firstColumn));
            return preview.toMapColors();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    // ----- drawing -----

    private void draw(WorkbookManager workbook, String title, int sheet, int firstRow, int firstColumn) {
        fill(0, 0, SIZE, SIZE, WHITE);

        // Title bar with the file name and the window buttons, like Excel's window.
        fill(0, 0, SIZE, TITLE_HEIGHT, EXCEL_GREEN);
        int buttonsLeft = drawWindowButtons();
        text(stripExtension(title), 2, 1, buttonsLeft - 3, WHITE);

        // Which rows fit, and how tall each one is drawn (see ROW_SCALE).
        int lastRow = firstRow;
        int[] rowTops = new int[SIZE];
        int[] rowHeights = new int[SIZE];
        int[] rowIndices = new int[SIZE];
        int rows = 0;
        int y = TITLE_HEIGHT + HEADER_HEIGHT;

        for (int row = firstRow; y < SIZE && rows < SIZE && row <= 1_048_575; row++) {
            int height = workbook.getRowHeightPixels(sheet, row);

            if (height <= 0) {
                continue; // hidden by a filter
            }

            int drawn = Math.max(MIN_ROW_HEIGHT, Math.round(height / ROW_SCALE));
            rowTops[rows] = y;
            rowHeights[rows] = drawn;
            rowIndices[rows] = row;
            rows++;
            lastRow = row;
            y += drawn;
        }

        // The row number column is as wide as the largest number shown.
        int headerWidth = String.valueOf(lastRow + 1).length() * ADVANCE + 3;

        // Which columns fit, and how wide each one is drawn (see COLUMN_SCALE).
        int[] columnLefts = new int[SIZE];
        int[] columnWidths = new int[SIZE];
        int[] columnIndices = new int[SIZE];
        int columns = 0;
        int x = headerWidth;

        for (int column = firstColumn; x < SIZE && columns < SIZE && column <= 16_383; column++) {
            int width = workbook.getColumnWidthPixels(sheet, column);

            if (width <= 1) {
                continue; // hidden
            }

            columnLefts[columns] = x;
            columnWidths[columns] = Math.max(3, Math.round(width / COLUMN_SCALE));
            columnIndices[columns] = column;
            x += columnWidths[columns];
            columns++;
        }

        // Gridlines first; a cell's fill covers them, as in Excel. Then each
        // cell draws its fill, borders and text.
        for (int r = 0; r < rows; r++) {
            fill(headerWidth, rowTops[r] + rowHeights[r] - 1, SIZE - headerWidth, 1, GRID);
        }

        for (int c = 0; c < columns; c++) {
            fill(columnLefts[c] + columnWidths[c] - 1, TITLE_HEIGHT + HEADER_HEIGHT, 1, SIZE, GRID);
        }

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                drawCell(workbook, sheet, rowIndices[r], columnIndices, c, columns, columnLefts, columnWidths,
                        rowTops[r], rowHeights[r]);
            }
        }

        // Column letters (readable font) along the top.
        fill(0, TITLE_HEIGHT, SIZE, HEADER_HEIGHT, HEADER_FILL);
        fill(0, TITLE_HEIGHT + HEADER_HEIGHT - 1, SIZE, 1, HEADER_LINE);

        for (int c = 0; c < columns; c++) {
            String name = columnName(columnIndices[c]);
            int left = columnLefts[c];
            int width = columnWidths[c];
            text(name, left + Math.max(1, (width - name.length() * ADVANCE + 1) / 2), TITLE_HEIGHT + 1,
                    left + width - 2, HEADER_TEXT);
            fill(left + width - 1, TITLE_HEIGHT, 1, HEADER_HEIGHT, HEADER_LINE);
        }

        // Row numbers down the side, in the middle of their row. A number is
        // shown wherever there's room for it (rows shorter than the font skip some).
        fill(0, TITLE_HEIGHT + HEADER_HEIGHT, headerWidth, SIZE, HEADER_FILL);
        fill(headerWidth - 1, TITLE_HEIGHT, 1, SIZE, HEADER_LINE);
        int freeFrom = TITLE_HEIGHT + HEADER_HEIGHT;

        for (int r = 0; r < rows; r++) {
            int top = rowTops[r] + Math.max(0, (rowHeights[r] - GLYPH_HEIGHT) / 2);

            if (top < freeFrom) {
                continue;
            }

            String number = String.valueOf(rowIndices[r] + 1);
            text(number, headerWidth - 2 - number.length() * ADVANCE + 1, top, headerWidth - 2, HEADER_TEXT);
            freeFrom = top + GLYPH_HEIGHT + 1;
        }
    }

    // Minimize, full screen and close at the right of the title bar (just for
    // looks). Returns where the buttons start, so the title stops before them.
    private int drawWindowButtons() {
        // Close: a white X on a red box, the full height of the bar.
        int closeLeft = SIZE - 11;
        fill(closeLeft, 0, SIZE - closeLeft, TITLE_HEIGHT, CLOSE_RED);
        for (int i = 0; i < 5; i++) {
            fillPixel(closeLeft + 3 + i, 1 + i, WHITE);
            fillPixel(closeLeft + 7 - i, 1 + i, WHITE);
        }

        // Full screen: a hollow square.
        int maximizeLeft = closeLeft - 9;
        fill(maximizeLeft, 1, 5, 1, WHITE);
        fill(maximizeLeft, 5, 5, 1, WHITE);
        fill(maximizeLeft, 1, 1, 5, WHITE);
        fill(maximizeLeft + 4, 1, 1, 5, WHITE);

        // Minimize: a short line.
        int minimizeLeft = maximizeLeft - 9;
        fill(minimizeLeft, 3, 5, 1, WHITE);

        return minimizeLeft;
    }

    private void drawCell(WorkbookManager workbook, int sheet, int row, int[] columnIndices, int c, int columns,
                          int[] lefts, int[] widths, int top, int height) {
        int column = columnIndices[c];
        CellFormat format = workbook.getCellFormat(sheet, row, column);
        ConditionalFormats.Style conditional = workbook.getConditionalStyle(sheet, row, column);
        int left = lefts[c];
        int width = widths[c];

        int background = format.fill();
        if (conditional != null && conditional.fill() >= 0) {
            background = conditional.fill();
        }

        if (background >= 0) {
            fill(left, top, width, height, background);
        }

        CellFormat.Borders borders = format.borders();
        if (borders.top().visible()) {
            fill(left, top - 1, width, 1, lineColor(borders.top()));
        }
        if (borders.bottom().visible()) {
            fill(left, top + height - 1, width, 1, lineColor(borders.bottom()));
        }
        if (borders.left().visible()) {
            fill(left - 1, top, 1, height, lineColor(borders.left()));
        }
        if (borders.right().visible()) {
            fill(left + width - 1, top, 1, height, lineColor(borders.right()));
        }

        String text = workbook.getCellText(sheet, row, column);

        if (text == null || text.isEmpty()) {
            return;
        }

        int color = format.fontColor() >= 0 ? format.fontColor() : TEXT;
        if (conditional != null && conditional.fontColor() >= 0) {
            color = conditional.fontColor();
        }

        // Rows tall enough for the font show the real text; shorter ones strokes.
        boolean readable = height - 1 >= GLYPH_HEIGHT;
        int textHeight = readable ? GLYPH_HEIGHT : SMALL_HEIGHT;
        int textWidth = text.length() * (readable ? ADVANCE : SMALL_ADVANCE) - 1;
        boolean right = format.hAlign() == 3 || format.hAlign() == 0 && format.numeric();
        boolean center = format.hAlign() == 2;

        // Text runs on over empty cells to its right, like in Excel (numbers don't).
        int clipRight = left + width - 2;
        if (!right && !center && !format.numeric()) {
            for (int next = c + 1; next < columns && textWidth > clipRight - left; next++) {
                if (!workbook.isCellEmpty(sheet, row, columnIndices[next])) {
                    break;
                }
                clipRight = lefts[next] + widths[next] - 2;
            }
        }

        int textX;
        if (right) {
            textX = left + width - 2 - textWidth;
        } else if (center) {
            textX = left + (width - 1 - textWidth) / 2;
        } else {
            textX = left + 1;
        }

        // The text sits on the cell's bottom line (above the gridline), or at
        // the top or middle of a taller row.
        int content = height - 1;
        int textTop;
        if (format.vAlign() == 1) {
            textTop = top;
        } else if (format.vAlign() == 2) {
            textTop = top + (content - textHeight) / 2;
        } else {
            textTop = top + content - textHeight;
        }

        if (readable) {
            text(text, Math.max(left + 1, textX), textTop, clipRight, color);
        } else {
            smallText(text, Math.max(left + 1, textX), textTop, clipRight, color);
        }
    }

    // Cell text in short rows: one pixel per character, as tall as the character
    // is (capitals and digits full height, lowercase lower), with a gap between,
    // so words keep their length and shape without being readable.
    private void smallText(String text, int x, int top, int clipRight, int rgb) {
        int limit = Math.min(clipRight, SIZE - 1);

        for (int i = 0; i < text.length() && x <= limit; i++, x += SMALL_ADVANCE) {
            char character = text.charAt(i);

            if (Character.isWhitespace(character) || x < 0) {
                continue;
            }

            int inkTop;
            if (Character.isUpperCase(character) || Character.isDigit(character)
                    || "bdfhklt#$%&@()[]{}|/\\!?".indexOf(character) >= 0) {
                inkTop = 0; // tall
            } else if (".,_".indexOf(character) >= 0) {
                inkTop = SMALL_HEIGHT - 1; // a dot
            } else if ("-+=~*:;'\"^".indexOf(character) >= 0) {
                fillPixel(x, top + 1, rgb); // a dash in the middle
                continue;
            } else {
                inkTop = 1; // lowercase
            }

            for (int py = top + inkTop; py < top + SMALL_HEIGHT; py++) {
                fillPixel(x, py, rgb);
            }
        }
    }

    private void fillPixel(int x, int y, int rgb) {
        if (x >= 0 && x < SIZE && y >= 0 && y < SIZE) {
            pixels[y * SIZE + x] = rgb;
        }
    }

    private static int lineColor(CellFormat.Edge edge) {
        return edge.color() >= 0 ? edge.color() : 0x000000;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String columnName(int column) {
        StringBuilder result = new StringBuilder();
        int value = column + 1;

        while (value > 0) {
            value--;
            result.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }

        return result.toString();
    }

    private void fill(int x, int y, int width, int height, int rgb) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(SIZE, x + width);
        int y1 = Math.min(SIZE, y + height);

        for (int py = y0; py < y1; py++) {
            for (int px = x0; px < x1; px++) {
                pixels[py * SIZE + px] = rgb;
            }
        }
    }

    // Draws text in the 3x5 font, cut off at clipRight (inclusive).
    private void text(String text, int x, int y, int clipRight, int rgb) {
        int limit = Math.min(clipRight, SIZE - 1);

        for (int i = 0; i < text.length() && x <= limit; i++, x += ADVANCE) {
            int glyph = glyph(text.charAt(i));

            for (int gy = 0; gy < GLYPH_HEIGHT; gy++) {
                for (int gx = 0; gx < GLYPH_WIDTH; gx++) {
                    if ((glyph >> (14 - gy * GLYPH_WIDTH - gx) & 1) == 0) {
                        continue;
                    }

                    int px = x + gx;
                    int py = y + gy;

                    if (px >= 0 && px <= limit && py >= 0 && py < SIZE) {
                        pixels[py * SIZE + px] = rgb;
                    }
                }
            }
        }
    }

    // ----- the 3x5 font -----

    private static final Map<Character, Integer> GLYPHS = new HashMap<>();
    // Letters and symbols the font lacks show as a small block, like blurred text.
    private static final int UNKNOWN = bits("...", "###", "###", "###", "...");

    static {
        glyph('0', "###", "#.#", "#.#", "#.#", "###");
        glyph('1', ".#.", "##.", ".#.", ".#.", "###");
        glyph('2', "###", "..#", "###", "#..", "###");
        glyph('3', "###", "..#", ".##", "..#", "###");
        glyph('4', "#.#", "#.#", "###", "..#", "..#");
        glyph('5', "###", "#..", "###", "..#", "###");
        glyph('6', "###", "#..", "###", "#.#", "###");
        glyph('7', "###", "..#", "..#", ".#.", ".#.");
        glyph('8', "###", "#.#", "###", "#.#", "###");
        glyph('9', "###", "#.#", "###", "..#", "###");
        glyph('A', ".#.", "#.#", "###", "#.#", "#.#");
        glyph('B', "##.", "#.#", "##.", "#.#", "##.");
        glyph('C', ".##", "#..", "#..", "#..", ".##");
        glyph('D', "##.", "#.#", "#.#", "#.#", "##.");
        glyph('E', "###", "#..", "##.", "#..", "###");
        glyph('F', "###", "#..", "##.", "#..", "#..");
        glyph('G', ".##", "#..", "#.#", "#.#", ".##");
        glyph('H', "#.#", "#.#", "###", "#.#", "#.#");
        glyph('I', "###", ".#.", ".#.", ".#.", "###");
        glyph('J', "..#", "..#", "..#", "#.#", ".#.");
        glyph('K', "#.#", "#.#", "##.", "#.#", "#.#");
        glyph('L', "#..", "#..", "#..", "#..", "###");
        glyph('M', "#.#", "###", "#.#", "#.#", "#.#");
        glyph('N', "##.", "#.#", "#.#", "#.#", "#.#");
        glyph('O', ".#.", "#.#", "#.#", "#.#", ".#.");
        glyph('P', "##.", "#.#", "##.", "#..", "#..");
        glyph('Q', ".#.", "#.#", "#.#", "###", ".##");
        glyph('R', "##.", "#.#", "##.", "#.#", "#.#");
        glyph('S', ".##", "#..", ".#.", "..#", "##.");
        glyph('T', "###", ".#.", ".#.", ".#.", ".#.");
        glyph('U', "#.#", "#.#", "#.#", "#.#", "###");
        glyph('V', "#.#", "#.#", "#.#", "#.#", ".#.");
        glyph('W', "#.#", "#.#", "#.#", "###", "#.#");
        glyph('X', "#.#", "#.#", ".#.", "#.#", "#.#");
        glyph('Y', "#.#", "#.#", ".#.", ".#.", ".#.");
        glyph('Z', "###", "..#", ".#.", "#..", "###");
        glyph(' ', "...", "...", "...", "...", "...");
        glyph('.', "...", "...", "...", "...", ".#.");
        glyph(',', "...", "...", "...", ".#.", "#..");
        glyph(':', "...", ".#.", "...", ".#.", "...");
        glyph(';', "...", ".#.", "...", ".#.", "#..");
        glyph('-', "...", "...", "###", "...", "...");
        glyph('+', "...", ".#.", "###", ".#.", "...");
        glyph('/', "..#", "..#", ".#.", "#..", "#..");
        glyph('\\', "#..", "#..", ".#.", "..#", "..#");
        glyph('%', "#.#", "..#", ".#.", "#..", "#.#");
        glyph('$', ".##", "##.", ".#.", ".##", "##.");
        glyph('€', ".##", "#..", "###", "#..", ".##");
        glyph('£', ".##", ".#.", "###", ".#.", "###");
        glyph('(', ".#.", "#..", "#..", "#..", ".#.");
        glyph(')', ".#.", "..#", "..#", "..#", ".#.");
        glyph('[', "##.", "#..", "#..", "#..", "##.");
        glyph(']', ".##", "..#", "..#", "..#", ".##");
        glyph('\'', ".#.", ".#.", "...", "...", "...");
        glyph('"', "#.#", "#.#", "...", "...", "...");
        glyph('!', ".#.", ".#.", ".#.", "...", ".#.");
        glyph('?', "##.", "..#", ".#.", "...", ".#.");
        glyph('=', "...", "###", "...", "###", "...");
        glyph('_', "...", "...", "...", "...", "###");
        glyph('#', "#.#", "###", "#.#", "###", "#.#");
        glyph('&', ".#.", "#.#", ".#.", "#.#", ".##");
        glyph('*', "#.#", ".#.", "#.#", "...", "...");
        glyph('<', "..#", ".#.", "#..", ".#.", "..#");
        glyph('>', "#..", ".#.", "..#", ".#.", "#..");
        glyph('^', ".#.", "#.#", "...", "...", "...");
        glyph('|', ".#.", ".#.", ".#.", ".#.", ".#.");
    }

    private static void glyph(char character, String... rows) {
        GLYPHS.put(character, bits(rows));
    }

    private static int bits(String... rows) {
        int value = 0;

        for (String row : rows) {
            for (int i = 0; i < GLYPH_WIDTH; i++) {
                value = value << 1 | (row.charAt(i) == '#' ? 1 : 0);
            }
        }

        return value;
    }

    private static int glyph(char character) {
        Integer glyph = GLYPHS.get(Character.toUpperCase(character));

        if (glyph == null) {
            // Accented letters use their base letter (é -> E).
            String plain = java.text.Normalizer.normalize(String.valueOf(character), java.text.Normalizer.Form.NFD);
            glyph = plain.isEmpty() ? null : GLYPHS.get(Character.toUpperCase(plain.charAt(0)));
        }

        return glyph != null ? glyph : UNKNOWN;
    }

    // ----- map colors -----

    // Every color a map can show: {rgb, packed id}.
    private static int[][] palette;

    private byte[] toMapColors() {
        Map<Integer, Byte> nearest = new HashMap<>();
        byte[] result = new byte[BYTES];

        for (int i = 0; i < BYTES; i++) {
            int rgb = pixels[i] & 0xFFFFFF;
            result[i] = nearest.computeIfAbsent(rgb, SheetPreview::nearestMapColor);
        }

        return result;
    }

    private static synchronized int[][] palette() {
        if (palette == null) {
            java.util.List<int[]> colors = new java.util.ArrayList<>();
            int[] modifiers = {180, 220, 255, 135}; // MapColor.Brightness LOW, NORMAL, HIGH, LOWEST

            for (int id = 1; id < 64; id++) {
                MapColor color = MapColor.byId(id);

                if (color == MapColor.NONE || color.id != id) {
                    continue;
                }

                for (int brightness = 0; brightness < 4; brightness++) {
                    int red = (color.col >> 16 & 0xFF) * modifiers[brightness] / 255;
                    int green = (color.col >> 8 & 0xFF) * modifiers[brightness] / 255;
                    int blue = (color.col & 0xFF) * modifiers[brightness] / 255;
                    colors.add(new int[] {red << 16 | green << 8 | blue, id << 2 | brightness});
                }
            }

            palette = colors.toArray(new int[0][]);
        }

        return palette;
    }

    private static byte nearestMapColor(int rgb) {
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        long best = Long.MAX_VALUE;
        int bestId = 0;

        for (int[] entry : palette()) {
            int dr = red - (entry[0] >> 16 & 0xFF);
            int dg = green - (entry[0] >> 8 & 0xFF);
            int db = blue - (entry[0] & 0xFF);
            // Weighted toward green, which the eye sees best.
            long distance = 2L * dr * dr + 4L * dg * dg + 3L * db * db;

            if (distance < best) {
                best = distance;
                bestId = entry[1];
            }
        }

        return (byte) bestId;
    }
}
