package io.github.reverendbrown.excelcomputer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * The Insert Function popup (the fx button next to the formula bar): common
 * functions in two columns, with the syntax and a short description of the
 * one under the mouse.
 */
public final class FunctionMenu {
    /** A worksheet function; {@code args} is empty for functions like TODAY(). */
    public record Function(String name, String args, String description) {
        public String syntax() {
            return name + "(" + args + ")";
        }
    }

    // All of these are evaluated by Apache POI.
    public static final List<Function> FUNCTIONS = List.of(
            new Function("SUM", "number1, [number2], ...", "Adds all the numbers."),
            new Function("AVERAGE", "number1, [number2], ...", "The average of the numbers."),
            new Function("COUNT", "value1, [value2], ...", "Counts cells that hold numbers."),
            new Function("COUNTA", "value1, [value2], ...", "Counts cells that are not empty."),
            new Function("MIN", "number1, [number2], ...", "The smallest number."),
            new Function("MAX", "number1, [number2], ...", "The largest number."),
            new Function("ROUND", "number, num_digits", "Rounds to a number of digits."),
            new Function("ABS", "number", "The number without its sign."),
            new Function("IF", "test, value_if_true, [value_if_false]", "Picks a value by a condition."),
            new Function("IFERROR", "value, value_if_error", "A fallback if a formula fails."),
            new Function("AND", "logical1, [logical2], ...", "TRUE if all are TRUE."),
            new Function("OR", "logical1, [logical2], ...", "TRUE if any is TRUE."),
            new Function("SUMIF", "range, criteria, [sum_range]", "Adds the cells that match."),
            new Function("COUNTIF", "range, criteria", "Counts the cells that match."),
            new Function("AVERAGEIF", "range, criteria, [avg_range]", "Averages the cells that match."),
            new Function("VLOOKUP", "value, table, col_index, [approx]", "Looks up a value in a table."),
            new Function("INDEX", "array, row_num, [column_num]", "The value at a row and column."),
            new Function("MATCH", "value, array, [match_type]", "The position of a value."),
            new Function("CONCATENATE", "text1, [text2], ...", "Joins pieces of text."),
            new Function("TODAY", "", "Today's date.")
    );

    private static final int COLUMNS = 2;
    private static final int ROWS = (FUNCTIONS.size() + COLUMNS - 1) / COLUMNS;
    public static final int WIDTH = 236;
    private static final int PAD = 4;
    private static final int ROW = 12;
    private static final int TITLE = 13;
    private static final int COLUMN_WIDTH = (WIDTH - PAD * 2) / COLUMNS;
    private static final int LIST_TOP = PAD + TITLE;
    private static final int INFO_TOP = LIST_TOP + ROWS * ROW + 5;
    public static final int HEIGHT = INFO_TOP + ROW * 2 + PAD;

    private boolean open;
    private int x;
    private int y;

    public boolean isOpen() {
        return open;
    }

    public void open(int anchorX, int anchorY, int screenWidth) {
        open = true;
        x = Math.max(4, Math.min(anchorX, screenWidth - WIDTH - 4));
        y = anchorY;
    }

    public void close() {
        open = false;
    }

    /** The function under the mouse, or null. */
    public Function functionAt(double mouseX, double mouseY) {
        if (!open) {
            return null;
        }

        double localX = mouseX - (x + PAD);
        double localY = mouseY - (y + LIST_TOP);

        if (localX < 0 || localY < 0 || localX >= COLUMN_WIDTH * COLUMNS || localY >= ROWS * ROW) {
            return null;
        }

        int index = (int) (localX / COLUMN_WIDTH) * ROWS + (int) (localY / ROW);

        return index < FUNCTIONS.size() ? FUNCTIONS.get(index) : null;
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!open) {
            return;
        }

        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, 0xFF5F6368);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF202124);

        graphics.drawString(font, "Insert Function", x + PAD + 2, y + PAD + 1, 0xFFB8DDBF, false);

        Function hovered = functionAt(mouseX, mouseY);

        for (int i = 0; i < FUNCTIONS.size(); i++) {
            Function function = FUNCTIONS.get(i);
            int left = x + PAD + (i / ROWS) * COLUMN_WIDTH;
            int top = y + LIST_TOP + (i % ROWS) * ROW;

            if (function == hovered) {
                graphics.fill(left, top, left + COLUMN_WIDTH - 2, top + ROW, 0xFF3C4043);
            }

            graphics.drawString(font, function.name(), left + 4, top + 2, 0xFFFFFFFF, false);
        }

        int lineY = y + INFO_TOP - 3;
        graphics.fill(x + PAD, lineY, x + WIDTH - PAD, lineY + 1, 0xFF5F6368);

        String syntax = hovered == null ? "Pick a function to start a formula." : hovered.syntax();
        String description = hovered == null ? "Then click cells to fill in its arguments." : hovered.description();
        int textWidth = WIDTH - PAD * 2 - 4;

        graphics.drawString(font, font.plainSubstrByWidth(syntax, textWidth),
                x + PAD + 2, y + INFO_TOP + 2, 0xFFFFFFFF, false);
        graphics.drawString(font, font.plainSubstrByWidth(description, textWidth),
                x + PAD + 2, y + INFO_TOP + ROW + 2, 0xFF9AA0A6, false);
    }
}
