package io.github.reverendbrown.excelcomputer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** The Paste dropdown: the different ways to paste copied cells, like Excel's Paste Special. */
public final class PasteMenu {
    public enum Mode {
        // label, what it brings: value/formula, formatting, column widths and row heights
        // The default leaves the column widths and row heights where you paste alone.
        ALL("Paste (Ctrl+V)", true, true, false),
        WITH_SIZES("With Widths & Heights", true, true, true),
        FORMULAS("Formulas", true, false, false),
        VALUES("Values (Ctrl+Shift+V)", true, false, false),
        FORMATTING("Formatting", false, true, false),
        SIZES("Widths & Heights", false, false, true);

        private final String label;
        private final boolean contents;
        private final boolean formatting;
        private final boolean sizes;

        Mode(String label, boolean contents, boolean formatting, boolean sizes) {
            this.label = label;
            this.contents = contents;
            this.formatting = formatting;
            this.sizes = sizes;
        }

        public String label() {
            return label;
        }

        /** Pastes the cells' values or formulas. */
        public boolean contents() {
            return contents;
        }

        /** Pastes the cells' formatting (colors, font, borders, number format). */
        public boolean formatting() {
            return formatting;
        }

        /** Pastes the copied column widths and row heights. */
        public boolean sizes() {
            return sizes;
        }
    }

    private static final Mode[] MODES = Mode.values();
    public static final int WIDTH = 130;
    private static final int ROW = 16;
    private static final int PAD = 4;
    public static final int HEIGHT = PAD * 2 + MODES.length * ROW;

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

    /** The mode under the mouse, or null. */
    public Mode modeAt(double mouseX, double mouseY) {
        if (!open || mouseX < x + PAD || mouseX >= x + WIDTH - PAD || mouseY < y + PAD) {
            return null;
        }

        int row = (int) Math.floor((mouseY - (y + PAD)) / ROW);

        return row >= 0 && row < MODES.length ? MODES[row] : null;
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!open) {
            return;
        }

        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, 0xFF5F6368);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF202124);

        Mode hovered = modeAt(mouseX, mouseY);

        for (int i = 0; i < MODES.length; i++) {
            int rowY = y + PAD + i * ROW;

            if (MODES[i] == hovered) {
                graphics.fill(x + PAD, rowY, x + WIDTH - PAD, rowY + ROW, 0xFF3C4043);
            }

            graphics.drawString(font, MODES[i].label(), x + PAD + 6, rowY + 4, 0xFFFFFFFF, false);
        }
    }
}
