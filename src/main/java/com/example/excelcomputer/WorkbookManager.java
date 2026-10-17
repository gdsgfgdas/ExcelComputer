package com.example.excelcomputer;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Pattern;

public class WorkbookManager implements AutoCloseable {
    // Plain decimal numbers only (so "1d", "NaN" or hex floats stay text).
    private static final Pattern NUMBER =
            Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    private final Workbook workbook;
    private final DataFormatter formatter = new DataFormatter();
    private final FormulaEvaluator evaluator;

    private WorkbookManager(Workbook workbook) {
        this.workbook = workbook;
        this.evaluator = workbook.getCreationHelper().createFormulaEvaluator();
    }

    public static WorkbookManager open(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return new WorkbookManager(WorkbookFactory.create(input));
        } catch (EncryptedDocumentException exception) {
            throw new IOException(
                    "The workbook is encrypted or password-protected.",
                    exception
            );
        }
    }

    public int getSheetCount() {
        return workbook.getNumberOfSheets();
    }

    public String getSheetName(int index) {
        return workbook.getSheetName(index);
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
