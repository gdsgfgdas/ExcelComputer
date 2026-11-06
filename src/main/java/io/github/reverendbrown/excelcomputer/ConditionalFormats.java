package io.github.reverendbrown.excelcomputer;

import org.apache.poi.ss.formula.ConditionalFormattingEvaluator;
import org.apache.poi.ss.formula.EvaluationConditionalFormatRule;
import org.apache.poi.ss.formula.FormulaParseException;
import org.apache.poi.ss.formula.FormulaParser;
import org.apache.poi.ss.formula.FormulaType;
import org.apache.poi.ss.formula.WorkbookEvaluatorProvider;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ComparisonOperator;
import org.apache.poi.ss.usermodel.ConditionType;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.ConditionalFormattingThreshold;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFColorScaleFormatting;
import org.apache.poi.xssf.usermodel.XSSFConditionalFormatting;
import org.apache.poi.xssf.usermodel.XSSFConditionalFormattingRule;
import org.apache.poi.xssf.usermodel.XSSFConditionalFormattingThreshold;
import org.apache.poi.xssf.usermodel.XSSFDataBarFormatting;
import org.apache.poi.xssf.usermodel.XSSFEvaluationWorkbook;
import org.apache.poi.xssf.usermodel.XSSFFontFormatting;
import org.apache.poi.xssf.usermodel.XSSFPatternFormatting;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFSheetConditionalFormatting;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCfRule;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTConditionalFormatting;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCfType;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STConditionalFormattingOperator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Conditional formatting (Home > Conditional Formatting): adds Excel's rules
 * to a sheet and works out how they change each cell's look.
 *
 * <p>Rules are written the way Excel writes them (cellIs, top10,
 * aboveAverage, duplicateValues, containsText, colorScale, dataBar), so a
 * workbook looks the same in Excel. Rules that came with a workbook are shown
 * too. Like {@link WorkbookManager} this uses only POI, no Minecraft classes.
 */
public final class ConditionalFormats {
    /**
     * How the rules change one cell. Colors are 0xRRGGBB, or -1 for "not
     * changed"; bold and italic are null when not changed; {@code bar} is the
     * data bar's length as a share of the cell width (0-1), or -1 for none.
     */
    public record Style(int fill, int fontColor, Boolean bold, Boolean italic, double bar, int barColor) {
        static final Style NONE = new Style(-1, -1, null, null, -1, -1);
    }

    /** The rules offered by the menu (Excel's Highlight Cells and Top/Bottom rules). */
    public enum Condition {
        GREATER("Greater Than...", "Format cells that are GREATER THAN:", 1),
        LESS("Less Than...", "Format cells that are LESS THAN:", 1),
        BETWEEN("Between...", "Format cells that are BETWEEN:", 2),
        EQUAL("Equal To...", "Format cells that are EQUAL TO:", 1),
        TEXT_CONTAINS("Text that Contains...", "Format cells that contain the text:", 1),
        DUPLICATE("Duplicate Values...", "Format cells that contain duplicate values.", 0),
        TOP("Top 10 Items...", "Format cells that rank in the TOP:", 1),
        BOTTOM("Bottom 10 Items...", "Format cells that rank in the BOTTOM:", 1),
        ABOVE_AVERAGE("Above Average...", "Format cells that are ABOVE AVERAGE.", 0),
        BELOW_AVERAGE("Below Average...", "Format cells that are BELOW AVERAGE.", 0);

        private final String label;
        private final String prompt;
        private final int inputs;

        Condition(String label, String prompt, int inputs) {
            this.label = label;
            this.prompt = prompt;
            this.inputs = inputs;
        }

        public String label() {
            return label;
        }

        public String prompt() {
            return prompt;
        }

        /** How many values the rule asks for (0, 1 or 2). */
        public int inputs() {
            return inputs;
        }

        /** What the value box starts with. */
        public String defaultInput() {
            return this == TOP || this == BOTTOM ? "10" : "";
        }
    }

    /** Excel's preset looks for highlight rules. */
    public enum Highlight {
        LIGHT_RED("Light Red Fill with Dark Red Text", 0xFFC7CE, 0x9C0006),
        YELLOW("Yellow Fill with Dark Yellow Text", 0xFFEB9C, 0x9C5700),
        GREEN("Green Fill with Dark Green Text", 0xC6EFCE, 0x006100),
        LIGHT_RED_FILL("Light Red Fill", 0xFFC7CE, -1),
        RED_TEXT("Red Text", -1, 0xC00000);

