package io.github.reverendbrown.excelcomputer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The filter list popup opened from a table header's arrow: sort the table by
 * the column, or tick the values to show.
 */
public final class FilterMenu {
    public enum Result { NONE, APPLY, CANCEL, SORT_ASCENDING, SORT_DESCENDING }

    public static final int WIDTH = 190;

    private static final int PAD = 6;
    private static final int ROW = 16;
    private static final int VISIBLE_ROWS = 10;
    private static final int BUTTON_HEIGHT = 18;
    // The two sort rows and the divider under them.
    private static final int SORT_HEIGHT = ROW * 2 + 7;
    private static final int HEIGHT = PAD + SORT_HEIGHT + ROW + 4 + VISIBLE_ROWS * ROW + 6 + BUTTON_HEIGHT + PAD;

    private boolean open;
    private int x;
    private int y;
    private TableInfo table;
    private int columnOffset;
    private boolean numeric;
    private List<String> values = List.of();
    private boolean[] checked = new boolean[0];
    private int scroll;

    /** The sort labels, like Excel's: numbers sort Smallest to Largest, text A to Z. */
    public static String sortLabel(boolean ascending, boolean numeric) {
        if (numeric) {
            return ascending ? "Sort Smallest to Largest" : "Sort Largest to Smallest";
        }

        return ascending ? "Sort A to Z" : "Sort Z to A";
    }

    public boolean isOpen() {
        return open;
    }

    public TableInfo table() {
        return table;
    }

    public int columnOffset() {
        return columnOffset;
    }

    /** allowed == null means every value is ticked (no filter yet). */
    public void open(
            int anchorX,
            int anchorY,
            int screenWidth,
            int screenHeight,
            TableInfo table,
            int columnOffset,
            List<String> values,
            Set<String> allowed,
            boolean numeric
    ) {
        this.open = true;
        this.table = table;
        this.columnOffset = columnOffset;
        this.numeric = numeric;
        this.values = values;
        this.checked = new boolean[values.size()];
        this.scroll = 0;

        for (int i = 0; i < values.size(); i++) {
            checked[i] = allowed == null || allowed.contains(values.get(i));
        }

        this.x = Math.max(4, Math.min(anchorX, screenWidth - WIDTH - 4));
        this.y = Math.max(4, Math.min(anchorY, screenHeight - HEIGHT - 4));
    }

    public void close() {
        open = false;
    }

    private boolean allChecked() {
        for (boolean value : checked) {
            if (!value) {
                return false;
            }
        }

        return true;
    }

    private boolean anyChecked() {
        for (boolean value : checked) {
            if (value) {
                return true;
            }
        }

        return false;
    }

    /** The ticked values, or null when everything is ticked (which means no filter). */
    public Set<String> selected() {
        if (allChecked()) {
            return null;
        }

        Set<String> result = new HashSet<>();

        for (int i = 0; i < values.size(); i++) {
            if (checked[i]) {
                result.add(values.get(i));
            }
        }

        return result;
    }

    private static boolean inside(double px, double py, int left, int top, int width, int height) {
        return px >= left && px < left + width && py >= top && py < top + height;
    }

    private int selectAllTop() {
        return y + PAD + SORT_HEIGHT;
    }

    private int listTop() {
        return selectAllTop() + ROW + 4;
    }

    private int buttonsTop() {
        return y + HEIGHT - PAD - BUTTON_HEIGHT;
    }

    private int buttonWidth() {
        return (WIDTH - PAD * 2 - 8) / 3;
    }

    private int buttonLeft(int index) {
        return x + PAD + index * (buttonWidth() + 4);
    }

    public void scroll(double amount) {
        int most = Math.max(0, values.size() - VISIBLE_ROWS);

        scroll = Math.max(0, Math.min(most, scroll - (int) Math.signum(amount) * 3));
    }

    /** Handles a click. Ticking values keeps the menu open (NONE); the buttons end it. */
    public Result click(double mouseX, double mouseY) {
        if (!open) {
            return Result.NONE;
        }

        if (!inside(mouseX, mouseY, x, y, WIDTH, HEIGHT)) {
            return Result.CANCEL;
        }

        if (inside(mouseX, mouseY, x + PAD, y + PAD, WIDTH - PAD * 2, ROW)) {
            return Result.SORT_ASCENDING;
        }

        if (inside(mouseX, mouseY, x + PAD, y + PAD + ROW, WIDTH - PAD * 2, ROW)) {
            return Result.SORT_DESCENDING;
        }

        if (inside(mouseX, mouseY, x + PAD, selectAllTop(), WIDTH - PAD * 2, ROW)) {
            boolean tickAll = !allChecked();

            for (int i = 0; i < checked.length; i++) {
                checked[i] = tickAll;
            }

            return Result.NONE;
        }

        int listTop = listTop();

        if (inside(mouseX, mouseY, x + PAD, listTop, WIDTH - PAD * 2, VISIBLE_ROWS * ROW)) {
            int index = scroll + (int) ((mouseY - listTop) / ROW);

            if (index < values.size()) {
                checked[index] = !checked[index];
            }

            return Result.NONE;
        }

        int buttonsTop = buttonsTop();

        if (inside(mouseX, mouseY, buttonLeft(0), buttonsTop, buttonWidth(), BUTTON_HEIGHT)) {
            // Clear filter: tick everything and apply.
            for (int i = 0; i < checked.length; i++) {
                checked[i] = true;
            }

            return Result.APPLY;
        }

        if (inside(mouseX, mouseY, buttonLeft(1), buttonsTop, buttonWidth(), BUTTON_HEIGHT)) {
            // At least one value has to stay ticked.
            return anyChecked() ? Result.APPLY : Result.NONE;
        }

        if (inside(mouseX, mouseY, buttonLeft(2), buttonsTop, buttonWidth(), BUTTON_HEIGHT)) {
            return Result.CANCEL;
        }

        return Result.NONE;
    }

