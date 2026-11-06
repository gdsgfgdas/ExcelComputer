package io.github.reverendbrown.excelcomputer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.function.Supplier;

/**
 * The small window for a Highlight Cells or Top/Bottom rule, like Excel's:
 * "Format cells that are GREATER THAN: [ 100 ] with [Light Red Fill...]".
 *
 * <p>It draws its own value boxes (rather than using EditBox widgets) so it
 * can sit on top of everything else. While it is open, the screen sends it
 * every key, character and click. Clicking a cell in the sheet puts that
 * cell's reference in the value box, as in Excel.
 */
public final class ConditionalDialog {
    public enum Result { NONE, OK, CANCEL }

    private static final int WIDTH = 250;
    private static final int PAD = 6;
    private static final int ROW = 12;
    private static final int FIELD_HEIGHT = 14;
    private static final int BUTTON_WIDTH = 50;

    private final ConditionalFormats.Condition condition;
    private final String rangeText;
    private final StringBuilder[] values;
    private final int[] carets;
    private int focused;
    private ConditionalFormats.Highlight look = ConditionalFormats.Highlight.LIGHT_RED;
    private String error;

    private int x;
    private int y;

    public ConditionalDialog(ConditionalFormats.Condition condition, String rangeText) {
        this.condition = condition;
        this.rangeText = rangeText;
        this.values = new StringBuilder[condition.inputs()];
        this.carets = new int[condition.inputs()];

        for (int i = 0; i < values.length; i++) {
            values[i] = new StringBuilder(i == 0 ? condition.defaultInput() : "");
            carets[i] = values[i].length();
        }
    }

    public ConditionalFormats.Condition condition() {
        return condition;
    }

    public ConditionalFormats.Highlight look() {
        return look;
    }

    /** The typed value (0 or 1), or null when the rule has no such value. */
    public String value(int index) {
        return index < values.length ? values[index].toString() : null;
    }

    /** Shown in red under the values, e.g. when a value is invalid. */
    public void setError(String error) {
        this.error = error;
    }

    private int height() {
        int lookRows = ConditionalFormats.Highlight.values().length;

        return PAD + ROW + 4                              // title
                + ROW                                     // prompt
                + (values.length > 0 ? FIELD_HEIGHT + 6 : 0)
                + ROW + lookRows * ROW + 4                // "with" and the looks
                + (error != null ? ROW : 0)
                + FIELD_HEIGHT + PAD;                     // buttons
    }

    /** Centers the dialog in the screen. */
    public void place(int screenWidth, int screenHeight) {
        x = (screenWidth - WIDTH) / 2;
        y = Math.max(4, (screenHeight - height()) / 2);
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + height();
    }

    // ----- layout (all positions are computed the same way for drawing and clicks) -----

    private int titleTop() {
        return y + PAD;
    }

    private int promptTop() {
        return titleTop() + ROW + 4;
    }

    private int fieldsTop() {
        return promptTop() + ROW;
    }

    private int fieldWidth() {
        return values.length == 2 ? (WIDTH - PAD * 2 - 24) / 2 : WIDTH - PAD * 2;
    }

    private int fieldLeft(int index) {
        return x + PAD + index * (fieldWidth() + 24);
    }

    private int looksTop() {
        return fieldsTop() + (values.length > 0 ? FIELD_HEIGHT + 6 : 0) + ROW;
    }

    private int buttonsTop() {
        return looksTop() + ConditionalFormats.Highlight.values().length * ROW + 4 + (error != null ? ROW : 0);
    }

    private int okLeft() {
        return x + WIDTH - PAD - BUTTON_WIDTH * 2 - 4;
    }

    private int cancelLeft() {
        return x + WIDTH - PAD - BUTTON_WIDTH;
    }

    // ----- input -----