        private final String label;
        private final int fill;
        private final int fontColor;

        Highlight(String label, int fill, int fontColor) {
            this.label = label;
            this.fill = fill;
            this.fontColor = fontColor;
        }

        public String label() {
            return label;
        }

        public int fill() {
            return fill;
        }

        public int fontColor() {
            return fontColor;
        }
    }

    /** Color scale presets; colors run from the lowest value to the highest. */
    public enum ColorScale {
        GREEN_YELLOW_RED("Green - Yellow - Red", 0xF8696B, 0xFFEB84, 0x63BE7B),
        RED_YELLOW_GREEN("Red - Yellow - Green", 0x63BE7B, 0xFFEB84, 0xF8696B),
        BLUE_WHITE_RED("Blue - White - Red", 0xF8696B, 0xFCFCFF, 0x5A8AC6),
        GREEN_WHITE("Green - White", 0xFCFCFF, 0x63BE7B);

        private final String label;
        private final int[] colors;

        ColorScale(String label, int... colors) {
            this.label = label;
            this.colors = colors;
        }

        public String label() {
            return label;
        }

        /** The colors from the lowest value to the highest. */
        public int[] colors() {
            return colors.clone();
        }
    }

    /** Data bar colors (Excel's gradient fill presets). */
    public enum DataBar {
        BLUE("Blue Data Bar", 0x638EC6),
        GREEN("Green Data Bar", 0x63C384),
        RED("Red Data Bar", 0xFF555A),
        ORANGE("Orange Data Bar", 0xFFB628);

        private final String label;
        private final int color;

        DataBar(String label, int color) {
            this.label = label;
            this.color = color;
        }

        public String label() {
            return label;
        }

        public int color() {
            return color;
        }
    }

    /** A copy of a sheet's rules, for undo. */
    public static final class State {
        private final CTConditionalFormatting[] formattings;

        private State(CTConditionalFormatting[] formattings) {
            this.formattings = formattings;
        }
    }

    // Excel 2007's data bars run from 10% to 90% of the cell width.
    private static final double BAR_MIN = 0.1;
    private static final double BAR_MAX = 0.9;

    private final XSSFWorkbook workbook;
    private final FormulaEvaluator formulaEvaluator;
    private ConditionalFormattingEvaluator evaluator;

    // The look of each cell (Style.NONE when no rule changes it), worked out
    // once and kept until something changes (see invalidate()).
    private final Map<Long, Style> styles = new HashMap<>();
    // The sorted numbers in a color scale's or data bar's ranges.
    private final Map<String, double[]> rangeValues = new HashMap<>();

    ConditionalFormats(XSSFWorkbook workbook, FormulaEvaluator formulaEvaluator) {
        this.workbook = workbook;
        this.formulaEvaluator = formulaEvaluator;
    }

    /** Forgets every worked-out look; call after any value or rule changes. */
    void invalidate() {
        styles.clear();
        rangeValues.clear();

        if (evaluator != null) {
            evaluator.clearAllCachedFormats();
            evaluator.clearAllCachedValues();
        }
    }

    private XSSFSheet sheet(int sheetIndex) {
        return workbook.getSheetAt(sheetIndex);
    }

    boolean hasRules(int sheetIndex) {
        return sheet(sheetIndex).getSheetConditionalFormatting().getNumConditionalFormattings() > 0;
    }

    // ----- adding and removing rules -----

