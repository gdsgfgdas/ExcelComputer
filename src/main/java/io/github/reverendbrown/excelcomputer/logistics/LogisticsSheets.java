package io.github.reverendbrown.excelcomputer.logistics;

import io.github.reverendbrown.excelcomputer.CellFormat;
import io.github.reverendbrown.excelcomputer.WorkbookManager;

import java.util.List;
import java.util.Objects;

/**
 * The three sheets a Logistics Hub's workbook gets.
 *
 * <pre>
 * Inventories (filled in by the game, except the names)
 *     A      B  C  D  E     F          G      H
 * 1   Name   X  Y  Z  Side  Dimension  Block  Status
 *
 * Stock (filled in by the game every cycle: what each inventory holds)
 *     A          B                C
 * 1   Inventory  Item             Count
 * 2   Chest 1    minecraft:coal   40
 *
 * Transfers (the player's: what to move every cycle)
 *     A     B          C               D       E
 * 1   From  To         Item            Amount  Status
 * 2   Chest 1  Furnace 1  minecraft:coal  16   Moved 16
 * </pre>
 *
 * The Stock columns are also named ranges (StockInventory, StockItem,
 * StockCount), sized to the data, so formulas can count items with SUMIFS.
 */
public final class LogisticsSheets {
    public static final String INVENTORIES = "Inventories";
    public static final String STOCK = "Stock";
    public static final String TRANSFERS = "Transfers";

    // Inventories columns
    static final int NAME = 0;
    static final int X = 1;
    static final int Y = 2;
    static final int Z = 3;
    static final int SIDE = 4;
    static final int DIMENSION = 5;
    static final int BLOCK = 6;
    static final int INVENTORY_STATUS = 7;
    private static final int INVENTORY_NOTES = 9;

    // The Inventories headers and the notes beside them. Both are written again
    // every cycle (see writeGuide), so older hubs get the current text too.
    private static final String[] INVENTORY_HEADERS = {
            "Name", "X", "Y", "Z", "Side items go in", "Dimension", "Block", "Status"
    };
    private static final String[] INVENTORY_GUIDE = {
            "How the Logistics Hub uses this sheet",
            "", // This hub's tier (writeGuide)
            "To add an inventory: right-click the hub, then sneak-right-click the inventory",
            "holding Excel on a Stick. Do the same again to remove it.",
            "Side items go in: starts as the side you clicked; type another and save to change it:",
            "Top, Bottom, North, South, East, West, or Input (top), Fuel (a side), Output (bottom).",
            "A furnace smelts what goes in the top and burns what goes in a side. Items come out",
            "of any side the block allows (a furnace's result), whatever this column says.",
            "Several sides of one block: each is its own row to move items with, but Stock lists",
            "what the block holds once, under the first side's name (its Status says which).",
            "You can change the Name and Side columns; the game fills in the rest.",
            "To remove an inventory, delete (or clear) its row and save."
    };
    private static final int SIDE_WIDTH = 120;

    // Transfers columns
    static final int FROM = 0;
    static final int TO = 1;
    static final int ITEM = 2;
    static final int AMOUNT = 3;
    static final int TRANSFER_STATUS = 4;
    private static final int TRANSFER_NOTES = 6;

    private LogisticsSheets() {
    }

    /** The indices of the three sheets. */
    public record Sheets(int inventories, int stock, int transfers) {
    }

    /** Adds whichever of the three sheets the workbook is missing; returns their indices. */
    public static Sheets ensure(WorkbookManager workbook) {
        boolean added = false;
        int inventories = workbook.findSheet(INVENTORIES);

        if (inventories < 0) {
            inventories = workbook.addSheet(INVENTORIES);
            header(workbook, inventories, INVENTORY_HEADERS);
            notes(workbook, inventories, INVENTORY_NOTES, INVENTORY_GUIDE);
            workbook.setColumnWidthPixels(inventories, SIDE, SIDE_WIDTH);
            workbook.setColumnWidthPixels(inventories, NAME, 120);
            workbook.setColumnWidthPixels(inventories, DIMENSION, 130);
            workbook.setColumnWidthPixels(inventories, BLOCK, 150);
            workbook.setColumnWidthPixels(inventories, INVENTORY_STATUS, 150);
            workbook.setColumnWidthPixels(inventories, INVENTORY_NOTES, 480);
            added = true;
        }

        int stock = workbook.findSheet(STOCK);

        if (stock < 0) {
            stock = workbook.addSheet(STOCK);
            header(workbook, stock, "Inventory", "Item", "Count");
            workbook.setColumnWidthPixels(stock, 0, 120);
            workbook.setColumnWidthPixels(stock, 1, 200);
            added = true;
        }

        int transfers = workbook.findSheet(TRANSFERS);

        if (transfers < 0) {
            transfers = workbook.addSheet(TRANSFERS);
            header(workbook, transfers, "From", "To", "Item", "Amount", "Status");
            notes(workbook, transfers, TRANSFER_NOTES,
                    "How the Logistics Hub uses this sheet",
                    "",
                    "Each row moves items between two inventories, every cycle, from top to bottom.",
                    "From / To: names from the Inventories sheet.",
                    "Item: an id (minecraft:coal), a tag (#c:ingots), a wildcard (*_ingot) or * for anything.",
                    "    Separate several with commas. ! in front leaves items out: *, !minecraft:dirt",
                    "Amount: the most to move per cycle, a number or a formula. Blank or ALL: as many as",
                    "    the hub allows. 0 or FALSE: skip the row this cycle.",
                    "Status: what happened in the last cycle (filled in by the game).",
                    "",
                    "Count items with the Stock sheet, for example:",
                    "    Formula: =SUMIFS(StockCount, StockInventory, \"Chest 1\", StockItem, \"minecraft:coal\")",
                    "Keep 16 coal in a furnace:",
                    "    Amount: =MAX(0, 16-SUMIFS(StockCount, StockInventory, \"Furnace 1\", StockItem, \"minecraft:coal\"))",
                    "Move all ingots out of a furnace: From Furnace 1, To Chest 1, Item *_ingot, Amount ALL");
            workbook.setColumnWidthPixels(transfers, FROM, 110);
            workbook.setColumnWidthPixels(transfers, TO, 110);
            workbook.setColumnWidthPixels(transfers, ITEM, 160);
            workbook.setColumnWidthPixels(transfers, TRANSFER_STATUS, 200);
            workbook.setColumnWidthPixels(transfers, TRANSFER_NOTES, 560);
            added = true;
        }

        if (added) {
            writeStockNames(workbook, 0);
            workbook.recalculate();
        }

        return new Sheets(inventories, stock, transfers);
    }

