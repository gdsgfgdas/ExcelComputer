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
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTAutoFilter;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTFilter;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTFilterColumn;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTFilters;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTable;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTableColumn;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTableColumns;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTableStyleInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WorkbookManager implements AutoCloseable {
    // Plain decimal numbers only (so "1d", "NaN" or hex floats stay text).
    private static final Pattern NUMBER =
            Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    private final Workbook workbook;
    private final DataFormatter formatter = new DataFormatter();
    private final FormulaEvaluator evaluator;
    private final boolean csvMode;
    private final List<List<String>> csvRows = new ArrayList<>();
    // The formatting of each cell style in the workbook, looked up once per style.
    private final Map<Integer, CellFormat> formatCache = new HashMap<>();
    // Styles already created by a formatting change, so repeating one reuses the style.
    private final Map<String, Integer> derivedStyles = new HashMap<>();

    private WorkbookManager(Workbook workbook) {
        this.workbook = workbook;
        this.evaluator = workbook == null
                ? null
                : workbook.getCreationHelper().createFormulaEvaluator();
        this.csvMode = workbook == null;
    }

    public static WorkbookManager open(Path path) throws IOException {
        String filename = path.getFileName()
                .toString()
                .toLowerCase(Locale.ROOT);

        if (filename.endsWith(".csv")) {
            WorkbookManager manager = new WorkbookManager(null);

            try {
                manager.csvRows.addAll(readCsv(path));
                return manager;
            } catch (IOException | RuntimeException exception) {
                manager.close();
                throw exception;
            }
        }

        try (InputStream input = Files.newInputStream(path)) {
            return new WorkbookManager(new XSSFWorkbook(input));
        } catch (RuntimeException exception) {
            throw new IOException(
                    "The workbook is password-protected, damaged or not a valid .xlsx file ("
                            + exception.getClass().getSimpleName() + ")",
                    exception
            );
        } catch (LinkageError error) {
            throw new IOException(
                    "A library the mod needs could not be loaded ("
                            + error.getClass().getSimpleName() + ")",
                    error
            );
        }
    }

    private static List<List<String>> readCsv(Path path) throws IOException {
        List<List<String>> rows = new ArrayList<>();

        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            StringBuilder field = new StringBuilder();
            List<String> row = new ArrayList<>();
            boolean insideQuotes = false;
            int current;

            while ((current = reader.read()) != -1) {
                char c = (char) current;

                if (insideQuotes) {
                    if (c == '"') {
                        reader.mark(1);
                        int next = reader.read();

                        if (next == '"') {
                            field.append('"');
                        } else {
                            insideQuotes = false;

                            if (next != -1) {
                                reader.reset();
                            }
                        }
                    } else {
                        field.append(c);
                    }
                } else if (c == '"') {
                    insideQuotes = true;
                } else if (c == ',') {
                    row.add(field.toString());
                    field.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    row.add(field.toString());
                    field.setLength(0);

                    rows.add(row);
                    row = new ArrayList<>();

                    if (c == '\r') {
                        reader.mark(1);
                        int next = reader.read();

                        if (next != '\n' && next != -1) {
                            reader.reset();
                        }
                    }
                } else {
                    field.append(c);
                }
            }

            if (insideQuotes) {
                throw new IOException("The CSV file contains an unclosed quoted field.");
            }

            if (!row.isEmpty() || field.length() > 0) {
                row.add(field.toString());
                rows.add(row);
            }
        }

        return rows;
    }

    public int getSheetCount() {
        return csvMode ? 1 : workbook.getNumberOfSheets();
    }

    public String getSheetName(int index) {
        if (csvMode) {
            if (index != 0) {
                throw new IndexOutOfBoundsException("CSV files only have one sheet.");
            }

            return "CSV Data";
        }

        return workbook.getSheetName(index);
    }

    // ----- cell formatting (fill color, text color, bold, ...) -----

    /**
     * How a cell is formatted. Cells without any formatting get the defaults.
     */
    public CellFormat getCellFormat(int sheetIndex, int rowIndex, int columnIndex) {
        if (csvMode) return CellFormat.DEFAULT;
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
        if (csvMode) return null;
        Cell existing = findCell(sheetIndex, rowIndex, columnIndex);
        int previous = existing == null ? 0 : existing.getCellStyle().getIndex();
        CellFormat.Change effective = change.kind() == CellFormat.Kind.DECIMALS
                ? decimalChange(existing, previous, change.value())
                : change;
        int target = deriveStyle(previous, effective);

        if (target == previous) {
            return null;
        }

        getOrCreateCell(sheetIndex, rowIndex, columnIndex)
                .setCellStyle(workbook.getCellStyleAt(target));

        return new int[]{previous, target};
    }

    /**
     * Puts a cell back to a style index (used by undo and redo).
     */
    public void setCellStyleIndex(int sheetIndex, int rowIndex, int columnIndex, int styleIndex) {
        if (csvMode) return;
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
                style.getDataFormatString() == null ? "General" : style.getDataFormatString(),
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
                new byte[]{(byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb},
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
            case NUMBER_FORMAT:
                return format.numberFormat().equals(change.text());
            default:
                return false;
        }
    }

    // ----- increase / decrease decimal places -----

    // Works out the new number format for "more decimals" (+1) or "fewer decimals" (-1).
    private CellFormat.Change decimalChange(Cell cell, int styleIndex, int delta) {
        String format = formatOfStyle(styleIndex).numberFormat();
        String updated;

        if ("General".equalsIgnoreCase(format)) {
            // Start from the decimals the cell shows now.
            int decimals = displayedDecimals(cell);
            int wanted = Math.max(0, decimals + delta);

            updated = wanted == decimals ? "General"
                    : wanted == 0 ? "0" : "0." + "0".repeat(wanted);
        } else {
            updated = adjustDecimals(format, delta);
        }

        return new CellFormat.Change(CellFormat.Kind.NUMBER_FORMAT, 0, updated);
    }

    private int displayedDecimals(Cell cell) {
        if (cell == null) {
            return 0;
        }

        String text;

        try {
            text = formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException exception) {
            return 0;
        }

        int dot = text.indexOf('.');
        int count = 0;

        if (dot < 0) {
            return 0;
        }

        for (int i = dot + 1; i < text.length() && Character.isDigit(text.charAt(i)); i++) {
            count++;
        }

        return count;
    }

    private static String adjustDecimals(String format, int delta) {
        String[] sections = format.split(";", -1);

        for (int i = 0; i < sections.length; i++) {
            sections[i] = adjustSection(sections[i], delta);
        }

        return String.join(";", sections);
    }

    // Adds or removes one 0 after the decimal point of one section of a format code.
    private static String adjustSection(String section, int delta) {
        boolean quoted = false;
        boolean bracketed = false;
        int dot = -1;
        int lastDigit = -1;

        for (int i = 0; i < section.length(); i++) {
            char c = section.charAt(i);

            if (c == '"') {
                quoted = !quoted;
            } else if (quoted) {
                continue;
            } else if (c == '[') {
                bracketed = true;
            } else if (c == ']') {
                bracketed = false;
            } else if (bracketed) {
                continue;
            } else if ((c == 'E' || c == 'e') && i + 1 < section.length()
                    && (section.charAt(i + 1) == '+' || section.charAt(i + 1) == '-')) {
                break;
            } else if (c == '.' && dot < 0) {
                dot = i;
            } else if (c == '0' || c == '#') {
                lastDigit = i;
            } else if ("ydhsYDHS".indexOf(c) >= 0) {
                return section; // a date or time: nothing to change
            }
        }

        if (lastDigit < 0) {
            return section;
        }

        int zeros = 0;

        if (dot >= 0) {
            while (dot + 1 + zeros < section.length() && section.charAt(dot + 1 + zeros) == '0') {
                zeros++;
            }
        }

        if (delta > 0) {
            if (dot >= 0) {
                int at = dot + 1 + zeros;

                return section.substring(0, at) + "0" + section.substring(at);
            }

            return section.substring(0, lastDigit + 1) + ".0" + section.substring(lastDigit + 1);
        }

        if (dot >= 0 && zeros > 0) {
            String decimals = zeros > 1 ? "." + "0".repeat(zeros - 1) : "";

            return section.substring(0, dot) + decimals + section.substring(dot + 1 + zeros);
        }

        return section;
    }

    // The index of the style that is the given style plus the change. Created once, then reused.
    private int deriveStyle(int baseIndex, CellFormat.Change change) {
        if (change.kind() == CellFormat.Kind.RESET) {
            return 0;
        }

        if (alreadyLooksLike(formatOfStyle(baseIndex), change)) {
            return baseIndex;
        }

        String key = baseIndex + "|" + change.kind() + ":" + change.value() + ":" + change.text();
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
            case NUMBER_FORMAT:
                style.setDataFormat(book.createDataFormat().getFormat(change.text()));
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

    // ----- tables and filters -----

    // Tables and filter ranges per sheet; cleared whenever one is added, removed or changed.
    private final Map<Integer, List<TableInfo>> tableCache = new HashMap<>();

    /**
     * The tables on a sheet, then its plain filter range (if it has one).
     */
    public List<TableInfo> getTables(int sheetIndex) {
        List<TableInfo> cached = tableCache.get(sheetIndex);

        if (cached == null) {
            try {
                cached = buildTables(sheetIndex);
            } catch (RuntimeException | LinkageError exception) {
                cached = List.of();
            }

            tableCache.put(sheetIndex, cached);
        }

        return cached;
    }

    /**
     * The table or filter range that contains this cell, or null.
     */
    public TableInfo tableAt(int sheetIndex, int rowIndex, int columnIndex) {
        for (TableInfo table : getTables(sheetIndex)) {
            if (table.contains(rowIndex, columnIndex)) {
                return table;
            }
        }

        return null;
    }

    private List<TableInfo> buildTables(int sheetIndex) {
        XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(sheetIndex);
        List<TableInfo> result = new ArrayList<>();

        for (XSSFTable table : sheet.getTables()) {
            CellReference start = table.getStartCellReference();
            CellReference end = table.getEndCellReference();

            if (start == null || end == null) {
                continue;
            }

            CTTable ct = table.getCTTable();
            CTAutoFilter autoFilter = ct.getAutoFilter();
            CTTableStyleInfo styleInfo = ct.getTableStyleInfo();

            result.add(new TableInfo(
                    table.getName(),
                    start.getRow(), start.getCol(), end.getRow(), end.getCol(),
                    autoFilter != null,
                    styleInfo == null || styleInfo.getName() == null ? "" : styleInfo.getName(),
                    styleInfo == null || styleInfo.getShowRowStripes(),
                    filteredColumns(autoFilter)
            ));
        }

        CTAutoFilter sheetFilter = sheet.getCTWorksheet().getAutoFilter();

        if (sheetFilter != null && sheetFilter.getRef() != null) {
            CellRangeAddress range = CellRangeAddress.valueOf(sheetFilter.getRef());

            result.add(new TableInfo(
                    "",
                    range.getFirstRow(), range.getFirstColumn(),
                    range.getLastRow(), range.getLastColumn(),
                    true, "", false, filteredColumns(sheetFilter)
            ));
        }

        return result;
    }

    private static Set<Integer> filteredColumns(CTAutoFilter autoFilter) {
        Set<Integer> columns = new HashSet<>();

        if (autoFilter != null) {
            for (CTFilterColumn column : autoFilter.getFilterColumnList()) {
                columns.add((int) column.getColId());
            }
        }

        return columns;
    }

    private CTAutoFilter autoFilterOf(int sheetIndex, TableInfo info) {
        XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(sheetIndex);

        if (info.isTable()) {
            for (XSSFTable table : sheet.getTables()) {
                if (info.name().equals(table.getName())) {
                    return table.getCTTable().getAutoFilter();
                }
            }

            return null;
        }

        return sheet.getCTWorksheet().getAutoFilter();
    }

    /**
     * What the filter on one column of a table does right now.
     */
    public TableInfo.FilterState getFilterState(int sheetIndex, TableInfo info, int columnOffset) {
        CTAutoFilter autoFilter = autoFilterOf(sheetIndex, info);

        if (autoFilter == null) {
            return TableInfo.FilterState.NONE;
        }

        for (CTFilterColumn column : autoFilter.getFilterColumnList()) {
            if (column.getColId() != columnOffset) {
                continue;
            }

            // Only plain "tick these values" filters can be edited here.
            if (!column.isSetFilters() || column.getFilters().sizeOfDateGroupItemArray() > 0) {
                return new TableInfo.FilterState(true, true, Set.of());
            }

            Set<String> allowed = new HashSet<>();

            for (CTFilter filter : column.getFilters().getFilterList()) {
                allowed.add(filter.getVal());
            }

            if (column.getFilters().getBlank()) {
                allowed.add("");
            }

            return new TableInfo.FilterState(true, false, allowed);
        }

        return TableInfo.FilterState.NONE;
    }

    private static final Comparator<String> FILTER_VALUE_ORDER = (a, b) -> {
        if (a.isEmpty() || b.isEmpty()) {
            return a.isEmpty() ? (b.isEmpty() ? 0 : 1) : -1; // blanks last
        }

        boolean numberA = NUMBER.matcher(a.replace(",", "").replace("$", "").replace("%", "")).matches();
        boolean numberB = NUMBER.matcher(b.replace(",", "").replace("$", "").replace("%", "")).matches();

        if (numberA && numberB) {
            return Double.compare(
                    Double.parseDouble(a.replace(",", "").replace("$", "").replace("%", "")),
                    Double.parseDouble(b.replace(",", "").replace("$", "").replace("%", "")));
        }

        if (numberA != numberB) {
            return numberA ? -1 : 1; // numbers before text
        }

        return String.CASE_INSENSITIVE_ORDER.compare(a, b);
    };

    /**
     * The different values in one column of a table (as shown in the cells), sorted.
     */
    public List<String> getDistinctValues(int sheetIndex, TableInfo info, int columnOffset, int limit) {
        Set<String> seen = new LinkedHashSet<>();
        int column = info.left() + columnOffset;

        for (int row = info.top() + 1; row <= info.bottom() && seen.size() < limit; row++) {
            seen.add(getCellText(sheetIndex, row, column));
        }

        List<String> values = new ArrayList<>(seen);
        values.sort(FILTER_VALUE_ORDER);

        return values;
    }

    /**
     * Sets which values one column shows (null shows all of them) and hides the
     * table rows that do not match any column's filter.
     */
    public void setColumnFilter(int sheetIndex, TableInfo info, int columnOffset, Set<String> allowed) {
        CTAutoFilter autoFilter = autoFilterOf(sheetIndex, info);

        if (autoFilter == null) {
            throw new IllegalStateException("There are no filter buttons here");
        }

        int width = info.right() - info.left() + 1;

        for (int column = 0; column < width; column++) {
            if (column != columnOffset && getFilterState(sheetIndex, info, column).custom()) {
                throw new IllegalStateException(
                        "Another column has a filter that can't be edited here. Use the Filter button to clear it.");
            }
        }

        for (int i = autoFilter.sizeOfFilterColumnArray() - 1; i >= 0; i--) {
            if (autoFilter.getFilterColumnArray(i).getColId() == columnOffset) {
                autoFilter.removeFilterColumn(i);
            }
        }

        if (allowed != null) {
            CTFilterColumn filterColumn = autoFilter.addNewFilterColumn();
            filterColumn.setColId(columnOffset);

            CTFilters filters = filterColumn.addNewFilters();

            if (allowed.contains("")) {
                filters.setBlank(true);
            }

            List<String> sorted = new ArrayList<>(allowed);
            sorted.sort(FILTER_VALUE_ORDER);

            for (String value : sorted) {
                if (!value.isEmpty()) {
                    filters.addNewFilter().setVal(value);
                }
            }
        }

        applyRowVisibility(sheetIndex, info);
        tableCache.remove(sheetIndex);
    }

    // Hides the rows the filters leave out and shows the rest.
    private void applyRowVisibility(int sheetIndex, TableInfo info) {
        Map<Integer, Set<String>> criteria = new HashMap<>();

        for (int column = 0; column <= info.right() - info.left(); column++) {
            TableInfo.FilterState state = getFilterState(sheetIndex, info, column);

            if (state.active() && !state.custom()) {
                criteria.put(column, state.allowed());
            }
        }

        Sheet sheet = workbook.getSheetAt(sheetIndex);

        for (int rowIndex = info.top() + 1; rowIndex <= info.bottom(); rowIndex++) {
            boolean visible = true;

            for (Map.Entry<Integer, Set<String>> criterion : criteria.entrySet()) {
                String text = getCellText(sheetIndex, rowIndex, info.left() + criterion.getKey());

                if (!criterion.getValue().contains(text)) {
                    visible = false;
                    break;
                }
            }

            Row row = sheet.getRow(rowIndex);

            if (row == null) {
                if (visible) {
                    continue;
                }

                row = sheet.createRow(rowIndex);
            }

            row.setZeroHeight(!visible);
        }
    }

    private void showAllRows(int sheetIndex, TableInfo info) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);

        for (int rowIndex = info.top() + 1; rowIndex <= info.bottom(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);

            if (row != null && row.getZeroHeight()) {
                row.setZeroHeight(false);
            }
        }
    }

    /**
     * Shows or hides a table's filter buttons (turning them off also clears its filters).
     */
    public void setFilterButtons(int sheetIndex, TableInfo info, boolean on) {
        if (!info.isTable()) {
            removeFilterRange(sheetIndex, info);
            return;
        }

        XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(sheetIndex);

        for (XSSFTable table : sheet.getTables()) {
            if (!info.name().equals(table.getName())) {
                continue;
            }

            CTTable ct = table.getCTTable();

            if (on) {
                if (ct.getAutoFilter() == null) {
                    ct.addNewAutoFilter().setRef(
                            new CellRangeAddress(info.top(), info.bottom(), info.left(), info.right())
                                    .formatAsString());
                }
            } else if (ct.getAutoFilter() != null) {
                ct.unsetAutoFilter();
                showAllRows(sheetIndex, info);
            }
        }

        tableCache.remove(sheetIndex);
    }

    /**
     * Puts filter buttons on the top row of a range that is not a table.
     */
    public void addFilterRange(int sheetIndex, int top, int left, int bottom, int right) {
        if (bottom == top && left == right) {
            throw new IllegalArgumentException("Select the cells to filter first");
        }

        ((XSSFSheet) workbook.getSheetAt(sheetIndex))
                .setAutoFilter(new CellRangeAddress(top, Math.max(top + 1, bottom), left, right));
        tableCache.remove(sheetIndex);
    }

    public void removeFilterRange(int sheetIndex, TableInfo info) {
        XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(sheetIndex);

        if (sheet.getCTWorksheet().getAutoFilter() != null) {
            sheet.getCTWorksheet().unsetAutoFilter();
        }

        showAllRows(sheetIndex, info);
        tableCache.remove(sheetIndex);
    }

    // ----- making and removing tables -----

    private boolean hasContent(Sheet sheet, int rowIndex, int columnIndex) {
        if (rowIndex < 0 || columnIndex < 0) {
            return false;
        }

        Row row = sheet.getRow(rowIndex);
        Cell cell = row == null ? null : row.getCell(columnIndex);

        return cell != null
                && cell.getCellType() != CellType.BLANK
                && !(cell.getCellType() == CellType.STRING && cell.getStringCellValue().isEmpty());
    }

    private boolean rowHasContent(Sheet sheet, int rowIndex, int fromColumn, int toColumn) {
        for (int column = Math.max(0, fromColumn); column <= toColumn; column++) {
            if (hasContent(sheet, rowIndex, column)) {
                return true;
            }
        }

        return false;
    }

    private boolean columnHasContent(Sheet sheet, int columnIndex, int fromRow, int toRow) {
        for (int row = Math.max(0, fromRow); row <= toRow; row++) {
            if (hasContent(sheet, row, columnIndex)) {
                return true;
            }
        }

        return false;
    }

    /**
     * The block of filled cells around a cell, like Excel finds when you make a table from one cell.
     */
    public int[] detectRegion(int sheetIndex, int rowIndex, int columnIndex) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);
        int top = rowIndex;
        int bottom = rowIndex;
        int left = columnIndex;
        int right = columnIndex;
        boolean grew = true;

        for (int pass = 0; grew && pass < 5000; pass++) {
            grew = false;

            if (top > 0 && rowHasContent(sheet, top - 1, left - 1, right + 1)) {
                top--;
                grew = true;
            }

            if (rowHasContent(sheet, bottom + 1, left - 1, right + 1)) {
                bottom++;
                grew = true;
            }

            if (left > 0 && columnHasContent(sheet, left - 1, top - 1, bottom + 1)) {
                left--;
                grew = true;
            }

            if (columnHasContent(sheet, right + 1, top - 1, bottom + 1)) {
                right++;
                grew = true;
            }
        }

        return new int[]{top, left, bottom, right};
    }

    /**
     * Turns a range into a table (its first row becomes the header).
     */
    public TableInfo createTable(
            int sheetIndex, int top, int left, int bottom, int right, String styleName
    ) {
        if (!hasContent(workbook.getSheetAt(sheetIndex), top, left)
                && top == bottom && left == right) {
            throw new IllegalArgumentException("Select the cells for the table first");
        }

        XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(sheetIndex);
        int lastRow = Math.max(bottom, top + 1); // a table needs a header and a data row

        for (TableInfo existing : getTables(sheetIndex)) {
            if (!existing.overlaps(top, left, lastRow, right)) {
                continue;
            }

            if (existing.isTable()) {
                throw new IllegalArgumentException("That overlaps the table " + existing.name());
            }

            // A plain filter range is replaced by the table's own filter buttons.
            sheet.getCTWorksheet().unsetAutoFilter();
        }

        // Header names: the header cells' text; empty ones become Column1, Column2, ...
        List<String> names = new ArrayList<>();
        Set<String> used = new HashSet<>();

        for (int column = left; column <= right; column++) {
            String name = getCellText(sheetIndex, top, column).trim();

            if (name.isEmpty()) {
                name = "Column" + (column - left + 1);
            }

            String unique = name;

            for (int copy = 2; !used.add(unique.toLowerCase(Locale.ROOT)); copy++) {
                unique = name + copy;
            }

            names.add(unique);
            getOrCreateCell(sheetIndex, top, column).setCellValue(unique);
        }

        // The table's name must be unique in the whole workbook.
        Set<String> tableNames = new HashSet<>();

        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            for (XSSFTable existing : ((XSSFSheet) workbook.getSheetAt(index)).getTables()) {
                tableNames.add(existing.getName());
            }
        }

        int number = 1;

        while (tableNames.contains("Table" + number)) {
            number++;
        }

        String name = "Table" + number;
        String ref = new CellRangeAddress(top, lastRow, left, right).formatAsString();

        XSSFTable table = sheet.createTable(null);
        table.setName(name);
        table.setDisplayName(name);

        CTTable ct = table.getCTTable();
        ct.setRef(ref);
        ct.setTotalsRowShown(false);

        CTTableStyleInfo styleInfo = ct.getTableStyleInfo();

        if (styleInfo == null) {
            styleInfo = ct.addNewTableStyleInfo();
        }

        styleInfo.setName(styleName);
        styleInfo.setShowRowStripes(true);
        styleInfo.setShowColumnStripes(false);
        styleInfo.setShowFirstColumn(false);
        styleInfo.setShowLastColumn(false);

        CTTableColumns columns = ct.getTableColumns();

        if (columns == null) {
            columns = ct.addNewTableColumns();
        }

        while (columns.sizeOfTableColumnArray() > 0) {
            columns.removeTableColumn(columns.sizeOfTableColumnArray() - 1);
        }

        for (int i = 0; i < names.size(); i++) {
            CTTableColumn column = columns.addNewTableColumn();
            column.setId(i + 1);
            column.setName(names.get(i));
        }

        columns.setCount(names.size());

        CTAutoFilter autoFilter = ct.getAutoFilter();

        if (autoFilter == null) {
            autoFilter = ct.addNewAutoFilter();
        }

        autoFilter.setRef(ref);

        tableCache.remove(sheetIndex);

        return tableAt(sheetIndex, top, left);
    }

    /**
     * Turns a table back into an ordinary range (cells and their values stay).
     */
    public void removeTable(int sheetIndex, TableInfo info) {
        XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(sheetIndex);

        for (XSSFTable table : new ArrayList<>(sheet.getTables())) {
            if (info.name().equals(table.getName())) {
                sheet.removeTable(table);
                break;
            }
        }

        showAllRows(sheetIndex, info);
        tableCache.remove(sheetIndex);
    }

    // ----- column widths and row heights -----
    // Excel stores sizes in its own units. These convert them to and from pixels
    // (7 pixels per character of width, 4/3 pixel per point of height), the same
    // way POI does, so sizes survive a save and reopen.

    private static final int MAX_COLUMN_UNITS = 255 * 256;

    /**
     * Column width in pixels, as stored in the file (or the sheet's default).
     */
    public int getColumnWidthPixels(int sheetIndex, int columnIndex) {
        if (csvMode) return 70;
        try {
            return Math.max(1, Math.round(
                    workbook.getSheetAt(sheetIndex).getColumnWidthInPixels(columnIndex)));
        } catch (RuntimeException exception) {
            return 64;
        }
    }

    /**
     * Row height in pixels, as stored in the file (or the sheet's default).
     */
    public int getRowHeightPixels(int sheetIndex, int rowIndex) {
        if (csvMode) return 20;
        try {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            Row row = sheet.getRow(rowIndex);

            // A row hidden by a filter takes no room on screen.
            if (row != null && row.getZeroHeight()) {
                return 0;
            }

            float points = row != null
                    ? row.getHeightInPoints()
                    : sheet.getDefaultRowHeightInPoints();

            return Math.max(1, Math.round(points * 4f / 3f));
        } catch (RuntimeException exception) {
            return 20;
        }
    }

    public void setColumnWidthPixels(int sheetIndex, int columnIndex, int pixels) {
        if (csvMode) return;
        int units = (int) Math.round(pixels * 256.0 / 7.0);

        workbook.getSheetAt(sheetIndex).setColumnWidth(
                columnIndex, Math.max(0, Math.min(MAX_COLUMN_UNITS, units)));
    }

    public void setRowHeightPixels(int sheetIndex, int rowIndex, int pixels) {
        if (csvMode) return;
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
        if (csvMode) {
            return getCsvCell(sheetIndex, rowIndex, columnIndex);
        }

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

                    BigDecimal number = BigDecimal.valueOf(cell.getNumericCellValue());

                    if (cell.getCellStyle().getDataFormatString().contains("%")) {
                        return number.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
                    }

                    return number.stripTrailingZeros().toPlainString();
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

    private String getCsvCell(
            int sheetIndex,
            int rowIndex,
            int columnIndex
    ) {
        if (sheetIndex != 0
                || rowIndex < 0
                || columnIndex < 0
                || rowIndex >= csvRows.size()
                || columnIndex >= csvRows.get(rowIndex).size()) {
            return "";
        }

        return csvRows.get(rowIndex).get(columnIndex);
    }

    /**
     * The formatted text shown inside the grid cell.
     */
    public String getCellText(
            int sheetIndex,
            int rowIndex,
            int columnIndex
    ) {
        if (csvMode) {
            return getCsvCell(sheetIndex, rowIndex, columnIndex);
        }

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

    /**
     * Returns {previousStyle, newStyle} if typing also set a number format (like "$5"), else null.
     */
    public int[] setCellValueDeferred(
            int sheetIndex,
            int rowIndex,
            int columnIndex,
            String value
    ) {
        if (csvMode) {
            if (sheetIndex != 0 || rowIndex < 0 || columnIndex < 0) {
                throw new IndexOutOfBoundsException("Invalid CSV cell position.");
            }

            while (csvRows.size() <= rowIndex) {
                csvRows.add(new ArrayList<>());
            }

            List<String> row = csvRows.get(rowIndex);

            while (row.size() <= columnIndex) {
                row.add("");
            }

            row.set(columnIndex, value);
            return null;
        }

        Sheet sheet = workbook.getSheetAt(sheetIndex);

        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }

        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            cell = row.createCell(columnIndex);
        }

        String impliedFormat = writeValue(cell, value);

        if (impliedFormat == null) {
            return null;
        }

        int current = cell.getCellStyle().getIndex();

        if (!"General".equalsIgnoreCase(formatOfStyle(current).numberFormat())) {
            return null;
        }

        int target = deriveStyle(
                current,
                new CellFormat.Change(CellFormat.Kind.NUMBER_FORMAT, 0, impliedFormat)
        );

        if (target == current) {
            return null;
        }

        cell.setCellStyle(workbook.getCellStyleAt(target));

        return new int[] {current, target};
    }

    /**
     * Recalculates all formulas when possible.
     */
    public void recalculate() {
        try {
            evaluator.clearAllCachedResultValues();
            evaluator.evaluateAll();
        } catch (RuntimeException exception) {
            // Keep the entered values if evaluation is unsupported.
        }
    }

    // Writes the typed text into the cell. Returns the number format the text
    // implies (for example "$5" implies a currency format), or null.
    private static String writeValue(Cell cell, String value) {
        String text = value.trim();

        if (text.startsWith("=") && text.length() > 1) {
            // The formula is validated before the cell changes, so an
            // invalid formula throws and leaves the old content untouched.
            cell.setCellFormula(text.substring(1));
            return null;
        }

        // Clears any old formula or value before writing the new one.
        cell.setBlank();

        if (text.isEmpty()) {
            return null;
        }

        if (text.equalsIgnoreCase("TRUE") || text.equalsIgnoreCase("FALSE")) {
            cell.setCellValue(text.toUpperCase(Locale.ROOT).equals("TRUE"));
            return null;
        }

        TypedNumber typed = parseTyped(text);

        if (typed != null) {
            cell.setCellValue(typed.value());
            return typed.format();
        }

        if (NUMBER.matcher(text).matches()) {
            cell.setCellValue(Double.parseDouble(text));
        } else {
            cell.setCellValue(value);
        }

        return null;
    }

    // ----- typed numbers: $5, 12.5%, 1,234, (5), dates and times -----

    private record TypedNumber(double value, String format) {
    }

    private static final Pattern PERCENT =
            Pattern.compile("([-+]?(?:\\d+\\.?\\d*|\\.\\d+))\\s*%");
    private static final Pattern CURRENCY =
            Pattern.compile("([-+]?)\\$\\s*([-+]?)(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?");
    private static final Pattern THOUSANDS =
            Pattern.compile("([-+]?)(\\d{1,3}(?:,\\d{3})+)(\\.\\d+)?");
    private static final Pattern US_DATE =
            Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4}|\\d{2})");
    private static final Pattern ISO_DATE =
            Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern TIME =
            Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*([AaPp][Mm])?");

    // Understands what Excel understands when you type into a General cell.
    // Returns null when the text is not one of these (it is then plain text or a plain number).
    private static TypedNumber parseTyped(String text) {
        // (5) and ($5.00) mean -5 and -$5.00
        if (text.length() > 2 && text.startsWith("(") && text.endsWith(")")) {
            TypedNumber inner = parseTypedBody(text.substring(1, text.length() - 1).trim(), true);

            return inner == null ? null : new TypedNumber(-inner.value(), inner.format());
        }

        return parseTypedBody(text, false);
    }

    private static TypedNumber parseTypedBody(String text, boolean allowPlain) {
        Matcher m = PERCENT.matcher(text);

        if (m.matches()) {
            double number = Double.parseDouble(m.group(1)) / 100.0;

            return new TypedNumber(number, m.group(1).contains(".") ? "0.00%" : "0%");
        }

        m = CURRENCY.matcher(text);

        if (m.matches()) {
            double number = Double.parseDouble(
                    m.group(3).replace(",", "") + (m.group(4) == null ? "" : m.group(4)));

            if ("-".equals(m.group(1)) || "-".equals(m.group(2))) {
                number = -number;
            }

            return new TypedNumber(
                    number, m.group(4) == null ? "\"$\"#,##0" : "\"$\"#,##0.00");
        }

        m = THOUSANDS.matcher(text);

        if (m.matches()) {
            double number = Double.parseDouble(
                    m.group(2).replace(",", "") + (m.group(3) == null ? "" : m.group(3)));

            if ("-".equals(m.group(1))) {
                number = -number;
            }

            return new TypedNumber(number, m.group(3) == null ? "#,##0" : "#,##0.00");
        }

        m = US_DATE.matcher(text);

        if (m.matches()) {
            int year = Integer.parseInt(m.group(3));

            if (m.group(3).length() == 2) {
                year += year < 30 ? 2000 : 1900;
            }

            return date(year, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }

        m = ISO_DATE.matcher(text);

        if (m.matches()) {
            return date(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))
            );
        }

        m = TIME.matcher(text);

        if (m.matches()) {
            int hours = Integer.parseInt(m.group(1));
            int minutes = Integer.parseInt(m.group(2));
            int seconds = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            String meridiem = m.group(4);

            if (meridiem != null) {
                if (hours < 1 || hours > 12) {
                    return null;
                }

                boolean pm = meridiem.equalsIgnoreCase("pm");

                if (pm && hours < 12) {
                    hours += 12;
                } else if (!pm && hours == 12) {
                    hours = 0;
                }
            } else if (hours > 23) {
                return null;
            }

            if (minutes > 59 || seconds > 59) {
                return null;
            }

            String format = (m.group(3) == null ? "h:mm" : "h:mm:ss")
                    + (meridiem == null ? "" : " AM/PM");

            return new TypedNumber((hours * 3600 + minutes * 60 + seconds) / 86400.0, format);
        }

        if (allowPlain && NUMBER.matcher(text).matches()) {
            return new TypedNumber(Double.parseDouble(text), null);
        }

        return null;
    }

    private static TypedNumber date(int year, int month, int day) {
        try {
            return new TypedNumber(
                    DateUtil.getExcelDate(LocalDate.of(year, month, day)), "m/d/yyyy");
        } catch (DateTimeException exception) {
            return null;
        }
    }

    /**
     * Writes to a temporary file first, then moves it over the original,
     * so a failed save can't corrupt the existing workbook.
     */
    public void save(Path path) throws IOException {
        if (csvMode) {
            saveCsv(path);
            return;
        }
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

    private void saveCsv(Path path) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");

        try (var writer = Files.newBufferedWriter(
                temp,
                StandardCharsets.UTF_8
        )) {
            for (int r = 0; r < csvRows.size(); r++) {
                List<String> row = csvRows.get(r);

                for (int c = 0; c < row.size(); c++) {
                    if (c > 0) {
                        writer.write(',');
                    }

                    String value = row.get(c);

                    boolean quote = value.contains(",")
                            || value.contains("\"")
                            || value.contains("\n")
                            || value.contains("\r");

                    if (quote) {
                        writer.write('"');
                        writer.write(value.replace("\"", "\"\""));
                        writer.write('"');
                    } else {
                        writer.write(value);
                    }
                }

                if (r < csvRows.size() - 1) {
                    writer.write("\r\n");
                }
            }
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temp);
            throw exception;
        }

        try {
            Files.move(
                    temp,
                    path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            Files.deleteIfExists(temp);
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        if (workbook != null) {
            workbook.close();
        }
    }
}