    /**
     * Adds a Highlight Cells or Top/Bottom rule to a range. The values are
     * what was typed: a number, text, or a formula starting with "=".
     *
     * @throws IllegalArgumentException with a message for the player when a value is missing or invalid
     */
    void addRule(int sheetIndex, CellRangeAddress range, Condition condition,
                 String value1, String value2, Highlight look) {
        XSSFSheet sheet = sheet(sheetIndex);
        XSSFSheetConditionalFormatting formatting = sheet.getSheetConditionalFormatting();
        XSSFConditionalFormattingRule rule;
        String text = value1 == null ? "" : value1.trim();
        long rank = -1;

        // Check the typed values before anything is added to the sheet.
        if (condition == Condition.TEXT_CONTAINS && text.isEmpty()) {
            throw new IllegalArgumentException("Type the text to look for");
        }

        if (condition == Condition.TOP || condition == Condition.BOTTOM) {
            try {
                rank = Long.parseLong(text);
            } catch (NumberFormatException exception) {
                rank = -1;
            }

            if (rank < 1 || rank > 1000) {
                throw new IllegalArgumentException("Enter a whole number from 1 to 1000");
            }
        }

        switch (condition) {
            case GREATER -> rule = formatting.createConditionalFormattingRule(
                    ComparisonOperator.GT, operand(sheetIndex, value1));
            case LESS -> rule = formatting.createConditionalFormattingRule(
                    ComparisonOperator.LT, operand(sheetIndex, value1));
            case EQUAL -> rule = formatting.createConditionalFormattingRule(
                    ComparisonOperator.EQUAL, operand(sheetIndex, value1));
            case BETWEEN -> rule = formatting.createConditionalFormattingRule(
                    ComparisonOperator.BETWEEN, operand(sheetIndex, value1), operand(sheetIndex, value2));
            default -> {
                // The other types have no POI method: start from a formula
                // rule and turn it into the right type below.
                rule = formatting.createConditionalFormattingRule("TRUE");
            }
        }

        if (look.fill() >= 0) {
            XSSFPatternFormatting pattern = rule.createPatternFormatting();
            pattern.setFillBackgroundColor(color(look.fill()));
        }

        if (look.fontColor() >= 0) {
            XSSFFontFormatting font = rule.createFontFormatting();
            font.setFontColor(color(look.fontColor()));
        }

        CTCfRule added = add(sheet, range, rule);

        switch (condition) {
            case TEXT_CONTAINS -> {
                // Excel stores the text and a matching formula.
                String topLeft = new CellReference(range.getFirstRow(), range.getFirstColumn()).formatAsString();

                added.setType(STCfType.CONTAINS_TEXT);
                added.setOperator(STConditionalFormattingOperator.CONTAINS_TEXT);
                added.setText(text);
                added.setFormulaArray(new String[]{
                        "NOT(ISERROR(SEARCH(\"" + text.replace("\"", "\"\"") + "\"," + topLeft + ")))"
                });
            }
            case DUPLICATE -> {
                added.setType(STCfType.DUPLICATE_VALUES);
                added.setFormulaArray(new String[0]);
            }
            case TOP, BOTTOM -> {
                added.setType(STCfType.TOP_10);
                added.setRank(rank);
                added.setBottom(condition == Condition.BOTTOM);
                added.setFormulaArray(new String[0]);
            }
            case ABOVE_AVERAGE, BELOW_AVERAGE -> {
                added.setType(STCfType.ABOVE_AVERAGE);
                added.setAboveAverage(condition == Condition.ABOVE_AVERAGE);
                added.setFormulaArray(new String[0]);
            }
            default -> {
            }
        }

        invalidate();
    }

    void addColorScale(int sheetIndex, CellRangeAddress range, ColorScale scale) {
        XSSFSheet sheet = sheet(sheetIndex);
        XSSFConditionalFormattingRule rule = sheet.getSheetConditionalFormatting()
                .createConditionalFormattingColorScaleRule();
        XSSFColorScaleFormatting colorScale = rule.getColorScaleFormatting();
        int[] colors = scale.colors();

        // POI starts with three points: lowest, 50th percentile, highest.
        if (colors.length == 2) {
            colorScale.setNumControlPoints(2);
            XSSFConditionalFormattingThreshold low = colorScale.createThreshold();
            XSSFConditionalFormattingThreshold high = colorScale.createThreshold();

            low.setRangeType(ConditionalFormattingThreshold.RangeType.MIN);
            high.setRangeType(ConditionalFormattingThreshold.RangeType.MAX);
            colorScale.setThresholds(new ConditionalFormattingThreshold[]{low, high});
        }

        XSSFColor[] xssfColors = new XSSFColor[colors.length];

        for (int i = 0; i < colors.length; i++) {
            xssfColors[i] = color(colors[i]);
        }

        colorScale.setColors(xssfColors);
        add(sheet, range, rule);
        invalidate();
    }

    void addDataBar(int sheetIndex, CellRangeAddress range, DataBar bar) {
        XSSFSheet sheet = sheet(sheetIndex);
        XSSFConditionalFormattingRule rule = sheet.getSheetConditionalFormatting()
                .createConditionalFormattingRule(color(bar.color()));

        add(sheet, range, rule);
        invalidate();
    }

