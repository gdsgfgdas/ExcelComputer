package io.github.reverendbrown.excelcomputer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** The menu that opens when you right-click a cell, like Excel's context menu. */
public final class CellMenu {
    public enum Action {
        CUT("Cut", "Ctrl+X", false),
        COPY("Copy", "Ctrl+C", false),
        PASTE("Paste", "Ctrl+V", false),
        PASTE_OPTIONS("Paste Options", ">", true),
        CLEAR("Clear Contents", "Del", true),
        NUMBER_FORMAT("Number Format", ">", false),
        INSERT_FUNCTION("Insert Function", ">", true),
        TABLE("Table", "", false),
        FILTER("Filter", "", false);

        private final String label;
        private final String hint;
        // Draws a divider line under this item.
        private final boolean dividerAfter;

        Action(String label, String hint, boolean dividerAfter) {
            this.label = label;
            this.hint = hint;
            this.dividerAfter = dividerAfter;
        }
    }

    private static final Action[] ACTIONS = Action.values();
    private static final int WIDTH = 120;
    private static final int ROW = 13;
    private static final int PAD = 3;
    private static final int DIVIDER = 5;

    private boolean open;
    private int x;
    private int y;

    public boolean isOpen() {
        return open;
    }

    public int x() {
        return x;
    }

    /** The y of an item's row, for opening a submenu next to it. */
    public int rowY(Action action) {
        int rowY = y + PAD;

        for (Action candidate : ACTIONS) {
            if (candidate == action) {
                return rowY;
            }

            rowY += ROW + (candidate.dividerAfter ? DIVIDER : 0);
        }

        return rowY;
    }

    public int width() {
        return WIDTH;
    }

    public int y() {
        return y;
    }

    public int menuHeight() {
        return height();
    }

    private static int height() {
        int height = PAD * 2;

        for (Action action : ACTIONS) {
            height += ROW + (action.dividerAfter ? DIVIDER : 0);
        }

        return height;
    }

    /** Opens at the mouse, moved left or up if it would go off the screen. */
    public void open(int mouseX, int mouseY, int screenWidth, int screenHeight) {
        open = true;
        x = Math.max(4, Math.min(mouseX, screenWidth - WIDTH - 4));
        y = Math.max(4, Math.min(mouseY, screenHeight - height() - 4));
    }

    public void close() {
        open = false;
    }

    public boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + height();
    }

    /** The action under the mouse, or null (also for the dividers). */
    public Action actionAt(double mouseX, double mouseY) {
        if (!open || mouseX < x + PAD || mouseX >= x + WIDTH - PAD) {
            return null;
        }

        int rowY = y + PAD;

        for (Action action : ACTIONS) {
            if (mouseY >= rowY && mouseY < rowY + ROW) {
                return action;
            }

            rowY += ROW + (action.dividerAfter ? DIVIDER : 0);
        }

        return null;
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!open) {
            return;
        }

        int height = height();
        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + height + 1, 0xFF5F6368);
        graphics.fill(x, y, x + WIDTH, y + height, 0xFF202124);

        Action hovered = actionAt(mouseX, mouseY);
        int rowY = y + PAD;

        for (Action action : ACTIONS) {
            if (action == hovered) {
                graphics.fill(x + PAD, rowY, x + WIDTH - PAD, rowY + ROW, 0xFF3C4043);
            }

            int textY = rowY + (ROW - 8) / 2;
            graphics.drawString(font, action.label, x + PAD + 5, textY, 0xFFFFFFFF, false);

            if (!action.hint.isEmpty()) {
                graphics.drawString(font, action.hint,
                        x + WIDTH - PAD - 5 - font.width(action.hint), textY, 0xFF9AA0A6, false);
            }

            rowY += ROW;

            if (action.dividerAfter) {
                graphics.fill(x + PAD + 2, rowY + DIVIDER / 2, x + WIDTH - PAD - 2, rowY + DIVIDER / 2 + 1, 0xFF5F6368);
                rowY += DIVIDER;
            }
        }
    }
}