    private void drawCheckbox(GuiGraphics graphics, int left, int top, int state) {
        graphics.fill(left, top, left + 10, top + 10, 0xFF8A8A8A);
        graphics.fill(left + 1, top + 1, left + 9, top + 9, 0xFF202124);

        if (state == 2) {
            graphics.fill(left + 2, top + 2, left + 8, top + 8, 0xFF8AB4F8);
        } else if (state == 1) {
            graphics.fill(left + 3, top + 3, left + 7, top + 7, 0xFF8AB4F8);
        }
    }

    private void drawButton(
            GuiGraphics graphics, Font font, int left, int top, String label, boolean hovered
    ) {
        graphics.fill(left, top, left + buttonWidth(), top + BUTTON_HEIGHT, 0xFF5F6368);
        graphics.fill(
                left + 1, top + 1, left + buttonWidth() - 1, top + BUTTON_HEIGHT - 1,
                hovered ? 0xFF4A4D52 : 0xFF303134
        );
        graphics.drawString(
                font,
                label,
                left + (buttonWidth() - font.width(label)) / 2,
                top + 5,
                0xFFFFFFFF,
                false
        );
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!open) {
            return;
        }

        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, 0xFF5F6368);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF202124);

        // Sorting, then a divider.
        for (int i = 0; i < 2; i++) {
            int rowY = y + PAD + i * ROW;

            if (inside(mouseX, mouseY, x + PAD, rowY, WIDTH - PAD * 2, ROW)) {
                graphics.fill(x + PAD, rowY, x + WIDTH - PAD, rowY + ROW, 0xFF3C4043);
            }

            // A small arrow: up for ascending, down for descending.
            int arrowX = x + PAD + 6;
            int arrowY = rowY + 4;

            for (int step = 0; step < 4; step++) {
                int line = i == 0 ? step : 3 - step;
                graphics.fill(arrowX + 3 - line, arrowY + step * 2, arrowX + 4 + line, arrowY + step * 2 + 2,
                        0xFF8AB4F8);
            }

            graphics.drawString(font, sortLabel(i == 0, numeric), x + PAD + 20, rowY + 4, 0xFFFFFFFF, false);
        }

        int dividerY = y + PAD + ROW * 2 + 3;
        graphics.fill(x + PAD + 2, dividerY, x + WIDTH - PAD - 2, dividerY + 1, 0xFF5F6368);

        // (Select All)
        int selectTop = selectAllTop();

        if (inside(mouseX, mouseY, x + PAD, selectTop, WIDTH - PAD * 2, ROW)) {
            graphics.fill(x + PAD, selectTop, x + WIDTH - PAD, selectTop + ROW, 0xFF3C4043);
        }

        drawCheckbox(graphics, x + PAD + 4, selectTop + 3,
                allChecked() ? 2 : anyChecked() ? 1 : 0);

        graphics.drawString(
                font,
                "(Select All)",
                x + PAD + 20,
                selectTop + 4,
                0xFFFFFFFF,
                false
        );

        // The values
        int listTop = listTop();

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int index = scroll + i;

            if (index >= values.size()) {
                break;
            }

            int rowY = listTop + i * ROW;

            if (inside(mouseX, mouseY, x + PAD, rowY, WIDTH - PAD * 2, ROW)) {
                graphics.fill(x + PAD, rowY, x + WIDTH - PAD, rowY + ROW, 0xFF3C4043);
            }

            drawCheckbox(graphics, x + PAD + 4, rowY + 3, checked[index] ? 2 : 0);

            String value = values.get(index);
            String label = value.isEmpty() ? "(Blanks)" : value;

            graphics.drawString(
                    font,
                    font.plainSubstrByWidth(label, WIDTH - PAD * 2 - 30),
                    x + PAD + 20,
                    rowY + 4,
                    0xFFFFFFFF,
                    false
            );
        }

        // A thin scroll bar when there are more values than rows.
        if (values.size() > VISIBLE_ROWS) {
            int trackHeight = VISIBLE_ROWS * ROW;
            int barHeight = Math.max(12, trackHeight * VISIBLE_ROWS / values.size());
            int most = values.size() - VISIBLE_ROWS;
            int barTop = listTop + (trackHeight - barHeight) * scroll / most;

            graphics.fill(x + WIDTH - PAD - 3, listTop, x + WIDTH - PAD, listTop + trackHeight, 0xFF303134);
            graphics.fill(x + WIDTH - PAD - 3, barTop, x + WIDTH - PAD, barTop + barHeight, 0xFF8A8A8A);
        }

        int buttonsTop = buttonsTop();

        drawButton(graphics, font, buttonLeft(0), buttonsTop, "Clear",
                inside(mouseX, mouseY, buttonLeft(0), buttonsTop, buttonWidth(), BUTTON_HEIGHT));
        drawButton(graphics, font, buttonLeft(1), buttonsTop, "OK",
                inside(mouseX, mouseY, buttonLeft(1), buttonsTop, buttonWidth(), BUTTON_HEIGHT));
        drawButton(graphics, font, buttonLeft(2), buttonsTop, "Cancel",
                inside(mouseX, mouseY, buttonLeft(2), buttonsTop, buttonWidth(), BUTTON_HEIGHT));
    }
}