    // Adds the rule to the sheet with the highest priority (like Excel, the
    // newest rule wins) and returns its XML, which the caller may adjust.
    private static CTCfRule add(XSSFSheet sheet, CellRangeAddress range, XSSFConditionalFormattingRule rule) {
        CTWorksheet worksheet = sheet.getCTWorksheet();

        // Every existing rule moves down one place.
        for (CTConditionalFormatting formatting : worksheet.getConditionalFormattingArray()) {
            for (CTCfRule existing : formatting.getCfRuleArray()) {
                existing.setPriority(existing.getPriority() + 1);
            }
        }

        int index = sheet.getSheetConditionalFormatting()
                .addConditionalFormatting(new CellRangeAddress[]{range}, rule);
        CTCfRule added = worksheet.getConditionalFormattingArray(index).getCfRuleArray(0);

        added.setPriority(1);
        return added;
    }

    // A typed value as a rule operand: "=..." is a formula, a number stays a
    // number, anything else is text.
    private String operand(int sheetIndex, String value) {
        String text = value == null ? "" : value.trim();

        if (text.isEmpty()) {
            throw new IllegalArgumentException("Enter a value");
        }

        String formula;

        if (text.startsWith("=")) {
            formula = text.substring(1);
        } else {
            try {
                Double.parseDouble(text);
                formula = text;
            } catch (NumberFormatException exception) {
                formula = "\"" + text.replace("\"", "\"\"") + "\"";
            }
        }

        try {
            FormulaParser.parse(formula, XSSFEvaluationWorkbook.create(workbook),
                    FormulaType.CELL, sheetIndex);
        } catch (FormulaParseException exception) {
            throw new IllegalArgumentException("\"" + text + "\" isn't a valid value or formula");
        }

        return formula;
    }

    /**
     * Excel's "Clear Rules from Selected Cells": the range is taken out of
     * every rule, and rules left with no cells are deleted.
     *
     * @return true if anything changed
     */
    boolean clearRules(int sheetIndex, CellRangeAddress cleared) {
        XSSFSheetConditionalFormatting formatting = sheet(sheetIndex).getSheetConditionalFormatting();
        boolean changed = false;

        for (int i = formatting.getNumConditionalFormattings() - 1; i >= 0; i--) {
            XSSFConditionalFormatting rules = formatting.getConditionalFormattingAt(i);
            CellRangeAddress[] ranges = rules.getFormattingRanges();
            List<CellRangeAddress> kept = new ArrayList<>();

            for (CellRangeAddress range : ranges) {
                kept.addAll(subtract(range, cleared));
            }

            if (kept.isEmpty()) {
                formatting.removeConditionalFormatting(i);
                changed = true;
            } else if (!kept.equals(Arrays.asList(ranges))) {
                rules.setFormattingRanges(kept.toArray(new CellRangeAddress[0]));
                changed = true;
            }
        }

        invalidate();
        return changed;
    }

    /** Excel's "Clear Rules from Entire Sheet". Returns true if there were any. */
    boolean clearAllRules(int sheetIndex) {
        XSSFSheetConditionalFormatting formatting = sheet(sheetIndex).getSheetConditionalFormatting();
        boolean any = formatting.getNumConditionalFormattings() > 0;

        while (formatting.getNumConditionalFormattings() > 0) {
            formatting.removeConditionalFormatting(0);
        }

        invalidate();
        return any;
    }

    // The parts of a range outside another one (up to four rectangles).
    private static List<CellRangeAddress> subtract(CellRangeAddress range, CellRangeAddress cut) {
        if (!range.intersects(cut)) {
            return List.of(range);
        }

        List<CellRangeAddress> parts = new ArrayList<>();
        int top = range.getFirstRow();
        int bottom = range.getLastRow();
        int left = range.getFirstColumn();
        int right = range.getLastColumn();
        int cutTop = Math.max(top, cut.getFirstRow());
        int cutBottom = Math.min(bottom, cut.getLastRow());

        // Above and below the cut: full width.
        if (top < cutTop) {
            parts.add(new CellRangeAddress(top, cutTop - 1, left, right));
        }
        if (bottom > cutBottom) {
            parts.add(new CellRangeAddress(cutBottom + 1, bottom, left, right));
        }

        // Left and right of the cut, on the cut's rows.
        if (left < cut.getFirstColumn()) {
            parts.add(new CellRangeAddress(cutTop, cutBottom, left, cut.getFirstColumn() - 1));
        }
        if (right > cut.getLastColumn()) {
            parts.add(new CellRangeAddress(cutTop, cutBottom, cut.getLastColumn() + 1, right));
        }

        return parts;
    }

