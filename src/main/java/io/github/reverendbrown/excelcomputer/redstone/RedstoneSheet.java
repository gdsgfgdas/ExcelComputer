package io.github.reverendbrown.excelcomputer.redstone;

import io.github.reverendbrown.excelcomputer.CellFormat;
import io.github.reverendbrown.excelcomputer.WorkbookManager;
import net.minecraft.core.Direction;

import java.util.Locale;

/**
 * The "Redstone" sheet a linked workbook gets: one row per side of the
 * Redstone Connector, the signal coming in (filled in by the game) and the
 * signal going out (the player's number or formula).
 *
 * <pre>
 *     A       B    C
 * 1   Side    In   Out
 * 2   North   0    0
 * ...
 * 7   Down    0    0
 * </pre>
 */
public final class RedstoneSheet {
    public static final String NAME = "Redstone";

    /** The sides, in the order of rows 2 to 7. */
    public static final Direction[] SIDES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN
    };

    private static final int FIRST_ROW = 1;
    private static final int SIDE_COLUMN = 0;
    private static final int IN_COLUMN = 1;
    private static final int OUT_COLUMN = 2;
    private static final int NOTES_COLUMN = 4;

    private RedstoneSheet() {
    }

    /** The row index (in {@link #SIDES}) of a side. */
    public static int indexOf(Direction side) {
        for (int i = 0; i < SIDES.length; i++) {
            if (SIDES[i] == side) {
                return i;
            }
        }

        return 0;
    }

    /**
     * The Redstone sheet's index, adding the sheet (with its table and a short
     * explanation) if the workbook doesn't have one.
     */
    public static int ensure(WorkbookManager workbook) {
        int existing = workbook.findSheet(NAME);

        if (existing >= 0) {
            return existing;
        }

        int sheet = workbook.addSheet(NAME);

        write(workbook, sheet, 0, SIDE_COLUMN, "Side");
        write(workbook, sheet, 0, IN_COLUMN, "In");
        write(workbook, sheet, 0, OUT_COLUMN, "Out");

        for (int i = 0; i < SIDES.length; i++) {
            String name = SIDES[i].getName();

            write(workbook, sheet, FIRST_ROW + i, SIDE_COLUMN,
                    name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1));
            write(workbook, sheet, FIRST_ROW + i, IN_COLUMN, "0");
            write(workbook, sheet, FIRST_ROW + i, OUT_COLUMN, "0");
        }

        write(workbook, sheet, 0, NOTES_COLUMN, "How the Redstone Connector uses this sheet");
        write(workbook, sheet, 1, NOTES_COLUMN,
                "In: the redstone signal coming into each side (0-15). The game fills it in.");
        write(workbook, sheet, 2, NOTES_COLUMN,
                "Out: the signal each side sends out. Type a number from 0 to 15 or a formula,");
        write(workbook, sheet, 3, NOTES_COLUMN,
                "for example =IF(B2>0,15,0) or =B4*2. TRUE counts as 15, FALSE and text as 0.");
        write(workbook, sheet, 4, NOTES_COLUMN,
                "Out formulas can use cells on any sheet of this workbook.");

        for (int column : new int[]{SIDE_COLUMN, IN_COLUMN, OUT_COLUMN, NOTES_COLUMN}) {
            workbook.applyFormat(sheet, 0, column, new CellFormat.Change(CellFormat.Kind.BOLD, 1));
        }

        workbook.setColumnWidthPixels(sheet, NOTES_COLUMN, 430);
        workbook.recalculate();
        return sheet;
    }

    private static void write(WorkbookManager workbook, int sheet, int row, int column, String value) {
        workbook.setCellValueDeferred(sheet, row, column, value);
    }

    /** Writes the incoming signals (in {@link #SIDES} order) into the In column. */
    public static void writeInputs(WorkbookManager workbook, int sheet, int[] inputs) {
        for (int i = 0; i < SIDES.length; i++) {
            write(workbook, sheet, FIRST_ROW + i, IN_COLUMN, Integer.toString(inputs[i]));
        }
    }

    /** Reads the Out column (call after recalculating) as signals from 0 to 15. */
    public static int[] readOutputs(WorkbookManager workbook, int sheet) {
        int[] outputs = new int[SIDES.length];

        for (int i = 0; i < SIDES.length; i++) {
            outputs[i] = level(workbook, sheet, FIRST_ROW + i, OUT_COLUMN);
        }

        return outputs;
    }

    // A cell as a signal: numbers are rounded and kept within 0-15, TRUE is
    // 15, anything else (FALSE, text, errors, blanks) is 0.
    private static int level(WorkbookManager workbook, int sheet, int row, int column) {
        Double number = workbook.getCellNumber(sheet, row, column);

        if (number != null && !number.isNaN()) {
            return (int) Math.max(0, Math.min(15, Math.round(number)));
        }

        return "TRUE".equalsIgnoreCase(workbook.getCellText(sheet, row, column).trim()) ? 15 : 0;
    }
}
