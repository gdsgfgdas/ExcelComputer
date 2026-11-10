package io.github.reverendbrown.excelcomputer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * The search bar for one table (Ctrl+F inside a table): while it has text,
 * only the table rows with a cell containing that text are shown. It only
 * changes the view; nothing is written to the workbook.
 */
public final class TableSearchBar {
    public enum Result { NONE, MISSED, CHANGED, NEXT, PREVIOUS, CLOSE }

    public static final int WIDTH = 240;
    public static final int HEIGHT = 20;

    private static final int FIELD_HEIGHT = 14;
    private static final int CLOSE_SIZE = 12;
    private static final int MAX_LENGTH = 100;

    private boolean open;
    private boolean focused;
    private final StringBuilder query = new StringBuilder();
    private int caret;
    private int sheet;
    private int top;
    private int left;
    private String label = "";
    private int shown;
    private int total;
    private int x;
    private int y;

    public boolean isOpen() {
        return open;
    }

    public boolean isFocused() {
        return open && focused;
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    // query() is asked for every row drawn, so it is worked out once per change.
    private String lowered = "";

    /** The text searched for, lower case, or "" when there is none. */
    public String query() {
        return lowered;
    }

    private void changed() {
        lowered = query.toString().trim().toLowerCase(Locale.ROOT);
    }

    public int sheet() {
        return sheet;
    }

    /** The table's top-left cell, which finds the table again after it changes size. */
    public int top() {
        return top;
    }

    public int left() {
        return left;
    }

    /** Opens for a table (keeping the text when it is the same table), focused. */
    public void open(int sheet, TableInfo table) {
        boolean sameTable = open && this.sheet == sheet && top == table.top() && left == table.left();

        if (!sameTable) {
            query.setLength(0);
            caret = 0;
            changed();
        }

        this.open = true;
        this.focused = true;
        this.sheet = sheet;
        this.top = table.top();
        this.left = table.left();
        this.label = table.isTable() ? table.name() : "table";
    }

    public void close() {
        open = false;
        focused = false;
        query.setLength(0);
        caret = 0;
        changed();
    }

    /** How many of the table's rows are shown, for the counter. */
    public void setCounts(int shown, int total) {
        this.shown = shown;
        this.total = total;
    }

    public void place(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT;
    }

    private int closeLeft() {
        return x + WIDTH - 4 - CLOSE_SIZE;
    }

    private int fieldLeft(Font font) {
        return x + 6 + font.width(labelText());
    }

    private int fieldRight(Font font) {
        return closeLeft() - 8 - font.width(countText());
    }

    private String labelText() {
        return "Search " + label + ":";
    }

    private String countText() {
        return query().isEmpty() ? "" : shown + " of " + total;
    }

    // ----- input -----

    public Result keyPressed(int key, boolean ctrl, boolean shift, Supplier<String> clipboard) {
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                return Result.CLOSE;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                return shift ? Result.PREVIOUS : Result.NEXT;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                return Result.NEXT;
            }
            case GLFW.GLFW_KEY_UP -> {
                return Result.PREVIOUS;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (caret > 0) {
                    query.deleteCharAt(caret - 1);
                    caret--;
                    changed();
                    return Result.CHANGED;
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (caret < query.length()) {
                    query.deleteCharAt(caret);
                    changed();
                    return Result.CHANGED;
                }
            }
            case GLFW.GLFW_KEY_LEFT -> caret = Math.max(0, caret - 1);
            case GLFW.GLFW_KEY_RIGHT -> caret = Math.min(query.length(), caret + 1);
            case GLFW.GLFW_KEY_HOME -> caret = 0;
            case GLFW.GLFW_KEY_END -> caret = query.length();
            case GLFW.GLFW_KEY_V -> {
                if (ctrl) {
                    return insert(clipboard.get().replaceAll("[\\r\\n\\t].*", "")) ? Result.CHANGED : Result.NONE;
                }
            }
            case GLFW.GLFW_KEY_A -> {
                if (ctrl && query.length() > 0) {
                    // Like selecting everything to type over it: clear the box.
                    query.setLength(0);
                    caret = 0;
                    changed();
                    return Result.CHANGED;
                }
            }
            default -> {
            }
        }

        return Result.NONE;
    }

    /** Returns true when the text changed. */
    public boolean charTyped(int codepoint) {
        if (Character.isISOControl(codepoint)) {
            return false;
        }

        return insert(new String(Character.toChars(codepoint)));
    }

    private boolean insert(String text) {
        int room = MAX_LENGTH - query.length();

        if (text.isEmpty() || room <= 0) {
            return false;
        }

        String added = text.length() > room ? text.substring(0, room) : text;
        query.insert(caret, added);
        caret += added.length();
        changed();
        return true;
    }

    /** A click: MISSED when it was outside the bar (the bar then loses focus). */
    public Result click(double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY)) {
            focused = false;
            return Result.MISSED;
        }

        if (mouseX >= closeLeft() && mouseX < closeLeft() + CLOSE_SIZE) {
            return Result.CLOSE;
        }

        focused = true;
        caret = query.length();
        return Result.NONE;
    }

    // ----- drawing -----

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!open) {
            return;
        }

        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, focused ? 0xFF8AB4F8 : 0xFF5F6368);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF202124);

        int textY = y + (HEIGHT - 8) / 2;
        graphics.drawString(font, labelText(), x + 4, textY, 0xFFB8DDBF, false);

        // The text box.
        int fieldLeft = fieldLeft(font);
        int fieldRight = fieldRight(font);
        int fieldTop = y + (HEIGHT - FIELD_HEIGHT) / 2;

        graphics.fill(fieldLeft, fieldTop, fieldRight, fieldTop + FIELD_HEIGHT, 0xFF000000);

        String text = query.toString();
        int room = fieldRight - fieldLeft - 6;
        int start = 0;

        // Scroll long text so the cursor stays visible.
        while (start < caret && font.width(text.substring(start, caret)) > room) {
            start++;
        }

        if (text.isEmpty() && !focused) {
            graphics.drawString(font, font.plainSubstrByWidth("Type to search", room),
                    fieldLeft + 3, fieldTop + 3, 0xFF80868B, false);
        } else {
            graphics.drawString(font, font.plainSubstrByWidth(text.substring(start), room),
                    fieldLeft + 3, fieldTop + 3, 0xFFFFFFFF, false);
        }

        if (focused && System.currentTimeMillis() / 500 % 2 == 0) {
            int caretX = fieldLeft + 3 + font.width(text.substring(start, Math.min(caret, text.length())));

            graphics.fill(caretX, fieldTop + 2, caretX + 1, fieldTop + FIELD_HEIGHT - 2, 0xFFFFFFFF);
        }

        // How many rows match, red when none do.
        String count = countText();

        if (!count.isEmpty()) {
            graphics.drawString(font, count, fieldRight + 4, textY, shown == 0 ? 0xFFF28B82 : 0xFF9AA0A6, false);
        }

        // The close button.
        int closeTop = y + (HEIGHT - CLOSE_SIZE) / 2;
        boolean hovered = mouseX >= closeLeft() && mouseX < closeLeft() + CLOSE_SIZE
                && mouseY >= closeTop && mouseY < closeTop + CLOSE_SIZE;

        graphics.fill(closeLeft(), closeTop, closeLeft() + CLOSE_SIZE, closeTop + CLOSE_SIZE,
                hovered ? 0xFF5F6368 : 0xFF3C4043);
        graphics.drawString(font, "x", closeLeft() + (CLOSE_SIZE - font.width("x")) / 2 + 1, closeTop + 2,
                0xFFFFFFFF, false);
    }
}
