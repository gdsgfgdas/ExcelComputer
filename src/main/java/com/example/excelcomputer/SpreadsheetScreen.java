package com.example.excelcomputer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpreadsheetScreen extends Screen {
    private static final int GRID_X = 45;
    private static final int GRID_Y = 130;
    // Top of the Home ribbon's buttons.
    private static final int RIBBON_Y = 72;
    private static final int BASE_CELL_WIDTH = 70;
    private static final int BASE_CELL_HEIGHT = 20;
    private static final int FIRST_TAB_X = 145;
    private static final int TAB_WIDTH = 85;
    private static final int MAX_VISIBLE_TABS = 5;

    // Space kept free below the grid for the hint line, tabs and buttons.
    private static final int BOTTOM_RESERVED = 72;

    // How close (in pixels) the mouse must be to a header edge to grab it.
    private static final int RESIZE_GRAB = 4;

    // The GUI size this screen always uses, whatever the video setting says.
    private static final int FIXED_GUI_SCALE = 2;

    // Largest range that Delete/Backspace will clear in one go.
    private static final long MAX_CLEAR_CELLS = 10_000;

    // Largest area that copy/paste will handle in one go.
    private static final long MAX_PASTE_CELLS = 10_000;

    // Matches A1, $A$1, A$1, $A1 (not function names like LOG10( ).
    private static final Pattern CELL_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_.])(\\$?)([A-Za-z]{1,3})(\\$?)(\\d+)(?![A-Za-z0-9_(!])");

    private final Path workbookPath;
    private WorkbookManager manager;
    private String status = "Opening workbook...";

    // The active cell. This is the cell that the edit box edits.
    private int selectedRow;
    private int selectedColumn;

    // The opposite corner of the selected range (equal to the active cell
    // when only one cell is selected).
    private int rangeEndRow;
    private int rangeEndColumn;

    private int firstVisibleRow;
    private int firstVisibleColumn;
    private int sheetIndex;
    private int firstVisibleTab;
    private int zoomPercent = 125;

    private boolean panning;
    private double panStartX;
    private double panStartY;
    private int panStartRow;
    private int panStartColumn;

    // Left mouse button is held and is extending a range.
    private boolean dragSelecting;

    // Column widths and row heights (in unzoomed pixels) read from the workbook,
    // cached so they are not looked up on every frame.
    private final Map<Long, Integer> columnWidthCache = new HashMap<>();
    private final Map<Long, Integer> rowHeightCache = new HashMap<>();

    // Resizing a column or row by dragging the edge of its header.
    private int resizingColumn = -1;
    private int resizingRow = -1;
    private double resizeStartMouse;
    private int resizeStartSize;

    // Formula "point mode": a cell reference was inserted into the edit box
    // by clicking/dragging cells. pointStart/pointEnd mark where in the text
    // that reference lives, so the next click or drag replaces it.
    private boolean pointing;
    private int pointStart;
    private int pointEnd;
    private int pointAnchorRow;
    private int pointAnchorColumn;
    private int pointEndRow;
    private int pointEndColumn;

    private boolean dirty;
    private boolean discardArmed;

    // True once the workbook opened, so the view is only saved for a workbook that was shown.
    private boolean opened;

    // True while this screen has replaced the game's GUI size with its own.
    private boolean guiScaleOverridden;

    // Home ribbon: the color popup and the colors the main buttons apply.
    private final ColorPalette palette = new ColorPalette();
    private int lastFillColor = 0xFFFF00;
    private int lastTextColor = 0xFF0000;
    private int fillButtonX;
    private int textButtonX;
    private int sizeLabelX;
    private final FormatMenu formatMenu = new FormatMenu();
    private final FilterMenu filterMenu = new FilterMenu();
    private int formatButtonX;

    // Copy/cut data: the raw input of every copied cell (formulas as "=...").
    private String[][] clipboardInputs;
    private int clipboardRows;
    private int clipboardColumns;
    private int clipboardSourceRow;
    private int clipboardSourceColumn;
    private int clipboardSheet;
    private boolean clipboardCut;
    // The text placed on the system clipboard, used to recognise our own copy.
    private String clipboardText;
    private EditBox cellEditor;
    private ZoomSlider zoomSlider;
    private final Deque<CellEdit> undoHistory = new ArrayDeque<>();
    private final Deque<CellEdit> redoHistory = new ArrayDeque<>();

    // "chained" means this edit belongs to the same group as the next OLDER
    // edit, so undo/redo treats them as one step (used when clearing ranges).
    private record CellEdit(
            int sheet,
            int row,
            int column,
            String previousValue,
            String newValue,
            boolean chained,
            // A formatting edit has style indexes (0 or more) and leaves the value
            // alone. A value edit has -1 for both.
            int previousStyle,
            int newStyle
    ) {
        CellEdit(
                int sheet,
                int row,
                int column,
                String previousValue,
                String newValue,
                boolean chained
        ) {
            this(sheet, row, column, previousValue, newValue, chained, -1, -1);
        }
    }

    public SpreadsheetScreen(Path workbookPath) {
        super(Component.literal("Excel Computer"));
        this.workbookPath = workbookPath;
        applyGuiScale();

        try {
            manager = WorkbookManager.open(workbookPath);
            status = "Workbook: " + workbookPath.getFileName();
            opened = true;
            restoreViewState();
        } catch (IOException exception) {
            status = "Could not open workbook: " + exception.getMessage();
        }
    }

    private int cellWidth() {
        return Math.max(25, BASE_CELL_WIDTH * zoomPercent / 100);
    }

    private int cellHeight() {
        return Math.max(12, BASE_CELL_HEIGHT * zoomPercent / 100);
    }

    // ----- grid layout: every column and row can have its own size -----

    private static long sizeKey(int sheet, int index) {
        return ((long) sheet << 32) | (index & 0xFFFFFFFFL);
    }

    // Size in unzoomed pixels, as stored in the workbook.
    private int baseColumnWidth(int column) {
        return columnWidthCache.computeIfAbsent(
                sizeKey(sheetIndex, column),
                key -> manager == null
                        ? BASE_CELL_WIDTH
                        : manager.getColumnWidthPixels(sheetIndex, column)
        );
    }

    private int baseRowHeight(int row) {
        return rowHeightCache.computeIfAbsent(
                sizeKey(sheetIndex, row),
                key -> manager == null
                        ? BASE_CELL_HEIGHT
                        : manager.getRowHeightPixels(sheetIndex, row)
        );
    }

    // Size on screen (zoom applied).
    private int columnWidth(int column) {
        return Math.max(4, (int) Math.round(baseColumnWidth(column) * zoomPercent / 100.0));
    }

    private int rowHeight(int row) {
        int base = baseRowHeight(row);

        // A row hidden by a filter takes no room.
        return base == 0 ? 0 : Math.max(4, (int) Math.round(base * zoomPercent / 100.0));
    }

    private int headerHeight() {
        return cellHeight();
    }

    private int gridRight() {
        return this.width - 10;
    }

    private int gridBottom() {
        return this.height - BOTTOM_RESERVED;
    }

    // How many columns fit completely, starting at the first visible one.
    private int visibleColumns() {
        int available = gridRight() - GRID_X;
        int used = 0;
        int count = 0;

        for (int column = firstVisibleColumn; ; column++) {
            int size = columnWidth(column);

            if (used + size > available) {
                break;
            }

            used += size;
            count++;
        }

        return Math.max(1, count);
    }

    // How many rows fit completely, starting at the first visible one.
    private int visibleRows() {
        int available = gridBottom() - (GRID_Y + headerHeight());
        int used = 0;
        int count = 0;

        for (int row = firstVisibleRow; ; row++) {
            int size = rowHeight(row);

            if (used + size > available) {
                break;
            }

            used += size;
            count++;
        }

        return Math.max(1, count);
    }

    private void scrollCellIntoView(int row, int column) {
        if (row < firstVisibleRow) {
            firstVisibleRow = row;
        } else {
            while (firstVisibleRow < row
                    && row >= firstVisibleRow + visibleRows()) {
                firstVisibleRow++;
            }
        }

        if (column < firstVisibleColumn) {
            firstVisibleColumn = column;
        } else {
            while (firstVisibleColumn < column
                    && column >= firstVisibleColumn + visibleColumns()) {
                firstVisibleColumn++;
            }
        }
    }

    private void scrollSelectionIntoView() {
        scrollCellIntoView(selectedRow, selectedColumn);
    }

    // ----- range helpers -----

    private int rangeTop() {
        return Math.min(selectedRow, rangeEndRow);
    }

    private int rangeBottom() {
        return Math.max(selectedRow, rangeEndRow);
    }

    private int rangeLeft() {
        return Math.min(selectedColumn, rangeEndColumn);
    }

    private int rangeRight() {
        return Math.max(selectedColumn, rangeEndColumn);
    }

    private boolean hasMultiSelection() {
        return rangeEndRow != selectedRow || rangeEndColumn != selectedColumn;
    }

    private void collapseRange() {
        rangeEndRow = selectedRow;
        rangeEndColumn = selectedColumn;
    }

    private static String cellRef(int row, int column) {
        return columnName(column) + (row + 1);
    }

    private static String rangeRef(int row1, int column1, int row2, int column2) {
        int top = Math.min(row1, row2);
        int bottom = Math.max(row1, row2);
        int left = Math.min(column1, column2);
        int right = Math.max(column1, column2);

        if (top == bottom && left == right) {
            return cellRef(top, left);
        }

        return cellRef(top, left) + ":" + cellRef(bottom, right);
    }

    private void markDirty() {
        dirty = true;
        discardArmed = false;
    }

    @Override
    protected void init() {
        cellEditor = new EditBox(
                this.font, 100, 32, Math.max(100, this.width - 100 - 135 - 8), 20,
                Component.literal("Cell value")
        );
        cellEditor.setMaxLength(1000);
        cellEditor.setValue(getSelectedCellInput());
        cellEditor.setFocused(false);
        this.addRenderableWidget(cellEditor);

        this.addRenderableWidget(
                Button.builder(Component.literal("Apply"), button -> {
                    commitPendingEdit();
                    status = "Applied cell edit";
                }).bounds(this.width - 135, 32, 60, 20).build()
        );

        this.addRenderableWidget(
                Button.builder(Component.literal("Save"), button -> saveWorkbook())
                        .bounds(this.width - 70, 32, 60, 20)
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(Component.literal("<"), button -> {
                    commitPendingEdit();
                    firstVisibleTab = Math.max(0, firstVisibleTab - 1);
                    rebuildWidgets();
                }).bounds(FIRST_TAB_X, this.height - 55, 20, 20).build()
        );

        this.addRenderableWidget(
                Button.builder(Component.literal(">"), button -> {
                    if (manager == null) {
                        return;
                    }

                    commitPendingEdit();
                    int maxStart = Math.max(
                            0, manager.getSheetCount() - MAX_VISIBLE_TABS
                    );
                    firstVisibleTab = Math.min(maxStart, firstVisibleTab + 1);
                    rebuildWidgets();
                }).bounds(
                        FIRST_TAB_X + 22 + MAX_VISIBLE_TABS * TAB_WIDTH,
                        this.height - 55, 20, 20
                ).build()
        );

        if (manager != null) {
            int end = Math.min(
                    manager.getSheetCount(),
                    firstVisibleTab + MAX_VISIBLE_TABS
            );

            for (int i = firstVisibleTab; i < end; i++) {
                final int tabIndex = i;
                String name = manager.getSheetName(i);

                if (name.length() > 10) {
                    name = name.substring(0, 10);
                }

                Component label = Component.literal(
                        (i == sheetIndex ? "* " : "") + name
                );

                this.addRenderableWidget(
                        Button.builder(label, button -> switchSheet(tabIndex))
                                .bounds(
                                        FIRST_TAB_X + 22
                                                + (i - firstVisibleTab) * TAB_WIDTH,
                                        this.height - 55,
                                        TAB_WIDTH - 3,
                                        20
                                )
                                .build()
                );
            }
        }

        zoomSlider = new ZoomSlider(
                this.width - 150,
                this.height - 28,
                130,
                20,
                zoomPercent
        );
        this.addRenderableWidget(zoomSlider);

        this.addRenderableWidget(
                Button.builder(Component.literal("< Columns"), button -> {
                    commitPendingEdit();
                    firstVisibleColumn = Math.max(0, firstVisibleColumn - 3);
                    selectedColumn = Math.max(
                            firstVisibleColumn,
                            Math.min(
                                    selectedColumn,
                                    firstVisibleColumn + visibleColumns() - 1
                            )
                    );
                    collapseRange();
                    refreshEditor();
                }).bounds(130, this.height - 30, 85, 20).build()
        );

        this.addRenderableWidget(
                Button.builder(Component.literal("Columns >"), button -> {
                    commitPendingEdit();
                    firstVisibleColumn += 3;

                    if (selectedColumn < firstVisibleColumn) {
                        selectedColumn = firstVisibleColumn;
                    }

                    collapseRange();
                    refreshEditor();
                }).bounds(220, this.height - 30, 90, 20).build()
        );

        this.addRenderableWidget(
                Button.builder(Component.literal("Back to files"), button ->
                        requestExit()
                ).bounds(10, this.height - 30, 110, 20).build()
        );

        addRibbonButtons();

        // Keep the selection visible after a window resize or rebuild.
        scrollSelectionIntoView();
    }

    @Override
    protected void rebuildWidgets() {
        this.clearWidgets();
        this.init();
    }

    private void switchSheet(int newSheetIndex) {
        if (manager == null || newSheetIndex < 0
                || newSheetIndex >= manager.getSheetCount()) {
            return;
        }

        commitPendingEdit();

        sheetIndex = newSheetIndex;
        selectedRow = 0;
        selectedColumn = 0;
        firstVisibleRow = 0;
        firstVisibleColumn = 0;
        collapseRange();

        refreshEditor();
        rebuildWidgets();
        status = "Worksheet: " + manager.getSheetName(sheetIndex);
    }

    // Before 1.21.6, Screen.render() draws the blurred menu background itself,
    // after our own drawing, which blurs the whole spreadsheet. This turns that
    // off. (Newer versions draw it earlier, so our drawing covers it.)
    //? if >=1.21.6 {
    //?} elif >=1.20.2 {
    /*@Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    *///?} else {
    /*@Override
    public void renderBackground(GuiGraphics graphics) {
    }

    *///?}

    @Override
    //? if >=26.1 {
    /*public void extractRenderState(
    *///?} else {
    public void render(
    //?}
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        graphics.fill(0, 0, this.width, this.height, 0xFF202124);
        graphics.fill(0, 0, this.width, 26, 0xFF217346);

        graphics.drawString(this.font, "Excel", 10, 9, 0xFFFFFFFF);
        graphics.drawString(
                this.font,
                workbookPath.getFileName().toString() + (dirty ? " *" : ""),
                75, 9, 0xFFFFFFFF
        );
        // Status messages share the title bar, to the right of the file name.
        String fileLabel = workbookPath.getFileName().toString() + (dirty ? " *" : "");
        int statusX = 75 + this.font.width(fileLabel) + 20;
        graphics.drawString(
                this.font,
                this.font.plainSubstrByWidth(status, Math.max(0, this.width - 12 - statusX)),
                statusX, 9, 0xFFDDFFE6
        );

        if (manager == null) {
            graphics.drawString(
                    this.font, "Check that the workbook is valid.",
                    12, 72, 0xFFFF7777
            );
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        // Shows "B2" for one cell or "B2:D5" for a range.
        String address = rangeRef(
                selectedRow, selectedColumn, rangeEndRow, rangeEndColumn
        );

        graphics.fill(10, 30, this.width - 10, 54, 0xFF303134);
        graphics.drawString(this.font, address, 16, 38, 0xFFB8DDBF);

        // The Home ribbon
        graphics.fill(10, 58, this.width - 10, GRID_Y - 4, 0xFF303134);
        graphics.drawString(this.font, "Home", 16, 60, 0xFFFFFFFF);
        graphics.fill(14, 70, 18 + this.font.width("Home"), 72, 0xFF217346);

        int headerHeight = headerHeight();
        int gridRight = gridRight();
        int gridBottom = gridBottom();
        List<TableInfo> tables = manager.getTables(sheetIndex);

        // The header edge under the mouse (if any), so it can be highlighted.
        int hotColumn = resizingColumn >= 0 ? resizingColumn
                : resizingRow >= 0 ? -1 : columnResizeHandleAt(mouseX, mouseY);
        int hotRow = resizingRow >= 0 ? resizingRow
                : (resizingColumn >= 0 || hotColumn >= 0)
                        ? -1 : rowResizeHandleAt(mouseX, mouseY);

        // Cell text scales with zoom; 0.75 at 100%.
        float textScale = Math.max(0.5f, 0.75f * zoomPercent / 100f);

        // The selected range, and (while building a formula) the range
        // that was inserted by clicking cells.
        int rangeTop = rangeTop();
        int rangeBottom = rangeBottom();
        int rangeLeft = rangeLeft();
        int rangeRight = rangeRight();
        boolean multi = hasMultiSelection();

        boolean showPoint = pointing && cellEditor != null
                && cellEditor.isFocused();
        int pointTop = Math.min(pointAnchorRow, pointEndRow);
        int pointBottom = Math.max(pointAnchorRow, pointEndRow);
        int pointLeft = Math.min(pointAnchorColumn, pointEndColumn);
        int pointRight = Math.max(pointAnchorColumn, pointEndColumn);

        boolean showClip = clipboardInputs != null
                && clipboardSheet == sheetIndex;
        int clipTop = clipboardSourceRow;
        int clipBottom = clipboardSourceRow + clipboardRows - 1;
        int clipLeft = clipboardSourceColumn;
        int clipRight = clipboardSourceColumn + clipboardColumns - 1;

        // Everything in the grid is clipped to the grid area, so a column or
        // row at the edge can be partly visible.
        graphics.enableScissor(10, GRID_Y, gridRight, gridBottom);

        graphics.fill(GRID_X, GRID_Y, gridRight, GRID_Y + headerHeight, 0xFF3C4043);

        int x = GRID_X;
        int cw = 0;
        for (int actualColumn = firstVisibleColumn; x < gridRight; actualColumn++, x += cw) {
            cw = columnWidth(actualColumn);

            graphics.drawString(
                    this.font, columnName(actualColumn),
                    x + 5, GRID_Y + Math.max(2, headerHeight / 4),
                    0xFFFFFFFF
            );

            // Divider between column headers (orange when it can be dragged).
            boolean hot = actualColumn == hotColumn;
            graphics.fill(
                    x + cw - (hot ? 2 : 1), GRID_Y,
                    x + cw, GRID_Y + headerHeight,
                    hot ? 0xFFF9AB00 : 0xFF5F6368
            );
        }

        int y = GRID_Y + headerHeight;
        int ch = 0;
        for (int actualRow = firstVisibleRow; y < gridBottom; actualRow++, y += ch) {
            ch = rowHeight(actualRow);

            if (ch == 0) {
                continue; // hidden by a filter
            }

            graphics.fill(10, y, GRID_X, y + ch, 0xFF3C4043);
            graphics.drawString(
                    this.font, Integer.toString(actualRow + 1),
                    20, y + Math.max(2, ch / 4), 0xFFFFFFFF
            );

            if (actualRow == hotRow) {
                graphics.fill(10, y + ch - 2, GRID_X, y + ch, 0xFFF9AB00);
            }

            x = GRID_X;
            cw = 0;
            for (int actualColumn = firstVisibleColumn; x < gridRight; actualColumn++, x += cw) {
                cw = columnWidth(actualColumn);

                CellFormat format = manager.getCellFormat(sheetIndex, actualRow, actualColumn);

                // Table styling: a colored header and shaded alternate rows.
                TableInfo table = findTable(tables, actualRow, actualColumn);
                boolean styled = table != null && table.isTable() && !table.style().isEmpty();
                boolean tableHeader = styled && actualRow == table.top();
                boolean hasArrow = table != null && table.filterButtons() && actualRow == table.top();
                int arrowWidth = hasArrow ? Math.min(14, cw / 2) : 0;

                int background = format.fill() >= 0
                        ? 0xFF000000 | format.fill()
                        : tableHeader ? 0xFF000000 | tableAccent(table.style())
                        : styled && table.bandedRows() && (actualRow - table.top()) % 2 == 1
                                ? 0xFF000000 | lighten(tableAccent(table.style()), 0.8)
                        : (actualRow + actualColumn) % 2 == 0 ? 0xFFFFFFFF : 0xFFF7F7F7;

                graphics.fill(x, y, x + cw, y + ch, background);
                graphics.fill(x, y, x + cw, y + 1, 0xFFD0D0D0);
                graphics.fill(x, y, x + 1, y + ch, 0xFFD0D0D0);

                // Selected range: tint, plus a border around the outside.
                boolean inRange = actualRow >= rangeTop
                        && actualRow <= rangeBottom
                        && actualColumn >= rangeLeft
                        && actualColumn <= rangeRight;

                if (inRange) {
                    boolean isActive = actualRow == selectedRow
                            && actualColumn == selectedColumn;

                    if (multi && !isActive) {
                        graphics.fill(x, y, x + cw, y + ch, 0x40217346);
                    }

                    int green = 0xFF217346;

                    if (actualRow == rangeTop) {
                        graphics.fill(x, y, x + cw, y + 2, green);
                    }
                    if (actualRow == rangeBottom) {
                        graphics.fill(x, y + ch - 2, x + cw, y + ch, green);
                    }
                    if (actualColumn == rangeLeft) {
                        graphics.fill(x, y, x + 2, y + ch, green);
                    }
                    if (actualColumn == rangeRight) {
                        graphics.fill(x + cw - 2, y, x + cw, y + ch, green);
                    }
                }

                // Formula point mode: blue range, like Excel's reference
                // highlight while you build a formula.
                if (showPoint
                        && actualRow >= pointTop && actualRow <= pointBottom
                        && actualColumn >= pointLeft
                        && actualColumn <= pointRight) {
                    int blue = 0xFF4285F4;

                    graphics.fill(x, y, x + cw, y + ch, 0x404285F4);

                    if (actualRow == pointTop) {
                        graphics.fill(x, y, x + cw, y + 2, blue);
                    }
                    if (actualRow == pointBottom) {
                        graphics.fill(x, y + ch - 2, x + cw, y + ch, blue);
                    }
                    if (actualColumn == pointLeft) {
                        graphics.fill(x, y, x + 2, y + ch, blue);
                    }
                    if (actualColumn == pointRight) {
                        graphics.fill(x + cw - 2, y, x + cw, y + ch, blue);
                    }
                }

                // Source of a copy/cut: amber outline.
                if (showClip
                        && actualRow >= clipTop && actualRow <= clipBottom
                        && actualColumn >= clipLeft
                        && actualColumn <= clipRight) {
                    int amber = 0xFFF9AB00;

                    if (actualRow == clipTop) {
                        graphics.fill(x, y, x + cw, y + 2, amber);
                    }
                    if (actualRow == clipBottom) {
                        graphics.fill(x, y + ch - 2, x + cw, y + ch, amber);
                    }
                    if (actualColumn == clipLeft) {
                        graphics.fill(x, y, x + 2, y + ch, amber);
                    }
                    if (actualColumn == clipRight) {
                        graphics.fill(x + cw - 2, y, x + cw, y + ch, amber);
                    }
                }

                // The filter arrow in a table's header cell (blue while a filter is on).
                if (hasArrow) {
                    boolean filtered = table.filteredColumns().contains(actualColumn - table.left());
                    int arrowLeft = x + cw - arrowWidth - 2;
                    int arrowTop = y + 2;
                    int arrowHeight = Math.max(6, ch - 4);

                    graphics.fill(arrowLeft, arrowTop, arrowLeft + arrowWidth, arrowTop + arrowHeight, 0xFF8A8A8A);
                    graphics.fill(
                            arrowLeft + 1, arrowTop + 1,
                            arrowLeft + arrowWidth - 1, arrowTop + arrowHeight - 1,
                            filtered ? 0xFF9CC3F5 : 0xFFF1F3F4
                    );

                    // A small triangle pointing down.
                    int size = Math.max(2, Math.min(arrowWidth, arrowHeight) / 4);
                    int centerX = arrowLeft + arrowWidth / 2;
                    int triangleTop = arrowTop + arrowHeight / 2 - size / 2;

                    for (int line = 0; line < size; line++) {
                        graphics.fill(
                                centerX - (size - line), triangleTop + line,
                                centerX + (size - line) + 1, triangleTop + line + 1,
                                0xFF3C4043
                        );
                    }
                }

                String value = manager.getCellText(
                        sheetIndex, actualRow, actualColumn
                );

                if (value.isEmpty()) {
                    continue;
                }

                float scale = textScale * format.fontPoints() / 11f;
                Component text = Component.literal(value).withStyle(
                        Style.EMPTY
                                .withBold(format.bold() || tableHeader)
                                .withItalic(format.italic())
                                .withUnderlined(format.underline())
                );
                float textWidth = this.font.width(text) * scale;

                // General alignment: numbers on the right, everything else on the left.
                int align = format.hAlign() != 0
                        ? format.hAlign()
                        : (format.numeric() ? 3 : 1);
                float textX = align == 2 ? (cw - textWidth) / 2f
                        : align == 3 ? cw - 4 - textWidth : 4;
                textX = Math.max(4f, textX);
                int textColor = 0xFF000000
                        | (format.fontColor() >= 0 ? format.fontColor()
                        : tableHeader ? 0xFFFFFF : 0x202124);

                // Clip long text to the cell instead of cutting characters.
                graphics.enableScissor(x + 1, y + 1, x + cw - 1 - (hasArrow ? arrowWidth + 2 : 0), y + ch - 1);
                pushTextScale(
                        graphics,
                        x + textX,
                        y + (ch - this.font.lineHeight * scale) / 2f,
                        scale
                );
                graphics.drawString(this.font, text, 0, 0, textColor, false);
                popTextScale(graphics);
                graphics.disableScissor();
            }
        }

        graphics.disableScissor();

        graphics.drawString(
                this.font,
                "Drag/Shift+Arrows: select | Ctrl+C/X/V: copy/cut/paste | Drag header edges: resize | Ctrl+S: save",
                GRID_X, gridBottom + 5,
                0xFFFFFFFF
        );

        super.render(graphics, mouseX, mouseY, partialTick);

        renderRibbonOverlay(graphics, mouseX, mouseY);
    }

    //? if >=1.20.2 {
    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double scrollX,
            double scrollY
    ) {
        if (handleMouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    //?} else {
    /*@Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (handleMouseScrolled(mouseX, mouseY, 0, delta)) {
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, delta);
    }
    *///?}

    // Shared by every Minecraft version. Returns true when the wheel was used.
    private boolean handleMouseScrolled(
            double mouseX,
            double mouseY,
            double scrollX,
            double scrollY
    ) {
        if (manager == null) {
            return false;
        }

        if (filterMenu.isOpen()) {
            filterMenu.scroll(scrollY);
            return true;
        }

        commitPendingEdit();

        int oldRow = selectedRow;
        int oldColumn = selectedColumn;

        if (scrollY != 0) {
            firstVisibleRow = Math.max(
                    0, firstVisibleRow + (scrollY > 0 ? -3 : 3)
            );
            selectedRow = Math.max(
                    firstVisibleRow,
                    Math.min(firstVisibleRow + visibleRows() - 1, selectedRow)
            );
        }

        if (scrollX != 0) {
            firstVisibleColumn = Math.max(
                    0, firstVisibleColumn + (scrollX > 0 ? -2 : 2)
            );
            selectedColumn = Math.max(
                    firstVisibleColumn,
                    Math.min(
                            firstVisibleColumn + visibleColumns() - 1,
                            selectedColumn
                    )
            );
        }

        // Only reset the range if scrolling had to move the active cell.
        if (selectedRow != oldRow || selectedColumn != oldColumn) {
            collapseRange();
        }

        refreshEditor();
        return true;
    }

    // Popups (color palette, number format list) must be drawn in front of the grid text.
    // Text is batched separately from boxes, so drawing later is not enough to be on top.
    //? if >=1.21.6 {
    private static void beginOverlay(GuiGraphics graphics) {
        graphics.nextStratum();
    }

    private static void endOverlay(GuiGraphics graphics) {
    }
    //?} else {
    /*private static void beginOverlay(GuiGraphics graphics) {
        graphics.pose().pushPose();
        graphics.pose().translate(0f, 0f, 400f);
    }

    private static void endOverlay(GuiGraphics graphics) {
        graphics.pose().popPose();
    }
    *///?}

    //? if >=1.21.6 {
    private static void pushTextScale(GuiGraphics graphics, float x, float y, float scale) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
    }

    private static void popTextScale(GuiGraphics graphics) {
        graphics.pose().popMatrix();
    }
    //?} else {
    /*private static void pushTextScale(GuiGraphics graphics, float x, float y, float scale) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0f);
        graphics.pose().scale(scale, scale, 1f);
    }

    private static void popTextScale(GuiGraphics graphics) {
        graphics.pose().popPose();
    }
    *///?}

    private static boolean isModifierKey(int key) {
        return key == GLFW.GLFW_KEY_LEFT_SHIFT
                || key == GLFW.GLFW_KEY_RIGHT_SHIFT
                || key == GLFW.GLFW_KEY_LEFT_CONTROL
                || key == GLFW.GLFW_KEY_RIGHT_CONTROL
                || key == GLFW.GLFW_KEY_LEFT_ALT
                || key == GLFW.GLFW_KEY_RIGHT_ALT;
    }

    //? if >=1.21.9 {
    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (handleKey(
                keyEvent.key(),
                keyEvent.hasControlDown(),
                keyEvent.hasShiftDown()
        )) {
            return true;
        }

        return super.keyPressed(keyEvent);
    }
    //?} else {
    /*@Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (handleKey(keyCode, hasControlDown(), hasShiftDown())) {
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    *///?}

    // Shared by every Minecraft version. Returns true when the key was used.
    private boolean handleKey(int key, boolean ctrl, boolean shift) {
        if ((palette.isOpen() || formatMenu.isOpen() || filterMenu.isOpen())
                && key == GLFW.GLFW_KEY_ESCAPE) {
            palette.close();
            formatMenu.close();
            filterMenu.close();
            return true;
        }


        if (key == GLFW.GLFW_KEY_S
                && ctrl
                && manager != null) {
            saveWorkbook();
            return true;
        }

        if (key == GLFW.GLFW_KEY_Z
                && ctrl
                && manager != null) {

            // Commit any pending edit first, so it can be undone too.
            commitPendingEdit();

            undoLastEdit();
            return true;
        }

        if (key == GLFW.GLFW_KEY_Y
                && ctrl
                && manager != null) {
            commitPendingEdit();
            redoLastEdit();
            return true;
        }

        // Handle spreadsheet shortcuts when the editor is not focused.
        if (cellEditor == null || !cellEditor.isFocused()) {
            if (ctrl && manager != null) {
                if (key == GLFW.GLFW_KEY_C) {
                    copySelection(false);
                    return true;
                }
                if (key == GLFW.GLFW_KEY_X) {
                    copySelection(true);
                    return true;
                }
                if (key == GLFW.GLFW_KEY_V) {
                    pasteClipboard();
                    return true;
                }
                if (key == GLFW.GLFW_KEY_B) {
                    toggleFlag(CellFormat.Kind.BOLD, activeFormat().bold());
                    return true;
                }
                if (key == GLFW.GLFW_KEY_I) {
                    toggleFlag(CellFormat.Kind.ITALIC, activeFormat().italic());
                    return true;
                }
                if (key == GLFW.GLFW_KEY_U) {
                    toggleFlag(CellFormat.Kind.UNDERLINE, activeFormat().underline());
                    return true;
                }
            }

            if ((key == GLFW.GLFW_KEY_BACKSPACE
                    || key == GLFW.GLFW_KEY_DELETE) && manager != null) {
                clearSelectedCells();
                return true;
            }
        }

        if (cellEditor != null && cellEditor.isFocused()) {
            // Any real key press ends "point mode" for the inserted
            // reference, so further clicks insert a new one.
            if (!isModifierKey(key)) {
                pointing = false;
            }

            if (key == GLFW.GLFW_KEY_ESCAPE) {
                leaveEditor();
                refreshEditor();
                return true;
            }

            if (key == GLFW.GLFW_KEY_ENTER
                    || key == GLFW.GLFW_KEY_KP_ENTER) {
                commitPendingEdit();
                moveSelection(0, 1);
                return true;
            }

            if (key == GLFW.GLFW_KEY_TAB) {
                commitPendingEdit();
                moveSelection(1, 0);
                return true;
            }

            return false;
        }

        switch (key) {
            case GLFW.GLFW_KEY_UP -> {
                navigate(0, -1, shift);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                navigate(0, 1, shift);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                moveSelection(0, shift ? -1 : 1);
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                navigate(-1, 0, shift);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                navigate(1, 0, shift);
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                moveSelection(shift ? -1 : 1, 0);
                return true;
            }
            case GLFW.GLFW_KEY_F2 -> {
                enterEditor();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // Arrow keys: move the active cell, or (with Shift) extend the range.
    private void navigate(int columnChange, int rowChange, boolean extend) {
        if (extend) {
            extendSelection(columnChange, rowChange);
        } else {
            moveSelection(columnChange, rowChange);
        }
    }

    private void extendSelection(int columnChange, int rowChange) {
        commitPendingEdit();

        rangeEndColumn = Math.max(0, rangeEndColumn + columnChange);
        rangeEndRow = stepRow(rangeEndRow, rowChange);

        scrollCellIntoView(rangeEndRow, rangeEndColumn);
    }

    // Clears every cell in the selected range as one undo step.
    private void clearSelectedCells() {
        if (manager == null) {
            return;
        }

        commitPendingEdit();

        int top = rangeTop();
        int bottom = rangeBottom();
        int left = rangeLeft();
        int right = rangeRight();

        long cellCount = (long) (bottom - top + 1) * (right - left + 1);

        if (cellCount > MAX_CLEAR_CELLS) {
            status = "Selection too large to clear";
            return;
        }

        int cleared = 0;
        boolean hasEarlierEdit = false;

        for (int row = top; row <= bottom; row++) {
            for (int column = left; column <= right; column++) {
                String previousValue = manager.getCellInput(
                        sheetIndex, row, column
                );

                if (previousValue == null || previousValue.isEmpty()) {
                    continue;
                }

                try {
                    manager.setCellValueDeferred(sheetIndex, row, column, "");

                    undoHistory.push(new CellEdit(
                            sheetIndex, row, column,
                            previousValue, "", hasEarlierEdit
                    ));

                    hasEarlierEdit = true;
                    cleared++;
                } catch (RuntimeException exception) {
                    status = "Could not clear "
                            + cellRef(row, column) + ": "
                            + exception.getMessage();
                    break;
                }
            }
        }

        if (cleared > 0) {
            manager.recalculate();
            redoHistory.clear();
            markDirty();
            status = "Cleared "
                    + rangeRef(top, left, bottom, right);
            refreshEditor();
        }
    }

    // ----- copy / cut / paste -----

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    // Quotes a value when needed so tabs and line breaks survive.
    private static String escapeTsv(String value) {
        if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('"') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }

        return value;
    }

    // Parses tab-separated text (what Excel puts on the clipboard).
    private static String[][] parseTsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.length() == 0) {
                inQuotes = true;
            } else if (ch == '\t') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length()
                        && text.charAt(i + 1) == '\n') {
                    i++;
                }

                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }

        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }

        int columns = 0;
        for (List<String> parsedRow : rows) {
            columns = Math.max(columns, parsedRow.size());
        }

        String[][] grid = new String[rows.size()][columns];

        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < columns; c++) {
                grid[r][c] = c < rows.get(r).size() ? rows.get(r).get(c) : "";
            }
        }

        return grid;
    }

    private static int columnIndex(String letters) {
        int value = 0;

        for (int i = 0; i < letters.length(); i++) {
            value = value * 26
                    + (Character.toUpperCase(letters.charAt(i)) - 'A' + 1);
        }

        return value - 1;
    }

    // Moves the relative references in a formula, like Excel does when you
    // copy and paste it. $A$1 style (absolute) parts stay where they are.
    private static String shiftFormula(String formula, int rowDelta, int columnDelta) {
        if (rowDelta == 0 && columnDelta == 0) {
            return formula;
        }

        StringBuilder result = new StringBuilder();
        StringBuilder segment = new StringBuilder();
        char quote = 0;

        for (int i = 0; i < formula.length(); i++) {
            char ch = formula.charAt(i);

            if (quote != 0) {
                // Text inside quotes is never changed.
                result.append(ch);

                if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '"' || ch == '\'') {
                result.append(shiftReferences(
                        segment.toString(), rowDelta, columnDelta
                ));
                segment.setLength(0);
                result.append(ch);
                quote = ch;
            } else {
                segment.append(ch);
            }
        }

        result.append(shiftReferences(
                segment.toString(), rowDelta, columnDelta
        ));

        return result.toString();
    }

    private static String shiftReferences(String text, int rowDelta, int columnDelta) {
        Matcher matcher = CELL_REFERENCE.matcher(text);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {
            String digits = matcher.group(4);

            if (digits.length() > 7) {
                matcher.appendReplacement(
                        out, Matcher.quoteReplacement(matcher.group())
                );
                continue;
            }

            boolean absoluteColumn = !matcher.group(1).isEmpty();
            boolean absoluteRow = !matcher.group(3).isEmpty();

            int column = columnIndex(matcher.group(2));
            int row = Integer.parseInt(digits) - 1;

            if (!absoluteColumn) {
                column += columnDelta;
            }
            if (!absoluteRow) {
                row += rowDelta;
            }

            String replacement;

            if (column < 0 || column > 16383 || row < 0 || row > 1048575) {
                replacement = "#REF!";
            } else {
                replacement = (absoluteColumn ? "$" : "")
                        + columnName(column)
                        + (absoluteRow ? "$" : "")
                        + (row + 1);
            }

            matcher.appendReplacement(
                    out, Matcher.quoteReplacement(replacement)
            );
        }

        matcher.appendTail(out);
        return out.toString();
    }

    // Ctrl+C / Ctrl+X: remembers the selected cells (formulas included) and
    // puts their displayed values on the system clipboard as tab-separated
    // text, so they can also be pasted into real Excel or other programs.
    private void copySelection(boolean cut) {
        if (manager == null) {
            return;
        }

        commitPendingEdit();

        int top = rangeTop();
        int bottom = rangeBottom();
        int left = rangeLeft();
        int right = rangeRight();
        int rows = bottom - top + 1;
        int columns = right - left + 1;

        if ((long) rows * columns > MAX_PASTE_CELLS) {
            status = "Selection too large to copy";
            return;
        }

        String[][] inputs = new String[rows][columns];
        StringBuilder text = new StringBuilder();

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                String input = manager.getCellInput(
                        sheetIndex, top + r, left + c
                );
                inputs[r][c] = input == null ? "" : input;

                if (c > 0) {
                    text.append('\t');
                }

                text.append(escapeTsv(manager.getCellText(
                        sheetIndex, top + r, left + c
                )));
            }

            text.append('\n');
        }

        clipboardInputs = inputs;
        clipboardRows = rows;
        clipboardColumns = columns;
        clipboardSourceRow = top;
        clipboardSourceColumn = left;
        clipboardSheet = sheetIndex;
        clipboardCut = cut;
        clipboardText = text.toString();

        this.minecraft.keyboardHandler.setClipboard(clipboardText);

        status = (cut ? "Cut " : "Copied ")
                + rangeRef(top, left, bottom, right);
    }

    // Ctrl+V: pastes at the top-left of the selection. A single copied cell
    // fills the whole selection. Pasting is one undo step.
    private void pasteClipboard() {
        if (manager == null) {
            return;
        }

        commitPendingEdit();

        String text = this.minecraft.keyboardHandler.getClipboard();

        if (text == null || text.isEmpty()) {
            status = "Clipboard is empty";
            return;
        }

        if (text.length() > 1_000_000) {
            status = "Clipboard text is too large";
            return;
        }

        String normalized = normalizeLineEndings(text);

        // Our own copy keeps formulas; anything else is plain tab-separated
        // text (for example copied from Excel).
        boolean internal = clipboardInputs != null && clipboardText != null
                && normalized.equals(normalizeLineEndings(clipboardText));

        String[][] grid = internal ? clipboardInputs : parseTsv(normalized);

        if (grid.length == 0 || grid[0].length == 0) {
            status = "Nothing to paste";
            return;
        }

        boolean cut = internal && clipboardCut;
        int top = rangeTop();
        int left = rangeLeft();

        boolean fill = grid.length == 1 && grid[0].length == 1
                && hasMultiSelection() && !cut;

        int pasteRows = fill ? rangeBottom() - top + 1 : grid.length;
        int pasteColumns = fill ? rangeRight() - left + 1 : grid[0].length;

        if ((long) pasteRows * pasteColumns > MAX_PASTE_CELLS) {
            status = "Paste area too large";
            return;
        }

        boolean hasEarlierEdit = false;
        int changed = 0;
        int skipped = 0;

        for (int r = 0; r < pasteRows; r++) {
            for (int c = 0; c < pasteColumns; c++) {
                int targetRow = top + r;
                int targetColumn = left + c;
                int sourceR = fill ? 0 : r;
                int sourceC = fill ? 0 : c;

                String input = grid[sourceR][sourceC];

                // Copied formulas move their relative references.
                // (A cut formula keeps its references unchanged.)
                if (internal && !cut && input.startsWith("=")) {
                    input = shiftFormula(
                            input,
                            targetRow - (clipboardSourceRow + sourceR),
                            targetColumn - (clipboardSourceColumn + sourceC)
                    );
                }

                String previous = manager.getCellInput(
                        sheetIndex, targetRow, targetColumn
                );

                if (previous == null) {
                    previous = "";
                }

                if (previous.equals(input)) {
                    continue;
                }

                try {
                    int[] styles = manager.setCellValueDeferred(
                            sheetIndex, targetRow, targetColumn, input
                    );

                    undoHistory.push(new CellEdit(
                            sheetIndex, targetRow, targetColumn,
                            previous, input, hasEarlierEdit
                    ));

                    if (styles != null) {
                        undoHistory.push(new CellEdit(
                                sheetIndex, targetRow, targetColumn, "", "",
                                true, styles[0], styles[1]
                        ));
                    }

                    hasEarlierEdit = true;
                    changed++;
                } catch (RuntimeException exception) {
                    // For example an invalid formula; leave that cell as is.
                    skipped++;
                }
            }
        }

        // Cut: clear the original cells (except any the paste landed on).
        if (cut) {
            for (int r = 0; r < clipboardRows; r++) {
                for (int c = 0; c < clipboardColumns; c++) {
                    int sourceRow = clipboardSourceRow + r;
                    int sourceColumn = clipboardSourceColumn + c;

                    boolean insideDestination = clipboardSheet == sheetIndex
                            && sourceRow >= top && sourceRow < top + pasteRows
                            && sourceColumn >= left
                            && sourceColumn < left + pasteColumns;

                    if (insideDestination) {
                        continue;
                    }

                    String previous = manager.getCellInput(
                            clipboardSheet, sourceRow, sourceColumn
                    );

                    if (previous == null || previous.isEmpty()) {
                        continue;
                    }

                    try {
                        manager.setCellValueDeferred(
                                clipboardSheet, sourceRow, sourceColumn, ""
                        );

                        undoHistory.push(new CellEdit(
                                clipboardSheet, sourceRow, sourceColumn,
                                previous, "", hasEarlierEdit
                        ));

                        hasEarlierEdit = true;
                        changed++;
                    } catch (RuntimeException exception) {
                        skipped++;
                    }
                }
            }

            clipboardInputs = null;
            clipboardText = null;
            clipboardCut = false;
        }

        manager.recalculate();

        if (changed > 0) {
            redoHistory.clear();
            markDirty();
        }

        // Select what was pasted.
        selectedRow = top;
        selectedColumn = left;
        rangeEndRow = top + pasteRows - 1;
        rangeEndColumn = left + pasteColumns - 1;

        scrollSelectionIntoView();
        refreshEditor();

        status = "Pasted " + rangeRef(top, left, rangeEndRow, rangeEndColumn)
                + (skipped > 0 ? " (" + skipped + " cells skipped)" : "");
    }

    private void undoLastEdit() {
        if (manager == null || undoHistory.isEmpty()) {
            status = "Nothing to undo";
            return;
        }

        CellEdit last = null;
        boolean continueGroup;

        do {
            CellEdit edit = undoHistory.pop();

            try {
                applyEdit(edit, false);

                redoHistory.push(edit);
                last = edit;
                continueGroup = edit.chained() && !undoHistory.isEmpty();
            } catch (RuntimeException exception) {
                undoHistory.push(edit);
                status = "Undo failed: " + exception.getMessage();
                manager.recalculate();
                return;
            }
        } while (continueGroup);

        manager.recalculate();
        markDirty();

        sheetIndex = last.sheet();
        selectedRow = last.row();
        selectedColumn = last.column();
        collapseRange();

        scrollSelectionIntoView();

        refreshEditor();
        status = "Undid edit to " + cellRef(selectedRow, selectedColumn);
    }

    private void redoLastEdit() {
        if (manager == null || redoHistory.isEmpty()) {
            status = "Nothing to redo";
            return;
        }

        CellEdit last = null;
        boolean continueGroup;

        do {
            CellEdit edit = redoHistory.pop();

            try {
                applyEdit(edit, true);

                undoHistory.push(edit);
                last = edit;
            } catch (RuntimeException exception) {
                redoHistory.push(edit);
                status = "Redo failed: " + exception.getMessage();
                manager.recalculate();
                return;
            }

            // The rest of a grouped edit sits on top of the redo stack.
            continueGroup = !redoHistory.isEmpty()
                    && redoHistory.peek().chained();
        } while (continueGroup);

        manager.recalculate();
        markDirty();

        sheetIndex = last.sheet();
        selectedRow = last.row();
        selectedColumn = last.column();
        collapseRange();

        scrollSelectionIntoView();

        refreshEditor();
        status = "Redid edit to " + cellRef(selectedRow, selectedColumn);
    }

    //? if >=1.21.9 {
    @Override
    public boolean charTyped(CharacterEvent characterEvent) {
        if (prepareTypedChar(characterEvent.codepoint())) {
            return cellEditor.charTyped(characterEvent);
        }

        return super.charTyped(characterEvent);
    }
    //?} else {
    /*@Override
    public boolean charTyped(char chr, int modifiers) {
        if (prepareTypedChar(chr)) {
            return cellEditor.charTyped(chr, modifiers);
        }

        return super.charTyped(chr, modifiers);
    }
    *///?}

    // Returns true when a character should be forwarded to the edit box
    // (typing on a selected cell starts a replacement edit).
    private boolean prepareTypedChar(int typed) {
        if (manager == null || cellEditor == null) {
            return false;
        }

        if (!cellEditor.isFocused()) {
            if (!Character.isISOControl(typed)) {
                cellEditor.setValue("");
                enterEditor();
                return true;
            }

            return false;
        }

        // Typing text ends point mode for the inserted reference.
        pointing = false;
        return false;
    }

    // ----- mouse -> cell helpers -----

    private int columnAt(double mouseX) {
        double x = GRID_X;
        int column = firstVisibleColumn;

        while (true) {
            x += columnWidth(column);

            if (mouseX < x) {
                return column;
            }

            column++;
        }
    }

    private int rowAt(double mouseY) {
        double y = GRID_Y + headerHeight();
        int row = firstVisibleRow;

        while (true) {
            y += rowHeight(row);

            if (mouseY < y) {
                return row;
            }

            row++;
        }
    }

    private boolean insideGrid(double mouseX, double mouseY) {
        return mouseX >= GRID_X
                && mouseX < gridRight()
                && mouseY >= GRID_Y + headerHeight()
                && mouseY < gridBottom();
    }

    // While dragging outside the grid, scroll one step and use the edge cell.
    private int dragColumn(double mouseX) {
        if (mouseX < GRID_X) {
            firstVisibleColumn = Math.max(0, firstVisibleColumn - 1);
            return firstVisibleColumn;
        }

        if (mouseX >= gridRight()) {
            firstVisibleColumn++;
            return firstVisibleColumn + visibleColumns() - 1;
        }

        return columnAt(mouseX);
    }

    private int dragRow(double mouseY) {
        if (mouseY < GRID_Y + headerHeight()) {
            firstVisibleRow = Math.max(0, firstVisibleRow - 1);
            return firstVisibleRow;
        }

        if (mouseY >= gridBottom()) {
            firstVisibleRow++;
            return firstVisibleRow + visibleRows() - 1;
        }

        return rowAt(mouseY);
    }

    // ----- resizing columns and rows -----

    // The column whose right edge is under the mouse in the header row, or -1.
    private int columnResizeHandleAt(double mouseX, double mouseY) {
        if (mouseY < GRID_Y || mouseY >= GRID_Y + headerHeight()) {
            return -1;
        }

        double x = GRID_X;

        for (int column = firstVisibleColumn; x < gridRight() + RESIZE_GRAB; column++) {
            x += columnWidth(column);

            if (Math.abs(mouseX - x) <= RESIZE_GRAB) {
                return column;
            }

            if (x > mouseX + RESIZE_GRAB) {
                break;
            }
        }

        return -1;
    }

    // The row whose bottom edge is under the mouse in the row header, or -1.
    private int rowResizeHandleAt(double mouseX, double mouseY) {
        if (mouseX < 10 || mouseX >= GRID_X) {
            return -1;
        }

        double y = GRID_Y + headerHeight();

        for (int row = firstVisibleRow; y < gridBottom() + RESIZE_GRAB; row++) {
            y += rowHeight(row);

            if (Math.abs(mouseY - y) <= RESIZE_GRAB) {
                return row;
            }

            if (y > mouseY + RESIZE_GRAB) {
                break;
            }
        }

        return -1;
    }

    private void applyColumnWidth(int column, int pixels) {
        int size = Math.max(8, Math.min(1000, pixels));

        try {
            manager.setColumnWidthPixels(sheetIndex, column, size);
            columnWidthCache.put(sizeKey(sheetIndex, column), size);
            markDirty();
            status = "Column " + columnName(column) + " width: " + size;
        } catch (RuntimeException exception) {
            status = "Could not resize column: " + exception.getMessage();
        }
    }

    private void applyRowHeight(int row, int pixels) {
        int size = Math.max(6, Math.min(500, pixels));

        try {
            manager.setRowHeightPixels(sheetIndex, row, size);
            rowHeightCache.put(sizeKey(sheetIndex, row), size);
            markDirty();
            status = "Row " + (row + 1) + " height: " + size;
        } catch (RuntimeException exception) {
            status = "Could not resize row: " + exception.getMessage();
        }
    }

    // ----- formula point mode -----

    // True when a click on the grid should insert a cell reference into the
    // formula being typed (like Excel), instead of selecting a cell.
    private boolean canInsertReference() {
        if (cellEditor == null || !cellEditor.isFocused()) {
            return false;
        }

        String text = cellEditor.getValue();

        if (!text.startsWith("=")) {
            return false;
        }

        if (pointing) {
            return true;
        }

        int caret = cellEditor.getCursorPosition();

        if (caret <= 0 || caret > text.length()) {
            return false;
        }

        // A reference is expected right after an operator or an opening
        // bracket/comma, e.g. "=", "=SUM(", "=A1+", "=IF(A1>".
        return "=+-*/^(,;<>&".indexOf(text.charAt(caret - 1)) >= 0;
    }

    private void applyPointReference() {
        String reference = rangeRef(
                pointAnchorRow, pointAnchorColumn,
                pointEndRow, pointEndColumn
        );

        String text = cellEditor.getValue();
        int start = Math.min(pointStart, text.length());
        int end = Math.max(start, Math.min(pointEnd, text.length()));

        cellEditor.setValue(
                text.substring(0, start) + reference + text.substring(end)
        );

        pointStart = start;
        pointEnd = start + reference.length();

        //? if >=1.21 {
        cellEditor.moveCursorTo(pointEnd, false);
        //?} else {
        /*cellEditor.setCursorPosition(pointEnd);
        cellEditor.setHighlightPos(pointEnd);
        *///?}
    }

    //? if >=1.21.9 {
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (handleMouseClicked(
                event.x(), event.y(), event.button(), event.hasShiftDown()
        )) {
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }
    //?} else {
    /*@Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (handleMouseClicked(mouseX, mouseY, button, hasShiftDown())) {
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }
    *///?}

    // Shared by every Minecraft version. Returns true when the click was used.
    private boolean handleMouseClicked(
            double mouseX,
            double mouseY,
            int button,
            boolean shift
    ) {
        // The filter list is a popup too: it stays open while you tick values.
        if (filterMenu.isOpen() && button == 0) {
            FilterMenu.Result result = filterMenu.click(mouseX, mouseY);

            if (result == FilterMenu.Result.APPLY) {
                applyFilterMenu();
            }

            if (result != FilterMenu.Result.NONE) {
                filterMenu.close();
            }

            return true;
        }

        // The number format list is a popup too.
        if (formatMenu.isOpen() && button == 0) {
            int item = formatMenu.itemAt(mouseX, mouseY);

            if (item >= 0) {
                applyNumberFormat(FormatMenu.ITEMS.get(item).format());
            }

            formatMenu.close();
            return true;
        }

        // The color palette is a popup: the next click picks a color or closes it.
        if (palette.isOpen() && button == 0) {
            int picked = palette.colorAt(mouseX, mouseY);

            if (picked != ColorPalette.OUTSIDE) {
                applyPaletteColor(palette.target(), picked);
            }

            palette.close();
            return true;
        }


        // Middle mouse button begins panning.
        if (button == 2 && manager != null) {
            commitPendingEdit();

            panning = true;
            panStartX = mouseX;
            panStartY = mouseY;
            panStartRow = firstVisibleRow;
            panStartColumn = firstVisibleColumn;
            return true;
        }

        // The small arrow in a table's header cell opens that column's filter list.
        if (button == 0 && manager != null && openFilterMenuAt(mouseX, mouseY)) {
            return true;
        }

        // Grabbing the edge of a column or row header starts a resize.
        if (button == 0 && manager != null) {
            int resizeColumn = columnResizeHandleAt(mouseX, mouseY);
            int resizeRow = resizeColumn >= 0
                    ? -1 : rowResizeHandleAt(mouseX, mouseY);

            if (resizeColumn >= 0 || resizeRow >= 0) {
                commitPendingEdit();

                resizingColumn = resizeColumn;
                resizingRow = resizeRow;
                resizeStartMouse = resizeColumn >= 0 ? mouseX : mouseY;
                resizeStartSize = resizeColumn >= 0
                        ? baseColumnWidth(resizeColumn)
                        : baseRowHeight(resizeRow);
                return true;
            }
        }

        // Clicks on the grid are handled here, before the widgets.
        if (button == 0 && manager != null && insideGrid(mouseX, mouseY)) {
            int column = Math.max(0, columnAt(mouseX));
            int row = Math.max(0, rowAt(mouseY));

            // Building a formula: insert the clicked cell as a reference.
            if (canInsertReference()) {
                if (!pointing) {
                    pointing = true;
                    pointStart = Math.min(
                            cellEditor.getCursorPosition(),
                            cellEditor.getValue().length()
                    );
                    pointEnd = pointStart;
                }

                pointAnchorRow = row;
                pointAnchorColumn = column;
                pointEndRow = row;
                pointEndColumn = column;

                applyPointReference();
                dragSelecting = true;
                return true;
            }

            commitPendingEdit();

            if (shift) {
                // Shift+click extends the range from the active cell.
                rangeEndRow = row;
                rangeEndColumn = column;
            } else {
                selectedRow = row;
                selectedColumn = column;
                collapseRange();
            }

            dragSelecting = true;
            refreshEditor();
            return true;
        }

        // Anything else (buttons, the slider, the edit box) is handled by
        // the normal widget code.
        return false;
    }

    //? if >=1.21.9 {
    @Override
    public boolean mouseDragged(
            MouseButtonEvent event,
            double dragX,
            double dragY
    ) {
        if (handleMouseDragged(event.x(), event.y())) {
            return true;
        }

        return super.mouseDragged(event, dragX, dragY);
    }
    //?} else {
    /*@Override
    public boolean mouseDragged(
            double mouseX,
            double mouseY,
            int button,
            double dragX,
            double dragY
    ) {
        if (handleMouseDragged(mouseX, mouseY)) {
            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }
    *///?}

    private boolean handleMouseDragged(double mouseX, double mouseY) {
        if (manager != null && (resizingColumn >= 0 || resizingRow >= 0)) {
            double zoom = zoomPercent / 100.0;

            if (resizingColumn >= 0) {
                applyColumnWidth(resizingColumn, (int) Math.round(
                        resizeStartSize + (mouseX - resizeStartMouse) / zoom));
            } else {
                applyRowHeight(resizingRow, (int) Math.round(
                        resizeStartSize + (mouseY - resizeStartMouse) / zoom));
            }

            return true;
        }

        if (panning && manager != null) {
            firstVisibleColumn = Math.max(0, panStartColumn
                    + (int) Math.round((panStartX - mouseX) / cellWidth()));
            firstVisibleRow = Math.max(0, panStartRow
                    + (int) Math.round((panStartY - mouseY) / cellHeight()));
            return true;
        }

        if (dragSelecting && manager != null) {
            int column = Math.max(0, dragColumn(mouseX));
            int row = Math.max(0, dragRow(mouseY));

            if (pointing) {
                pointEndRow = row;
                pointEndColumn = column;
                applyPointReference();
            } else {
                rangeEndRow = row;
                rangeEndColumn = column;
            }

            return true;
        }

        return false;
    }

    //? if >=1.21.9 {
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (handleMouseReleased(event.button())) {
            return true;
        }

        return super.mouseReleased(event);
    }
    //?} else {
    /*@Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (handleMouseReleased(button)) {
            return true;
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }
    *///?}

    private boolean handleMouseReleased(int button) {
        if (button == 0 && (resizingColumn >= 0 || resizingRow >= 0)) {
            resizingColumn = -1;
            resizingRow = -1;
            return true;
        }

        if (button == 2 && panning) {
            panning = false;
            return true;
        }

        if (button == 0 && dragSelecting) {
            dragSelecting = false;
            return true;
        }

        return false;
    }

    private void moveSelection(int columnChange, int rowChange) {
        commitPendingEdit();

        selectedColumn = Math.max(0, selectedColumn + columnChange);
        selectedRow = stepRow(selectedRow, rowChange);
        collapseRange();

        scrollSelectionIntoView();

        refreshEditor();
    }

    private void refreshEditor() {
        if (cellEditor != null) {
            cellEditor.setValue(getSelectedCellInput());
        }
    }

    private String getSelectedCellInput() {
        if (manager == null) {
            return "";
        }

        try {
            return manager.getCellInput(
                    sheetIndex, selectedRow, selectedColumn
            );
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private void enterEditor() {
        if (cellEditor != null && manager != null) {
            cellEditor.setFocused(true);
            this.setFocused(cellEditor);
        }
    }

    private void leaveEditor() {
        if (cellEditor != null) {
            cellEditor.setFocused(false);
        }

        pointing = false;
        this.setFocused(null);
    }

    private void commitPendingEdit() {
        if (manager != null && cellEditor != null
                && cellEditor.isFocused()) {
            applyCellEdit();
        }

        leaveEditor();
    }

    private void applyCellEdit() {
        if (manager == null || cellEditor == null) {
            return;
        }

        String previousValue = manager.getCellInput(
                sheetIndex,
                selectedRow,
                selectedColumn
        );

        String newValue = cellEditor.getValue();

        if (previousValue == null) {
            previousValue = "";
        }

        if (previousValue.equals(newValue)) {
            return;
        }

        CellEdit edit = new CellEdit(
                sheetIndex,
                selectedRow,
                selectedColumn,
                previousValue,
                newValue,
                false
        );

        try {
            int[] styles = manager.setCellValue(
                    sheetIndex,
                    selectedRow,
                    selectedColumn,
                    newValue
            );

            undoHistory.push(edit);

            if (styles != null) {
                // Typing "$5" or "50%" also set a number format; undo takes it back too.
                undoHistory.push(new CellEdit(
                        sheetIndex, selectedRow, selectedColumn, "", "",
                        true, styles[0], styles[1]
                ));
            }

            // A new edit invalidates the redo history.
            redoHistory.clear();
            markDirty();

            status = "Updated "
                    + cellRef(selectedRow, selectedColumn);

        } catch (RuntimeException exception) {
            status = "Could not update cell: "
                    + exception.getMessage();
        }
    }

    private void saveWorkbook() {
        if (manager == null) {
            return;
        }

        commitPendingEdit();

        try {
            manager.save(workbookPath);
            dirty = false;
            discardArmed = false;
            status = "Saved: " + workbookPath.getFileName();
        } catch (IOException | RuntimeException exception) {
            status = "Save failed: " + exception.getMessage();
        }
    }

    private static String columnName(int column) {
        StringBuilder result = new StringBuilder();
        int value = column + 1;

        while (value > 0) {
            value--;
            result.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }

        return result.toString();
    }

    // Warns once about unsaved changes before leaving the workbook.
    private void requestExit() {
        commitPendingEdit();

        if (dirty && !discardArmed) {
            discardArmed = true;
            status = "Unsaved changes! Save (Ctrl+S), or leave again to discard.";
            return;
        }

        returnToPicker();
    }

    private void returnToPicker() {
        commitPendingEdit();
        closeWorkbook();
        this.minecraft.setScreen(new WorkbookPickerScreen());
    }

    private void closeWorkbook() {
        if (manager != null) {
            try {
                manager.close();
            } catch (IOException exception) {
                status = "Warning: workbook close failed";
            }

            manager = null;
        }
    }

    // ----- Home ribbon: formatting tools -----

    private CellFormat activeFormat() {
        return manager == null
                ? CellFormat.DEFAULT
                : manager.getCellFormat(sheetIndex, selectedRow, selectedColumn);
    }

    private void addRibbonButton(
            int x,
            int width,
            Component label,
            String tip,
            Runnable action
    ) {
        addRibbonButton(x, RIBBON_Y + 3, width, label, tip, action);
    }

    private void addRibbonButton(
            int x,
            int y,
            int width,
            Component label,
            String tip,
            Runnable action
    ) {
        this.addRenderableWidget(
                Button.builder(label, button -> action.run())
                        .bounds(x, y, width, 20)
                        .tooltip(Tooltip.create(Component.literal(tip)))
                        .build()
        );
    }

    private void addRibbonButtons() {
        if (manager == null) {
            return;
        }

        int x = 14;

        fillButtonX = x;
        addRibbonButton(x, 38, Component.literal("Fill"),
                "Fill color (applies the last color used)",
                () -> applyFormat(new CellFormat.Change(CellFormat.Kind.FILL, lastFillColor)));
        x += 38;
        final int fillPaletteX = fillButtonX;
        addRibbonButton(x, 14, Component.literal("v"), "Fill color palette",
                () -> togglePalette(ColorPalette.Target.FILL, fillPaletteX));
        x += 14 + 6;

        textButtonX = x;
        addRibbonButton(x, 26, Component.literal("A"),
                "Text color (applies the last color used)",
                () -> applyFormat(new CellFormat.Change(CellFormat.Kind.FONT_COLOR, lastTextColor)));
        x += 26;
        final int textPaletteX = textButtonX;
        addRibbonButton(x, 14, Component.literal("v"), "Text color palette",
                () -> togglePalette(ColorPalette.Target.TEXT, textPaletteX));
        x += 14 + 10;

        addRibbonButton(x, 20, Component.literal("B").withStyle(ChatFormatting.BOLD),
                "Bold (Ctrl+B)",
                () -> toggleFlag(CellFormat.Kind.BOLD, activeFormat().bold()));
        x += 22;
        addRibbonButton(x, 20, Component.literal("I").withStyle(ChatFormatting.ITALIC),
                "Italic (Ctrl+I)",
                () -> toggleFlag(CellFormat.Kind.ITALIC, activeFormat().italic()));
        x += 22;
        addRibbonButton(x, 20, Component.literal("U").withStyle(ChatFormatting.UNDERLINE),
                "Underline (Ctrl+U)",
                () -> toggleFlag(CellFormat.Kind.UNDERLINE, activeFormat().underline()));
        x += 20 + 10;

        addRibbonButton(x, 20, Component.literal("L"), "Align left", () -> applyAlignment(1));
        x += 22;
        addRibbonButton(x, 20, Component.literal("C"), "Center", () -> applyAlignment(2));
        x += 22;
        addRibbonButton(x, 20, Component.literal("R"), "Align right", () -> applyAlignment(3));
        x += 20 + 10;

        addRibbonButton(x, 18, Component.literal("-"), "Smaller text", () -> changeFontSize(-1));
        x += 18 + 4;
        sizeLabelX = x;
        x += 20;
        addRibbonButton(x, 18, Component.literal("+"), "Larger text", () -> changeFontSize(1));
        x += 18 + 10;

        addRibbonButton(x, 46, Component.literal("Clear"),
                "Clear formatting",
                () -> applyFormat(new CellFormat.Change(CellFormat.Kind.RESET, 0)));

        // Second row: number formats
        int y2 = RIBBON_Y + 3 + 24;
        int x2 = 14;

        formatButtonX = x2;
        final int menuX = x2;
        addRibbonButton(x2, y2, 104, Component.literal(""),
                "Number format",
                () -> toggleFormatMenu(menuX));
        x2 += 104 + 8;
        addRibbonButton(x2, y2, 22, Component.literal("$"), "Currency format",
                () -> applyNumberFormat("\"$\"#,##0.00"));
        x2 += 24;
        addRibbonButton(x2, y2, 22, Component.literal("%"), "Percent format",
                () -> applyNumberFormat("0%"));
        x2 += 24;
        addRibbonButton(x2, y2, 22, Component.literal(","), "Thousands separator",
                () -> applyNumberFormat("#,##0.00"));
        x2 += 22 + 8;
        addRibbonButton(x2, y2, 30, Component.literal("+.0"), "More decimal places",
                () -> applyFormat(new CellFormat.Change(CellFormat.Kind.DECIMALS, 1)));
        x2 += 32;
        addRibbonButton(x2, y2, 30, Component.literal("-.0"), "Fewer decimal places",
                () -> applyFormat(new CellFormat.Change(CellFormat.Kind.DECIMALS, -1)));
        x2 += 32 + 10;
        addRibbonButton(x2, y2, 44, Component.literal("Table"),
                "Make a table from the selection (click again inside a table to turn it back into a range)",
                this::toggleTable);
        x2 += 46;
        addRibbonButton(x2, y2, 44, Component.literal("Filter"),
                "Show or hide the filter buttons",
                this::toggleFilter);
    }

    private void togglePalette(ColorPalette.Target target, int anchorX) {
        formatMenu.close();
        filterMenu.close();

        if (palette.isOpen() && palette.target() == target) {
            palette.close();
        } else {
            palette.open(target, anchorX, RIBBON_Y + 26, this.width);
        }
    }

    private void toggleFormatMenu(int anchorX) {
        palette.close();
        filterMenu.close();

        if (formatMenu.isOpen()) {
            formatMenu.close();
        } else {
            formatMenu.open(anchorX, RIBBON_Y + 3 + 24 + 22, this.width);
        }
    }

    private void applyNumberFormat(String format) {
        applyFormat(new CellFormat.Change(CellFormat.Kind.NUMBER_FORMAT, 0, format));
    }

    // A color picked in the palette; NONE means "No fill" / "Automatic".
    private void applyPaletteColor(ColorPalette.Target target, int color) {
        if (target == ColorPalette.Target.FILL) {
            if (color >= 0) {
                lastFillColor = color;
            }

            applyFormat(new CellFormat.Change(CellFormat.Kind.FILL, color));
        } else {
            if (color >= 0) {
                lastTextColor = color;
            }

            applyFormat(new CellFormat.Change(CellFormat.Kind.FONT_COLOR, color));
        }
    }

    // Bold, italic and underline switch on or off based on the active cell.
    private void toggleFlag(CellFormat.Kind kind, boolean currentlyOn) {
        applyFormat(new CellFormat.Change(kind, currentlyOn ? 0 : 1));
    }

    // Clicking the alignment that is already set goes back to general.
    private void applyAlignment(int code) {
        int current = activeFormat().hAlign();

        applyFormat(new CellFormat.Change(CellFormat.Kind.ALIGN, current == code ? 0 : code));
    }

    private void changeFontSize(int delta) {
        int size = Math.max(6, Math.min(72, activeFormat().fontPoints() + delta));

        applyFormat(new CellFormat.Change(CellFormat.Kind.FONT_SIZE, size));
    }

    // Applies a formatting change to every cell in the selected range as one undo step.
    private void applyFormat(CellFormat.Change change) {
        if (manager == null) {
            return;
        }

        commitPendingEdit();

        int top = rangeTop();
        int bottom = rangeBottom();
        int left = rangeLeft();
        int right = rangeRight();

        if ((long) (bottom - top + 1) * (right - left + 1) > MAX_CLEAR_CELLS) {
            status = "Selection too large to format";
            return;
        }

        int changed = 0;
        boolean hasEarlierEdit = false;
        String error = null;

        try {
            for (int row = top; row <= bottom; row++) {
                for (int column = left; column <= right; column++) {
                    int[] styles = manager.applyFormat(sheetIndex, row, column, change);

                    if (styles == null) {
                        continue;
                    }

                    undoHistory.push(new CellEdit(
                            sheetIndex, row, column, "", "",
                            hasEarlierEdit, styles[0], styles[1]
                    ));

                    hasEarlierEdit = true;
                    changed++;
                }
            }
        } catch (RuntimeException exception) {
            error = "Could not format: " + exception.getMessage();
        }

        if (change.kind() == CellFormat.Kind.FONT_SIZE) {
            fitRowHeights(top, bottom, change.value());
        }

        if (changed > 0) {
            redoHistory.clear();
            markDirty();
        }

        if (error != null) {
            status = error;
        } else if (changed > 0) {
            status = "Formatted " + rangeRef(top, left, bottom, right);
        } else {
            status = "Already formatted that way";
        }
    }

    // Makes rows tall enough for larger text (rows are never shrunk).
    private void fitRowHeights(int top, int bottom, int points) {
        int needed = (int) Math.ceil(points * 1.82);

        for (int row = top; row <= bottom; row++) {
            if (baseRowHeight(row) < needed) {
                applyRowHeight(row, needed);
            }
        }
    }

    // Puts a value or formatting edit back (undo) or applies it again (redo).
    private void applyEdit(CellEdit edit, boolean forward) {
        if (edit.previousStyle() >= 0) {
            manager.setCellStyleIndex(
                    edit.sheet(), edit.row(), edit.column(),
                    forward ? edit.newStyle() : edit.previousStyle()
            );
        } else {
            manager.setCellValueDeferred(
                    edit.sheet(), edit.row(), edit.column(),
                    forward ? edit.newValue() : edit.previousValue()
            );
        }
    }

    // Drawn on top of the buttons: the color bars, the font size and the popup palette.
    private void renderRibbonOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        if (manager == null) {
            return;
        }

        int barY = RIBBON_Y + 3 + 16;

        graphics.fill(fillButtonX + 5, barY, fillButtonX + 33, barY + 3, 0xFF000000 | lastFillColor);
        graphics.fill(textButtonX + 5, barY, textButtonX + 21, barY + 3, 0xFF000000 | lastTextColor);

        graphics.drawString(
                this.font,
                Integer.toString(activeFormat().fontPoints()),
                sizeLabelX, RIBBON_Y + 9, 0xFFFFFFFF
        );

        // The dropdown button shows the name of the active cell's number format.
        graphics.drawString(
                this.font,
                FormatMenu.nameOf(activeFormat().numberFormat()) + " v",
                formatButtonX + 6, RIBBON_Y + 3 + 24 + 6, 0xFFFFFFFF
        );

        beginOverlay(graphics);
        palette.render(graphics, this.font, mouseX, mouseY);
        formatMenu.render(graphics, this.font, mouseX, mouseY, activeFormat().numberFormat());
        filterMenu.render(graphics, this.font, mouseX, mouseY);
        endOverlay(graphics);
    }

    // ----- fixed GUI size -----

    // GUI size 2, or the largest size that fits when the window is small.
    private static int fixedGuiScale(Minecraft minecraft) {
        int largestThatFits = minecraft.getWindow().calculateScale(0, minecraft.isEnforceUnicode());

        return Math.max(1, Math.min(FIXED_GUI_SCALE, largestThatFits));
    }

    // Runs before the screen is shown, so the screen is laid out at the fixed size.
    private void applyGuiScale() {
        Minecraft minecraft = Minecraft.getInstance();

        minecraft.getWindow().setGuiScale(fixedGuiScale(minecraft));
        guiScaleOverridden = true;
    }

    // Puts the GUI size from the video settings back (the next screen is set up after this).
    private void restoreGuiScale() {
        if (!guiScaleOverridden) {
            return;
        }

        guiScaleOverridden = false;

        Minecraft minecraft = Minecraft.getInstance();

        minecraft.getWindow().setGuiScale(minecraft.getWindow().calculateScale(
                minecraft.options.guiScale().get(), minecraft.isEnforceUnicode()));
    }

    // Resizing the window resets the GUI size, so put ours back.
    //? if >=26.1 {
    /*@Override
    public void resize(int width, int height) {
        if (!guiScaleOverridden) {
            super.resize(width, height);
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getWindow().setGuiScale(fixedGuiScale(minecraft));
        super.resize(
                minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight()
        );
    }
    *///?} else {
    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        if (!guiScaleOverridden) {
            super.resize(minecraft, width, height);
            return;
        }

        minecraft.getWindow().setGuiScale(fixedGuiScale(minecraft));
        super.resize(
                minecraft,
                minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight()
        );
    }
    //?}

    // ----- tables and filters -----

    private static TableInfo findTable(List<TableInfo> tables, int row, int column) {
        for (TableInfo table : tables) {
            if (table.contains(row, column)) {
                return table;
            }
        }

        return null;
    }

    // The main color of a table style such as "TableStyleMedium2" (an approximation of Excel's).
    private static int tableAccent(String style) {
        int end = style.length();
        int start = end;

        while (start > 0 && Character.isDigit(style.charAt(start - 1))) {
            start--;
        }

        int number = start < end ? Integer.parseInt(style.substring(start, end)) : 2;
        int[] accents = {0x595959, 0x4472C4, 0xED7D31, 0xA5A5A5, 0xFFC000, 0x5B9BD5, 0x70AD47};

        return accents[((number - 1) % 7 + 7) % 7];
    }

    private static int lighten(int rgb, double amount) {
        int red = (int) Math.round(((rgb >> 16) & 0xFF) + (255 - ((rgb >> 16) & 0xFF)) * amount);
        int green = (int) Math.round(((rgb >> 8) & 0xFF) + (255 - ((rgb >> 8) & 0xFF)) * amount);
        int blue = (int) Math.round((rgb & 0xFF) + (255 - (rgb & 0xFF)) * amount);

        return (red << 16) | (green << 8) | blue;
    }

    // Moves up or down by whole rows, skipping the rows a filter hides.
    private int stepRow(int row, int change) {
        int target = Math.max(0, row + change);

        if (change == 0 || baseRowHeight(target) != 0) {
            return target;
        }

        int direction = change > 0 ? 1 : -1;

        for (int guard = 0; target >= 0 && guard < 1_048_576 && baseRowHeight(target) == 0; guard++) {
            target += direction;
        }

        return target < 0 ? row : target;
    }

    // Left edge of a visible column / top edge of a visible row, on screen.
    private int columnLeft(int column) {
        int x = GRID_X;

        for (int c = firstVisibleColumn; c < column; c++) {
            x += columnWidth(c);
        }

        return x;
    }

    private int rowTop(int row) {
        int y = GRID_Y + headerHeight();

        for (int r = firstVisibleRow; r < row; r++) {
            y += rowHeight(r);
        }

        return y;
    }

    private static String describe(Throwable problem) {
        return problem.getMessage() != null ? problem.getMessage() : problem.getClass().getSimpleName();
    }

    // Opens the filter list when the click is on a header cell's arrow.
    private boolean openFilterMenuAt(double mouseX, double mouseY) {
        if (!insideGrid(mouseX, mouseY)) {
            return false;
        }

        int row = rowAt(mouseY);
        int column = columnAt(mouseX);
        TableInfo table = manager.tableAt(sheetIndex, row, column);

        if (table == null || !table.filterButtons() || row != table.top()) {
            return false;
        }

        int cellLeft = columnLeft(column);
        int cellRight = cellLeft + columnWidth(column);
        int top = rowTop(row);
        int arrowWidth = Math.min(14, columnWidth(column) / 2);

        if (mouseX < cellRight - arrowWidth - 2 || mouseX >= cellRight - 2
                || mouseY < top + 2 || mouseY >= top + rowHeight(row) - 2) {
            return false;
        }

        commitPendingEdit();
        palette.close();
        formatMenu.close();

        int offset = column - table.left();

        try {
            TableInfo.FilterState state = manager.getFilterState(sheetIndex, table, offset);

            if (state.custom()) {
                status = "This column has a filter that can't be edited here. "
                        + "Use the Filter button to remove it.";
                return true;
            }

            filterMenu.open(
                    cellRight - FilterMenu.WIDTH,
                    top + rowHeight(row),
                    this.width,
                    this.height,
                    table,
                    offset,
                    manager.getDistinctValues(sheetIndex, table, offset, 500),
                    state.active() ? state.allowed() : null
            );
        } catch (RuntimeException | LinkageError exception) {
            status = "Could not open the filter list: " + describe(exception);
        }

        return true;
    }

    // OK was pressed in the filter list: apply the ticked values.
    private void applyFilterMenu() {
        TableInfo table = filterMenu.table();

        try {
            manager.setColumnFilter(
                    sheetIndex, table, filterMenu.columnOffset(), filterMenu.selected());
            rowHeightCache.clear();
            markDirty();

            // If the selected cell was filtered away, go back to the header.
            if (baseRowHeight(selectedRow) == 0) {
                selectedRow = table.top();
                collapseRange();
                refreshEditor();
            }

            status = filterMenu.selected() == null ? "Filter cleared" : "Filter applied";
        } catch (RuntimeException | LinkageError exception) {
            status = "Could not apply the filter: " + describe(exception);
        }
    }

    // The selected cells, or the block of data around the selected cell.
    private int[] selectionOrRegion() {
        if (hasMultiSelection()) {
            return new int[] {rangeTop(), rangeLeft(), rangeBottom(), rangeRight()};
        }

        return manager.detectRegion(sheetIndex, selectedRow, selectedColumn);
    }

    // The Table button: makes a table from the selection, or turns the table you are in back into a range.
    private void toggleTable() {
        if (manager == null) {
            return;
        }

        commitPendingEdit();
        filterMenu.close();

        try {
            TableInfo existing = manager.tableAt(sheetIndex, selectedRow, selectedColumn);

            if (existing != null && existing.isTable()) {
                manager.removeTable(sheetIndex, existing);
                status = existing.name() + " is a normal range again";
            } else {
                int[] area = selectionOrRegion();
                TableInfo created = manager.createTable(
                        sheetIndex, area[0], area[1], area[2], area[3], "TableStyleMedium2");

                status = "Created " + created.name();
            }

            rowHeightCache.clear();
            markDirty();
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
        } catch (RuntimeException | LinkageError exception) {
            status = "Could not change the table: " + describe(exception);
        }
    }

    // The Filter button: shows or hides the filter arrows (for a table, or for a plain range).
    private void toggleFilter() {
        if (manager == null) {
            return;
        }

        commitPendingEdit();
        filterMenu.close();

        try {
            TableInfo existing = manager.tableAt(sheetIndex, selectedRow, selectedColumn);

            if (existing != null) {
                manager.setFilterButtons(sheetIndex, existing, !existing.filterButtons());
                status = existing.filterButtons() ? "Filter buttons hidden" : "Filter buttons shown";
            } else {
                int[] area = selectionOrRegion();

                manager.addFilterRange(sheetIndex, area[0], area[1], area[2], area[3]);
                status = "Filter buttons added";
            }

            rowHeightCache.clear();
            markDirty();
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
        } catch (RuntimeException | LinkageError exception) {
            status = "Could not change the filter: " + describe(exception);
        }
    }

    // ----- remembering the view between visits -----

    // Brings back the zoom, sheet, selected cell and scroll position from last time.
    private void restoreViewState() {
        zoomPercent = Math.max(50, Math.min(300, ViewStateStore.loadZoom(zoomPercent)));

        ViewStateStore.ViewState saved =
                ViewStateStore.load(workbookPath.getFileName().toString());

        if (saved == null || manager == null) {
            return;
        }

        if (saved.sheet() >= 0 && saved.sheet() < manager.getSheetCount()) {
            sheetIndex = saved.sheet();
        }

        selectedRow = Math.max(0, Math.min(1_048_575, saved.selectedRow()));
        selectedColumn = Math.max(0, Math.min(16_383, saved.selectedColumn()));
        firstVisibleRow = Math.max(0, Math.min(1_048_575, saved.firstRow()));
        firstVisibleColumn = Math.max(0, Math.min(16_383, saved.firstColumn()));
        collapseRange();

        // Make sure the tab of the restored sheet is showing.
        int lastFirstTab = Math.max(0, manager.getSheetCount() - MAX_VISIBLE_TABS);
        firstVisibleTab = Math.max(0, Math.min(lastFirstTab, sheetIndex - MAX_VISIBLE_TABS + 1));
    }

    private void saveViewState() {
        if (!opened) {
            return;
        }

        ViewStateStore.save(
                workbookPath.getFileName().toString(),
                zoomPercent,
                new ViewStateStore.ViewState(
                        sheetIndex,
                        selectedRow,
                        selectedColumn,
                        firstVisibleRow,
                        firstVisibleColumn
                )
        );
    }

    // Called whenever this screen is closed or replaced.
    @Override
    public void removed() {
        saveViewState();
        restoreGuiScale();
        super.removed();
    }

    @Override
    public void onClose() {
        requestExit();
    }

    private class ZoomSlider extends AbstractSliderButton {
        ZoomSlider(int x, int y, int width, int height, int initialZoom) {
            super(
                    x, y, width, height,
                    Component.literal("Zoom: " + initialZoom + "%"),
                    (initialZoom - 50) / 250.0
            );
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int newZoom = 50 + (int) Math.round(this.value * 250);
            setMessage(Component.literal("Zoom: " + newZoom + "%"));
        }

        @Override
        protected void applyValue() {
            commitPendingEdit();
            zoomPercent = 50 + (int) Math.round(this.value * 250);
            scrollSelectionIntoView();
        }
    }
}