    public Result keyPressed(int key, boolean ctrl, Supplier<String> clipboard) {
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                return Result.CANCEL;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                return Result.OK;
            }
            default -> {
            }
        }

        if (values.length == 0) {
            // Up/Down pick the look when there is nothing to type.
            moveLook(key);
            return Result.NONE;
        }

        StringBuilder value = values[focused];
        int caret = carets[focused];

        switch (key) {
            case GLFW.GLFW_KEY_TAB -> focused = (focused + 1) % values.length;
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (caret > 0) {
                    value.deleteCharAt(caret - 1);
                    carets[focused] = caret - 1;
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (caret < value.length()) {
                    value.deleteCharAt(caret);
                }
            }
            case GLFW.GLFW_KEY_LEFT -> carets[focused] = Math.max(0, caret - 1);
            case GLFW.GLFW_KEY_RIGHT -> carets[focused] = Math.min(value.length(), caret + 1);
            case GLFW.GLFW_KEY_HOME -> carets[focused] = 0;
            case GLFW.GLFW_KEY_END -> carets[focused] = value.length();
            case GLFW.GLFW_KEY_V -> {
                if (ctrl) {
                    insert(clipboard.get().replaceAll("[\\r\\n\\t].*", ""));
                }
            }
            case GLFW.GLFW_KEY_A -> {
                if (ctrl) {
                    // Like selecting everything to type over it: clear the box.
                    value.setLength(0);
                    carets[focused] = 0;
                }
            }
            default -> moveLook(key);
        }

        error = null;
        return Result.NONE;
    }

    private void moveLook(int key) {
        ConditionalFormats.Highlight[] looks = ConditionalFormats.Highlight.values();

        if (key == GLFW.GLFW_KEY_DOWN) {
            look = looks[(look.ordinal() + 1) % looks.length];
        } else if (key == GLFW.GLFW_KEY_UP) {
            look = looks[(look.ordinal() + looks.length - 1) % looks.length];
        }
    }

    public void charTyped(int codepoint) {
        if (values.length == 0 || Character.isISOControl(codepoint)) {
            return;
        }

        insert(new String(Character.toChars(codepoint)));
    }

    private void insert(String text) {
        if (values.length == 0 || text.isEmpty()) {
            return;
        }

        StringBuilder value = values[focused];
        int room = 255 - value.length();

        if (room <= 0) {
            return;
        }

        String added = text.length() > room ? text.substring(0, room) : text;
        value.insert(carets[focused], added);
        carets[focused] += added.length();
        error = null;
    }

    /** Puts a cell reference (from a click on the sheet) into the focused value box. */
    public void insertReference(String reference) {
        if (values.length == 0 || condition == ConditionalFormats.Condition.TOP
                || condition == ConditionalFormats.Condition.BOTTOM
                || condition == ConditionalFormats.Condition.TEXT_CONTAINS) {
            return;
        }

        values[focused].setLength(0);
        values[focused].append('=').append(reference);
        carets[focused] = values[focused].length();
        error = null;
    }

    public Result click(double mouseX, double mouseY) {
        for (int i = 0; i < values.length; i++) {
            if (mouseX >= fieldLeft(i) && mouseX < fieldLeft(i) + fieldWidth()
                    && mouseY >= fieldsTop() && mouseY < fieldsTop() + FIELD_HEIGHT) {
                focused = i;
                carets[i] = values[i].length();
                return Result.NONE;
            }
        }

        ConditionalFormats.Highlight[] looks = ConditionalFormats.Highlight.values();
        int row = (int) Math.floor((mouseY - looksTop()) / ROW);

        if (mouseX >= x + PAD && mouseX < x + WIDTH - PAD && row >= 0 && row < looks.length
                && mouseY >= looksTop()) {
            look = looks[row];
            return Result.NONE;
        }

        if (mouseY >= buttonsTop() && mouseY < buttonsTop() + FIELD_HEIGHT) {
            if (mouseX >= okLeft() && mouseX < okLeft() + BUTTON_WIDTH) {
                return Result.OK;
            }
            if (mouseX >= cancelLeft() && mouseX < cancelLeft() + BUTTON_WIDTH) {
                return Result.CANCEL;
            }
        }

        return Result.NONE;
    }

    // ----- drawing -----

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        int height = height();

        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + height + 1, 0xFF5F6368);
        graphics.fill(x, y, x + WIDTH, y + height, 0xFF202124);

        String title = condition.label().replace("...", "") + "  (" + rangeText + ")";
        graphics.drawString(font, font.plainSubstrByWidth(title, WIDTH - PAD * 2),
                x + PAD, titleTop() + 2, 0xFFB8DDBF, false);
        graphics.fill(x + PAD, titleTop() + ROW + 1, x + WIDTH - PAD, titleTop() + ROW + 2, 0xFF3C4043);

        graphics.drawString(font, condition.prompt(), x + PAD, promptTop() + 2, 0xFFFFFFFF, false);

        for (int i = 0; i < values.length; i++) {
            renderField(graphics, font, i);
        }

        if (values.length == 2) {
            graphics.drawString(font, "and", fieldLeft(0) + fieldWidth() + 5, fieldsTop() + 3, 0xFF9AA0A6, false);
        }

        graphics.drawString(font, values.length > 0 ? "with" : "Format with", x + PAD,
                looksTop() - ROW + 2, 0xFF9AA0A6, false);

        ConditionalFormats.Highlight[] looks = ConditionalFormats.Highlight.values();

        for (int i = 0; i < looks.length; i++) {
            ConditionalFormats.Highlight option = looks[i];
            int top = looksTop() + i * ROW;
            boolean hovered = mouseX >= x + PAD && mouseX < x + WIDTH - PAD
                    && mouseY >= top && mouseY < top + ROW;

            if (option == look) {
                graphics.fill(x + PAD, top, x + WIDTH - PAD, top + ROW, 0xFF185ABC);
            } else if (hovered) {
                graphics.fill(x + PAD, top, x + WIDTH - PAD, top + ROW, 0xFF3C4043);
            }

            // A preview of the look: the fill with sample text in the text color.
            int swatchLeft = x + PAD + 3;
            graphics.fill(swatchLeft, top + 1, swatchLeft + 30, top + ROW - 1, 0xFFFFFFFF);
            if (option.fill() >= 0) {
                graphics.fill(swatchLeft, top + 1, swatchLeft + 30, top + ROW - 1, 0xFF000000 | option.fill());
            }
            graphics.drawString(font, "Aa", swatchLeft + 9, top + 2,
                    0xFF000000 | (option.fontColor() >= 0 ? option.fontColor() : 0x202124), false);

            graphics.drawString(font, option.label(), swatchLeft + 36, top + 2, 0xFFFFFFFF, false);
        }

        if (error != null) {
            graphics.drawString(font, font.plainSubstrByWidth(error, WIDTH - PAD * 2),
                    x + PAD, buttonsTop() - ROW + 1, 0xFFF28B82, false);
        }

        renderButton(graphics, font, okLeft(), "OK", mouseX, mouseY);
        renderButton(graphics, font, cancelLeft(), "Cancel", mouseX, mouseY);
    }

    private void renderField(GuiGraphics graphics, Font font, int index) {
        int left = fieldLeft(index);
        int top = fieldsTop();
        int width = fieldWidth();
        boolean active = index == focused;

        graphics.fill(left - 1, top - 1, left + width + 1, top + FIELD_HEIGHT + 1, active ? 0xFF8AB4F8 : 0xFF5F6368);
        graphics.fill(left, top, left + width, top + FIELD_HEIGHT, 0xFF000000);

        String text = values[index].toString();
        int caret = Math.min(carets[index], text.length());
        int room = width - 8;

        // Scroll long text so the cursor stays visible.
        int start = 0;

        while (start < caret && font.width(text.substring(start, caret)) > room) {
            start++;
        }

        String shown = font.plainSubstrByWidth(text.substring(start), room);
        graphics.drawString(font, shown, left + 4, top + 3, 0xFFFFFFFF, false);

        if (active && System.currentTimeMillis() / 500 % 2 == 0) {
            int caretX = left + 4 + font.width(text.substring(start, caret));

            graphics.fill(caretX, top + 2, caretX + 1, top + FIELD_HEIGHT - 2, 0xFFFFFFFF);
        }
    }

    private void renderButton(GuiGraphics graphics, Font font, int left, String label, int mouseX, int mouseY) {
        int top = buttonsTop();
        boolean hovered = mouseX >= left && mouseX < left + BUTTON_WIDTH && mouseY >= top && mouseY < top + FIELD_HEIGHT;

        graphics.fill(left, top, left + BUTTON_WIDTH, top + FIELD_HEIGHT, hovered ? 0xFF5F6368 : 0xFF3C4043);
        graphics.drawString(font, label, left + (BUTTON_WIDTH - font.width(label)) / 2, top + 3, 0xFFFFFFFF, false);
    }
}
