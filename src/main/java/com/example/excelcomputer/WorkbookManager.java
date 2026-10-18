package com.example.excelcomputer;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class WorkbookManager implements AutoCloseable {
    // Plain decimal numbers only (so "1d", "NaN" or hex floats stay text).
    private static final Pattern NUMBER =
            Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    private final Workbook workbook;
    private final DataFormatter formatter = new DataFormatter();
    private final FormulaEvaluator evaluator;

    // The formatting of each cell style in the workbook, looked up once per style.
    private final Map<Integer, CellFormat> formatCache = new HashMap<>();
    // Styles already created by a formatting change, so repeating one reuses the style.
    private final Map<String, Integer> derivedStyles = new HashMap<>();

    private WorkbookManager(Workbook workbook) {
        this.workbook = workbook;
        this.evaluator = workbook.getCreationHelper().createFormulaEvaluator();
    }

    public static WorkbookManager open(Path path) throws IOException {
        // Only .xlsx files are listed, so the .xlsx reader is used directly.
        // (WorkbookFactory looks its readers up with a service lookup, which
        // does not find them when the mod is loaded inside some modpacks.)
        try (InputStream input = Files.newInputStream(path)) {
            return new WorkbookManager(new XSSFWorkbook(input));
        } catch (RuntimeException exception) {
            // POI reports unreadable files with several different exception types.
            throw new IOException(
                    "The workbook is password-protected, damaged or not a valid .xlsx file ("
                            + exception.getClass().getSimpleName() + ")",
                    exception
            );
        } catch (LinkageError error) {
            // A missing or mismatched library (for example NoSuchMethodError)
            // is reported on screen instead of crashing the game.
            throw new IOException(
                    "A library the mod needs could not be loaded ("
                            + error.getClass().getSimpleName() + ")",
                    error
            );
        }
    }

    public int getSheetCount() {
        return workbook.getNumberOfSheets();
    }

    public String getSheetName(int index) {
        return workbook.getSheetName(index);
    }

    // ----- cell formatting (fill color, text color, bold, ...) -----

    /** How a cell is formatted. Cells without any formatting get the defaults. */
    public CellFormat getCellFormat(int sheetIndex, int rowIndex, int columnIndex) {
        Cell cell = findCell(sheetIndex, rowIndex, columnIndex);

        if (cell == null) {
            return CellFormat.DEFAULT;
        }

        try {
            CellFormat base = formatOfStyle(cell.getCellStyle().getIndex());
            CellType type = cell.getCellType();
            boolean numeric = type == CellType.NUMERIC
                    || (type == CellType.FORMULA
                    && cell.getCachedFormulaResultType() == CellType.NUMERIC);

            return numeric == base.numeric() ? base : base.withNumeric(numeric);
        } catch (RuntimeException exception) {
            return CellFormat.DEFAULT;
        }
    }

    /**
     * Applies a formatting change to one cell. Returns {previousStyle, newStyle}
     * (needed for undo), or null if the cell already looked like that.
     */
    public int[] applyFormat(
            int sheetIndex,
            int rowIndex,
            int columnIndex,
            CellFormat.Change change
    ) {
        Cell existing = findCell(sheetIndex, rowIndex, columnIndex);
        int previous = existing == null ? 0 : existing.getCellStyle().getIndex();
        int target = deriveStyle(previous, change);

        if (target == previous) {
            return null;
        }

        getOrCreateCell(sheetIndex, rowIndex, columnIndex)
                .setCellStyle(workbook.getCellStyleAt(target));

        return new int[] {previous, target};
    }

    /** Puts a cell back to a style index (used by undo and redo). */
    public void setCellStyleIndex(int sheetIndex, int rowIndex, int columnIndex, int styleIndex) {
        getOrCreateCell(sheetIndex, rowIndex, columnIndex)
                .setCellStyle(workbook.getCellStyleAt(styleIndex));
    }

    private Cell getOrCreateCell(int sheetIndex, int rowIndex, int columnIndex) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);
        Row row = sheet.getRow(rowIndex);

        if (row == null) {
            row = sheet.createRow(rowIndex);
        }

        Cell cell = row.getCell(columnIndex);

        if (cell == null) {
            cell = row.createCell(columnIndex);
        }

        return cell;
    }

    private CellFormat formatOfStyle(int styleIndex) {
        CellFormat cached = formatCache.get(styleIndex);

        if (cached == null) {
            cached = buildFormat((XSSFCellStyle) workbook.getCellStyleAt(styleIndex));
            formatCache.put(styleIndex, cached);
        }

        return cached;
    }

    private static CellFormat buildFormat(XSSFCellStyle style) {
        int fill = -1;

        if (style.getFillPattern() == FillPatternType.SOLID_FOREGROUND) {
            fill = rgbOf(style.getFillForegroundXSSFColor());
        }

        XSSFFont font = style.getFont();

        return new CellFormat(
                fill,
                rgbOf(font.getXSSFColor()),
                font.getBold(),
                font.getItalic(),
                font.getUnderline() != Font.U_NONE,
                Math.max(1, Math.round(font.getFontHeightInPoints())),
                alignmentCode(style.getAlignment()),
                false
        );
    }

    // The color as 0xRRGGBB (theme colors and tints resolved), or -1 for none/automatic.
    private static int rgbOf(XSSFColor color) {
        if (color == null || (color.isIndexed() && color.getIndexed() >= 64)) {
            return -1;
        }

        byte[] rgb = color.getRGBWithTint();

        if (rgb == null) {
            rgb = color.getRGB();
        }

        if (rgb == null || rgb.length < 3) {
            return -1;
        }

        int n = rgb.length;

        return ((rgb[n - 3] & 0xFF) << 16) | ((rgb[n - 2] & 0xFF) << 8) | (rgb[n - 1] & 0xFF);
    }

    private static XSSFColor color(int rgb) {
        return new XSSFColor(
                new byte[] {(byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb},
                null
        );
    }

    private static int alignmentCode(HorizontalAlignment alignment) {
        if (alignment == null) {
            return 0;
        }

        switch (alignment) {
            case LEFT:
            case FILL:
            case JUSTIFY:
            case DISTRIBUTED:
                return 1;
            case CENTER:
            case CENTER_SELECTION:
                return 2;
            case RIGHT:
                return 3;
            default:
                return 0;
        }
    }

    private static HorizontalAlignment alignmentOf(int code) {
        switch (code) {
            case 1:
                return HorizontalAlignment.LEFT;
            case 2:
                return HorizontalAlignment.CENTER;
            case 3:
                return HorizontalAlignment.RIGHT;
            default:
                return HorizontalAlignment.GENERAL;
        }
    }

    private static boolean alreadyLooksLike(CellFormat format, CellFormat.Change change) {
        switch (change.kind()) {
            case FILL:
                return format.fill() == change.value();
            case FONT_COLOR:
                return format.fontColor() == change.value();
            case BOLD:
                return format.bold() == (change.value() != 0);
            case ITALIC:
                return format.italic() == (change.value() != 0);
            case UNDERLINE:
                return format.underline() == (change.value() != 0);
            case FONT_SIZE:
                return format.fontPoints() == change.value();
            case ALIGN:
                return format.hAlign() == change.value();
            default:
                return false;
        }
    }

    // The index of the style that is the given style plus the change. Created once, then reused.
    private int deriveStyle(int baseIndex, CellFormat.Change change) {
        if (change.kind() == CellFormat.Kind.RESET) {
            return 0;
        }

        if (alreadyLooksLike(formatOfStyle(baseIndex), change)) {
            return baseIndex;
        }

        String key = baseIndex + "|" + change.kind() + ":" + change.value();
        Integer known = derivedStyles.get(key);

        if (known != null) {
            return known;
        }

        XSSFWorkbook book = (XSSFWorkbook) workbook;
        XSSFCellStyle base = book.getCellStyleAt(baseIndex);
        XSSFCellStyle style = book.createCellStyle();

        // Copies everything (borders, number format, ...) so only the change differs.
        style.cloneStyleFrom(base);

        switch (change.kind()) {
            case FILL:
                if (change.value() < 0) {
                    style.setFillPattern(FillPatternType.NO_FILL);
                } else {
                    style.setFillForegroundColor(color(change.value()));
                    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                }
                break;
            case ALIGN:
                style.setAlignment(alignmentOf(change.value()));
                break;
            default:
                style.setFont(deriveFont(base.getFont(), change));
                break;
        }

        int index = style.getIndex();
        derivedStyles.put(key, index);

        return index;
    }

    // A copy of the font with the change applied.
    private XSSFFont deriveFont(XSSFFont base, CellFormat.Change change) {
        XSSFFont font = ((XSSFWorkbook) workbook).createFont();

        font.setFontName(base.getFontName());
        font.setFontHeightInPoints(base.getFontHeightInPoints());
        font.setBold(base.getBold());
        font.setItalic(base.getItalic());
        font.setUnderline(base.getUnderline());
        font.setStrikeout(base.getStrikeout());
        font.setFamily(base.getFamily());
        font.setCharSet(base.getCharSet());

        XSSFColor baseColor = base.getXSSFColor();

        if (change.kind() != CellFormat.Kind.FONT_COLOR && baseColor != null) {
            font.setColor(baseColor);
        }

        switch (change.kind()) {
            case FONT_COLOR:
                if (change.value() >= 0) {
                    font.setColor(color(change.value()));
                } else {
                    font.setColor(IndexedColors.AUTOMATIC.getIndex());
                }
                break;
            case BOLD:
                font.setBold(change.value() != 0);
                break;
            case ITALIC:
                font.setItalic(change.value() != 0);
                break;
            case UNDERLINE:
                font.setUnderline(change.value() != 0 ? Font.U_SINGLE : Font.U_NONE);
                break;
            case FONT_SIZE:
                font.setFontHeightInPoints((short) change.value());
                break;
            default:
                break;
        }

        return font;
    }

    // ----- column widths and row heights -----
    // Excel stores sizes in its own units. These convert them to and from pixels
    // (7 pixels per character of width, 4/3 pixel per point of height), the same
    // way POI does, so sizes survive a save and reopen.

    private static final int MAX_COLUMN_UNITS = 255 * 256;

    /** Column width in pixels, as stored in the file (or the sheet's default). */
    public int getColumnWidthPixels(int sheetIndex, int columnIndex) {
        try {
            return Math.max(1, Math.round(
                    workbook.getSheetAt(sheetIndex).getColumnWidthInPixels(columnIndex)));
        } catch (RuntimeException exception) {
            return 64;
        }
    }

    /** Row height in pixels, as stored in the file (or the sheet's default). */
    public int getRowHeightPixels(int sheetIndex, int rowIndex) {
        try {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            Row row = sheet.getRow(rowIndex);
            float points = row != null
                    ? row.getHeightInPoints()
                    : sheet.getDefaultRowHeightInPoints();

            return Math.max(1, Math.round(points * 4f / 3f));
        } catch (RuntimeException exception) {
            return 20;
        }
    }

    public void setColumnWidthPixels(int sheetIndex, int columnIndex, int pixels) {
        int units = (int) Math.round(pixels * 256.0 / 7.0);

        workbook.getSheetAt(sheetIndex).setColumnWidth(
                columnIndex, Math.max(0, Math.min(MAX_COLUMN_UNITS, units)));
    }

    public void setRowHeightPixels(int sheetIndex, int rowIndex, int pixels) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);
        Row row = sheet.getRow(rowIndex);

        if (row == null) {
            row = sheet.createRow(rowIndex);
        }

        row.setHeightInPoints(pixels * 0.75f);
    }

    private Cell findCell(int sheetIndex, int rowIndex, int columnIndex) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);
        Row row = sheet.getRow(rowIndex);

        if (row == null) {
            return null;
        }

        return row.getCell(columnIndex);
    }

    /**
     * The text shown in the edit box: formulas as "=...", numbers as their
     * raw value (not the rounded display format), booleans and text as-is.
     */
    public String getCellInput(
            int sheetIndex,
            int rowIndex,
            int columnIndex
    ) {
        Cell cell = findCell(sheetIndex, rowIndex, columnIndex);

        if (cell == null) {
            return "";
        }

        try {
            switch (cell.getCellType()) {
                case FORMULA:
                    return "=" + cell.getCellFormula();
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        return getCellText(sheetIndex, rowIndex, columnIndex);
                    }
                    return BigDecimal.valueOf(cell.getNumericCellValue())
                            .stripTrailingZeros()
                            .toPlainString();
                case BOOLEAN:
                    return cell.getBooleanCellValue() ? "TRUE" : "FALSE";
                case STRING:
                    return cell.getStringCellValue();
                case BLANK:
                    return "";
                default:
                    return getCellText(sheetIndex, rowIndex, columnIndex);
            }
        } catch (RuntimeException exception) {
            return getCellText(sheetIndex, rowIndex, columnIndex);
        }
    }

    /** The formatted text shown inside the grid cell. */
    public String getCellText(
            int sheetIndex,
            int rowIndex,
            int columnIndex
    ) {
        Cell cell = findCell(sheetIndex, rowIndex, columnIndex);

        if (cell == null) {
            return "";
        }

        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException exception) {
            return "#ERROR!";
        }
    }

    public void setCellValue(
            int sheetIndex,
            int rowIndex,
            int columnIndex,
            String value
    ) {
        setCellValueDeferred(sheetIndex, rowIndex, columnIndex, value);
        recalculate();
    }

    /** Writes a cell without recalculating. Call recalculate() afterwards. */
    public void setCellValueDeferred(
            int sheetIndex,
            int rowIndex,
            int columnIndex,
            String value
    ) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);

        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }

        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            cell = row.createCell(columnIndex);
        }

        writeValue(cell, value);
    }

    /** Recalculates all formulas when possible. */
    public void recalculate() {
        try {
            evaluator.clearAllCachedResultValues();
            evaluator.evaluateAll();
        } catch (RuntimeException exception) {
            // Keep the entered values if evaluation is unsupported.
        }
    }

    private static void writeValue(Cell cell, String value) {
        String text = value.trim();

        if (text.startsWith("=") && text.length() > 1) {
            // The formula is validated before the cell changes, so an
            // invalid formula throws and leaves the old content untouched.
            cell.setCellFormula(text.substring(1));
            return;
        }

        // Clears any old formula or value before writing the new one.
        cell.setBlank();

        if (text.isEmpty()) {
            return;
        }

        if (text.equalsIgnoreCase("TRUE") || text.equalsIgnoreCase("FALSE")) {
            cell.setCellValue(text.toUpperCase(Locale.ROOT).equals("TRUE"));
        } else if (NUMBER.matcher(text).matches()) {
            cell.setCellValue(Double.parseDouble(text));
        } else {
            cell.setCellValue(value);
        }
    }

    /**
     * Writes to a temporary file first, then moves it over the original,
     * so a failed save can't corrupt the existing workbook.
     */
    public void save(Path path) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");

        try (OutputStream output = Files.newOutputStream(temp)) {
            workbook.write(output);
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temp);
            throw exception;
        } catch (LinkageError error) {
            Files.deleteIfExists(temp);
            throw new IOException(
                    "A library the mod needs could not be loaded ("
                            + error.getClass().getSimpleName() + ")",
                    error
            );
        }

        try {
            Files.move(
                    temp, path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void close() throws IOException {
        workbook.close();
    }
}