    private static void header(WorkbookManager workbook, int sheet, String... names) {
        for (int column = 0; column < names.length; column++) {
            workbook.setCellValueDeferred(sheet, 0, column, names[column]);
            workbook.applyFormat(sheet, 0, column, new CellFormat.Change(CellFormat.Kind.BOLD, 1));
        }
    }

    private static void notes(WorkbookManager workbook, int sheet, int column, String... lines) {
        for (int row = 0; row < lines.length; row++) {
            workbook.setCellValueDeferred(sheet, row, column, lines[row]);
        }

        workbook.applyFormat(sheet, 0, column, new CellFormat.Change(CellFormat.Kind.BOLD, 1));
    }

    // ----- writing what the game knows -----

    /** Writes a cell only if it changed (most cells stay the same from one cycle to the next). */
    static void put(WorkbookManager workbook, int sheet, int row, int column, String value) {
        if (!Objects.equals(workbook.getCellInput(sheet, row, column), value)) {
            workbook.setCellValueDeferred(sheet, row, column, value);
        }
    }

    /** The Inventories headers and notes (current text), and the hub's tier beside them. */
    static void writeGuide(WorkbookManager workbook, int sheet, String name, HubTier tier) {
        for (int column = 0; column < INVENTORY_HEADERS.length; column++) {
            put(workbook, sheet, 0, column, INVENTORY_HEADERS[column]);
        }

        for (int row = 0; row < INVENTORY_GUIDE.length; row++) {
            if (row != 1) {
                put(workbook, sheet, row, INVENTORY_NOTES, INVENTORY_GUIDE[row]);
            }
        }

        if (workbook.getColumnWidthPixels(sheet, SIDE) < SIDE_WIDTH) {
            workbook.setColumnWidthPixels(sheet, SIDE, SIDE_WIDTH);
        }

        put(workbook, sheet, 1, INVENTORY_NOTES, name.equals(tier.label())
                ? "This hub: " + tier.describe()
                : "This hub: " + name + " (" + tier.describe() + ")");
    }

    /** A message for players under the Inventories notes (blank clears it), e.g. that the hub is paused. */
    static void writeNotice(WorkbookManager workbook, int sheet, String notice) {
        // Below the notes (rows 0-9).
        put(workbook, sheet, 10, INVENTORY_NOTES, notice);
    }

    /** Clears the cells of rows [from, to) in columns [0, columns). */
    static void clearRows(WorkbookManager workbook, int sheet, int from, int to, int columns) {
        for (int row = from; row < to; row++) {
            for (int column = 0; column < columns; column++) {
                put(workbook, sheet, row, column, "");
            }
        }
    }

    /** Writes the Stock rows (inventory, item, count) and resizes the named ranges to them. */
    static void writeStock(WorkbookManager workbook, int sheet, List<String[]> rows, int previousRows) {
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);

            for (int column = 0; column < 3; column++) {
                put(workbook, sheet, i + 1, column, row[column]);
            }
        }

        clearRows(workbook, sheet, rows.size() + 1, previousRows + 1, 3);
        writeStockNames(workbook, rows.size());
    }

    private static void writeStockNames(WorkbookManager workbook, int rows) {
        // At least one row, so the ranges stay valid (SUMIFS over a blank row gives 0).
        int last = Math.max(rows, 1) + 1;
        workbook.setNamedRange("StockInventory", "'" + STOCK + "'!$A$2:$A$" + last);
        workbook.setNamedRange("StockItem", "'" + STOCK + "'!$B$2:$B$" + last);
        workbook.setNamedRange("StockCount", "'" + STOCK + "'!$C$2:$C$" + last);
    }
}
