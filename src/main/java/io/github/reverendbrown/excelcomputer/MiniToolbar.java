package io.github.reverendbrown.excelcomputer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The small formatting bar shown above the right-click menu, like Excel's
 * mini toolbar: font size, number formats, bold/italic/underline, alignment,
 * fill and text color, and borders. The screen supplies the buttons.
 */
public final class MiniToolbar {
    /**
     * One button.
     *
     * @param label    its text (a supplier, so the font size can change)
     * @param width    its width in pixels

     * @param action   what it does, or null for a plain label (the font size)
     * @param colorBar a color drawn as a bar under the label (fill and text color), or null
     */
    public record Item(Supplier<Component> label, int width, Runnable action, IntSupplier colorBar) {
        public static Item button(String text, int width, Runnable action) {
            Component label = Component.literal(text);
            return new Item(() -> label, width, action, null);
        }

        public static Item button(Component label, int width, Runnable action) {
            return new Item(() -> label, width, action, null);
        }
    }

    private static final int BUTTON_HEIGHT = 12;
    private static final int GAP = 2;
    private static final int PAD = 3;

    private final List<List<Item>> rows;
    private final int width;
    private final int height;
    private boolean open;
    private int x;
    private int y;

    public MiniToolbar(List<List<Item>> rows) {
        this.rows = rows;
        int widest = 0;

        for (List<Item> row : rows) {
            int rowWidth = 0;

            for (Item item : row) {
                rowWidth += item.width() + GAP;
            }

            widest = Math.max(widest, rowWidth - GAP);
        }

        this.width = PAD * 2 + widest;
        this.height = PAD * 2 + rows.size() * BUTTON_HEIGHT + (rows.size() - 1) * GAP;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int y() {
        return y;
    }

    public boolean isOpen() {
        return open;
    }

    public void open(int x, int y) {
        this.open = true;
        this.x = x;
        this.y = y;
    }

    public void close() {
        open = false;
    }

    public boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** The left edge of an item (to open a dropdown under it). */
    public int itemX(Item wanted) {
        for (List<Item> row : rows) {
            int itemX = x + PAD;

            for (Item item : row) {
                if (item == wanted) {
                    return itemX;
                }

                itemX += item.width() + GAP;
            }
        }

        return x;
    }

    /** The button under the mouse, or null. */
    public Item itemAt(double mouseX, double mouseY) {
        if (!open) {
            return null;
        }

        int rowY = y + PAD;

        for (List<Item> row : rows) {
            if (mouseY >= rowY && mouseY < rowY + BUTTON_HEIGHT) {
                int itemX = x + PAD;

                for (Item item : row) {
                    if (mouseX >= itemX && mouseX < itemX + item.width()) {
                        return item;
                    }

                    itemX += item.width() + GAP;
                }
            }

            rowY += BUTTON_HEIGHT + GAP;
        }

        return null;
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!open) {
            return;
        }

        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF5F6368);
        graphics.fill(x, y, x + width, y + height, 0xFF202124);

        Item hovered = itemAt(mouseX, mouseY);
        int rowY = y + PAD;

        for (List<Item> row : rows) {
            int itemX = x + PAD;

            for (Item item : row) {
                if (item.action() != null) {
                    graphics.fill(itemX, rowY, itemX + item.width(), rowY + BUTTON_HEIGHT,
                            item == hovered ? 0xFF4A4D51 : 0xFF2D2E30);
                }

                Component label = item.label().get();
                int textX = itemX + (item.width() - font.width(label)) / 2;
                boolean bar = item.colorBar() != null;
                int textY = rowY + (bar ? 1 : (BUTTON_HEIGHT - 8) / 2);
                graphics.drawString(font, label, textX, textY, 0xFFFFFFFF, false);

                if (bar) {
                    graphics.fill(itemX + 3, rowY + BUTTON_HEIGHT - 3, itemX + item.width() - 3, rowY + BUTTON_HEIGHT - 1,
                            0xFF000000 | item.colorBar().getAsInt());
                }

                itemX += item.width() + GAP;
            }

            rowY += BUTTON_HEIGHT + GAP;
        }
    }

}
