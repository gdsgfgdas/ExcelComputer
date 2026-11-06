package io.github.reverendbrown.excelcomputer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.apache.poi.ss.formula.atp.AnalysisToolPak;
import org.apache.poi.ss.formula.eval.FunctionEval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Formula autocomplete, like Excel's: while a formula is being typed, the
 * function name at the cursor (e.g. "=D" or "=SUM(A1, RO") opens a list of
 * the functions starting with it. Up/Down pick one, Tab (or a click) inserts
 * it with its opening bracket, Escape hides the list.
 *
 * <p>The list holds every function Apache POI can evaluate, so nothing it
 * offers ends up as #NAME?.
 */
public final class FormulaAutocomplete {
    /** A function in the list; {@code args} and {@code description} are null when unknown. */
    public record Suggestion(String name, String args, String description) {
        public String syntax() {
            return name + "(" + (args == null ? "" : args) + ")";
        }

        boolean noArgs() {
            return args != null && args.isEmpty();
        }
    }

    /** The edit box's new text and cursor after a suggestion was inserted. */
    public record Accepted(String text, int caret, Suggestion suggestion) {
    }

    private static final int MAX_VISIBLE = 8;
    public static final int WIDTH = 210;
    private static final int PAD = 3;
    private static final int ROW = 11;
    private static final int SCROLLBAR = 3;