    State capture(int sheetIndex) {
        CTConditionalFormatting[] current = sheet(sheetIndex).getCTWorksheet().getConditionalFormattingArray();
        CTConditionalFormatting[] copies = new CTConditionalFormatting[current.length];

        for (int i = 0; i < current.length; i++) {
            copies[i] = (CTConditionalFormatting) current[i].copy();
        }

        return new State(copies);
    }

    void restore(int sheetIndex, State state) {
        sheet(sheetIndex).getCTWorksheet().setConditionalFormattingArray(state.formattings);
        invalidate();
    }

    // ----- how a cell looks -----

    /** How the sheet's rules change a cell, or null if they don't. */
    Style styleAt(int sheetIndex, int row, int column) {
        if (!hasRules(sheetIndex)) {
            return null;
        }

        long key = ((long) sheetIndex << 36) | ((long) row << 14) | column;
        Style style = styles.get(key);

        if (style == null) {
            try {
                style = computeStyle(sheetIndex, row, column);
            } catch (RuntimeException exception) {
                // A rule POI can't evaluate just doesn't apply.
                style = Style.NONE;
            }

            styles.put(key, style);
        }

        return style == Style.NONE ? null : style;
    }

    private Style computeStyle(int sheetIndex, int row, int column) {
        if (evaluator == null) {
            evaluator = new ConditionalFormattingEvaluator(workbook, (WorkbookEvaluatorProvider) formulaEvaluator);
        }

        XSSFSheet sheet = sheet(sheetIndex);
        List<EvaluationConditionalFormatRule> matching = evaluator.getConditionalFormattingForCell(
                new CellReference(sheet.getSheetName(), row, column, false, false));

        if (matching.isEmpty()) {
            return Style.NONE;
        }

        int fill = -1;
        int fontColor = -1;
        Boolean bold = null;
        Boolean italic = null;
        double bar = -1;
        int barColor = -1;

        // The rules come highest priority first; each part of the look is
        // taken from the first rule that sets it.
        for (EvaluationConditionalFormatRule match : matching) {
            ConditionalFormattingRule rule = match.getRule();

            if (!(rule instanceof XSSFConditionalFormattingRule xssfRule)) {
                continue;
            }

            ConditionType type = match.getType();

            if (type == ConditionType.COLOR_SCALE) {
                if (fill < 0) {
                    fill = colorScaleColor(sheet, row, column, match, xssfRule.getColorScaleFormatting());
                }
            } else if (type == ConditionType.DATA_BAR) {
                if (bar < 0) {
                    XSSFDataBarFormatting dataBar = xssfRule.getDataBarFormatting();
                    double share = dataBarShare(sheet, row, column, match, dataBar);

                    if (share >= 0) {
                        bar = share;
                        barColor = WorkbookManager.rgbOf(dataBar.getColor());
                    }
                }
            } else if (type != ConditionType.ICON_SET) {
                XSSFPatternFormatting pattern = xssfRule.getPatternFormatting();

                if (fill < 0 && pattern != null) {
                    // Excel keeps a solid conditional fill in the background color.
                    int color = WorkbookManager.rgbOf(pattern.getFillBackgroundColorColor());

                    if (color < 0) {
                        color = WorkbookManager.rgbOf(pattern.getFillForegroundColorColor());
                    }

                    fill = color;
                }

                XSSFFontFormatting font = xssfRule.getFontFormatting();

                if (font != null) {
                    if (fontColor < 0) {
                        fontColor = WorkbookManager.rgbOf(font.getFontColor());
                    }
                    if (bold == null && font.isBold()) {
                        bold = true;
                    }
                    if (italic == null && font.isItalic()) {
                        italic = true;
                    }
                }
            }

            if (rule.getStopIfTrue()) {
                break;
            }
        }

        if (fill < 0 && fontColor < 0 && bold == null && italic == null && bar < 0) {
            return Style.NONE;
        }

        return new Style(fill, fontColor, bold, italic, bar, barColor);
    }

    // The cell's number, or null for text, blanks and errors.
    private Double numberAt(Sheet sheet, int rowIndex, int column) {
        Row row = sheet.getRow(rowIndex);
        Cell cell = row == null ? null : row.getCell(column);

        if (cell == null) {
            return null;
        }

        if (cell.getCellType() == CellType.NUMERIC
                || (cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC)) {
            return cell.getNumericCellValue();
        }

        return null;
    }

