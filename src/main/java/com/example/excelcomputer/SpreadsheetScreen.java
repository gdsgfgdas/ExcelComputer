package com.example.excelcomputer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpreadsheetScreen extends Screen {
    private static final int GRID_X = 45;
    private static final int GRID_Y = 72;
    private static final int BASE_CELL_WIDTH = 70;
    private static final int BASE_CELL_HEIGHT = 20;
    private static final int FIRST_TAB_X = 145;
    private static final int TAB_WIDTH = 85;
    private static final int MAX_VISIBLE_TABS = 5;

    // Space kept free below the grid for the hint line, tabs and buttons.
    private static final int BOTTOM_RESERVED = 72;

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
            boolean chained
    ) {}

    public SpreadsheetScreen(Path workbookPath) {
        super(Component.literal("Excel Computer"));
        this.workbookPath = workbookPath;

        try {
            manager = WorkbookManager.open(workbookPath);
            status = "Workbook: " + workbookPath.getFileName();
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

    // The grid fits the window instead of using a fixed size.
    private int visibleColumns() {
        return Math.max(1, (this.width - GRID_X - 10) / cellWidth());
    }

    private int visibleRows() {
        int available = this.height - GRID_Y - cellHeight() - BOTTOM_RESERVED;
        return Math.max(1, available / cellHeight());
    }

    private void scrollCellIntoView(int row, int column) {
        int rows = visibleRows();
        int cols = visibleColumns();

        if (row < firstVisibleRow) {
            firstVisibleRow = row;
        } else if (row >= firstVisibleRow + rows) {
            firstVisibleRow = row - rows + 1;
        }

        if (column < firstVisibleColumn) {
            firstVisibleColumn = column;
        } else if (column >= firstVisibleColumn + cols) {
            firstVisibleColumn = column - cols + 1;
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
    //? if <1.21.6 {
    /*@Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    *///?}

    @Override
    public void render(
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
        graphics.drawString(this.font, status, 12, 58, 0xFFFFFFFF);

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

        int cw = cellWidth();
        int ch = cellHeight();
        int cols = visibleColumns();
        int rows = visibleRows();

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

        graphics.fill(
                GRID_X, GRID_Y,
                GRID_X + cols * cw, GRID_Y + ch,
                0xFF3C4043
        );

        for (int col = 0; col < cols; col++) {
            int actualColumn = firstVisibleColumn + col;
            int x = GRID_X + col * cw;

            graphics.drawString(
                    this.font, columnName(actualColumn),
                    x + 5, GRID_Y + Math.max(2, ch / 4),
                    0xFFFFFFFF
            );
        }

        for (int row = 0; row < rows; row++) {
            int actualRow = firstVisibleRow + row;
            int y = GRID_Y + ch + row * ch;

            graphics.fill(10, y, GRID_X, y + ch, 0xFF3C4043);
            graphics.drawString(
                    this.font, Integer.toString(actualRow + 1),
                    20, y + Math.max(2, ch / 4), 0xFFFFFFFF
            );

            for (int col = 0; col < cols; col++) {
                int actualColumn = firstVisibleColumn + col;
                int x = GRID_X + col * cw;

                int background = (actualRow + actualColumn) % 2 == 0
                        ? 0xFFFFFFFF : 0xFFF7F7F7;

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

                String value = manager.getCellText(
                        sheetIndex, actualRow, actualColumn
                );

                if (value.isEmpty()) {
                    continue;
                }

                // Clip long text to the cell instead of cutting characters.
                graphics.enableScissor(x + 1, y + 1, x + cw - 1, y + ch - 1);
                pushTextScale(
                        graphics,
                        x + 4,
                        y + (ch - this.font.lineHeight * textScale) / 2f,
                        textScale
                );
                graphics.drawString(this.font, value, 0, 0, 0xFF202124, false);
                popTextScale(graphics);
                graphics.disableScissor();
            }
        }

        graphics.drawString(
                this.font,
                "Drag/Shift+Arrows: select | Ctrl+C/X/V: copy/cut/paste | Formula: click cells | Ctrl+S: save",
                GRID_X, GRID_Y + ch * (rows + 1) + 5,
                0xFFFFFFFF
        );

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double scrollX,
            double scrollY
    ) {
        if (manager == null) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
        rangeEndRow = Math.max(0, rangeEndRow + rowChange);

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
                    manager.setCellValueDeferred(
                            sheetIndex, targetRow, targetColumn, input
                    );

                    undoHistory.push(new CellEdit(
                            sheetIndex, targetRow, targetColumn,
                            previous, input, hasEarlierEdit
                    ));

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
                manager.setCellValueDeferred(
                        edit.sheet(),
                        edit.row(),
                        edit.column(),
                        edit.previousValue()
                );

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
                manager.setCellValueDeferred(
                        edit.sheet(),
                        edit.row(),
                        edit.column(),
                        edit.newValue()
                );

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
        return firstVisibleColumn
                + (int) Math.floor((mouseX - GRID_X) / cellWidth());
    }

    private int rowAt(double mouseY) {
        return firstVisibleRow
                + (int) Math.floor(
                        (mouseY - (GRID_Y + cellHeight())) / cellHeight());
    }

    private boolean insideGrid(double mouseX, double mouseY) {
        int firstRowY = GRID_Y + cellHeight();

        return mouseX >= GRID_X
                && mouseX < GRID_X + (double) visibleColumns() * cellWidth()
                && mouseY >= firstRowY
                && mouseY < firstRowY + (double) visibleRows() * cellHeight();
    }

    // While dragging outside the grid, scroll one step and use the edge cell.
    private int dragColumn(double mouseX) {
        int cols = visibleColumns();

        if (mouseX < GRID_X) {
            firstVisibleColumn = Math.max(0, firstVisibleColumn - 1);
            return firstVisibleColumn;
        }

        if (mouseX >= GRID_X + (double) cols * cellWidth()) {
            firstVisibleColumn++;
            return firstVisibleColumn + cols - 1;
        }

        return columnAt(mouseX);
    }

    private int dragRow(double mouseY) {
        int rows = visibleRows();
        int gridTop = GRID_Y + cellHeight();

        if (mouseY < gridTop) {
            firstVisibleRow = Math.max(0, firstVisibleRow - 1);
            return firstVisibleRow;
        }

        if (mouseY >= gridTop + (double) rows * cellHeight()) {
            firstVisibleRow++;
            return firstVisibleRow + rows - 1;
        }

        return rowAt(mouseY);
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

        cellEditor.moveCursorTo(pointEnd, false);
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
        selectedRow = Math.max(0, selectedRow + rowChange);
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
            manager.setCellValue(
                    sheetIndex,
                    selectedRow,
                    selectedColumn,
                    newValue
            );

            undoHistory.push(edit);

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