    // Syntax and descriptions for functions that the fx menu doesn't list.
    private static final Map<String, String[]> EXTRA_HELP = Map.ofEntries(
            help("NOW", "", "The current date and time."),
            help("DATE", "year, month, day", "Makes a date from its parts."),
            help("DAY", "date", "The day of the month (1-31)."),
            help("MONTH", "date", "The month (1-12)."),
            help("YEAR", "date", "The year of a date."),
            help("WEEKDAY", "date, [return_type]", "The day of the week as a number."),
            help("DAYS", "end_date, start_date", "The number of days between two dates."),
            help("EDATE", "start_date, months", "The date some months before or after."),
            help("HOUR", "time", "The hour (0-23)."),
            help("MINUTE", "time", "The minute (0-59)."),
            help("LEN", "text", "The number of characters."),
            help("LEFT", "text, [num_chars]", "Characters from the start of the text."),
            help("RIGHT", "text, [num_chars]", "Characters from the end of the text."),
            help("MID", "text, start_num, num_chars", "Characters from the middle of the text."),
            help("UPPER", "text", "Text in capital letters."),
            help("LOWER", "text", "Text in small letters."),
            help("PROPER", "text", "Capitalizes each word."),
            help("TRIM", "text", "Removes extra spaces."),
            help("TEXT", "value, format_text", "Formats a number as text."),
            help("VALUE", "text", "Turns text into a number."),
            help("FIND", "find_text, within_text, [start_num]", "Position of text (case-sensitive)."),
            help("SEARCH", "find_text, within_text, [start_num]", "Position of text (ignores case)."),
            help("SUBSTITUTE", "text, old_text, new_text, [instance]", "Replaces text with other text."),
            help("REPLACE", "old_text, start_num, num_chars, new_text", "Replaces part of the text."),
            help("REPT", "text, number_times", "Repeats text."),
            help("CONCAT", "text1, [text2], ...", "Joins pieces of text."),
            help("TEXTJOIN", "delimiter, ignore_empty, text1, ...", "Joins text with a separator."),
            help("NOT", "logical", "Reverses TRUE and FALSE."),
            help("XOR", "logical1, [logical2], ...", "TRUE if an odd number are TRUE."),
            help("IFS", "test1, value1, [test2, value2], ...", "The value for the first TRUE test."),
            help("SWITCH", "expression, value1, result1, ...", "Picks a result that matches a value."),
            help("ISBLANK", "value", "TRUE if the cell is empty."),
            help("ISNUMBER", "value", "TRUE if the value is a number."),
            help("ISTEXT", "value", "TRUE if the value is text."),
            help("ISERROR", "value", "TRUE if the value is an error."),
            help("SUMIFS", "sum_range, range1, criteria1, ...", "Adds cells that match every condition."),
            help("COUNTIFS", "range1, criteria1, ...", "Counts cells that match every condition."),
            help("AVERAGEIFS", "avg_range, range1, criteria1, ...", "Averages cells matching every condition."),
            help("MAXIFS", "max_range, range1, criteria1, ...", "The largest value matching conditions."),
            help("MINIFS", "min_range, range1, criteria1, ...", "The smallest value matching conditions."),
            help("COUNTBLANK", "range", "Counts the empty cells."),
            help("SUMPRODUCT", "array1, [array2], ...", "Multiplies arrays, then adds them up."),
            help("PRODUCT", "number1, [number2], ...", "Multiplies the numbers."),
            help("MEDIAN", "number1, [number2], ...", "The middle number."),
            help("MODE", "number1, [number2], ...", "The most common number."),
            help("STDEV", "number1, [number2], ...", "Standard deviation of a sample."),
            help("LARGE", "array, k", "The k-th largest value."),
            help("SMALL", "array, k", "The k-th smallest value."),
            help("RANK", "number, ref, [order]", "The rank of a number in a list."),
            help("ROUNDUP", "number, num_digits", "Rounds away from zero."),
            help("ROUNDDOWN", "number, num_digits", "Rounds toward zero."),
            help("INT", "number", "Rounds down to a whole number."),
            help("MOD", "number, divisor", "The remainder after dividing."),
            help("POWER", "number, power", "A number raised to a power."),
            help("SQRT", "number", "The square root."),
            help("PI", "", "The number pi."),
            help("RAND", "", "A random number from 0 to 1."),
            help("RANDBETWEEN", "bottom, top", "A random whole number in a range."),
            help("CEILING", "number, significance", "Rounds up to a multiple."),
            help("FLOOR", "number, significance", "Rounds down to a multiple."),
            help("HLOOKUP", "value, table, row_index, [approx]", "Looks up a value in a table's top row."),
            help("XLOOKUP", "value, lookup_array, return_array, ...", "Looks up a value and returns a match."),
            help("LOOKUP", "value, lookup_vector, [result_vector]", "Looks up a value in one row or column."),
            help("CHOOSE", "index_num, value1, [value2], ...", "Picks a value from a list by position."),
            help("OFFSET", "reference, rows, cols, [height], [width]", "A reference moved from a starting cell."),
            help("INDIRECT", "ref_text, [a1]", "The reference named by some text."),
            help("ROW", "[reference]", "The row number of a reference."),
            help("COLUMN", "[reference]", "The column number of a reference."),
            help("ROWS", "array", "The number of rows."),
            help("COLUMNS", "array", "The number of columns."),
            help("PMT", "rate, nper, pv, [fv], [type]", "The payment for a loan."),
            help("NA", "", "The #N/A error value."),
            help("TRUE", "", "The logical value TRUE."),
            help("FALSE", "", "The logical value FALSE.")
    );

    private static List<Suggestion> allFunctions;

    private List<Suggestion> matches = List.of();
    private int selected;
    private int scroll;
    private int tokenStart = -1;
    private int tokenEnd;
    private String token = "";

    // Escape hides the list until the text or the cursor changes.
    private String dismissedText;
    private int dismissedCaret = -1;

    private int x;
    private int y;

    private static Map.Entry<String, String[]> help(String name, String args, String description) {
        return Map.entry(name, new String[]{args, description});
    }

