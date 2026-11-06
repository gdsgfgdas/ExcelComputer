package io.github.reverendbrown.excelcomputer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * The Conditional Formatting dropdown (Home > Styles), laid out like Excel's
 * menu in two columns: Highlight Cells and Top/Bottom rules on the left,
 * Data Bars, Color Scales and Clear Rules on the right.
 */
public final class ConditionalMenu {
    /** What a menu row does; exactly one of the fields is set (or clear is). */
    public record Item(String label,
                       ConditionalFormats.Condition condition,
                       ConditionalFormats.DataBar dataBar,
                       ConditionalFormats.ColorScale colorScale,
                       Clear clear) {
    }

    public enum Clear {
        SELECTION("Clear Rules from Selected Cells"),
        SHEET("Clear Rules from Entire Sheet");

        private final String label;

        Clear(String label) {
            this.label = label;
        }
    }

    // A row: a group heading (item == null) or an item.
    private record Row(String heading, Item item) {
    }

    private static final int ROW = 12;
    private static final int PAD = 4;
    private static final int COLUMN_WIDTH = 150;
    private static final int SWATCH = 24;
    public static final int WIDTH = PAD * 2 + COLUMN_WIDTH * 2 + 4;

    private static final List<Row> LEFT = new ArrayList<>();
    private static final List<Row> RIGHT = new ArrayList<>();
    public static final int HEIGHT;

    static {
        LEFT.add(new Row("Highlight Cells Rules", null));
        for (ConditionalFormats.Condition condition : List.of(
                ConditionalFormats.Condition.GREATER, ConditionalFormats.Condition.LESS,
                ConditionalFormats.Condition.BETWEEN, ConditionalFormats.Condition.EQUAL,
                ConditionalFormats.Condition.TEXT_CONTAINS, ConditionalFormats.Condition.DUPLICATE)) {
            LEFT.add(new Row(null, new Item(condition.label(), condition, null, null, null)));
        }

        LEFT.add(new Row("Top/Bottom Rules", null));
        for (ConditionalFormats.Condition condition : List.of(
                ConditionalFormats.Condition.TOP, ConditionalFormats.Condition.BOTTOM,
                ConditionalFormats.Condition.ABOVE_AVERAGE, ConditionalFormats.Condition.BELOW_AVERAGE)) {
            LEFT.add(new Row(null, new Item(condition.label(), condition, null, null, null)));
        }

        RIGHT.add(new Row("Data Bars", null));
        for (ConditionalFormats.DataBar bar : ConditionalFormats.DataBar.values()) {
            RIGHT.add(new Row(null, new Item(bar.label(), null, bar, null, null)));
        }

        RIGHT.add(new Row("Color Scales", null));
        for (ConditionalFormats.ColorScale scale : ConditionalFormats.ColorScale.values()) {
            RIGHT.add(new Row(null, new Item(scale.label(), null, null, scale, null)));
        }

        RIGHT.add(new Row("Clear Rules", null));
        for (Clear clear : Clear.values()) {
            RIGHT.add(new Row(null, new Item(clear.label, null, null, null, clear)));
        }

        HEIGHT = PAD * 2 + Math.max(LEFT.size(), RIGHT.size()) * ROW;
    }

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

    public boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT;
    }

    /** The item under the mouse, or null (headings and gaps are not items). */
    public Item itemAt(double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY)) {
            return null;
        }

        double localX = mouseX - (x + PAD);
        int row = (int) Math.floor((mouseY - (y + PAD)) / ROW);
        List<Row> column = localX < COLUMN_WIDTH ? LEFT : localX >= COLUMN_WIDTH + 4 ? RIGHT : null;

        if (column == null || row < 0 || row >= column.size()) {
            return null;
        }

        return column.get(row).item();
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!open) {
            return;
        }

        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, 0xFF5F6368);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF202124);

        int divider = x + PAD + COLUMN_WIDTH + 1;
        graphics.fill(divider, y + PAD, divider + 1, y + HEIGHT - PAD, 0xFF3C4043);

        Item hovered = itemAt(mouseX, mouseY);

        renderColumn(graphics, font, LEFT, x + PAD, hovered);
        renderColumn(graphics, font, RIGHT, x + PAD + COLUMN_WIDTH + 4, hovered);
    }

    private void renderColumn(GuiGraphics graphics, Font font, List<Row> rows, int left, Item hovered) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int top = y + PAD + i * ROW;

            if (row.heading() != null) {
                graphics.drawString(font, row.heading(), left + 2, top + 2, 0xFFB8DDBF, false);
                continue;
            }

            Item item = row.item();

            if (item == hovered) {
                graphics.fill(left, top, left + COLUMN_WIDTH, top + ROW, 0xFF3C4043);
            }

            int textX = left + 8;

            // A small preview of the bar or scale.
            if (item.dataBar() != null) {
                drawBarSwatch(graphics, left + 8, top + 2, item.dataBar().color());
                textX += SWATCH + 4;
            } else if (item.colorScale() != null) {
                drawScaleSwatch(graphics, left + 8, top + 2, item.colorScale().colors());
                textX += SWATCH + 4;
            }

            graphics.drawString(font, item.label(), textX, top + 2, 0xFFFFFFFF, false);
        }
    }

    private static void drawBarSwatch(GuiGraphics graphics, int left, int top, int color) {
        graphics.fill(left, top, left + SWATCH, top + 8, 0xFFFFFFFF);
        graphics.fill(left + 1, top + 1, left + SWATCH * 2 / 3, top + 7, 0xFF000000 | color);
    }

    // The scale's colors from highest (left) to lowest, like Excel's icons.
    private static void drawScaleSwatch(GuiGraphics graphics, int left, int top, int[] lowToHigh) {
        int parts = lowToHigh.length;

        for (int i = 0; i < parts; i++) {
            int from = left + SWATCH * i / parts;
            int to = left + SWATCH * (i + 1) / parts;

            graphics.fill(from, top, to, top + 8, 0xFF000000 | lowToHigh[parts - 1 - i]);
        }
    }
}
