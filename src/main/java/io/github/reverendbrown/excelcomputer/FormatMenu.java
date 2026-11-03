package io.github.reverendbrown.excelcomputer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.Locale;

/** The number format list popup (General, Number, Currency, Percentage, ...). */
public final class FormatMenu {
    public record Item(String label, String format) {
    }

    public static final List<Item> ITEMS = List.of(
            new Item("General", "General"),
            new Item("Number", "0.00"),
            new Item("Currency", "\"$\"#,##0.00"),
            new Item("Percentage", "0.00%"),
            new Item("Scientific", "0.00E+00"),
            new Item("Fraction", "# ?/?"),
            new Item("Short date", "m/d/yyyy"),
            new Item("Long date", "dddd, mmmm d, yyyy"),
            new Item("Time", "h:mm:ss AM/PM"),
            new Item("Text", "@")
    );

    private static final int WIDTH = 150;
    private static final int ROW = 16;
    private static final int PAD = 4;
    private static final int HEIGHT = PAD * 2 + ITEMS.size() * ROW;

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

    /** The index of the item under the mouse, or -1. */
    public int itemAt(double mouseX, double mouseY) {
        if (!open || mouseX < x + PAD || mouseX >= x + WIDTH - PAD) {
            return -1;
        }

        int row = (int) Math.floor((mouseY - (y + PAD)) / ROW);

        return row >= 0 && row < ITEMS.size() && mouseY >= y + PAD ? row : -1;
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, String currentFormat) {
        if (!open) {
            return;
        }

        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, 0xFF5F6368);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF202124);

        int hovered = itemAt(mouseX, mouseY);

        for (int i = 0; i < ITEMS.size(); i++) {
            int rowY = y + PAD + i * ROW;
            Item item = ITEMS.get(i);

            if (i == hovered) {
                graphics.fill(x + PAD, rowY, x + WIDTH - PAD, rowY + ROW, 0xFF3C4043);
            }

            boolean current = item.format().equals(currentFormat);

            graphics.drawString(
                    font,
                    item.label(),
                    x + PAD + 6,
                    rowY + 4,
                    current ? 0xFFB8DDBF : 0xFFFFFFFF,
                    false
            );

        }
    }

    /** A short name for any Excel format code (shown on the dropdown button). */
    public static String nameOf(String format) {
        if (format == null || format.equalsIgnoreCase("General")) {
            return "General";
        }

        if (format.contains("$") || format.contains("\u20AC")
                || format.contains("\u00A3") || format.contains("\u00A5")) {
            return "Currency";
        }

        // Text in quotes and [colors] is not part of the number pattern.
        String pattern = format
                .replaceAll("\"[^\"]*\"", "")
                .replaceAll("\\[[^\\]]*\\]", "")
                .toLowerCase(Locale.ROOT);

        if (pattern.contains("%")) {
            return "Percentage";
        }

        if (pattern.contains("e+") || pattern.contains("e-")) {
            return "Scientific";
        }

        if (pattern.contains("?/")) {
            return "Fraction";
        }

        if (pattern.equals("@")) {
            return "Text";
        }

        if (pattern.contains("y") || pattern.contains("d")) {
            return "Date";
        }

        if (pattern.contains("h") || pattern.contains("s")) {
            return "Time";
        }

        if (pattern.matches("[#0,.?_() -]+")) {
            return "Number";
        }

        return "Custom";
    }
}