    // All the numbers in a rule's ranges, sorted (only the rows and columns in use are read).
    private double[] valuesOf(XSSFSheet sheet, EvaluationConditionalFormatRule rule) {
        String key = sheet.getSheetName() + "!" + rule.getFormattingIndex() + ":" + rule.getRuleIndex();

        return rangeValues.computeIfAbsent(key, unused -> {
            List<Double> numbers = new ArrayList<>();
            int lastRow = sheet.getLastRowNum();

            for (CellRangeAddress range : rule.getRegions()) {
                for (int r = range.getFirstRow(); r <= Math.min(range.getLastRow(), lastRow); r++) {
                    Row row = sheet.getRow(r);

                    if (row == null) {
                        continue;
                    }

                    int lastColumn = Math.min(range.getLastColumn(), row.getLastCellNum() - 1);

                    for (int c = range.getFirstColumn(); c <= lastColumn; c++) {
                        Double number = numberAt(sheet, r, c);

                        if (number != null) {
                            numbers.add(number);
                        }
                    }
                }
            }

            double[] sorted = numbers.stream().mapToDouble(Double::doubleValue).toArray();
            Arrays.sort(sorted);
            return sorted;
        });
    }

    // Where a threshold (lowest, highest, a number, percent or percentile) falls.
    private static double thresholdValue(ConditionalFormattingThreshold threshold, double[] sorted, boolean low) {
        double min = sorted[0];
        double max = sorted[sorted.length - 1];
        Double value = threshold.getValue();
        ConditionalFormattingThreshold.RangeType type = threshold.getRangeType();

        if (type == null) {
            return low ? min : max;
        }

        return switch (type) {
            case MIN -> min;
            case MAX -> max;
            case NUMBER -> value != null ? value : (low ? min : max);
            case PERCENT -> min + (max - min) * (value != null ? value : 0) / 100.0;
            case PERCENTILE -> percentile(sorted, value != null ? value : 50);
            default -> low ? min : max;
        };
    }

    // Like Excel's PERCENTILE.INC.
    private static double percentile(double[] sorted, double percent) {
        double rank = Math.max(0, Math.min(100, percent)) / 100.0 * (sorted.length - 1);
        int below = (int) Math.floor(rank);
        int above = Math.min(sorted.length - 1, below + 1);

        return sorted[below] + (sorted[above] - sorted[below]) * (rank - below);
    }

    private int colorScaleColor(XSSFSheet sheet, int row, int column,
                                EvaluationConditionalFormatRule rule, XSSFColorScaleFormatting scale) {
        Double number = numberAt(sheet, row, column);

        if (number == null || scale == null) {
            return -1;
        }

        double[] sorted = valuesOf(sheet, rule);
        XSSFConditionalFormattingThreshold[] thresholds = scale.getThresholds();
        XSSFColor[] colors = scale.getColors();
        int points = Math.min(thresholds.length, colors.length);

        if (sorted.length == 0 || points < 2) {
            return -1;
        }

        double[] stops = new double[points];

        for (int i = 0; i < points; i++) {
            stops[i] = thresholdValue(thresholds[i], sorted, i == 0);
        }

        if (number <= stops[0]) {
            return WorkbookManager.rgbOf(colors[0]);
        }

        for (int i = 1; i < points; i++) {
            if (number <= stops[i]) {
                double span = stops[i] - stops[i - 1];
                double t = span <= 0 ? 1 : (number - stops[i - 1]) / span;

                return mix(WorkbookManager.rgbOf(colors[i - 1]), WorkbookManager.rgbOf(colors[i]), t);
            }
        }

        return WorkbookManager.rgbOf(colors[points - 1]);
    }

    private double dataBarShare(XSSFSheet sheet, int row, int column,
                                EvaluationConditionalFormatRule rule, XSSFDataBarFormatting dataBar) {
        Double number = numberAt(sheet, row, column);

        if (number == null || dataBar == null) {
            return -1;
        }

        double[] sorted = valuesOf(sheet, rule);

        if (sorted.length == 0) {
            return -1;
        }

        double low = thresholdValue(dataBar.getMinThreshold(), sorted, true);
        double high = thresholdValue(dataBar.getMaxThreshold(), sorted, false);
        double t = high <= low ? 1 : (number - low) / (high - low);

        return BAR_MIN + (BAR_MAX - BAR_MIN) * Math.max(0, Math.min(1, t));
    }

    private static int mix(int from, int to, double t) {
        if (from < 0 || to < 0) {
            return from < 0 ? to : from;
        }

        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);

        return (r << 16) | (g << 8) | b;
    }

    private static XSSFColor color(int rgb) {
        return new XSSFColor(new byte[]{(byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb}, null);
    }
}