    // Every function POI can evaluate, in alphabetical order (built on first use).
    private static List<Suggestion> allFunctions() {
        if (allFunctions != null) {
            return allFunctions;
        }

        Map<String, Suggestion> byName = new TreeMap<>();

        try {
            List<String> names = new ArrayList<>(FunctionEval.getSupportedFunctionNames());
            names.addAll(AnalysisToolPak.getSupportedFunctionNames());

            for (String name : names) {
                String upper = name.toUpperCase(Locale.ROOT);

                if (upper.matches("[A-Z][A-Z0-9.]*")) {
                    String[] extra = EXTRA_HELP.get(upper);

                    byName.put(upper, extra == null
                            ? new Suggestion(upper, null, null)
                            : new Suggestion(upper, extra[0], extra[1]));
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            ExcelComputer.LOGGER.warn("Couldn't list the formula functions: {}", exception.toString());
        }

        // The fx menu's functions always have their full help text.
        for (FunctionMenu.Function function : FunctionMenu.FUNCTIONS) {
            byName.put(function.name(), new Suggestion(function.name(), function.args(), function.description()));
        }

        allFunctions = List.copyOf(byName.values());
        return allFunctions;
    }

    public boolean isOpen() {
        return !matches.isEmpty();
    }

    public void close() {
        matches = List.of();
        tokenStart = -1;
        token = "";
    }

    /** Where the function name being typed starts in the text (only while open). */
    public int tokenStart() {
        return tokenStart;
    }

    /**
     * Looks at the text and cursor of the edit box and opens, updates or
     * closes the list. Called every frame and before each key, so it always
     * matches what is typed.
     */
    public void update(String text, int caret) {
        if (text.equals(dismissedText) && caret == dismissedCaret) {
            close();
            return;
        }

        dismissedText = null;
        dismissedCaret = -1;

        int start = nameStart(text, caret);

        if (start < 0) {
            close();
            return;
        }

        int end = caret;

        while (end < text.length() && isNameChar(text.charAt(end))) {
            end++;
        }

        String typed = text.substring(start, caret).toUpperCase(Locale.ROOT);

        if (typed.equals(token) && start == tokenStart) {
            tokenEnd = end;
            return;
        }

        List<Suggestion> found = new ArrayList<>();

        for (Suggestion suggestion : allFunctions()) {
            if (suggestion.name().startsWith(typed)) {
                found.add(suggestion);
            }
        }

        // A function that is already written out with its bracket needs no list.
        boolean complete = found.size() == 1 && found.get(0).name().equals(typed)
                && end < text.length() && text.charAt(end) == '(';

        if (found.isEmpty() || complete) {
            close();
            return;
        }

        matches = found;
        tokenStart = start;
        tokenEnd = end;
        token = typed;
        selected = 0;
        scroll = 0;
    }

    // The start of a function name ending at the cursor, or -1 when the
    // cursor isn't on one (not a formula, inside "text", a sheet name, ...).
    private static int nameStart(String text, int caret) {
        if (!text.startsWith("=") || caret < 2 || caret > text.length()) {
            return -1;
        }

        boolean inString = false;

        for (int i = 1; i < caret; i++) {
            if (text.charAt(i) == '"') {
                inString = !inString;
            }
        }

        if (inString) {
            return -1;
        }

        int start = caret;

        while (start > 1 && isNameChar(text.charAt(start - 1))) {
            start--;
        }

        if (start == caret || !Character.isLetter(text.charAt(start))) {
            return -1;
        }

        // Sheet1!A1, $A$1, A1:B2 and 'My Sheet'!A1 are references, not functions.
        char before = text.charAt(start - 1);

        if (before == '!' || before == '$' || before == ':' || before == '\'') {
            return -1;
        }

        return start;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '_';
    }

    /** Hides the list until the text or cursor changes (Escape). */
    public void dismiss(String text, int caret) {
        dismissedText = text;
        dismissedCaret = caret;
        close();
    }

    public void move(int change) {
        if (!isOpen()) {
            return;
        }

        selected = Math.floorMod(selected + change, matches.size());

        if (selected < scroll) {
            scroll = selected;
        } else if (selected >= scroll + MAX_VISIBLE) {
            scroll = selected - MAX_VISIBLE + 1;
        }
    }

    public void scroll(double amount) {
        int maxScroll = Math.max(0, matches.size() - MAX_VISIBLE);

        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(amount)));
    }

    /** Inserts the selected function in place of the name being typed. */
    public Accepted accept(String text) {
        if (!isOpen() || tokenStart < 0 || tokenEnd > text.length()) {
            return null;
        }

        Suggestion suggestion = matches.get(selected);
        String after = text.substring(tokenEnd);
        String call;
        int caret;

        if (after.startsWith("(")) {
            // Fixing the name of a function that already has its brackets.
            call = suggestion.name();
            caret = tokenStart + call.length() + 1;
        } else if (suggestion.noArgs()) {
            call = suggestion.name() + "()";
            caret = tokenStart + call.length();
        } else {
            // Like Excel: just the opening bracket. The closing one is
            // added when the formula is entered.
            call = suggestion.name() + "(";
            caret = tokenStart + call.length();
        }

        String before = text.substring(0, tokenStart);

        // close() resets tokenStart, so the text is built first.
        close();
        return new Accepted(before + call + after, caret, suggestion);
    }

    public void setPosition(int x, int y, int screenWidth) {
        this.x = Math.max(2, Math.min(x, screenWidth - WIDTH - 2));
        this.y = y;
    }

    private int visibleRows() {
        return Math.min(MAX_VISIBLE, matches.size());
    }

    private Suggestion current() {
        return matches.get(selected);
    }

    private int listHeight() {
        return PAD * 2 + visibleRows() * ROW;
    }

    private int height() {
        return listHeight() + (current().description() != null ? ROW * 2 + 4 : 0);
    }

    public boolean contains(double mouseX, double mouseY) {
        return isOpen() && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + height();
    }

    /** Selects the row under the mouse; returns false if the mouse isn't on a row. */
    public boolean selectAt(double mouseX, double mouseY) {
        int index = indexAt(mouseX, mouseY);

        if (index < 0) {
            return false;
        }

        selected = index;
        return true;
    }

    private int indexAt(double mouseX, double mouseY) {
        if (!isOpen() || mouseX < x || mouseX >= x + WIDTH - SCROLLBAR - 1) {
            return -1;
        }

        int row = (int) Math.floor((mouseY - (y + PAD)) / ROW);

        if (row < 0 || row >= visibleRows()) {
            return -1;
        }

        return scroll + row;
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!isOpen()) {
            return;
        }

        int height = height();
        int hovered = indexAt(mouseX, mouseY);

        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + height + 1, 0xFF5F6368);
        graphics.fill(x, y, x + WIDTH, y + height, 0xFF202124);

        int rowRight = x + WIDTH - SCROLLBAR - 2;

        for (int i = 0; i < visibleRows(); i++) {
            int index = scroll + i;
            Suggestion suggestion = matches.get(index);
            int top = y + PAD + i * ROW;

            if (index == selected) {
                graphics.fill(x + 1, top, rowRight, top + ROW, 0xFF185ABC);
            } else if (index == hovered) {
                graphics.fill(x + 1, top, rowRight, top + ROW, 0xFF3C4043);
            }

            graphics.drawString(font, "fx", x + 4, top + 2, 0xFF9AA0A6, false);
            graphics.drawString(font, suggestion.name(), x + 18, top + 2, 0xFFFFFFFF, false);
        }

        // The scroll bar, when there are more matches than rows.
        if (matches.size() > MAX_VISIBLE) {
            int trackTop = y + PAD;
            int trackHeight = visibleRows() * ROW;
            int thumbHeight = Math.max(6, trackHeight * MAX_VISIBLE / matches.size());
            int thumbTop = trackTop + (trackHeight - thumbHeight) * scroll / (matches.size() - MAX_VISIBLE);
            int barX = x + WIDTH - SCROLLBAR - 1;

            graphics.fill(barX, trackTop, barX + SCROLLBAR, trackTop + trackHeight, 0xFF303134);
            graphics.fill(barX, thumbTop, barX + SCROLLBAR, thumbTop + thumbHeight, 0xFF9AA0A6);
        }

        // The selected function's syntax and what it does.
        Suggestion current = current();

        if (current.description() != null) {
            int infoTop = y + listHeight();
            int textWidth = WIDTH - PAD * 2 - 4;

            graphics.fill(x + PAD, infoTop, x + WIDTH - PAD, infoTop + 1, 0xFF5F6368);
            graphics.drawString(font, font.plainSubstrByWidth(current.syntax(), textWidth),
                    x + PAD + 2, infoTop + 4, 0xFFB8DDBF, false);
            graphics.drawString(font, font.plainSubstrByWidth(current.description(), textWidth),
                    x + PAD + 2, infoTop + 4 + ROW, 0xFF9AA0A6, false);
        }
    }
}
