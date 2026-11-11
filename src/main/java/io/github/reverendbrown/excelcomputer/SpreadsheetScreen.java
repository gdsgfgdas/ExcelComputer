package io.github.reverendbrown.excelcomputer;

import io.github.reverendbrown.excelcomputer.share.ShareClient;
import io.github.reverendbrown.excelcomputer.share.ShareProtocol;
import io.github.reverendbrown.excelcomputer.share.SharedWorkbook;
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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SpreadsheetScreen extends Screen {
    // Height of every button in this screen (vanilla is 20). The layout
    // below is derived from it, so changing this one value is enough.
    private static final int BUTTON_HEIGHT = 10;
    // Vertical offset of a line of text centred in a button.
    private static final int BUTTON_TEXT_Y = (BUTTON_HEIGHT - 8) / 2;

    // The Home ribbon, just below the title bar.
    private static final int RIBBON_PANEL_TOP = 30;
    // Top of the Home ribbon area, below the "Home" label.
    private static final int RIBBON_Y = RIBBON_PANEL_TOP + 14;
    private static final int RIBBON_ROW1_Y = RIBBON_Y + 3;
    private static final int RIBBON_ROW2_Y = RIBBON_ROW1_Y + BUTTON_HEIGHT + 4;
    // Group names (Clipboard, Font, ...) sit under the buttons, like Excel.
    private static final int RIBBON_GROUP_LABEL_Y = RIBBON_ROW2_Y + BUTTON_HEIGHT + 4;
    private static final int RIBBON_PANEL_BOTTOM = RIBBON_GROUP_LABEL_Y + 8 + 3;

    // Formula bar (the cell editor and Save), below the ribbon.
    private static final int FORMULA_PANEL_TOP = RIBBON_PANEL_BOTTOM + 4;
    private static final int FORMULA_Y = FORMULA_PANEL_TOP + 2;
    private static final int FORMULA_PANEL_BOTTOM = FORMULA_Y + BUTTON_HEIGHT + 2;
    // The fx (Insert Function) button, between the cell address and the editor.
    private static final int FX_X = 80;
    private static final int FX_WIDTH = 16;

    private static final int GRID_X = 45;
    private static final int GRID_Y = FORMULA_PANEL_BOTTOM + 4;
    private static final int BASE_CELL_WIDTH = 70;
    private static final int BASE_CELL_HEIGHT = 20;
    // Bottom of the screen, measured up from the bottom edge: the row with
    // Back to files and the zoom slider, and above it Excel's sheet
    // bar (tab arrows, sheet tabs, column scroll bar) right under the grid.
    private static final int BOTTOM_ROW_OFFSET = 8 + BUTTON_HEIGHT;
    private static final int SHEET_BAR_HEIGHT = 13;
    private static final int SHEET_BAR_OFFSET = BOTTOM_ROW_OFFSET + 4 + SHEET_BAR_HEIGHT;

    // Space kept free below the grid for the sheet bar and the bottom row.
    private static final int BOTTOM_RESERVED = SHEET_BAR_OFFSET;

    // Sheet bar pieces (x positions; the scroll bar is placed from the right).
    private static final int TAB_ARROW_WIDTH = 12;
    private static final int TABS_LEFT = 12 + TAB_ARROW_WIDTH * 2 + 6;
    private static final int SCROLL_ARROW_WIDTH = 10;

    // How close (in pixels) the mouse must be to a header edge to grab it.
    private static final int RESIZE_GRAB = 4;

    // Zoom slider range, in percent.
    // Below about 30% cells get too small for text and layout to behave.
    private static final int MIN_ZOOM = 30;
    private static final int MAX_ZOOM = 300;

    // Smallest size a column or row is drawn at, however far you zoom out.
    private static final int MIN_DRAWN_SIZE = 2;

    // The GUI size this screen always uses, whatever the video setting says.
    private static final int FIXED_GUI_SCALE = 2;

    // Largest range that Delete/Backspace will clear in one go.
    private static final long MAX_CLEAR_CELLS = 10_000;

    // The last row and column of a worksheet (0-based), as in Excel.
    private static final int LAST_ROW = 1_048_575;
    private static final int LAST_COLUMN = 16_383;

    // Two clicks on the same header edge within this time are a double-click (AutoFit).
    private static final long DOUBLE_CLICK_MS = 400;

    // Largest area that copy/paste will handle in one go.
    private static final long MAX_PASTE_CELLS = 10_000;

    // Matches A1, $A$1, A$1, $A1 (not function names like LOG10( ).
    private static final Pattern CELL_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_.])(\\$?)([A-Za-z]{1,3})(\\$?)(\\d+)(?![A-Za-z0-9_(!])");

    private final Path workbookPath;
    // Set when this workbook came from a shared workbook item.
    private final SharedWorkbook shared;
    // Where a player who doesn't own a shared workbook saves their own copy.
    private Path sharedCopyPath;
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
    // Set when the active sheet changes, so its tab is scrolled into view
    // (the tab arrows can still scroll it away afterwards, like Excel).
    private boolean revealActiveTab = true;

    // Dragging the column scroll bar's thumb.
    private boolean draggingScrollThumb;
    private double scrollThumbGrab;
    private int scrollDragExtent;
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
    // The columns or rows that get the new size when the drag ends: all the
    // selected ones if the dragged one is part of a whole-column/row
    // selection, like Excel, otherwise just the dragged one.
    private int resizeFirst;
    private int resizeLast;
    private int[] resizeStartSizes;

    // The last click on a header edge, to spot a double-click (AutoFit).
    private long lastEdgeClickTime;
    private int lastEdgeClickColumn = -1;
    private int lastEdgeClickRow = -1;

    // Dragging across the column letters or row numbers selects whole columns or rows.
    private boolean headerSelectingColumns;
    private boolean headerSelectingRows;

    // Ctrl+Space and Shift+Space are selection shortcuts; this keeps their
    // space from also starting to type in the cell (the time it was pressed).
    private long swallowSpaceUntil;

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

    // Ribbon tabs, like Excel's. Each tab shows its own set of buttons.
    private static final String[] RIBBON_TABS = {"Home", "Insert", "Draw"};
    private static final int TAB_HOME = 0;
    private static final int TAB_INSERT = 1;
    private static final int TAB_DRAW = 2;
    private static final int RIBBON_TAB_LEFT = 16;
    private static final int RIBBON_TAB_GAP = 14;
    private int ribbonTab = TAB_HOME;
    // The current tab's groups, filled in while its buttons are added.
    private final List<RibbonGroup> ribbonGroups = new ArrayList<>();

    // Home ribbon: the color popup and the colors the main buttons apply.
    private final ColorPalette palette = new ColorPalette();
    private int lastFillColor = 0xFFFF00;
    private int lastTextColor = 0xFF0000;
    private int fillButtonX;
    private int textButtonX;
    private int sizeLabelX;
    private final FormatMenu formatMenu = new FormatMenu();
    private final PasteMenu pasteMenu = new PasteMenu();
    // The right-click menu on cells, and the formatting bar above it.
    private final CellMenu cellMenu = new CellMenu();
    private final MiniToolbar miniToolbar = buildMiniToolbar();
    private final FilterMenu filterMenu = new FilterMenu();
    // The search bar for one table, and which of its rows match (bit 0 is the
    // header row). Null when they need working out again (after any change).
    private final TableSearchBar tableSearch = new TableSearchBar();
    private java.util.BitSet searchMatches;
    private int searchBottom;
    private int formatButtonX;

    // Borders: the dropdown, the preset the Borders button repeats, and the
    // "pen" (line style and color) that presets draw with.
    private final BorderMenu borderMenu = new BorderMenu();
    private final FunctionMenu functionMenu = new FunctionMenu();
    // The function list that pops up under the formula bar while a formula is typed.
    private final FormulaAutocomplete autocomplete = new FormulaAutocomplete();
    // Home > Conditional Formatting: the dropdown, and the window for a rule's
    // value and look (null when closed) with the range it applies to.
    private final ConditionalMenu conditionalMenu = new ConditionalMenu();
    private ConditionalDialog conditionalDialog;
    private int[] conditionalRange;
    private BorderMenu.Option lastBorder = BorderMenu.Option.BOTTOM;
    private int penStyle = CellFormat.LINE_THIN;
    private int penColor = -1;
    private int borderButtonX;
    private Button borderButton;

    // Copy/cut data: the raw input of every copied cell (formulas as "=...").
    private String[][] clipboardInputs;
    // The calculated value of every copied cell (for Paste Values).
    private String[][] clipboardValues;
    // Unzoomed pixel sizes of the copied columns and rows, or null for a CSV
    // file (which has no sizes). A row hidden by a filter is 0.
    private int[] clipboardColumnWidths;
    private int[] clipboardRowHeights;
    // Style index of each copied cell (colors, font, borders, number format),
    // or null for a CSV file, which has no formatting.
    private int[][] clipboardStyles;
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
            int newStyle,
            // A size edit (from pasting) changes a column width (row is -1)
            // or a row height (column is -1); the styles hold the pixel sizes.
            boolean size,
            // A table or filter change (Table / Filter buttons): the sheet's
            // tables before and after it. Null for every other edit.
            TableChange table,
            // A conditional formatting change: the sheet's rules before and
            // after it. Null for every other edit.
            RuleChange rules,
            // A drawing change (Draw tab): the sheet's strokes before and after it.
            // Null for every other edit.
            InkChange ink
    ) {
        CellEdit(
                int sheet,
                int row,
                int column,
                String previousValue,
                String newValue,
                boolean chained
        ) {
            this(sheet, row, column, previousValue, newValue, chained, -1, -1, false, null, null, null);
        }

        CellEdit(
                int sheet,
                int row,
                int column,
                String previousValue,
                String newValue,
                boolean chained,
                int previousStyle,
                int newStyle
        ) {
            this(sheet, row, column, previousValue, newValue, chained, previousStyle, newStyle, false,
                    null, null, null);
        }

        static CellEdit resize(int sheet, int row, int column, boolean chained, int previous, int updated) {
            return new CellEdit(sheet, row, column, "", "", chained, previous, updated, true, null, null, null);
        }

        // row and column: the cell that undo/redo selects (the table's top-left corner).
        static CellEdit tableChange(int sheet, int row, int column, TableChange change) {
            return new CellEdit(sheet, row, column, "", "", false, -1, -1, false, change, null, null);
        }

        // row and column: the cell that undo/redo selects (the range's top-left corner).
        static CellEdit ruleChange(int sheet, int row, int column, RuleChange change) {
            return new CellEdit(sheet, row, column, "", "", false, -1, -1, false, null, change, null);
        }

        // A drawing change keeps the selection where it is (see selectAfterHistory).
        static CellEdit inkChange(int sheet, InkChange change) {
            return new CellEdit(sheet, 0, 0, "", "", false, -1, -1, false, null, null, change);
        }

        // What undo/redo reports, e.g. "Undid: adding a color scale".
        String description() {
            return table != null ? table.description() : rules != null ? rules.description()
                    : ink != null ? ink.description() : null;
        }
    }

    private record InkChange(
            List<InkDrawings.Stroke> before,
            List<InkDrawings.Stroke> after,
            String description
    ) {
    }

    private record TableChange(
            WorkbookManager.TableState before,
            WorkbookManager.TableState after,
            String description
    ) {
    }

    private record RuleChange(
            ConditionalFormats.State before,
            ConditionalFormats.State after,
            String description
    ) {
    }

    // Opening and saving run on this thread, so a big workbook does not freeze
    // the game (a long freeze can get a player timed out of a server). One
    // thread for all workbooks keeps them in order: reopening a file waits for
    // its save to finish.
    private static final ExecutorService WORKBOOK_IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Excel on a Stick workbook IO");
        thread.setDaemon(true);
        return thread;
    });

    // Work running on WORKBOOK_IO; tick() picks up the result on the game thread.
    private CompletableFuture<WorkbookManager> pendingOpen;
    private CompletableFuture<Void> pendingSave;

    public SpreadsheetScreen(Path workbookPath) {
        this(workbookPath, null);
    }

    public SpreadsheetScreen(Path workbookPath, SharedWorkbook shared) {
        super(Component.literal("Excel on a Stick"));
        this.workbookPath = workbookPath;
        this.shared = shared;
        applyGuiScale();

        status = "Opening " + workbookPath.getFileName() + "...";
        pendingOpen = CompletableFuture.supplyAsync(() -> {
            try {
                return WorkbookManager.open(workbookPath);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, WORKBOOK_IO);
    }

    // POI workbooks cannot be changed while they are being written, so input
    // waits while a save runs (the rest of the game keeps going).
    private boolean saving() {
        return pendingSave != null;
    }

    @Override
    public void tick() {
        super.tick();
        finishPendingWork();
    }

    private void finishPendingWork() {
        if (pendingOpen != null && pendingOpen.isDone()) {
            try {
                manager = pendingOpen.join();
                status = shared == null
                        ? "Workbook: " + workbookPath.getFileName()
                        : shared.isConnector()
                        ? ShareClient.isHub(shared.connector())
                        ? ShareClient.linkedLabel(shared.connector())
                                + ": the Transfers sheet says what to move. Save to update the hub"
                        : "Redstone Connector: the Redstone sheet's Out column sets each side's signal. Save to update it"
                        : shared.owner()
                        ? "Shared workbook, " + ShareProtocol.describeExpiry(shared.expiresAt())
                                + ". Saving updates it for everyone"
                        : "Shared by " + shared.ownerName() + ", " + ShareProtocol.describeExpiry(shared.expiresAt())
                                + ". Saving keeps a copy in your workbooks";
                opened = true;
                restoreViewState();
            } catch (CompletionException | CancellationException exception) {
                status = "Could not open workbook: " + messageOf(exception);
            }

            pendingOpen = null;
            // The ribbon, tabs and editor need the workbook, so lay them out again.
            rebuildWidgets();
        }

        if (pendingSave != null && pendingSave.isDone()) {
            try {
                pendingSave.join();
                dirty = false;
                discardArmed = false;
                status = "Saved: " + workbookPath.getFileName();
                afterSharedSave();
            } catch (CompletionException | CancellationException exception) {
                status = "Save failed: " + messageOf(exception);
            }

            pendingSave = null;
        }
    }

    private static String messageOf(Throwable exception) {
        Throwable cause = exception instanceof CompletionException && exception.getCause() != null
                ? exception.getCause()
                : exception;

        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private int cellWidth() {
        return Math.max(MIN_DRAWN_SIZE, BASE_CELL_WIDTH * zoomPercent / 100);
    }

    private int cellHeight() {
        return Math.max(MIN_DRAWN_SIZE, BASE_CELL_HEIGHT * zoomPercent / 100);
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
        return Math.max(MIN_DRAWN_SIZE, (int) Math.round(baseColumnWidth(column) * zoomPercent / 100.0));
    }

    private int rowHeight(int row) {
        int base = baseRowHeight(row);

        // A row hidden by a filter, or left out by the table search, takes no room.
        return base == 0 || searchHides(row)
                ? 0 : Math.max(MIN_DRAWN_SIZE, (int) Math.round(base * zoomPercent / 100.0));
    }

    // Hidden by a filter or by the table search: moving the selection skips it.
    private boolean rowHidden(int row) {
        return baseRowHeight(row) == 0 || searchHides(row);
    }

    // The column letters stay readable when zoomed far out.
    private int headerHeight() {
        return Math.max(12, cellHeight());
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

    // Whole columns (clicked on the column letters) or whole rows (row numbers).
    private boolean wholeColumnsSelected() {
        return rangeTop() == 0 && rangeBottom() == LAST_ROW;
    }

    private boolean wholeRowsSelected() {
        return rangeLeft() == 0 && rangeRight() == LAST_COLUMN;
    }

    // The bottom and right of the selection for actions on cells (clear,
    // copy, format, ...). Whole columns or rows stop at the last used row or
    // column, like Excel, instead of touching a million empty cells. So does
    // any range that runs past both the data and the screen (e.g. Ctrl+Shift+Down
    // from a filled cell to the bottom of the sheet): it stops at whichever is further.
    private int actionBottom() {
        if (manager == null) {
            return rangeBottom();
        }

        if (wholeColumnsSelected()) {
            return Math.max(rangeTop(), Math.min(rangeBottom(), manager.getLastUsedRow(sheetIndex)));
        }

        int limit = Math.max(manager.getLastUsedRow(sheetIndex), firstVisibleRow + visibleRows());
        return Math.max(rangeTop(), Math.min(rangeBottom(), limit));
    }

    private int actionRight() {
        if (manager == null) {
            return rangeRight();
        }

        if (wholeRowsSelected()) {
            return Math.max(rangeLeft(), Math.min(rangeRight(), manager.getLastUsedColumn(sheetIndex)));
        }

        int limit = Math.max(manager.getLastUsedColumn(sheetIndex), firstVisibleColumn + visibleColumns());
        return Math.max(rangeLeft(), Math.min(rangeRight(), limit));
    }

    // Selects whole columns, from the anchor column to the end column.
    private void selectColumns(int anchor, int end) {
        selectedRow = 0;
        selectedColumn = anchor;
        rangeEndRow = LAST_ROW;
        rangeEndColumn = end;
        refreshEditor();
    }

    // Selects whole rows, from the anchor row to the end row.
    private void selectRows(int anchor, int end) {
        selectedRow = anchor;
        selectedColumn = 0;
        rangeEndRow = end;
        rangeEndColumn = LAST_COLUMN;
        refreshEditor();
    }

    private void selectAll() {
        selectedRow = 0;
        selectedColumn = 0;
        rangeEndRow = LAST_ROW;
        rangeEndColumn = LAST_COLUMN;
        refreshEditor();
    }

    private static String cellRef(int row, int column) {
        return columnName(column) + (row + 1);
    }

    // "B2", "B2:D5", or for whole columns "C:E" and whole rows "3:5", like Excel.
    private static String rangeRef(int row1, int column1, int row2, int column2) {
        int top = Math.min(row1, row2);
        int bottom = Math.max(row1, row2);
        int left = Math.min(column1, column2);
        int right = Math.max(column1, column2);

        if (top == bottom && left == right) {
            return cellRef(top, left);
        }

        if (left == 0 && right == LAST_COLUMN) {
            return (top + 1) + ":" + (bottom + 1);
        }

        if (top == 0 && bottom == LAST_ROW) {
            return columnName(left) + ":" + columnName(right);
        }

        return cellRef(top, left) + ":" + cellRef(bottom, right);
    }

    private void markDirty() {
        dirty = true;
        discardArmed = false;
        // The cells may have changed, so the table search looks again.
        searchMatches = null;
    }

    // ----- the window -----
    // The spreadsheet is drawn in a window in the middle of the screen, so the
    // game stays visible around it. Everything inside is laid out and drawn as
    // if the window were the whole screen (this.width and this.height are the
    // window's size), then moved into place: drawing is translated by the
    // window's corner, mouse positions are converted into window coordinates,
    // and the widgets are moved onto their real screen positions.

    // Full screen instead of a window (the button in the title bar). Kept
    // while the game runs, for every workbook.
    private static boolean maximized;

    private int windowX;
    private int windowY;
    // The real mouse position, for drawing the widgets (see renderWidgets).
    private int screenMouseX;
    private int screenMouseY;

    // Right edge of the widest ribbon laid out so far (the Home tab). The
    // ribbon has a fixed layout, so the window is never made narrower than this.
    private static int widestRibbon;

    // Size of the maximize button in the title bar, measured from the window's right edge.
    private static final int MAXIMIZE_RIGHT = 24;
    private static final int MAXIMIZE_WIDTH = 16;

    @Override
    protected void init() {
        int screenWidth = this.minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = this.minecraft.getWindow().getGuiScaledHeight();
        int marginX = maximized ? 0 : Math.max(12, screenWidth / 14);
        int marginY = maximized ? 0 : Math.max(10, screenHeight / 14);

        // Too small a screen for a window: use all of it.
        if (screenWidth - marginX * 2 < 400 || screenHeight - marginY * 2 < 260) {
            marginX = 0;
            marginY = 0;
        }

        // Never narrower than the ribbon: shrink the side margins instead.
        int ribbonNeeds = widestRibbon + 14;

        if (screenWidth - marginX * 2 < ribbonNeeds) {
            marginX = Math.max(0, (screenWidth - ribbonNeeds) / 2);
        }

        windowX = marginX;
        windowY = marginY;
        this.width = screenWidth - marginX * 2;
        this.height = screenHeight - marginY * 2;

        layoutWidgets();

        // The first time, the ribbon's width is only known after laying it out.
        if (marginX > 0 && widestRibbon + 14 > this.width) {
            marginX = Math.max(0, (screenWidth - widestRibbon - 14) / 2);
            windowX = marginX;
            this.width = screenWidth - marginX * 2;
            this.clearWidgets();
            layoutWidgets();
        }

        this.addRenderableWidget(
                Button.builder(Component.literal(maximized ? "o" : "[ ]"), button -> {
                    maximized = !maximized;
                    rebuildWidgets();
                }).bounds(this.width - MAXIMIZE_RIGHT, 8, MAXIMIZE_WIDTH, BUTTON_HEIGHT)
                        .tooltip(Tooltip.create(Component.literal(maximized ? "Show as a window" : "Full screen")))
                        .build()
        );

        // Move every widget from window coordinates onto the screen.
        for (net.minecraft.client.gui.components.events.GuiEventListener child : this.children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget) {
                widget.setX(widget.getX() + windowX);
                widget.setY(widget.getY() + windowY);
            }
        }
    }

    // The game keeps running behind the window, even in singleplayer.
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void layoutWidgets() {
        cellEditor = new EditBox(
                this.font, 100, FORMULA_Y, Math.max(100, this.width - 100 - 70 - 8), BUTTON_HEIGHT,
                Component.literal("Cell value")
        );
        cellEditor.setMaxLength(1000);
        cellEditor.setValue(getSelectedCellInput());
        cellEditor.setFocused(false);
        this.addRenderableWidget(cellEditor);

        // fx: Insert Function. Its clicks are handled in handleMouseClicked so
        // the button never takes focus away from a formula being typed.
        this.addRenderableWidget(
                Button.builder(Component.literal("fx").withStyle(ChatFormatting.ITALIC), button -> { })
                        .bounds(FX_X, FORMULA_Y, FX_WIDTH, BUTTON_HEIGHT)
                        .tooltip(Tooltip.create(Component.literal("Insert Function")))
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(Component.literal("Save"), button -> saveWorkbook())
                        .bounds(this.width - 70, FORMULA_Y, 60, BUTTON_HEIGHT)
                        .build()
        );

        // The sheet bar (tabs and column scroll bar) is drawn and clicked by
        // the screen itself; see renderSheetBar and handleSheetBarClick.

        zoomSlider = new ZoomSlider(
                this.width - 150,
                this.height - BOTTOM_ROW_OFFSET,
                140,
                BUTTON_HEIGHT,
                zoomPercent
        );
        this.addRenderableWidget(zoomSlider);

        this.addRenderableWidget(
                Button.builder(Component.literal("Back to files"), button ->
                        requestExit()
                ).bounds(10, this.height - BOTTOM_ROW_OFFSET, 70, BUTTON_HEIGHT).build()
        );

        if (shared != null && !shared.isConnector()) {
            this.addRenderableWidget(
                    Button.builder(Component.literal("Copy item"), button ->
                            ShareClient.requestCopy(shared.id(), message -> status = message)
                    ).bounds(85, this.height - BOTTOM_ROW_OFFSET, 60, BUTTON_HEIGHT)
                            .tooltip(Tooltip.create(Component.literal(
                                    "Get another item for this shared workbook to give to someone. Costs 1 Excel Paper (free in creative).")))
                            .build()
            );
        }

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

        if (!commitPendingEdit()) {
            return;
        }

        sheetIndex = newSheetIndex;
        tableSearch.close();
        searchMatches = null;
        selectedRow = 0;
        selectedColumn = 0;
        firstVisibleRow = 0;
        firstVisibleColumn = 0;
        collapseRange();

        refreshEditor();
        revealActiveTab = true;
        rebuildWidgets();
        status = "Worksheet: " + manager.getSheetName(sheetIndex);
    }

    // No darkened or blurred menu background: the game stays visible around
    // the window. (Before 1.21.6 it would also be drawn over the spreadsheet.)
    //? if >=26.1 {
    /*@Override
    public void extractBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    *///?} elif >=1.20.2 {
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    //?} else {
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
        screenMouseX = mouseX;
        screenMouseY = mouseY;

        if (windowX > 0 || windowY > 0) {
            // A shadow and an outline around the window.
            graphics.fill(windowX - 3, windowY - 3, windowX + this.width + 3, windowY + this.height + 3, 0x40000000);
            graphics.fill(windowX - 1, windowY - 1, windowX + this.width + 1, windowY + this.height + 1, 0xFF5F6368);
        }

        pushWindowTransform(graphics);
        renderWindow(graphics, mouseX - windowX, mouseY - windowY, partialTick);
        popWindowTransform(graphics);
    }

    // Draws the widgets (buttons, edit box, slider). They already sit at their
    // screen positions, so the window translation is taken off while they draw.
    private void renderWidgets(GuiGraphics graphics, float partialTick) {
        popWindowTransform(graphics);
        super.render(graphics, screenMouseX, screenMouseY, partialTick);
        pushWindowTransform(graphics);
    }

    //? if >=1.21.6 {
    private void pushWindowTransform(GuiGraphics graphics) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(windowX, windowY);
    }

    private void popWindowTransform(GuiGraphics graphics) {
        graphics.pose().popMatrix();
    }

    // Clips drawing to a rectangle in window coordinates (the translation
    // applies to clipping on 1.21.6+).
    private void enableWindowScissor(GuiGraphics graphics, int left, int top, int right, int bottom) {
        graphics.enableScissor(left, top, right, bottom);
    }
    //?} else {
    /*private void pushWindowTransform(GuiGraphics graphics) {
        graphics.pose().pushPose();
        graphics.pose().translate(windowX, windowY, 0f);
    }

    private void popWindowTransform(GuiGraphics graphics) {
        graphics.pose().popPose();
    }

    // Clips drawing to a rectangle in window coordinates. Clipping ignores
    // the translation before 1.21.6, so the window's corner is added here.
    private void enableWindowScissor(GuiGraphics graphics, int left, int top, int right, int bottom) {
        graphics.enableScissor(left + windowX, top + windowY, right + windowX, bottom + windowY);
    }
    *///?}

    // Everything inside the window, in window coordinates.
    private void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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
                this.font.plainSubstrByWidth(status, Math.max(0, this.width - MAXIMIZE_RIGHT - 8 - statusX)),
                statusX, 9, 0xFFDDFFE6
        );

        if (manager == null) {
            graphics.drawString(
                    this.font,
                    pendingOpen != null ? "Loading the workbook..." : "Check that the workbook is valid.",
                    12, 72, pendingOpen != null ? 0xFF9AA0A6 : 0xFFFF7777
            );
            renderWidgets(graphics, partialTick);
            return;
        }

        // Shows "B2" for one cell or "B2:D5" for a range.
        String address = rangeRef(
                selectedRow, selectedColumn, rangeEndRow, rangeEndColumn
        );

        graphics.fill(10, FORMULA_PANEL_TOP, this.width - 10, FORMULA_PANEL_BOTTOM, 0xFF303134);
        graphics.drawString(
                this.font, this.font.plainSubstrByWidth(address, FX_X - 4 - 16),
                16, FORMULA_Y + BUTTON_TEXT_Y, 0xFFB8DDBF
        );

        // The Home ribbon
        graphics.fill(10, RIBBON_PANEL_TOP, this.width - 10, RIBBON_PANEL_BOTTOM, 0xFF303134);
        int hoveredTab = ribbonTabAt(mouseX, mouseY);

        for (int i = 0; i < RIBBON_TABS.length; i++) {
            int tabX = ribbonTabX(i);
            int color = i == ribbonTab ? 0xFFFFFFFF
                    : i == hoveredTab ? 0xFFDDDDDD : 0xFF9AA0A6;

            graphics.drawString(this.font, RIBBON_TABS[i], tabX, RIBBON_PANEL_TOP + 2, color);

            if (i == ribbonTab) {
                graphics.fill(
                        tabX - 2, RIBBON_Y - 2,
                        tabX + 2 + this.font.width(RIBBON_TABS[i]), RIBBON_Y,
                        0xFF217346
                );
            }
        }

        // Each group's name centred under its buttons, and a divider after it.
        for (RibbonGroup group : ribbonGroups) {
            int labelX = (group.left() + group.right() - this.font.width(group.label())) / 2;

            graphics.drawString(this.font, group.label(), labelX, RIBBON_GROUP_LABEL_Y, 0xFF9AA0A6);

            int dividerX = group.right() + GROUP_PAD;
            graphics.fill(dividerX, RIBBON_ROW1_Y, dividerX + 1, RIBBON_PANEL_BOTTOM - 3, 0xFF5F6368);
        }

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
        float textScale = 0.75f * zoomPercent / 100f;

        // The selected range, and (while building a formula) the range
        // that was inserted by clicking cells.
        int rangeTop = rangeTop();
        int rangeBottom = rangeBottom();
        int rangeLeft = rangeLeft();
        int rangeRight = rangeRight();
        boolean multi = hasMultiSelection();

        boolean showPoint = pointing && cellEditor != null
                && cellEditor.isFocused();
        boolean editingCell = cellEditor != null && cellEditor.isFocused();
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
        enableWindowScissor(graphics, 10, GRID_Y, gridRight, gridBottom);

        graphics.fill(GRID_X, GRID_Y, gridRight, GRID_Y + headerHeight, 0xFF3C4043);

        // Headers of the selected columns and rows are shaded, with a green
        // edge toward the grid; fully selected ones are filled green, like Excel.
        boolean wholeColumns = rangeTop == 0 && rangeBottom == LAST_ROW;
        boolean wholeRows = rangeLeft == 0 && rangeRight == LAST_COLUMN;

        // The corner box selects everything; it has Excel's small triangle.
        graphics.fill(10, GRID_Y, GRID_X, GRID_Y + headerHeight,
                wholeColumns && wholeRows ? 0xFF1E6B41 : 0xFF3C4043);
        int triangle = Math.max(2, Math.min(headerHeight, GRID_X - 10) / 2 - 2);

        for (int line = 0; line < triangle; line++) {
            graphics.fill(GRID_X - 3 - line, GRID_Y + headerHeight - 3 - triangle + line,
                    GRID_X - 3, GRID_Y + headerHeight - 2 - triangle + line, 0xFF9AA0A6);
        }

        int x = GRID_X;
        int cw = 0;
        for (int actualColumn = firstVisibleColumn; x < gridRight; actualColumn++, x += cw) {
            cw = columnWidth(actualColumn);

            if (actualColumn >= rangeLeft && actualColumn <= rangeRight) {
                graphics.fill(x, GRID_Y, x + cw, GRID_Y + headerHeight, wholeColumns ? 0xFF1E6B41 : 0xFF4A5057);
                graphics.fill(x, GRID_Y + headerHeight - 2, x + cw, GRID_Y + headerHeight, 0xFF33A06A);
            }

            drawHeaderLabel(
                    graphics, columnName(actualColumn),
                    x, GRID_Y, cw, headerHeight, 5
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

            boolean rowSelected = actualRow >= rangeTop && actualRow <= rangeBottom;
            graphics.fill(10, y, GRID_X, y + ch,
                    !rowSelected ? 0xFF3C4043 : wholeRows ? 0xFF1E6B41 : 0xFF4A5057);

            if (rowSelected) {
                graphics.fill(GRID_X - 2, y, GRID_X, y + ch, 0xFF33A06A);
            }

            drawHeaderLabel(
                    graphics, Integer.toString(actualRow + 1),
                    10, y, GRID_X - 10, ch, 10
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

                // Conditional formatting goes over the cell's own look, like Excel.
                ConditionalFormats.Style conditional = manager.getConditionalStyle(
                        sheetIndex, actualRow, actualColumn);

                if (conditional != null && conditional.fill() >= 0) {
                    background = 0xFF000000 | conditional.fill();
                }

                graphics.fill(x, y, x + cw, y + ch, background);
                graphics.fill(x, y, x + cw, y + 1, 0xFFD0D0D0);
                graphics.fill(x, y, x + 1, y + ch, 0xFFD0D0D0);

                if (conditional != null && conditional.bar() >= 0) {
                    drawDataBar(graphics, x, y, cw, ch, conditional);
                }

                if (format.borders().any()) {
                    drawCellBorders(graphics, x, y, cw, ch, format.borders());
                }

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

                // The cell being edited shows what is typed (with a cursor), like Excel.
                boolean editingHere = editingCell
                        && actualRow == selectedRow && actualColumn == selectedColumn;
                String value = editingHere
                        ? cellEditor.getValue()
                        : manager.getCellText(sheetIndex, actualRow, actualColumn);

                if (value.isEmpty() && !editingHere) {
                    continue;
                }

                float scale = crispTextScale(textScale * format.fontPoints() / 11f);

                // Text never gets smaller than readable (see crispTextScale);
                // once the cell is too short for it, leave the text out.
                if (8f * scale > ch) {
                    continue;
                }
                boolean conditionalBold = conditional != null && Boolean.TRUE.equals(conditional.bold());
                boolean conditionalItalic = conditional != null && Boolean.TRUE.equals(conditional.italic());
                Component text = Component.literal(value).withStyle(
                        Style.EMPTY
                                .withBold(format.bold() || tableHeader || conditionalBold)
                                .withItalic(format.italic() || conditionalItalic)
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
                float caretX = 0;

                if (editingHere) {
                    // Typing is shown left-aligned, scrolled so the cursor stays in the cell.
                    int caret = Math.max(0, Math.min(cellEditor.getCursorPosition(), value.length()));

                    caretX = this.font.width(Component.literal(value.substring(0, caret)).withStyle(text.getStyle()));
                    textX = Math.min(4f, cw - 5 - caretX * scale);
                }
                int textColor = 0xFF000000
                        | (conditional != null && conditional.fontColor() >= 0 ? conditional.fontColor()
                        : format.fontColor() >= 0 ? format.fontColor()
                        : tableHeader ? 0xFFFFFF : 0x202124);

                // Clip long text to the cell instead of cutting characters.
                enableWindowScissor(graphics, x + 1, y + 1, x + cw - 1 - (hasArrow ? arrowWidth + 2 : 0), y + ch - 1);
                // Vertical alignment: top, middle or bottom (Excel's default),
                // a little way in from the edge when the row has the room.
                float spare = ch - 8f * scale;
                float edge = Math.min(2f, spare / 2f);
                float textTop = format.vAlign() == 1 ? edge
                        : format.vAlign() == 2 ? spare / 2f
                        : spare - edge;

                pushTextScale(
                        graphics,
                        snapToPixel(x + textX),
                        snapToPixel(y + textTop),
                        scale
                );
                graphics.drawString(this.font, text, 0, 0, ColorPalette.cellTextColor(textColor), false);

                // A blinking cursor where the next character goes.
                if (editingHere && System.currentTimeMillis() / 500 % 2 == 0) {
                    int cursorX = Math.round(caretX);

                    graphics.fill(cursorX, -1, cursorX + 1, 9, textColor);
                }

                popTextScale(graphics);
                graphics.disableScissor();
            }
        }

        graphics.disableScissor();

        renderInk(graphics);

        renderSheetBar(graphics, mouseX, mouseY);

        renderWidgets(graphics, partialTick);

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
        if (handleMouseScrolled(mouseX - windowX, mouseY - windowY, scrollX, scrollY)) {
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    //?} else {
    /*@Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (handleMouseScrolled(mouseX - windowX, mouseY - windowY, 0, delta)) {
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

        if (conditionalDialog != null || conditionalMenu.isOpen()) {
            return true;
        }

        if (autocomplete.isOpen() && autocomplete.contains(mouseX, mouseY)) {
            autocomplete.scroll(scrollY);
            return true;
        }

        // While typing, the wheel only scrolls the view, like Excel: the edit
        // stays open on its cell, even when that cell scrolls out of sight.
        if (cellEditor != null && cellEditor.isFocused()) {
            if (scrollY != 0) {
                firstVisibleRow = Math.max(0, firstVisibleRow + (scrollY > 0 ? -3 : 3));
            }

            if (scrollX != 0) {
                firstVisibleColumn = Math.max(0, firstVisibleColumn + (scrollX > 0 ? -2 : 2));
            }

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

    // A cell's border lines, each drawn just inside the cell's own edge.
    private static void drawCellBorders(
            GuiGraphics graphics, int x, int y, int width, int height, CellFormat.Borders borders
    ) {
        CellFormat.Edge top = borders.top();
        CellFormat.Edge bottom = borders.bottom();
        CellFormat.Edge left = borders.left();
        CellFormat.Edge right = borders.right();

        if (top.visible()) {
            BorderMenu.drawHorizontal(graphics, x, x + width, y,
                    top.style(), BorderMenu.lineColor(top.color()));
        }
        if (bottom.visible()) {
            BorderMenu.drawHorizontal(graphics, x, x + width,
                    y + height - Math.min(height, BorderMenu.thickness(bottom.style())),
                    bottom.style(), BorderMenu.lineColor(bottom.color()));
        }
        if (left.visible()) {
            BorderMenu.drawVertical(graphics, x, y, y + height,
                    left.style(), BorderMenu.lineColor(left.color()));
        }
        if (right.visible()) {
            BorderMenu.drawVertical(graphics,
                    x + width - Math.min(width, BorderMenu.thickness(right.style())), y, y + height,
                    right.style(), BorderMenu.lineColor(right.color()));
        }
    }

    // A column letter or row number, shrunk to fit its header cell when
    // zoomed out, and left out once it would be too small to read.
    private void drawHeaderLabel(
            GuiGraphics graphics, String label,
            int left, int top, int width, int height, int padding
    ) {
        int textWidth = this.font.width(label);
        float fit = Math.min(1f, Math.min(
                (height - 2) / 8f,
                (width - padding - 1) / (float) textWidth
        ));
        // Round down to a sharp size, so the label still fits its header.
        int gui = guiScale();
        float scale = (float) Math.floor(fit * gui + 0.001f) / gui;

        if (scale <= 0f) {
            return;
        }

        float y = top + (height - 8 * scale) / 2f;

        if (scale >= 1f) {
            graphics.drawString(this.font, label, left + padding, Math.round(y), 0xFFFFFFFF);
            return;
        }

        pushTextScale(graphics, snapToPixel(left + padding * scale), snapToPixel(y), scale);
        graphics.drawString(this.font, label, 0, 0, 0xFFFFFFFF);
        popTextScale(graphics);
    }

    // ----- sharp scaled text -----
    // Minecraft's font is pixel art. Scaled text is only sharp when every font
    // pixel covers a whole number of screen pixels and starts on a screen
    // pixel; otherwise some pixel columns get doubled and others dropped,
    // and small text turns into a smudge.

    // Screen pixels per GUI pixel.
    private int guiScale() {
        return Math.max(1, (int) Math.round(this.minecraft.getWindow().getGuiScale()));
    }

    // Never below one screen pixel per font pixel: smaller than that, letters
    // lose pixels and become unreadable. Above it the exact size is kept (not
    // rounded to whole pixels), so every font size step changes the text size.
    private float crispTextScale(float scale) {
        int gui = guiScale();
        return Math.max(1f / gui, scale);
    }

    // Moves a GUI position onto the nearest whole screen pixel.
    private float snapToPixel(float position) {
        int gui = guiScale();
        return Math.round(position * gui) / (float) gui;
    }

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
        // Keys (Escape included) wait while a save is being written.
        if (saving()) {
            return true;
        }

        // The conditional formatting rule window takes every key while it's open.
        if (conditionalDialog != null) {
            ConditionalDialog.Result result = conditionalDialog.keyPressed(key, ctrl,
                    () -> this.minecraft.keyboardHandler.getClipboard());

            if (result == ConditionalDialog.Result.OK) {
                applyConditionalDialog();
            } else if (result == ConditionalDialog.Result.CANCEL) {
                conditionalDialog = null;
            }

            return true;
        }

        // The table search box takes the keys while you type in it (Ctrl+S still saves).
        if (tableSearch.isFocused() && !(ctrl && key == GLFW.GLFW_KEY_S)) {
            if (ctrl && key == GLFW.GLFW_KEY_F) {
                return true;
            }

            handleSearchResult(tableSearch.keyPressed(key, ctrl, shift,
                    () -> this.minecraft.keyboardHandler.getClipboard()));
            return true;
        }

        // Ctrl+F searches the table the selected cell is in.
        if (ctrl && key == GLFW.GLFW_KEY_F && manager != null) {
            openTableSearch();
            return true;
        }

        if (conditionalMenu.isOpen() && key == GLFW.GLFW_KEY_ESCAPE) {
            conditionalMenu.close();
            return true;
        }

        if (groupPanel != null && groupPanel.isOpen() && key == GLFW.GLFW_KEY_ESCAPE) {
            groupPanel.close();
            return true;
        }

        if ((palette.isOpen() || formatMenu.isOpen() || filterMenu.isOpen() || borderMenu.isOpen()
                || functionMenu.isOpen() || pasteMenu.isOpen() || cellMenu.isOpen() || miniToolbar.isOpen())
                && key == GLFW.GLFW_KEY_ESCAPE) {
            cellMenu.close();
            miniToolbar.close();
            palette.close();
            formatMenu.close();
            pasteMenu.close();
            filterMenu.close();
            borderMenu.close();
            functionMenu.close();
            return true;
        }

        // Escape ends a table search before it closes anything else.
        if (tableSearch.isOpen() && key == GLFW.GLFW_KEY_ESCAPE
                && (cellEditor == null || !cellEditor.isFocused())) {
            closeTableSearch();
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
            if (!commitPendingEdit()) {
                return true;
            }

            undoLastEdit();
            return true;
        }

        if (key == GLFW.GLFW_KEY_Y
                && ctrl
                && manager != null) {
            if (!commitPendingEdit()) {
                return true;
            }

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
                    // Ctrl+Shift+V pastes only the values, like Excel's Paste Values.
                    pasteClipboard(shift ? PasteMenu.Mode.VALUES : PasteMenu.Mode.ALL);
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
                // Ctrl+A selects the whole sheet, Ctrl+Space the selected cells' whole columns.
                if (key == GLFW.GLFW_KEY_A) {
                    selectAll();
                    status = "Selected the whole sheet";
                    return true;
                }
                if (key == GLFW.GLFW_KEY_SPACE) {
                    selectColumns(selectedColumn, rangeEndColumn);
                    swallowSpaceUntil = System.currentTimeMillis() + 250;
                    status = "Selected " + rangeRef(rangeTop(), rangeLeft(), rangeBottom(), rangeRight());
                    return true;
                }
            }

            // Shift+Space selects the selected cells' whole rows.
            if (shift && !ctrl && key == GLFW.GLFW_KEY_SPACE && manager != null) {
                selectRows(selectedRow, rangeEndRow);
                swallowSpaceUntil = System.currentTimeMillis() + 250;
                status = "Selected " + rangeRef(rangeTop(), rangeLeft(), rangeBottom(), rangeRight());
                return true;
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

            // The formula autocomplete list takes the arrow keys, Tab and Escape.
            syncAutocomplete();

            if (autocomplete.isOpen()) {
                switch (key) {
                    case GLFW.GLFW_KEY_UP -> {
                        autocomplete.move(-1);
                        return true;
                    }
                    case GLFW.GLFW_KEY_DOWN -> {
                        autocomplete.move(1);
                        return true;
                    }
                    case GLFW.GLFW_KEY_PAGE_UP -> {
                        autocomplete.move(-7);
                        return true;
                    }
                    case GLFW.GLFW_KEY_PAGE_DOWN -> {
                        autocomplete.move(7);
                        return true;
                    }
                    case GLFW.GLFW_KEY_TAB -> {
                        acceptAutocomplete();
                        return true;
                    }
                    case GLFW.GLFW_KEY_ESCAPE -> {
                        autocomplete.dismiss(cellEditor.getValue(), cellEditor.getCursorPosition());
                        return true;
                    }
                    default -> {
                    }
                }
            }

            if (key == GLFW.GLFW_KEY_ESCAPE) {
                leaveEditor();
                refreshEditor();
                return true;
            }

            if (key == GLFW.GLFW_KEY_ENTER
                    || key == GLFW.GLFW_KEY_KP_ENTER) {
                if (!commitPendingEdit()) {
                    return true;
                }

                // Ctrl+Enter keeps the same cell selected, like Excel.
                if (!ctrl) {
                    moveSelection(0, 1);
                }

                return true;
            }

            if (key == GLFW.GLFW_KEY_TAB) {
                if (commitPendingEdit()) {
                    moveSelection(1, 0);
                }

                return true;
            }

            return false;
        }

        // Ctrl+Arrow jumps to the edge of the data; with Shift it extends the selection there.
        if (ctrl && manager != null) {
            switch (key) {
                case GLFW.GLFW_KEY_UP -> {
                    jump(0, -1, shift);
                    return true;
                }
                case GLFW.GLFW_KEY_DOWN -> {
                    jump(0, 1, shift);
                    return true;
                }
                case GLFW.GLFW_KEY_LEFT -> {
                    jump(-1, 0, shift);
                    return true;
                }
                case GLFW.GLFW_KEY_RIGHT -> {
                    jump(1, 0, shift);
                    return true;
                }
                default -> {
                }
            }
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

    // ----- Ctrl+Arrow / Ctrl+Shift+Arrow, as in Excel -----
    //
    // From a cell, one step in the arrow's direction decides where it goes:
    //  - the cell and the next one both hold something: to the last filled cell
    //    before an empty one (the end of this block of data);
    //  - otherwise: to the next filled cell, or the sheet's edge if there is none.
    // Rows hidden by a filter are skipped. Ctrl+Arrow moves the active cell
    // (and the range shrinks to it). Ctrl+Shift+Arrow keeps the active cell and
    // moves the range's far corner, starting from that corner's row (or column)
    // but looking at the cells in the active cell's column (or row), like Excel.
    private void jump(int columnChange, int rowChange, boolean extend) {
        commitPendingEdit();

        if (extend) {
            if (rowChange != 0) {
                rangeEndRow = jumpRow(rangeEndRow, selectedColumn, rowChange);
            } else {
                rangeEndColumn = jumpColumn(rangeEndColumn, selectedRow, columnChange);
            }

            scrollCellIntoView(rangeEndRow, rangeEndColumn);
            status = "Selected " + rangeRef(rangeTop(), rangeLeft(), rangeBottom(), rangeRight());
            return;
        }

        if (rowChange != 0) {
            selectedRow = jumpRow(selectedRow, selectedColumn, rowChange);
        } else {
            selectedColumn = jumpColumn(selectedColumn, selectedRow, columnChange);
        }

        collapseRange();
        scrollSelectionIntoView();
        refreshEditor();
    }

    private boolean filled(int row, int column) {
        try {
            return !manager.isCellEmpty(sheetIndex, row, column);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    // The next row in that direction that isn't hidden, or -1 past the sheet's edge.
    private int nextVisibleRow(int row, int direction) {
        for (int next = row + direction; next >= 0 && next <= LAST_ROW; next += direction) {
            if (!rowHidden(next)) {
                return next;
            }
        }

        return -1;
    }

    // Where Ctrl+Up / Ctrl+Down goes from row start, looking at the cells in column.
    private int jumpRow(int start, int column, int direction) {
        int next = nextVisibleRow(start, direction);

        if (next < 0) {
            return start;
        }

        // Inside a block of data: to its last filled cell.
        if (filled(start, column) && filled(next, column)) {
            int row = next;

            for (int after = nextVisibleRow(row, direction); after >= 0 && filled(after, column);
                 after = nextVisibleRow(after, direction)) {
                row = after;
            }

            return row;
        }

        // Otherwise: to the next filled cell, or the edge. Rows past the last
        // used one are all empty, so those are passed over in one go.
        int last = manager.getLastUsedRow(sheetIndex);
        int row = next;

        while (!filled(row, column)) {
            if (direction > 0 && row > last) {
                return lastVisibleRow(row);
            }

            if (direction < 0 && row > last + 1) {
                int below = nextVisibleRow(Math.max(last, -1) + 2, -1);

                if (below >= 0 && below < row) {
                    row = below;
                    continue;
                }
            }

            int after = nextVisibleRow(row, direction);

            if (after < 0) {
                return row;
            }

            row = after;
        }

        return row;
    }

    // The sheet's last row that isn't hidden (rows past the data never are).
    private int lastVisibleRow(int from) {
        return baseRowHeight(LAST_ROW) != 0 ? LAST_ROW : Math.max(from, nextVisibleRow(LAST_ROW + 1, -1));
    }

    // Where Ctrl+Left / Ctrl+Right goes from column start, looking at the cells in row.
    private int jumpColumn(int start, int row, int direction) {
        int next = start + direction;

        if (next < 0 || next > LAST_COLUMN) {
            return start;
        }

        if (filled(row, start) && filled(row, next)) {
            int column = next;

            while (column + direction >= 0 && column + direction <= LAST_COLUMN
                    && filled(row, column + direction)) {
                column += direction;
            }

            return column;
        }

        int last = manager.getLastUsedColumn(sheetIndex);
        int column = next;

        while (!filled(row, column)) {
            if (direction > 0 && column > last) {
                return LAST_COLUMN;
            }

            if (direction < 0 && column > last + 1) {
                column = last + 1;
                continue;
            }

            if (column + direction < 0 || column + direction > LAST_COLUMN) {
                return column;
            }

            column += direction;
        }

        return column;
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
        int bottom = actionBottom();
        int left = rangeLeft();
        int right = actionRight();

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
        return shiftFormula(formula, rowDelta, columnDelta, -1);
    }

    // onlyRow >= 0: only references to that row move (see shiftReferences).
    private static String shiftFormula(String formula, int rowDelta, int columnDelta, int onlyRow) {
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
                        segment.toString(), rowDelta, columnDelta, onlyRow
                ));
                segment.setLength(0);
                result.append(ch);
                quote = ch;
            } else {
                segment.append(ch);
            }
        }

        result.append(shiftReferences(
                segment.toString(), rowDelta, columnDelta, onlyRow
        ));

        return result.toString();
    }

    private static String shiftReferences(String text, int rowDelta, int columnDelta) {
        return shiftReferences(text, rowDelta, columnDelta, -1);
    }

    // onlyRow >= 0: only references to that row move (sorting moves a row's
    // own cells with it, but a SUM over other rows keeps pointing at them).
    private static String shiftReferences(String text, int rowDelta, int columnDelta, int onlyRow) {
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

            if (onlyRow >= 0 && row != onlyRow) {
                matcher.appendReplacement(
                        out, Matcher.quoteReplacement(matcher.group())
                );
                continue;
            }

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
        int bottom = actionBottom();
        int left = rangeLeft();
        int right = actionRight();
        int rows = bottom - top + 1;
        int columns = right - left + 1;

        if ((long) rows * columns > MAX_PASTE_CELLS) {
            status = "Selection too large to copy";
            return;
        }

        String[][] inputs = new String[rows][columns];
        String[][] values = new String[rows][columns];
        int[][] styles = manager.getCellStyleIndex(sheetIndex, top, left) < 0
                ? null
                : new int[rows][columns];
        StringBuilder text = new StringBuilder();

        // Column widths and row heights go along too (not for CSV, which has none).
        int[] columnWidths = null;
        int[] rowHeights = null;

        if (styles != null) {
            columnWidths = new int[columns];
            rowHeights = new int[rows];

            for (int c = 0; c < columns; c++) {
                columnWidths[c] = baseColumnWidth(left + c);
            }
            for (int r = 0; r < rows; r++) {
                rowHeights[r] = baseRowHeight(top + r);
            }
        }

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                String input = manager.getCellInput(
                        sheetIndex, top + r, left + c
                );
                inputs[r][c] = input == null ? "" : input;

                String value = input != null && input.startsWith("=")
                        ? manager.getCellValueInput(sheetIndex, top + r, left + c)
                        : input;
                values[r][c] = value == null ? "" : value;

                if (styles != null) {
                    styles[r][c] = manager.getCellStyleIndex(
                            sheetIndex, top + r, left + c
                    );
                }

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
        clipboardValues = values;
        clipboardColumnWidths = columnWidths;
        clipboardRowHeights = rowHeights;
        clipboardStyles = styles;
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

    // Pastes at the top-left of the selection. A single copied cell fills the
    // whole selection. Pasting is one undo step. The mode (the Paste dropdown)
    // picks what is pasted: Ctrl+V pastes the contents, and cells copied in
    // this workbook bring their formatting (colors, font, borders, number
    // format), but not their column widths and row heights (that is the
    // "With Widths & Heights" option); Ctrl+Shift+V pastes only the
    // calculated values.
    private void pasteClipboard(PasteMenu.Mode mode) {
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

        // Formatting and sizes only exist for cells copied in this workbook.
        if (!mode.contents() && !internal) {
            status = "Only cells copied here can paste "
                    + (mode.sizes() ? "widths and heights" : "formatting");
            return;
        }

        String[][] grid;

        if (!internal) {
            grid = parseTsv(normalized);
        } else if (mode == PasteMenu.Mode.VALUES && clipboardValues != null) {
            grid = clipboardValues;
        } else {
            grid = clipboardInputs;
        }

        if (grid.length == 0 || grid[0].length == 0) {
            status = "Nothing to paste";
            return;
        }

        boolean cut = internal && clipboardCut;

        // Cut cells move with their contents, so they need a paste that has them.
        if (cut && !mode.contents()) {
            status = "Cut cells can only be pasted with Paste, Formulas or Values";
            return;
        }

        int top = rangeTop();
        int left = rangeLeft();

        boolean fill = grid.length == 1 && grid[0].length == 1
                && hasMultiSelection() && !cut;

        int pasteRows = fill ? actionBottom() - top + 1 : grid.length;
        int pasteColumns = fill ? actionRight() - left + 1 : grid[0].length;

        if ((long) pasteRows * pasteColumns > MAX_PASTE_CELLS) {
            status = "Paste area too large";
            return;
        }

        // Formatting only travels within this workbook (style indexes belong
        // to it) and never into a CSV file.
        boolean targetHasFormats = manager.getCellStyleIndex(sheetIndex, top, left) >= 0;
        boolean pasteFormats = mode.formatting() && internal && clipboardStyles != null
                && targetHasFormats;
        // Widths and heights follow the copied block; one cell filling a
        // bigger selection leaves the sizes alone.
        boolean pasteSizes = mode.sizes() && internal && !fill && targetHasFormats
                && clipboardColumnWidths != null && clipboardRowHeights != null;

        if (!mode.contents() && !pasteFormats && !pasteSizes) {
            status = "Nothing to paste here";
            return;
        }

        boolean hasEarlierEdit = false;
        int changed = 0;
        int skipped = 0;

        // Column widths and row heights first, so the cells' own height fits
        // below don't get undone by them.
        if (pasteSizes) {
            for (int c = 0; c < pasteColumns && c < clipboardColumnWidths.length; c++) {
                int column = left + c;
                int width = clipboardColumnWidths[c];
                int current = baseColumnWidth(column);

                if (width <= 0 || width == current) {
                    continue;
                }

                try {
                    manager.setColumnWidthPixels(sheetIndex, column, width);
                    columnWidthCache.put(sizeKey(sheetIndex, column), width);
                    undoHistory.push(CellEdit.resize(
                            sheetIndex, -1, column, hasEarlierEdit, current, width
                    ));
                    hasEarlierEdit = true;
                    changed++;
                } catch (RuntimeException exception) {
                    skipped++;
                }
            }

            for (int r = 0; r < pasteRows && r < clipboardRowHeights.length; r++) {
                int row = top + r;
                int height = clipboardRowHeights[r];
                int current = baseRowHeight(row);

                // A row hidden by a filter (height 0), here or in the copy, is left alone.
                if (height <= 0 || current <= 0 || height == current) {
                    continue;
                }

                try {
                    manager.setRowHeightPixels(sheetIndex, row, height);
                    rowHeightCache.put(sizeKey(sheetIndex, row), height);
                    undoHistory.push(CellEdit.resize(
                            sheetIndex, row, -1, hasEarlierEdit, current, height
                    ));
                    hasEarlierEdit = true;
                    changed++;
                } catch (RuntimeException exception) {
                    skipped++;
                }
            }
        }

        for (int r = 0; r < pasteRows; r++) {
            int largestFont = 0;

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

                try {
                    if (mode.contents() && !previous.equals(input)) {
                        int[] styles = manager.setCellValueDeferred(
                                sheetIndex, targetRow, targetColumn, input
                        );

                        undoHistory.push(new CellEdit(
                                sheetIndex, targetRow, targetColumn,
                                previous, input, hasEarlierEdit
                        ));
                        hasEarlierEdit = true;

                        if (styles != null) {
                            undoHistory.push(new CellEdit(
                                    sheetIndex, targetRow, targetColumn, "", "",
                                    true, styles[0], styles[1]
                            ));
                        }

                        changed++;
                    }

                    // Then the copied formatting, which replaces any format the
                    // value picked up on its own (for example "$" -> currency).
                    if (pasteFormats) {
                        int style = clipboardStyles[sourceR][sourceC];
                        int current = manager.getCellStyleIndex(
                                sheetIndex, targetRow, targetColumn
                        );

                        if (style != current) {
                            manager.setCellStyleIndex(
                                    sheetIndex, targetRow, targetColumn, style
                            );
                            undoHistory.push(new CellEdit(
                                    sheetIndex, targetRow, targetColumn, "", "",
                                    hasEarlierEdit, current, style
                            ));
                            hasEarlierEdit = true;
                            changed++;
                        }

                        largestFont = Math.max(largestFont, manager.getCellFormat(
                                sheetIndex, targetRow, targetColumn
                        ).fontPoints());
                    }
                } catch (RuntimeException exception) {
                    // For example an invalid formula; leave that cell as is.
                    skipped++;
                }
            }

            // Bigger pasted text gets a taller row, as when setting the size.
            if (largestFont > 0) {
                fitRowHeights(top + r, top + r, largestFont);
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

                    try {
                        if (previous != null && !previous.isEmpty()) {
                            manager.setCellValueDeferred(
                                    clipboardSheet, sourceRow, sourceColumn, ""
                            );

                            undoHistory.push(new CellEdit(
                                    clipboardSheet, sourceRow, sourceColumn,
                                    previous, "", hasEarlierEdit
                            ));

                            hasEarlierEdit = true;
                            changed++;
                        }

                        // The formatting moved with the cells, so the old
                        // place goes back to the default style.
                        if (pasteFormats) {
                            int style = manager.getCellStyleIndex(
                                    clipboardSheet, sourceRow, sourceColumn
                            );

                            if (style > 0) {
                                manager.setCellStyleIndex(
                                        clipboardSheet, sourceRow, sourceColumn, 0
                                );
                                undoHistory.push(new CellEdit(
                                        clipboardSheet, sourceRow, sourceColumn, "", "",
                                        hasEarlierEdit, style, 0
                                ));
                                hasEarlierEdit = true;
                                changed++;
                            }
                        }
                    } catch (RuntimeException exception) {
                        skipped++;
                    }
                }
            }

            clipboardInputs = null;
            clipboardValues = null;
            clipboardColumnWidths = null;
            clipboardRowHeights = null;
            clipboardStyles = null;
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

        String what = mode == PasteMenu.Mode.ALL
                ? ""
                : " " + mode.label().replaceAll(" \\(.*\\)", "").toLowerCase(Locale.ROOT);

        status = "Pasted" + what + " to " + rangeRef(top, left, rangeEndRow, rangeEndColumn)
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

                if (!edit.size() || last == null) {
                    last = edit;
                }
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

        selectAfterHistory(last);
        status = last.description() != null
                ? "Undid: " + last.description()
                : last.size()
                ? "Undid size change"
                : "Undid edit to " + cellRef(selectedRow, selectedColumn);
    }

    // After undo/redo: shows the sheet and selects the cell that changed (a
    // column width or row height change leaves the selection where it is).
    private void selectAfterHistory(CellEdit last) {
        sheetIndex = last.sheet();

        if (!last.size() && last.ink() == null) {
            selectedRow = last.row();
            selectedColumn = last.column();
            collapseRange();
        }

        scrollSelectionIntoView();
        refreshEditor();
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

                if (!edit.size() || last == null) {
                    last = edit;
                }
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

        selectAfterHistory(last);
        status = last.description() != null
                ? "Redid: " + last.description()
                : last.size()
                ? "Redid size change"
                : "Redid edit to " + cellRef(selectedRow, selectedColumn);
    }

    //? if >=1.21.9 {
    @Override
    public boolean charTyped(CharacterEvent characterEvent) {
        if (saving()) {
            return true;
        }

        if (conditionalDialog != null) {
            conditionalDialog.charTyped(characterEvent.codepoint());
            return true;
        }

        if (tableSearch.isFocused()) {
            if (tableSearch.charTyped(characterEvent.codepoint())) {
                handleSearchResult(TableSearchBar.Result.CHANGED);
            }

            return true;
        }

        if (prepareTypedChar(characterEvent.codepoint())) {
            return cellEditor.charTyped(characterEvent);
        }

        return super.charTyped(characterEvent);
    }
    //?} else {
    /*@Override
    public boolean charTyped(char chr, int modifiers) {
        if (saving()) {
            return true;
        }

        if (conditionalDialog != null) {
            conditionalDialog.charTyped(chr);
            return true;
        }

        if (tableSearch.isFocused()) {
            if (tableSearch.charTyped(chr)) {
                handleSearchResult(TableSearchBar.Result.CHANGED);
            }

            return true;
        }

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

        // The space from Ctrl+Space / Shift+Space only selects; it doesn't start typing.
        if (typed == ' ' && System.currentTimeMillis() < swallowSpaceUntil) {
            swallowSpaceUntil = 0;
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
            int size = columnWidth(column);
            x += size;

            // Narrow columns get a narrower grab zone so they stay clickable.
            if (Math.abs(mouseX - x) <= Math.min(RESIZE_GRAB, size / 3)) {
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
            int size = rowHeight(row);
            y += size;

            // Short rows get a smaller grab zone so they stay clickable.
            if (size > 0 && Math.abs(mouseY - y) <= Math.min(RESIZE_GRAB, size / 3)) {
                return row;
            }

            if (y > mouseY + RESIZE_GRAB) {
                break;
            }
        }

        return -1;
    }

    // ----- whole columns and rows -----

    private static final int HEADER_NONE = 0;
    private static final int HEADER_COLUMN = 1;
    private static final int HEADER_ROW = 2;
    private static final int HEADER_CORNER = 3;

    // Which header the mouse is over: the column letters, the row numbers,
    // the corner box where they meet, or none. Edges count as headers too
    // (resize grabs are checked before this).
    private int headerAreaAt(double mouseX, double mouseY) {
        boolean inColumnHeader = mouseY >= GRID_Y && mouseY < GRID_Y + headerHeight();
        boolean inRowHeader = mouseX >= 10 && mouseX < GRID_X;

        if (inColumnHeader && inRowHeader) {
            return HEADER_CORNER;
        }

        if (inColumnHeader && mouseX >= GRID_X && mouseX < gridRight()) {
            return HEADER_COLUMN;
        }

        if (inRowHeader && mouseY >= GRID_Y + headerHeight() && mouseY < gridBottom()) {
            return HEADER_ROW;
        }

        return HEADER_NONE;
    }

    // Selecting everything and resizing would touch all 16,384 columns or
    // 1,048,576 rows. A selection bigger than MAX_RESIZE_COUNT stops at the
    // used or visible area, whichever is further, and never goes past
    // MAX_RESIZE_TOTAL (the columns/rows beyond keep their size).
    private static final int MAX_RESIZE_COUNT = 1_000;
    private static final int MAX_RESIZE_TOTAL = 20_000;

    // The columns a drag on this column's edge resizes: all selected columns
    // if this one is part of a whole-column selection (like Excel), else just it.
    private int[] columnsToResize(int column) {
        if (!wholeColumnsSelected() || column < rangeLeft() || column > rangeRight()) {
            return new int[] {column, column};
        }

        int last = rangeRight();

        if (last - rangeLeft() + 1 > MAX_RESIZE_COUNT) {
            int lastVisible = firstVisibleColumn + visibleColumns();
            last = Math.min(last, Math.max(column, Math.max(manager.getLastUsedColumn(sheetIndex), lastVisible)));
        }

        return new int[] {rangeLeft(), Math.max(column, Math.min(last, rangeLeft() + MAX_RESIZE_TOTAL - 1))};
    }

    private int[] rowsToResize(int row) {
        if (!wholeRowsSelected() || row < rangeTop() || row > rangeBottom()) {
            return new int[] {row, row};
        }

        int last = rangeBottom();

        if (last - rangeTop() + 1 > MAX_RESIZE_COUNT) {
            int lastVisible = firstVisibleRow + visibleRows();
            last = Math.min(last, Math.max(row, Math.max(manager.getLastUsedRow(sheetIndex), lastVisible)));
        }

        return new int[] {rangeTop(), Math.max(row, Math.min(last, rangeTop() + MAX_RESIZE_TOTAL - 1))};
    }

    // Sets a column's width or a row's height without touching the status line.
    private void setSize(boolean column, int index, int pixels) {
        if (column) {
            manager.setColumnWidthPixels(sheetIndex, index, pixels);
            columnWidthCache.put(sizeKey(sheetIndex, index), pixels);
        } else {
            manager.setRowHeightPixels(sheetIndex, index, pixels);
            rowHeightCache.put(sizeKey(sheetIndex, index), pixels);
        }
    }

    // The end of a header-edge drag: every column or row in the group gets
    // the dragged one's new size, as one undo step.
    private void finishResize() {
        if (manager == null || resizeStartSizes == null) {
            return;
        }

        boolean columns = resizingColumn >= 0;
        int dragged = columns ? resizingColumn : resizingRow;
        int size = columns ? baseColumnWidth(dragged) : baseRowHeight(dragged);

        recordSizes(columns, resizeFirst, resizeLast, resizeStartSizes, index -> size);
        resizeStartSizes = null;
    }

    // Gives each column/row in first..last its new size and records the
    // changes as one undo step. Rows hidden by a filter are left alone.
    private void recordSizes(boolean columns, int first, int last, int[] previous,
                             java.util.function.IntUnaryOperator newSize) {
        boolean chained = false;
        int changed = 0;

        try {
            for (int index = first; index <= last; index++) {
                int before = previous[index - first];

                if (!columns && before == 0) {
                    continue;
                }

                int after = columns
                        ? Math.max(8, Math.min(1000, newSize.applyAsInt(index)))
                        : Math.max(6, Math.min(500, newSize.applyAsInt(index)));

                setSize(columns, index, after);

                if (after != before) {
                    undoHistory.push(columns
                            ? CellEdit.resize(sheetIndex, -1, index, chained, before, after)
                            : CellEdit.resize(sheetIndex, index, -1, chained, before, after));
                    chained = true;
                    changed++;
                }
            }
        } catch (RuntimeException exception) {
            status = "Could not resize: " + exception.getMessage();
        }

        if (changed > 0) {
            redoHistory.clear();
            markDirty();
        }

        if (first == last) {
            status = columns
                    ? "Column " + columnName(first) + " width: " + baseColumnWidth(first)
                    : "Row " + (first + 1) + " height: " + baseRowHeight(first);
        } else {
            status = (columns ? "Columns " + columnName(first) + ":" + columnName(last) + " width: "
                    + baseColumnWidth(first)
                    : "Rows " + (first + 1) + ":" + (last + 1) + " height: " + baseRowHeight(first));
        }
    }

    // Double-clicking a header edge: each column becomes as wide as its
    // widest text, each row as tall as its largest text (an empty one goes
    // back to the standard size), like Excel's AutoFit.
    private void autoFit(boolean columns, int first, int last) {
        int[] previous = new int[last - first + 1];

        for (int index = first; index <= last; index++) {
            previous[index - first] = columns ? baseColumnWidth(index) : baseRowHeight(index);
        }

        recordSizes(columns, first, last, previous,
                index -> columns ? fittedColumnWidth(index) : fittedRowHeight(index));
        status = "AutoFit " + status.substring(0, 1).toLowerCase(Locale.ROOT) + status.substring(1);
    }

    // Most cells AutoFit looks at in one column or row, so a huge sheet can't freeze the game.
    private static final int AUTOFIT_SCAN_LIMIT = 20_000;

    private int fittedColumnWidth(int column) {
        int lastRow = Math.min(manager.getLastUsedRow(sheetIndex), AUTOFIT_SCAN_LIMIT);
        int widest = -1;

        for (int row = 0; row <= lastRow; row++) {
            String text = manager.getCellText(sheetIndex, row, column);

            if (text.isEmpty()) {
                continue;
            }

            CellFormat format = manager.getCellFormat(sheetIndex, row, column);
            Component styled = Component.literal(text).withStyle(Style.EMPTY
                    .withBold(format.bold())
                    .withItalic(format.italic()));
            // The same size cells are drawn at, at 100% zoom, plus the 4-pixel padding on each side.
            float scale = 0.75f * format.fontPoints() / 11f;
            widest = Math.max(widest, (int) Math.ceil(this.font.width(styled) * scale) + 8);
        }

        return widest < 0 ? manager.getDefaultColumnWidthPixels(sheetIndex) : widest;
    }

    private int fittedRowHeight(int row) {
        int lastColumn = Math.min(manager.getLastUsedColumn(sheetIndex), AUTOFIT_SCAN_LIMIT);
        int largest = -1;

        for (int column = 0; column <= lastColumn; column++) {
            if (!manager.getCellText(sheetIndex, row, column).isEmpty()) {
                largest = Math.max(largest, manager.getCellFormat(sheetIndex, row, column).fontPoints());
            }
        }

        int standard = manager.getDefaultRowHeightPixels(sheetIndex);

        // The same rule as when the font size is changed (see fitRowHeights).
        return largest < 0 ? standard : Math.max(standard, (int) Math.ceil(largest * 1.82));
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

    // Starts a formula with the function (or adds it at the cursor of a
    // formula being typed) and leaves the cursor inside the brackets, ready
    // for its arguments: clicking cells then inserts their references.
    private void insertFunction(FunctionMenu.Function function) {
        if (cellEditor == null) {
            return;
        }

        boolean noArgs = function.args().isEmpty();
        String call = function.name() + (noArgs ? "()" : "(");
        String text = cellEditor.getValue();
        String updated;
        int cursor;

        if (cellEditor.isFocused() && text.startsWith("=")) {
            int caret = Math.max(1, Math.min(cellEditor.getCursorPosition(), text.length()));

            updated = text.substring(0, caret) + call + text.substring(caret);
            cursor = caret + call.length();
        } else {
            updated = "=" + call;
            cursor = updated.length();
        }

        if (!noArgs) {
            // Excel adds the closing bracket on Enter; it is added here so
            // the formula is valid, with the cursor before it.
            updated = updated.substring(0, cursor) + ")" + updated.substring(cursor);
        }

        enterEditor();
        pointing = false;
        cellEditor.setValue(updated);

        //? if >=1.21 {
        cellEditor.moveCursorTo(cursor, false);
        //?} else {
        /*cellEditor.setCursorPosition(cursor);
        cellEditor.setHighlightPos(cursor);
        *///?}

        status = function.syntax();
    }

    // ----- formula autocomplete -----

    // Opens, updates or closes the autocomplete list to match the formula
    // being typed, and places it under the function name in the formula bar.
    private void syncAutocomplete() {
        if (manager == null || manager.isCsv() || cellEditor == null || !cellEditor.isFocused()) {
            autocomplete.close();
            return;
        }

        String text = cellEditor.getValue();

        autocomplete.update(text, cellEditor.getCursorPosition());

        if (autocomplete.isOpen()) {
            // Widgets are placed in screen coordinates; popups are drawn in window coordinates.
            int editorLeft = cellEditor.getX() - windowX;
            int nameX = editorLeft + 4 + this.font.width(text.substring(0, autocomplete.tokenStart()));
            int x = Math.min(nameX, editorLeft + cellEditor.getWidth() - 20);

            autocomplete.setPosition(x, FORMULA_PANEL_BOTTOM + 2, this.width);
        }
    }

    // Tab (or a click) on the autocomplete list: insert the function.
    private void acceptAutocomplete() {
        FormulaAutocomplete.Accepted result = autocomplete.accept(cellEditor.getValue());

        if (result == null) {
            return;
        }

        pointing = false;
        cellEditor.setValue(result.text());

        //? if >=1.21 {
        cellEditor.moveCursorTo(result.caret(), false);
        //?} else {
        /*cellEditor.setCursorPosition(result.caret());
        cellEditor.setHighlightPos(result.caret());
        *///?}

        status = result.suggestion().syntax();
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
                event.x() - windowX, event.y() - windowY, event.button(), event.hasShiftDown()
        )) {
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }
    //?} else {
    /*@Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (handleMouseClicked(mouseX - windowX, mouseY - windowY, button, hasShiftDown())) {
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
        // Nothing can be clicked while a save is being written.
        if (saving()) {
            return true;
        }

        // The rule window is modal. Clicking a cell outside it puts that
        // cell's reference in its value box, like Excel.
        if (conditionalDialog != null) {
            if (button == 0 && conditionalDialog.contains(mouseX, mouseY)) {
                ConditionalDialog.Result result = conditionalDialog.click(mouseX, mouseY);

                if (result == ConditionalDialog.Result.OK) {
                    applyConditionalDialog();
                } else if (result == ConditionalDialog.Result.CANCEL) {
                    conditionalDialog = null;
                }
            } else if (button == 0 && insideGrid(mouseX, mouseY)) {
                int column = Math.max(0, columnAt(mouseX));
                int row = Math.max(0, rowAt(mouseY));

                conditionalDialog.insertReference(
                        new org.apache.poi.ss.util.CellReference(row, column, true, true).formatAsString());
            }

            return true;
        }

        // The table search bar sits over the grid. A click anywhere else takes
        // the typing focus back to the sheet but keeps the search on.
        // (Open popups are drawn over it and take their clicks first.)
        boolean popupOpen = cellMenu.isOpen() || filterMenu.isOpen() || palette.isOpen()
                || formatMenu.isOpen() || borderMenu.isOpen() || functionMenu.isOpen()
                || pasteMenu.isOpen() || conditionalMenu.isOpen() || miniToolbar.isOpen()
                || (groupPanel != null && groupPanel.isOpen());

        if (tableSearch.isOpen() && !popupOpen) {
            TableSearchBar.Result result = tableSearch.click(mouseX, mouseY);

            if (result != TableSearchBar.Result.MISSED) {
                if (result == TableSearchBar.Result.NONE) {
                    // Typing goes to the search box, not the cell.
                    commitPendingEdit();
                    if (cellEditor != null) {
                        cellEditor.setFocused(false);
                    }
                    this.setFocused(null);
                }

                handleSearchResult(result);
                return true;
            }
        }

        // A collapsed ribbon group's dropdown. Like the right-click formatting
        // bar it stays open, so several formats can be applied; a list opened
        // from it (palette, borders, paste, number format) takes its place.
        if (groupPanel != null && groupPanel.isOpen() && button == 0) {
            MiniToolbar.Item item = groupPanel.itemAt(mouseX, mouseY);

            if (item != null && item.action() != null) {
                item.action().run();

                if (palette.isOpen() || borderMenu.isOpen() || pasteMenu.isOpen()
                        || formatMenu.isOpen() || conditionalMenu.isOpen()) {
                    groupPanel.close();
                }
            } else if (!groupPanel.contains(mouseX, mouseY)) {
                groupPanel.close();
            }

            return true;
        }

        // The Conditional Formatting dropdown.
        if (conditionalMenu.isOpen() && button == 0) {
            ConditionalMenu.Item item = conditionalMenu.itemAt(mouseX, mouseY);
            boolean inside = conditionalMenu.contains(mouseX, mouseY);

            if (item != null || !inside) {
                conditionalMenu.close();
            }

            if (item != null) {
                chooseConditionalItem(item);
            }

            return true;
        }

        // Clicking a function in the autocomplete list inserts it.
        if (autocomplete.isOpen() && autocomplete.contains(mouseX, mouseY)) {
            if (button == 0 && autocomplete.selectAt(mouseX, mouseY)) {
                acceptAutocomplete();
            }

            return true;
        }

        // Clicking away enters the edit first. An unfinished formula can't be
        // entered, so the click is ignored and the typing kept, like Excel.
        if (clickLeavesEditor(mouseX, mouseY) && !commitPendingEdit()) {
            return true;
        }

        // The formatting bar above the right-click menu. Like Excel, using it
        // closes the menu but keeps the bar, so you can apply several formats.
        if (miniToolbar.isOpen() && button == 0 && miniToolbar.contains(mouseX, mouseY)) {
            MiniToolbar.Item item = miniToolbar.itemAt(mouseX, mouseY);

            if (item != null && item.action() != null) {
                cellMenu.close();
                item.action().run();
            }

            return true;
        }

        miniToolbar.close();

        // The right-click menu: a left click picks an item; any click closes it.
        if (cellMenu.isOpen()) {
            CellMenu.Action action = button == 0 ? cellMenu.actionAt(mouseX, mouseY) : null;
            boolean inside = cellMenu.contains(mouseX, mouseY);

            if (action != null) {
                runCellMenuAction(action);
            }

            cellMenu.close();

            // A right click elsewhere on the grid goes on to open the menu there.
            if (inside || button != 1) {
                return true;
            }
        }

        // Right-clicking a cell opens the right-click menu. A cell outside
        // the selection gets selected first, as in Excel.
        // On a column letter or row number, the whole column or row is
        // selected first (unless it is already part of the selection).
        int rightClickHeader = button == 1 && manager != null ? headerAreaAt(mouseX, mouseY) : HEADER_NONE;

        if (button == 1 && manager != null && (insideGrid(mouseX, mouseY) || rightClickHeader != HEADER_NONE)) {
            commitPendingEdit();

            if (rightClickHeader == HEADER_CORNER) {
                selectAll();
            } else if (rightClickHeader == HEADER_COLUMN) {
                int column = Math.max(0, columnAt(mouseX));

                if (!wholeColumnsSelected() || column < rangeLeft() || column > rangeRight()) {
                    selectColumns(column, column);
                }
            } else if (rightClickHeader == HEADER_ROW) {
                int row = Math.max(0, rowAt(mouseY));

                if (!wholeRowsSelected() || row < rangeTop() || row > rangeBottom()) {
                    selectRows(row, row);
                }
            } else {
                int column = Math.max(0, columnAt(mouseX));
                int row = Math.max(0, rowAt(mouseY));

                if (row < rangeTop() || row > rangeBottom() || column < rangeLeft() || column > rangeRight()) {
                    selectedRow = row;
                    selectedColumn = column;
                    collapseRange();
                    refreshEditor();
                }
            }

            closePopups();
            cellMenu.open((int) mouseX, (int) mouseY, this.width, this.height);
            openMiniToolbar();
            return true;
        }

        // The filter list is a popup too: it stays open while you tick values.
        if (filterMenu.isOpen() && button == 0) {
            FilterMenu.Result result = filterMenu.click(mouseX, mouseY);

            if (result == FilterMenu.Result.APPLY) {
                applyFilterMenu();
            }

            if (result != FilterMenu.Result.NONE) {
                filterMenu.close();
            }

            if (result == FilterMenu.Result.SORT_ASCENDING || result == FilterMenu.Result.SORT_DESCENDING) {
                sortTable(filterMenu.table(), filterMenu.columnOffset(),
                        result == FilterMenu.Result.SORT_ASCENDING);
            }

            return true;
        }

        // The Paste dropdown: the different ways to paste.
        if (pasteMenu.isOpen() && button == 0) {
            PasteMenu.Mode mode = pasteMenu.modeAt(mouseX, mouseY);

            pasteMenu.close();

            if (mode != null) {
                pasteClipboard(mode);
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

        // The Insert Function list: picking one starts (or extends) a formula.
        if (functionMenu.isOpen() && button == 0) {
            FunctionMenu.Function function = functionMenu.functionAt(mouseX, mouseY);

            functionMenu.close();

            if (function != null) {
                insertFunction(function);
            }

            return true;
        }

        // The fx button opens the Insert Function list.
        if (button == 0 && manager != null
                && mouseX >= FX_X && mouseX < FX_X + FX_WIDTH
                && mouseY >= FORMULA_Y && mouseY < FORMULA_Y + BUTTON_HEIGHT) {
            palette.close();
            formatMenu.close();
            pasteMenu.close();
            borderMenu.close();
            filterMenu.close();
            functionMenu.open(FX_X, FORMULA_PANEL_BOTTOM + 2, this.width);
            return true;
        }

        // The Borders dropdown. Picking a line style keeps it open, so you can
        // then pick the border to draw with it.
        if (borderMenu.isOpen() && button == 0) {
            BorderMenu.Result result = borderMenu.click(mouseX, mouseY);

            switch (result.kind()) {
                case OPTION:
                    borderMenu.close();
                    applyBorder(result.option());
                    break;
                case LINE_COLOR:
                    borderMenu.close();
                    palette.open(ColorPalette.Target.BORDER, borderMenu.x(), borderMenu.y(), this.width);
                    break;
                case LINE_STYLE:
                    penStyle = result.lineStyle();
                    status = "Line style set; pick a border to draw it";
                    break;
                case OUTSIDE:
                    borderMenu.close();
                    break;
                default:
                    break;
            }

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

        // Clicking a ribbon tab name (Home, Insert, Draw) switches to it.
        if (button == 0 && manager != null) {
            int tab = ribbonTabAt(mouseX, mouseY);

            if (tab >= 0) {
                switchRibbonTab(tab);
                return true;
            }
        }

        // The sheet bar under the grid: tab arrows, sheet tabs, scroll bar.
        if (button == 0 && manager != null && handleSheetBarClick(mouseX, mouseY)) {
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

                boolean columns = resizeColumn >= 0;
                int dragged = columns ? resizeColumn : resizeRow;
                int[] group = columns ? columnsToResize(dragged) : rowsToResize(dragged);

                // A second click on the same edge soon after: AutoFit, like Excel.
                long now = System.currentTimeMillis();
                boolean doubleClick = now - lastEdgeClickTime <= DOUBLE_CLICK_MS
                        && resizeColumn == lastEdgeClickColumn && resizeRow == lastEdgeClickRow;
                lastEdgeClickTime = doubleClick ? 0 : now;
                lastEdgeClickColumn = resizeColumn;
                lastEdgeClickRow = resizeRow;

                if (doubleClick) {
                    autoFit(columns, group[0], group[1]);
                    return true;
                }

                resizingColumn = resizeColumn;
                resizingRow = resizeRow;
                resizeStartMouse = columns ? mouseX : mouseY;
                resizeStartSize = columns ? baseColumnWidth(dragged) : baseRowHeight(dragged);
                resizeFirst = group[0];
                resizeLast = group[1];
                resizeStartSizes = new int[resizeLast - resizeFirst + 1];

                for (int i = resizeFirst; i <= resizeLast; i++) {
                    resizeStartSizes[i - resizeFirst] = columns ? baseColumnWidth(i) : baseRowHeight(i);
                }

                return true;
            }
        }

        // The column letters, row numbers and the corner box select whole
        // columns, whole rows or the whole sheet. Drag to select several,
        // Shift+click to extend. While typing a formula they insert a
        // reference instead (C:C, 3:3), like Excel.
        int header = button == 0 && manager != null ? headerAreaAt(mouseX, mouseY) : HEADER_NONE;

        if (header != HEADER_NONE) {
            int column = header == HEADER_COLUMN ? Math.max(0, columnAt(mouseX)) : 0;
            int row = header == HEADER_ROW ? Math.max(0, rowAt(mouseY)) : 0;

            if (canInsertReference()) {
                if (!pointing) {
                    pointing = true;
                    pointStart = Math.min(cellEditor.getCursorPosition(), cellEditor.getValue().length());
                    pointEnd = pointStart;
                }

                pointAnchorRow = header == HEADER_ROW ? row : 0;
                pointEndRow = header == HEADER_ROW ? row : LAST_ROW;
                pointAnchorColumn = header == HEADER_COLUMN ? column : 0;
                pointEndColumn = header == HEADER_COLUMN ? column : LAST_COLUMN;
                applyPointReference();
                headerSelectingColumns = header == HEADER_COLUMN;
                headerSelectingRows = header == HEADER_ROW;
                return true;
            }

            commitPendingEdit();

            if (header == HEADER_CORNER) {
                selectAll();
            } else if (header == HEADER_COLUMN) {
                selectColumns(shift ? selectedColumn : column, column);
                headerSelectingColumns = true;
            } else {
                selectRows(shift ? selectedRow : row, row);
                headerSelectingRows = true;
            }

            status = "Selected " + rangeRef(rangeTop(), rangeLeft(), rangeBottom(), rangeRight());
            return true;
        }

        // A drawing tool (Draw tab) draws or erases instead of selecting.
        if (button == 0 && manager != null && inkTool != InkTool.SELECT && insideGrid(mouseX, mouseY)
                && startInk(mouseX, mouseY)) {
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
        if (handleMouseDragged(event.x() - windowX, event.y() - windowY)) {
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
        if (handleMouseDragged(mouseX - windowX, mouseY - windowY)) {
            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }
    *///?}

    private boolean handleMouseDragged(double mouseX, double mouseY) {
        if (inkPoints != null && manager != null) {
            addInkPoint(mouseX, mouseY, false);
            return true;
        }

        if (inkBeforeErase != null && manager != null) {
            eraseInkAt(mouseX, mouseY);
            return true;
        }

        if (draggingScrollThumb && manager != null) {
            dragScrollThumb(mouseX);
            return true;
        }

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

        // Dragging across the column letters or row numbers.
        if (headerSelectingColumns && manager != null) {
            int column = Math.max(0, dragColumn(mouseX));

            if (pointing) {
                pointEndColumn = column;
                applyPointReference();
            } else {
                rangeEndColumn = column;
                status = "Selected " + rangeRef(rangeTop(), rangeLeft(), rangeBottom(), rangeRight());
            }

            return true;
        }

        if (headerSelectingRows && manager != null) {
            int row = Math.max(0, dragRow(mouseY));

            if (pointing) {
                pointEndRow = row;
                applyPointReference();
            } else {
                rangeEndRow = row;
                status = "Selected " + rangeRef(rangeTop(), rangeLeft(), rangeBottom(), rangeRight());
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
        if (button == 0 && (inkPoints != null || inkBeforeErase != null) && manager != null) {
            finishInk();
            return true;
        }

        if (button == 0 && draggingScrollThumb) {
            draggingScrollThumb = false;
            return true;
        }

        if (button == 0 && (resizingColumn >= 0 || resizingRow >= 0)) {
            finishResize();
            resizingColumn = -1;
            resizingRow = -1;
            return true;
        }

        if (button == 0 && (headerSelectingColumns || headerSelectingRows)) {
            headerSelectingColumns = false;
            headerSelectingRows = false;
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
        autocomplete.close();
        this.setFocused(null);
    }

    // Enters the text being typed into the cell and leaves the editor.
    // Returns false (and stays in the editor, keeping the text) when it
    // can't be entered, e.g. an unfinished formula like "=A1+", so the
    // caller should stop instead of throwing the typing away.
    private boolean commitPendingEdit() {
        if (manager != null && cellEditor != null
                && cellEditor.isFocused()
                && !applyCellEdit()) {
            return false;
        }

        leaveEditor();
        return true;
    }

    // True when a click would end the edit in progress: anywhere except the
    // edit box itself and the clicks that add to the formula being typed.
    private boolean clickLeavesEditor(double mouseX, double mouseY) {
        if (manager == null || cellEditor == null || !cellEditor.isFocused()) {
            return false;
        }

        int editorLeft = cellEditor.getX() - windowX;
        int editorTop = cellEditor.getY() - windowY;

        if (mouseX >= editorLeft && mouseX < editorLeft + cellEditor.getWidth()
                && mouseY >= editorTop && mouseY < editorTop + cellEditor.getHeight()) {
            return false;
        }

        // The Insert Function list and the fx button add a function to the formula.
        if (functionMenu.isOpen()
                || (mouseX >= FX_X && mouseX < FX_X + FX_WIDTH
                && mouseY >= FORMULA_Y && mouseY < FORMULA_Y + BUTTON_HEIGHT)) {
            return false;
        }

        // Clicking cells or headers inserts references into the formula.
        return !(canInsertReference()
                && (insideGrid(mouseX, mouseY) || headerAreaAt(mouseX, mouseY) != HEADER_NONE));
    }

    // Returns false when the cell couldn't be changed (the editor keeps the text).
    private boolean applyCellEdit() {
        if (manager == null || cellEditor == null) {
            return true;
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
            return true;
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

            // An unfinished or mistyped formula is entered too, showing an error like #NAME?.
            String shown = manager.getCellText(sheetIndex, selectedRow, selectedColumn);

            if (newValue.trim().startsWith("=") && shown.startsWith("#")) {
                status += " (the formula gives " + shown + ")";
            }

            return true;

        } catch (RuntimeException exception) {
            // Keep editing so nothing typed is lost (Esc cancels).
            status = "Could not update cell: " + exception.getMessage();
            return false;
        }
    }

    private void saveWorkbook() {
        if (manager == null) {
            return;
        }

        if (saving()) {
            return;
        }

        if (!commitPendingEdit()) {
            return;
        }

        WorkbookManager workbook = manager;
        Path path = workbookPath;

        // Someone else's shared workbook: save to a copy in this player's own workbooks.
        if (shared != null && !shared.owner()) {
            try {
                if (sharedCopyPath == null) {
                    sharedCopyPath = WorkbookFiles.freeWorkbookPath(shared.name());
                }
            } catch (IOException exception) {
                status = "Save failed: " + exception.getMessage();
                return;
            }

            path = sharedCopyPath;
        }

        Path target = path;
        status = "Saving " + target.getFileName() + "...";
        pendingSave = CompletableFuture.runAsync(() -> {
            try {
                workbook.save(target);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, WORKBOOK_IO);
    }

    // After a save: the owner of a shared workbook sends it to the server.
    private void afterSharedSave() {
        if (shared == null) {
            return;
        }

        if (shared.isConnector()) {
            String error = ShareClient.saveConnector(workbookPath, shared.connector(), message -> status = message);
            String label = ShareClient.linkedLabel(shared.connector());
            status = error != null ? "Saved here, but the " + label + " wasn't updated: " + error
                    : "Saved. Updating the " + label + "...";
            return;
        }

        if (!shared.owner()) {
            status = "Saved a copy to your workbooks: " + sharedCopyPath.getFileName();
            return;
        }

        // The item's map picture shows what the owner is looking at.
        byte[] preview = io.github.reverendbrown.excelcomputer.share.SheetPreview.render(manager,
                workbookPath.getFileName().toString(), sheetIndex, firstVisibleRow, firstVisibleColumn);
        String error = ShareClient.upload(workbookPath, shared.id(), preview, message -> status = message);
        status = error != null ? "Saved here, but not shared: " + error : "Saved. Updating the shared copy...";
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
        if (!commitPendingEdit()) {
            return;
        }


        if (dirty && !discardArmed) {
            discardArmed = true;
            status = "Unsaved changes! Save (Ctrl+S), or leave again to discard.";
            return;
        }

        returnToPicker();
    }

    private void returnToPicker() {
        if (!commitPendingEdit()) {
            return;
        }

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
        addRibbonButton(x, RIBBON_ROW1_Y, width, label, tip, action);
    }

    private Button addRibbonButton(
            int x,
            int y,
            int width,
            Component label,
            String tip,
            Runnable action
    ) {
        Button button = Button.builder(label, pressed -> action.run())
                .bounds(x, y, width, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.literal(tip)))
                .build();

        // Measuring the Home groups (see addHomeButtons): nothing is added.
        if (measuringRibbon) {
            return button;
        }

        widestRibbon = Math.max(widestRibbon, x + width);
        return this.addRenderableWidget(button);
    }

    // ----- sheet bar (Excel's strip under the grid) -----
    //
    //   [<][>]  Sheet1 | Sheet2 | Sheet3 |          [<]  [====thumb====]      [>]
    //   tab arrows      sheet tabs                  column scroll bar

    private int sheetBarTop() {
        return this.height - SHEET_BAR_OFFSET;
    }

    private int scrollBarRight() {
        return this.width - 10;
    }

    private int scrollBarLeft() {
        int width = Math.max(80, Math.min(260, (this.width - 20) * 2 / 5));

        return scrollBarRight() - width;
    }

    private int tabsRight() {
        return scrollBarLeft() - 12;
    }

    private String tabLabel(int sheet) {
        return this.font.plainSubstrByWidth(manager.getSheetName(sheet), 90);
    }

    private int tabWidth(int sheet) {
        return this.font.width(tabLabel(sheet)) + 16;
    }

    // The last tab that fits, starting at firstVisibleTab (at least one shows).
    private int lastVisibleTab() {
        int x = TABS_LEFT;
        int last = firstVisibleTab - 1;

        for (int i = firstVisibleTab; i < manager.getSheetCount(); i++) {
            int width = tabWidth(i);

            if (x + width > tabsRight() && i > firstVisibleTab) {
                break;
            }

            x += width + 1;
            last = i;
        }

        return last;
    }

    private void revealActiveTabIfNeeded() {
        if (!revealActiveTab || manager == null) {
            return;
        }

        revealActiveTab = false;
        firstVisibleTab = Math.max(0, Math.min(firstVisibleTab, manager.getSheetCount() - 1));

        if (sheetIndex < firstVisibleTab) {
            firstVisibleTab = sheetIndex;
        }

        while (firstVisibleTab < sheetIndex && lastVisibleTab() < sheetIndex) {
            firstVisibleTab++;
        }
    }

    // How many columns the scroll bar spans: a little past what is on screen,
    // so the bar can always scroll further right, like Excel's.
    private int scrollExtent() {
        return Math.max(52, firstVisibleColumn + visibleColumns() * 2);
    }

    // {trackLeft, trackRight, thumbLeft, thumbWidth} for a given extent.
    private int[] scrollGeometry(int extent) {
        int trackLeft = scrollBarLeft() + SCROLL_ARROW_WIDTH + 2;
        int trackRight = scrollBarRight() - SCROLL_ARROW_WIDTH - 2;
        int length = Math.max(1, trackRight - trackLeft);
        int visible = visibleColumns();
        int thumbWidth = Math.max(12, Math.min(length, length * visible / Math.max(1, extent)));
        int room = length - thumbWidth;
        int maxFirst = Math.max(1, extent - visible);
        int thumbLeft = trackLeft + (int) Math.min(room, (long) room * firstVisibleColumn / maxFirst);

        return new int[]{trackLeft, trackRight, thumbLeft, thumbWidth};
    }

    // Scrolls the grid so the given column is first, keeping the active cell on screen.
    private void scrollColumnsTo(int first) {
        commitPendingEdit();
        firstVisibleColumn = Math.max(0, Math.min(16_383, first));

        int visible = visibleColumns();
        selectedColumn = Math.max(
                firstVisibleColumn,
                Math.min(selectedColumn, firstVisibleColumn + visible - 1)
        );
        collapseRange();
        refreshEditor();
    }

    private void drawCentered(GuiGraphics graphics, String text, int left, int width, int y, int color) {
        graphics.drawString(this.font, text, left + (width - this.font.width(text)) / 2, y, color);
    }

    private void renderSheetBar(GuiGraphics graphics, int mouseX, int mouseY) {
        if (manager == null) {
            return;
        }

        revealActiveTabIfNeeded();

        int top = sheetBarTop();
        int bottom = top + SHEET_BAR_HEIGHT;
        int textY = top + (SHEET_BAR_HEIGHT - 8) / 2;
        boolean overBar = mouseY >= top && mouseY < bottom;
        int count = manager.getSheetCount();
        int lastTab = lastVisibleTab();

        graphics.fill(10, top, this.width - 10, bottom, 0xFF2A2B2E);
        graphics.fill(10, top, this.width - 10, top + 1, 0xFF3C4043);

        // Tab arrows: grey when there is nothing more to scroll to.
        int prevX = 12;
        int nextX = 12 + TAB_ARROW_WIDTH;
        boolean canPrev = firstVisibleTab > 0;
        boolean canNext = lastTab < count - 1;

        if (overBar && canPrev && mouseX >= prevX && mouseX < prevX + TAB_ARROW_WIDTH) {
            graphics.fill(prevX, top + 1, prevX + TAB_ARROW_WIDTH, bottom, 0xFF3C4043);
        }
        if (overBar && canNext && mouseX >= nextX && mouseX < nextX + TAB_ARROW_WIDTH) {
            graphics.fill(nextX, top + 1, nextX + TAB_ARROW_WIDTH, bottom, 0xFF3C4043);
        }

        drawCentered(graphics, "<", prevX, TAB_ARROW_WIDTH, textY, canPrev ? 0xFFDADCE0 : 0xFF5F6368);
        drawCentered(graphics, ">", nextX, TAB_ARROW_WIDTH, textY, canNext ? 0xFFDADCE0 : 0xFF5F6368);

        // Sheet tabs: flat, with a divider after each; the active one is
        // raised, white and underlined in green.
        int x = TABS_LEFT;

        for (int i = firstVisibleTab; i <= lastTab; i++) {
            int width = tabWidth(i);
            boolean active = i == sheetIndex;
            boolean hovered = overBar && mouseX >= x && mouseX < x + width;

            if (active) {
                graphics.fill(x, top + 1, x + width, bottom, 0xFF3C4043);
            } else if (hovered) {
                graphics.fill(x, top + 1, x + width, bottom, 0xFF34363A);
            }

            String label = tabLabel(i);
            drawCentered(graphics, label, x, width, textY, active ? 0xFFFFFFFF : 0xFFBDC1C6);

            if (active) {
                int labelWidth = this.font.width(label);
                int underlineLeft = x + (width - labelWidth) / 2;
                graphics.fill(underlineLeft, bottom - 2, underlineLeft + labelWidth, bottom - 1, 0xFF33A06A);
            }

            graphics.fill(x + width, top + 3, x + width + 1, bottom - 2, 0xFF5F6368);
            x += width + 1;
        }

        // Column scroll bar: end arrows, a track and a draggable thumb.
        int barLeft = scrollBarLeft();
        int barRight = scrollBarRight();
        int[] geometry = scrollGeometry(draggingScrollThumb ? scrollDragExtent : scrollExtent());
        int trackTop = top + 4;
        int trackBottom = bottom - 3;
        boolean overThumb = overBar && mouseX >= geometry[2] && mouseX < geometry[2] + geometry[3];

        drawCentered(graphics, "<", barLeft, SCROLL_ARROW_WIDTH, textY,
                firstVisibleColumn > 0 ? 0xFFDADCE0 : 0xFF5F6368);
        drawCentered(graphics, ">", barRight - SCROLL_ARROW_WIDTH, SCROLL_ARROW_WIDTH, textY, 0xFFDADCE0);

        graphics.fill(geometry[0], trackTop, geometry[1], trackBottom, 0xFF202124);
        graphics.fill(
                geometry[2], trackTop, geometry[2] + geometry[3], trackBottom,
                draggingScrollThumb || overThumb ? 0xFFBDC1C6 : 0xFF80868B
        );
    }

    // Returns true when the click was on the sheet bar.
    private boolean handleSheetBarClick(double mouseX, double mouseY) {
        int top = sheetBarTop();

        if (mouseY < top || mouseY >= top + SHEET_BAR_HEIGHT
                || mouseX < 10 || mouseX >= this.width - 10) {
            return false;
        }

        int count = manager.getSheetCount();

        // Tab arrows.
        if (mouseX >= 12 && mouseX < 12 + TAB_ARROW_WIDTH) {
            firstVisibleTab = Math.max(0, firstVisibleTab - 1);
            return true;
        }

        if (mouseX >= 12 + TAB_ARROW_WIDTH && mouseX < 12 + TAB_ARROW_WIDTH * 2) {
            if (lastVisibleTab() < count - 1) {
                firstVisibleTab++;
            }
            return true;
        }

        // Sheet tabs.
        int x = TABS_LEFT;
        int lastTab = lastVisibleTab();

        for (int i = firstVisibleTab; i <= lastTab; i++) {
            int width = tabWidth(i);

            if (mouseX >= x && mouseX < x + width) {
                if (i != sheetIndex) {
                    switchSheet(i);
                }
                return true;
            }

            x += width + 1;
        }

        // Column scroll bar.
        int barLeft = scrollBarLeft();
        int barRight = scrollBarRight();

        if (mouseX < barLeft) {
            return true;
        }

        if (mouseX < barLeft + SCROLL_ARROW_WIDTH) {
            scrollColumnsTo(firstVisibleColumn - 1);
            return true;
        }

        if (mouseX >= barRight - SCROLL_ARROW_WIDTH) {
            scrollColumnsTo(firstVisibleColumn + 1);
            return true;
        }

        int extent = scrollExtent();
        int[] geometry = scrollGeometry(extent);

        if (mouseX >= geometry[2] && mouseX < geometry[2] + geometry[3]) {
            draggingScrollThumb = true;
            scrollThumbGrab = mouseX - geometry[2];
            scrollDragExtent = extent;
        } else if (mouseX < geometry[2]) {
            scrollColumnsTo(firstVisibleColumn - visibleColumns());
        } else {
            scrollColumnsTo(firstVisibleColumn + visibleColumns());
        }

        return true;
    }

    private void dragScrollThumb(double mouseX) {
        int[] geometry = scrollGeometry(scrollDragExtent);
        int room = Math.max(1, geometry[1] - geometry[0] - geometry[3]);
        int maxFirst = Math.max(1, scrollDragExtent - visibleColumns());
        double fraction = (mouseX - scrollThumbGrab - geometry[0]) / room;

        scrollColumnsTo((int) Math.round(Math.max(0, Math.min(1, fraction)) * maxFirst));
    }

    // ----- ribbon tabs -----

    // Left edge of a tab's name in the ribbon header.
    private int ribbonTabX(int tab) {
        int x = RIBBON_TAB_LEFT;

        for (int i = 0; i < tab; i++) {
            x += this.font.width(RIBBON_TABS[i]) + RIBBON_TAB_GAP;
        }

        return x;
    }

    // The ribbon tab whose name is under the mouse, or -1.
    private int ribbonTabAt(double mouseX, double mouseY) {
        if (mouseY < RIBBON_PANEL_TOP || mouseY >= RIBBON_Y) {
            return -1;
        }

        for (int i = 0; i < RIBBON_TABS.length; i++) {
            int left = ribbonTabX(i) - RIBBON_TAB_GAP / 2;
            int right = ribbonTabX(i) + this.font.width(RIBBON_TABS[i]) + RIBBON_TAB_GAP / 2;

            if (mouseX >= left && mouseX < right) {
                return i;
            }
        }

        return -1;
    }

    private void switchRibbonTab(int tab) {
        if (tab == ribbonTab) {
            return;
        }

        // Rebuilding the widgets resets the edit box, so keep any typing first.
        if (!commitPendingEdit()) {
            return;
        }

        palette.close();
        formatMenu.close();
        pasteMenu.close();
        borderMenu.close();
        ribbonTab = tab;
        // The drawing tools only work while the Draw tab is open.
        if (tab != TAB_DRAW) {
            inkTool = InkTool.SELECT;
        }

        rebuildWidgets();
    }

    // ----- ribbon groups (Clipboard, Font, Alignment, ...) -----

    // A labelled group of ribbon buttons, from left to right (inclusive of buttons).
    private record RibbonGroup(String label, int left, int right) {
    }

    // Space between a group's buttons and the divider line on each side.
    private static final int GROUP_PAD = 6;

    // Ends a group whose buttons run from left to right; returns where the next group starts.
    private int endGroup(String label, int left, int right) {
        int labelWidth = this.font.width(label);
        right = Math.max(right, left + labelWidth);

        if (!measuringRibbon) {
            ribbonGroups.add(new RibbonGroup(label, left, right));
        }

        return right + GROUP_PAD * 2 + 1;
    }

    private void addRibbonButtons() {
        ribbonGroups.clear();

        if (manager == null) {
            return;
        }

        if (ribbonTab == TAB_HOME) {
            addHomeButtons();
        } else if (ribbonTab == TAB_INSERT) {
            addInsertButtons();
        } else if (ribbonTab == TAB_DRAW) {
            addDrawButtons();
        }
    }

    // ----- drawing (the Draw tab): pen, highlighter and eraser on the sheet -----
    //
    // Strokes are kept by InkDrawings in cell space (column + how far across it,
    // row + how far down it), so they stay on the cells they were drawn over.

    private enum InkTool { SELECT, PEN, HIGHLIGHTER, ERASER }

    // Pen widths in pixels at 100% zoom (Thin, Medium, Thick); the highlighter is wider.
    private static final float[] PEN_WIDTHS = {1, 2, 4};
    private static final float[] HIGHLIGHTER_WIDTHS = {6, 10, 16};
    private static final String[] WIDTH_NAMES = {"Thin", "Medium", "Thick"};
    // A new point is only added once the mouse has moved this far (screen pixels).
    private static final double INK_MIN_STEP = 1.5;
    // How see-through the highlighter is on screen (alpha of 0xAARRGGBB).
    private static final int HIGHLIGHTER_SCREEN_ALPHA = 0x66;

    private InkTool inkTool = InkTool.SELECT;
    private int inkPenColor = 0x000000;
    private int highlighterColor = 0xFFFF00;
    private int inkWidth = 1;
    private int inkColorButtonX;
    // The stroke being drawn (cell space), or null when not drawing.
    private List<double[]> inkPoints;
    private double inkLastX;
    private double inkLastY;
    // While erasing: the sheet's strokes before this drag (for one undo step).
    private List<InkDrawings.Stroke> inkBeforeErase;

    private void addDrawButtons() {
        int start = 14;
        int x = start;

        addRibbonButton(x, 40, inkToolLabel("Select", InkTool.SELECT),
                "Select cells again (stop drawing)", () -> setInkTool(InkTool.SELECT));
        x += 42;
        addRibbonButton(x, 30, inkToolLabel("Pen", InkTool.PEN),
                "Draw on the sheet with the mouse", () -> setInkTool(InkTool.PEN));
        x += 32;
        addRibbonButton(x, 60, inkToolLabel("Highlighter", InkTool.HIGHLIGHTER),
                "Draw see-through strokes over the cells", () -> setInkTool(InkTool.HIGHLIGHTER));
        x += 62;
        addRibbonButton(x, 44, inkToolLabel("Eraser", InkTool.ERASER),
                "Click or drag over strokes to erase them", () -> setInkTool(InkTool.ERASER));
        x += 44;

        int next = endGroup("Tools", start, x);
        start = next;
        x = next;

        inkColorButtonX = x;
        addRibbonButton(x, 36, Component.literal("Color"),
                "The color of the pen (or the highlighter, while it is chosen)", this::toggleInkPalette);
        x += 38;
        addRibbonButton(x, 46, Component.literal(WIDTH_NAMES[inkWidth]),
                "How thick the strokes are (click to change)", this::cycleInkWidth);
        x += 46;

        next = endGroup("Pen", start, x);
        start = next;
        x = next;

        addRibbonButton(x, 36, Component.literal("Clear"),
                "Erase every drawing on this sheet", this::clearInk);
        x += 36;

        endGroup("Edit", start, x);
    }

    // The chosen tool's name is shown highlighted.
    private Component inkToolLabel(String name, InkTool tool) {
        return inkTool == tool
                ? Component.literal(name).withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE)
                : Component.literal(name);
    }

    private void setInkTool(InkTool tool) {
        if (tool != InkTool.SELECT && manager.ink() == null) {
            status = "Drawings need an .xlsx workbook (a .csv file can't hold them)";
            return;
        }

        if (!commitPendingEdit()) {
            return;
        }

        palette.close();
        inkTool = tool;
        status = switch (tool) {
            case SELECT -> "Selecting cells";
            case PEN -> "Pen: drag on the sheet to draw";
            case HIGHLIGHTER -> "Highlighter: drag over cells to highlight them";
            case ERASER -> "Eraser: click or drag over a stroke to erase it";
        };
        rebuildWidgets();
    }

    private void toggleInkPalette() {
        if (palette.isOpen() && palette.target() == ColorPalette.Target.INK) {
            palette.close();
        } else {
            closePopups();
            palette.open(ColorPalette.Target.INK, inkColorButtonX, RIBBON_ROW1_Y + BUTTON_HEIGHT + 2, this.width);
        }
    }

    private void cycleInkWidth() {
        inkWidth = (inkWidth + 1) % WIDTH_NAMES.length;
        status = WIDTH_NAMES[inkWidth] + " strokes";
        rebuildWidgets();
    }

    private void applyInkColor(int color) {
        // "Automatic" (the top strip): black pen, yellow highlighter.
        if (inkTool == InkTool.HIGHLIGHTER) {
            highlighterColor = color >= 0 ? color : 0xFFFF00;
            status = "Highlighter color set";
        } else {
            inkPenColor = color >= 0 ? color : 0x000000;
            status = "Pen color set";
        }
    }

    private int currentInkColor() {
        return inkTool == InkTool.HIGHLIGHTER ? highlighterColor : inkPenColor;
    }

    private void clearInk() {
        InkDrawings ink = manager.ink();

        if (ink == null || ink.strokes(sheetIndex).isEmpty()) {
            status = "No drawings on this sheet";
            return;
        }

        List<InkDrawings.Stroke> before = new ArrayList<>(ink.strokes(sheetIndex));
        ink.set(sheetIndex, List.of());
        recordInk(before, "clearing the drawings");
        status = "Cleared the drawings on this sheet";
    }

    // One undo step for a drawing change, from the strokes before it to how they are now.
    private void recordInk(List<InkDrawings.Stroke> before, String description) {
        undoHistory.push(CellEdit.inkChange(sheetIndex,
                new InkChange(before, new ArrayList<>(manager.ink().strokes(sheetIndex)), description)));
        redoHistory.clear();
        markDirty();
    }

    // A click on the grid while a drawing tool is chosen.
    private boolean startInk(double mouseX, double mouseY) {
        if (manager.ink() == null) {
            inkTool = InkTool.SELECT;
            return false;
        }

        commitPendingEdit();

        if (inkTool == InkTool.ERASER) {
            inkBeforeErase = new ArrayList<>(manager.ink().strokes(sheetIndex));
            eraseInkAt(mouseX, mouseY);
        } else {
            inkPoints = new ArrayList<>();
            addInkPoint(mouseX, mouseY, true);
        }

        return true;
    }

    private void addInkPoint(double mouseX, double mouseY, boolean first) {
        // Strokes stay inside the grid (drawing off its edge doesn't scroll).
        double x = Math.max(GRID_X, Math.min(gridRight() - 1, mouseX));
        double y = Math.max(GRID_Y + headerHeight(), Math.min(gridBottom() - 1, mouseY));

        if (!first && Math.hypot(x - inkLastX, y - inkLastY) < INK_MIN_STEP) {
            return;
        }

        inkLastX = x;
        inkLastY = y;
        inkPoints.add(new double[]{cellSpaceX(x), cellSpaceY(y)});
    }

    private void finishInk() {
        if (inkPoints != null) {
            List<double[]> points = inkPoints;
            inkPoints = null;
            double[] xs = new double[points.size()];
            double[] ys = new double[points.size()];

            for (int i = 0; i < points.size(); i++) {
                xs[i] = points.get(i)[0];
                ys[i] = points.get(i)[1];
            }

            boolean highlighter = inkTool == InkTool.HIGHLIGHTER;
            List<InkDrawings.Stroke> before = new ArrayList<>(manager.ink().strokes(sheetIndex));
            manager.ink().add(sheetIndex, new InkDrawings.Stroke(currentInkColor(),
                    (highlighter ? HIGHLIGHTER_WIDTHS : PEN_WIDTHS)[inkWidth], highlighter, xs, ys));
            recordInk(before, highlighter ? "highlighting" : "drawing");
        }

        if (inkBeforeErase != null) {
            List<InkDrawings.Stroke> before = inkBeforeErase;
            inkBeforeErase = null;

            if (before.size() != manager.ink().strokes(sheetIndex).size()) {
                recordInk(before, "erasing");
            }
        }
    }

    // Erases every stroke that passes under the mouse.
    private void eraseInkAt(double mouseX, double mouseY) {
        InkDrawings ink = manager.ink();
        List<InkDrawings.Stroke> hit = new ArrayList<>();

        for (InkDrawings.Stroke stroke : ink.strokes(sheetIndex)) {
            double reach = Math.max(4, stroke.width() * zoomPercent / 200.0 + 2);

            if (strokeNear(stroke, mouseX, mouseY, reach)) {
                hit.add(stroke);
            }
        }

        for (InkDrawings.Stroke stroke : hit) {
            ink.remove(sheetIndex, stroke);
        }

        if (!hit.isEmpty()) {
            status = "Erased " + hit.size() + (hit.size() == 1 ? " stroke" : " strokes");
        }
    }

    private boolean strokeNear(InkDrawings.Stroke stroke, double mouseX, double mouseY, double reach) {
        double previousX = screenX(stroke.xs()[0]);
        double previousY = screenY(stroke.ys()[0]);

        if (stroke.size() == 1) {
            return Math.hypot(mouseX - previousX, mouseY - previousY) <= reach;
        }

        for (int i = 1; i < stroke.size(); i++) {
            double x = screenX(stroke.xs()[i]);
            double y = screenY(stroke.ys()[i]);

            if (distanceToSegment(mouseX, mouseY, previousX, previousY, x, y) <= reach) {
                return true;
            }

            previousX = x;
            previousY = y;
        }

        return false;
    }

    private static double distanceToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lengthSquared));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    // ----- screen <-> cell space -----

    // Screen x (inside the grid) to cell space: the column plus how far across it.
    private double cellSpaceX(double mouseX) {
        int column = Math.max(0, columnAt(mouseX));
        int width = columnWidth(column);
        return column + Math.max(0, Math.min(1, (mouseX - columnLeft(column)) / (double) width));
    }

    private double cellSpaceY(double mouseY) {
        int row = Math.max(0, rowAt(mouseY));
        int height = Math.max(1, rowHeight(row));
        return row + Math.max(0, Math.min(1, (mouseY - rowTop(row)) / (double) height));
    }

    // Cell space to screen; also works for columns and rows scrolled out of view.
    private double screenX(double cellX) {
        int column = (int) Math.floor(cellX);
        return edgeX(column) + (cellX - column) * columnWidth(column);
    }

    private double screenY(double cellY) {
        int row = (int) Math.floor(cellY);
        return edgeY(row) + (cellY - row) * rowHeight(row);
    }

    // Left edge of any column on screen (left of the grid for columns scrolled past).
    private int edgeX(int column) {
        if (column >= firstVisibleColumn) {
            return columnLeft(column);
        }

        int x = GRID_X;

        for (int c = column; c < firstVisibleColumn; c++) {
            x -= columnWidth(c);
        }

        return x;
    }

    private int edgeY(int row) {
        if (row >= firstVisibleRow) {
            return rowTop(row);
        }

        int y = GRID_Y + headerHeight();

        for (int r = row; r < firstVisibleRow; r++) {
            y -= rowHeight(r);
        }

        return y;
    }

    // ----- drawing the strokes -----

    // Draws the sheet's strokes and the one being drawn, over the cells.
    private void renderInk(GuiGraphics graphics) {
        InkDrawings ink = manager == null ? null : manager.ink();

        if (ink == null) {
            return;
        }

        List<InkDrawings.Stroke> strokes = ink.strokes(sheetIndex);

        if (strokes.isEmpty() && inkPoints == null) {
            return;
        }

        int lastColumn = firstVisibleColumn + visibleColumns() + 1;
        int lastRow = firstVisibleRow + visibleRows() + 1;

        beginOverlay(graphics);
        enableWindowScissor(graphics, GRID_X, GRID_Y + headerHeight(), gridRight(), gridBottom());

        for (InkDrawings.Stroke stroke : strokes) {
            if (visible(stroke.xs(), stroke.ys(), lastColumn, lastRow)) {
                renderStroke(graphics, stroke.xs(), stroke.ys(), stroke.color(), stroke.width(),
                        stroke.highlighter());
            }
        }

        if (inkPoints != null && !inkPoints.isEmpty()) {
            double[] xs = new double[inkPoints.size()];
            double[] ys = new double[inkPoints.size()];

            for (int i = 0; i < inkPoints.size(); i++) {
                xs[i] = inkPoints.get(i)[0];
                ys[i] = inkPoints.get(i)[1];
            }

            boolean highlighter = inkTool == InkTool.HIGHLIGHTER;
            renderStroke(graphics, xs, ys, currentInkColor(),
                    (highlighter ? HIGHLIGHTER_WIDTHS : PEN_WIDTHS)[inkWidth], highlighter);
        }

        graphics.disableScissor();
        endOverlay(graphics);
    }

    // Whether any part of a stroke can be on screen (strokes far off are skipped).
    private boolean visible(double[] xs, double[] ys, int lastColumn, int lastRow) {
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }

        return maxX >= firstVisibleColumn - 1 && minX <= lastColumn
                && maxY >= firstVisibleRow - 1 && minY <= lastRow;
    }

    // A stroke as runs of 1-pixel strips across each segment: the strips don't
    // overlap, so a see-through highlighter stays even.
    private void renderStroke(GuiGraphics graphics, double[] xs, double[] ys, int color, float width,
                              boolean highlighter) {
        int argb = (highlighter ? HIGHLIGHTER_SCREEN_ALPHA << 24 : 0xFF000000) | (color & 0xFFFFFF);
        int size = Math.max(1, Math.round(width * zoomPercent / 100f));
        double previousX = screenX(xs[0]);
        double previousY = screenY(ys[0]);

        if (xs.length == 1) {
            int x = (int) Math.round(previousX) - size / 2;
            int y = (int) Math.round(previousY) - size / 2;
            graphics.fill(x, y, x + size, y + size, argb);
            return;
        }

        for (int i = 1; i < xs.length; i++) {
            double x = screenX(xs[i]);
            double y = screenY(ys[i]);
            // Each segment leaves out its first pixel (the previous one drew it), except the first.
            renderSegment(graphics, previousX, previousY, x, y, size, argb, i > 1);
            previousX = x;
            previousY = y;
        }
    }

    private static void renderSegment(GuiGraphics graphics, double x0, double y0, double x1, double y1,
                                      int size, int argb, boolean skipStart) {
        int half = size / 2;

        if (Math.abs(x1 - x0) >= Math.abs(y1 - y0)) {
            int from = (int) Math.round(x0);
            int to = (int) Math.round(x1);
            int step = from <= to ? 1 : -1;

            for (int x = skipStart && from != to ? from + step : from; ; x += step) {
                double t = to == from ? 0 : (x - from) / (double) (to - from);
                int y = (int) Math.round(y0 + (y1 - y0) * t) - half;
                graphics.fill(x, y, x + 1, y + size, argb);

                if (x == to) {
                    break;
                }
            }
        } else {
            int from = (int) Math.round(y0);
            int to = (int) Math.round(y1);
            int step = from <= to ? 1 : -1;

            // Shorter than a pixel after rounding: one strip.
            if (from == to) {
                int x = (int) Math.round(x0) - half;
                graphics.fill(x, from, x + size, from + 1, argb);
                return;
            }

            for (int y = skipStart ? from + step : from; ; y += step) {
                double t = (y - from) / (double) (to - from);
                int x = (int) Math.round(x0 + (x1 - x0) * t) - half;
                graphics.fill(x, y, x + size, y + 1, argb);

                if (y == to) {
                    break;
                }
            }
        }
    }

    private void addInsertButtons() {
        int start = 14;
        int x = start;

        addRibbonButton(x, 44, Component.literal("Table"),
                "Make a table from the selection (click again inside a table to turn it back into a range)",
                this::toggleTable);
        x += 46;
        addRibbonButton(x, 44, Component.literal("Filter"),
                "Show or hide the filter buttons",
                this::toggleFilter);
        x += 44;

        x = endGroup("Tables", start, x);
        start = x;

        addRibbonButton(x, RIBBON_ROW1_Y, 96, Component.literal("Sort Ascending"),
                "Sort the table by the selected column: Smallest to Largest (A to Z for text)",
                () -> sortSelectedColumn(true));
        addRibbonButton(x, RIBBON_ROW2_Y, 96, Component.literal("Sort Descending"),
                "Sort the table by the selected column: Largest to Smallest (Z to A for text)",
                () -> sortSelectedColumn(false));
        x += 98;
        addRibbonButton(x, 72, Component.literal("Search"),
                "Search the selected table: only rows containing the text are shown (Ctrl+F)",
                this::openTableSearch);
        x += 72;

        endGroup("Sort & Search", start, x);
    }

    // The Home tab's groups, left to right, like Excel's.
    private enum HomeGroup {
        CLIPBOARD("Clipboard"),
        FONT("Font"),
        ALIGNMENT("Alignment"),
        NUMBER("Number"),
        STYLES("Styles"),
        EDITING("Editing");

        private final String label;

        HomeGroup(String label) {
            this.label = label;
        }
    }

    // While true, addRibbonButton and endGroup only measure (nothing is added).
    private boolean measuringRibbon;
    // Home groups shown as one dropdown button because the ribbon doesn't fit.
    private final java.util.EnumSet<HomeGroup> collapsedGroups = java.util.EnumSet.noneOf(HomeGroup.class);
    // The open dropdown of a collapsed group, and which group it is (null when none).
    private MiniToolbar groupPanel;
    private HomeGroup openGroup;

    // Laid out like Excel's Home tab: Clipboard | Font | Alignment | Number | Styles | Editing.
    // When the window is too narrow for everything, groups collapse from the
    // right into one button each (like Excel), which opens their controls as
    // a dropdown. A group only collapses if that actually saves room.
    private void addHomeButtons() {
        HomeGroup[] groups = HomeGroup.values();
        int[] full = new int[groups.length];
        int[] small = new int[groups.length];

        measuringRibbon = true;
        try {
            for (int i = 0; i < groups.length; i++) {
                full[i] = layoutHomeGroup(groups[i], 0, false);
                small[i] = layoutHomeGroup(groups[i], 0, true);
            }
        } finally {
            measuringRibbon = false;
        }

        // The right edge of the last group, and the room for it (the same
        // 14 pixel margin init keeps on the right).
        int total = 14 - (GROUP_PAD * 2 + 1);

        for (int width : full) {
            total += width;
        }

        int available = this.width - 14;

        // A window (not full screen) first grows to fit the whole ribbon (see
        // init); groups only collapse when the screen itself is too narrow.
        widestRibbon = Math.max(widestRibbon, total);

        collapsedGroups.clear();

        for (int i = groups.length - 1; i >= 0 && total > available; i--) {
            if (small[i] < full[i]) {
                collapsedGroups.add(groups[i]);
                total -= full[i] - small[i];
            }
        }

        int start = 14;

        for (HomeGroup group : groups) {
            start = layoutHomeGroup(group, start, collapsedGroups.contains(group));
        }
    }

    // Lays out one group from start; returns where the next group starts.
    private int layoutHomeGroup(HomeGroup group, int start, boolean collapsed) {
        if (collapsed) {
            String label = group.label + " v";
            int width = this.font.width(label) + 10;
            final int anchorX = start;

            addRibbonButton(start, RIBBON_ROW1_Y, width, Component.literal(label),
                    group.label + " (the window is too narrow to show these buttons)",
                    () -> toggleGroupPanel(group, anchorX));
            return endGroup(group.label, start, start + width);
        }

        return switch (group) {
            case CLIPBOARD -> layoutClipboard(start);
            case FONT -> layoutFont(start);
            case ALIGNMENT -> layoutAlignment(start);
            case NUMBER -> layoutNumber(start);
            case STYLES -> layoutStyles(start);
            case EDITING -> layoutEditing(start);
        };
    }

    private int layoutClipboard(int start) {
        int row1 = RIBBON_ROW1_Y;
        int row2 = RIBBON_ROW2_Y;
        int x = start;

        // Paste: the button pastes everything, the arrow opens the other paste types.
        addRibbonButton(x, row1, 55, Component.literal("Paste"),
                "Paste with formatting (Ctrl+V). Column widths and row heights stay as they are",
                () -> pasteClipboard(PasteMenu.Mode.ALL));
        final int pasteMenuX = x;
        addRibbonButton(x + 57, row1, 12, Component.literal("v"),
                "Paste options: formulas, values, formatting, widths and heights",
                () -> togglePasteMenu(pasteMenuX));
        addRibbonButton(x, row2, 31, Component.literal("Cut"), "Cut (Ctrl+X)",
                () -> copySelection(true));
        addRibbonButton(x + 33, row2, 36, Component.literal("Copy"), "Copy (Ctrl+C)",
                () -> copySelection(false));
        return endGroup("Clipboard", start, x + 69);
    }

    private int layoutFont(int start) {
        int row1 = RIBBON_ROW1_Y;
        int row2 = RIBBON_ROW2_Y;

        // Row 1: font size.
        int x = start;
        addRibbonButton(x, row1, 14, Component.literal("-"), "Smaller text", () -> changeFontSize(-1));
        x += 14 + 4;
        sizeLabelX = x;
        x += 18;
        addRibbonButton(x, row1, 14, Component.literal("+"), "Larger text", () -> changeFontSize(1));
        int fontRow1End = x + 14;

        // Row 2: bold, italic, underline, borders, fill color, text color.
        x = start;
        addRibbonButton(x, row2, 16, Component.literal("B").withStyle(ChatFormatting.BOLD),
                "Bold (Ctrl+B)",
                () -> toggleFlag(CellFormat.Kind.BOLD, activeFormat().bold()));
        x += 18;
        addRibbonButton(x, row2, 16, Component.literal("I").withStyle(ChatFormatting.ITALIC),
                "Italic (Ctrl+I)",
                () -> toggleFlag(CellFormat.Kind.ITALIC, activeFormat().italic()));
        x += 18;
        addRibbonButton(x, row2, 16, Component.literal("U").withStyle(ChatFormatting.UNDERLINE),
                "Underline (Ctrl+U)",
                () -> toggleFlag(CellFormat.Kind.UNDERLINE, activeFormat().underline()));
        x += 16 + 6;

        // Borders: the button repeats the last preset, the arrow opens the list.
        borderButtonX = x;
        Button borders = addRibbonButton(x, row2, 42, Component.literal("Borders"),
                borderTip(), () -> applyBorder(lastBorder));
        if (!measuringRibbon) {
            borderButton = borders;
        }
        x += 42;
        final int borderMenuX = borderButtonX;
        addRibbonButton(x, row2, 12, Component.literal("v"), "More borders, line color and line style",
                () -> toggleBorderMenu(borderMenuX));
        x += 12 + 6;

        fillButtonX = x;
        addRibbonButton(x, row2, 30, Component.literal("Fill"),
                "Fill color (applies the last color used)",
                () -> applyFormat(new CellFormat.Change(CellFormat.Kind.FILL, lastFillColor)));
        x += 30;
        final int fillPaletteX = fillButtonX;
        addRibbonButton(x, row2, 12, Component.literal("v"), "Fill color palette",
                () -> togglePalette(ColorPalette.Target.FILL, fillPaletteX));
        x += 12 + 6;

        textButtonX = x;
        addRibbonButton(x, row2, 18, Component.literal("A"),
                "Text color (applies the last color used)",
                () -> applyFormat(new CellFormat.Change(CellFormat.Kind.FONT_COLOR, lastTextColor)));
        x += 18;
        final int textPaletteX = textButtonX;
        addRibbonButton(x, row2, 12, Component.literal("v"), "Text color palette",
                () -> togglePalette(ColorPalette.Target.TEXT, textPaletteX));
        x += 12;

        return endGroup("Font", start, Math.max(fontRow1End, x));
    }

    // Like Excel: vertical alignment on the top row, horizontal below it.
    private int layoutAlignment(int start) {
        int row1 = RIBBON_ROW1_Y;
        int row2 = RIBBON_ROW2_Y;
        int x = start;

        addRibbonButton(x, row1, 16, Component.literal("T"), "Top Align: text at the top of the cell",
                () -> applyVerticalAlignment(1));
        x += 18;
        addRibbonButton(x, row1, 16, Component.literal("M"), "Middle Align: text centered between top and bottom",
                () -> applyVerticalAlignment(2));
        x += 18;
        addRibbonButton(x, row1, 16, Component.literal("B"), "Bottom Align: text at the bottom of the cell (the default)",
                () -> applyVerticalAlignment(3));
        int row1End = x + 16;

        x = start;
        addRibbonButton(x, row2, 16, Component.literal("L"), "Align left", () -> applyAlignment(1));
        x += 18;
        addRibbonButton(x, row2, 16, Component.literal("C"), "Center", () -> applyAlignment(2));
        x += 18;
        addRibbonButton(x, row2, 16, Component.literal("R"), "Align right", () -> applyAlignment(3));
        x += 16;
        return endGroup("Alignment", start, Math.max(row1End, x));
    }

    private int layoutNumber(int start) {
        int row1 = RIBBON_ROW1_Y;
        int row2 = RIBBON_ROW2_Y;

        // Row 1: the number format dropdown. Row 2: quick formats and decimals.
        formatButtonX = start;
        final int menuX = start;
        addRibbonButton(start, row1, 104, Component.literal(""),
                "Number format",
                () -> toggleFormatMenu(menuX));

        int x = start;
        addRibbonButton(x, row2, 18, Component.literal("$"), "Currency format",
                () -> applyNumberFormat("\"$\"#,##0.00"));
        x += 20;
        addRibbonButton(x, row2, 18, Component.literal("%"), "Percent format",
                () -> applyNumberFormat("0%"));
        x += 20;
        addRibbonButton(x, row2, 18, Component.literal(","), "Thousands separator",
                () -> applyNumberFormat("#,##0.00"));
        x += 18 + 6;
        addRibbonButton(x, row2, 24, Component.literal("+.0"), "More decimal places",
                () -> applyFormat(new CellFormat.Change(CellFormat.Kind.DECIMALS, 1)));
        x += 26;
        addRibbonButton(x, row2, 24, Component.literal("-.0"), "Fewer decimal places",
                () -> applyFormat(new CellFormat.Change(CellFormat.Kind.DECIMALS, -1)));
        x += 24;
        return endGroup("Number", start, Math.max(start + 104, x));
    }

    private int layoutStyles(int start) {
        final int conditionalMenuX = start;

        addRibbonButton(start, RIBBON_ROW1_Y, 74, Component.literal("Conditional v"),
                "Conditional Formatting: color cells by their values (highlight rules, data bars, color scales)",
                () -> toggleConditionalMenu(conditionalMenuX));
        return endGroup("Styles", start, start + 74);
    }

    private int layoutEditing(int start) {
        addRibbonButton(start, RIBBON_ROW1_Y, 40, Component.literal("Clear"),
                "Clear formatting",
                () -> applyFormat(new CellFormat.Change(CellFormat.Kind.RESET, 0)));
        return endGroup("Editing", start, start + 40);
    }

    // ----- collapsed group dropdowns -----

    private void toggleGroupPanel(HomeGroup group, int anchorX) {
        boolean wasOpen = groupPanel != null && groupPanel.isOpen() && openGroup == group;

        closePopups();

        if (!wasOpen) {
            openGroup = group;
            groupPanel = buildGroupPanel(group);
            groupPanel.open(Math.max(4, Math.min(anchorX, this.width - groupPanel.width() - 4)),
                    RIBBON_ROW1_Y + BUTTON_HEIGHT + 2);
        }
    }

    // Where a dropdown from a group's panel opens: under the panel.
    private int belowGroupPanel(int popupHeight) {
        return popupY(groupPanel.y() + groupPanel.height() + 2, popupHeight);
    }

    // The same controls as the expanded group, in the same rows. A list
    // opened from it (palette, borders, paste, number format) replaces it
    // (see handleMouseClicked).
    private MiniToolbar buildGroupPanel(HomeGroup group) {
        MiniToolbar.Item[] anchor = new MiniToolbar.Item[3];
        MiniToolbar[] panel = new MiniToolbar[1];

        List<List<MiniToolbar.Item>> rows = switch (group) {
            case CLIPBOARD -> {
                anchor[0] = MiniToolbar.Item.button("v", 10, () ->
                        pasteMenu.open(panel[0].itemX(anchor[0]) - 42, belowGroupPanel(PasteMenu.HEIGHT), this.width));
                yield List.of(
                        List.of(MiniToolbar.Item.button("Paste", 40, () -> pasteClipboard(PasteMenu.Mode.ALL)),
                                anchor[0]),
                        List.of(MiniToolbar.Item.button("Cut", 24, () -> copySelection(true)),
                                MiniToolbar.Item.button("Copy", 28, () -> copySelection(false))));
            }
            case FONT -> {
                anchor[0] = MiniToolbar.Item.button("v", 10, () ->
                        palette.open(ColorPalette.Target.FILL, panel[0].itemX(anchor[0]) - 24,
                                belowGroupPanel(ColorPalette.HEIGHT), this.width));
                anchor[1] = MiniToolbar.Item.button("v", 10, () ->
                        palette.open(ColorPalette.Target.TEXT, panel[0].itemX(anchor[1]) - 16,
                                belowGroupPanel(ColorPalette.HEIGHT), this.width));
                anchor[2] = MiniToolbar.Item.button("v", 10, () ->
                        borderMenu.open(panel[0].itemX(anchor[2]) - 40,
                                belowGroupPanel(BorderMenu.HEIGHT), this.width));
                yield List.of(
                        List.of(MiniToolbar.Item.button("-", 14, () -> changeFontSize(-1)),
                                new MiniToolbar.Item(() -> Component.literal(
                                        manager == null ? "" : Integer.toString(activeFormat().fontPoints())),
                                        16, null, null),
                                MiniToolbar.Item.button("+", 14, () -> changeFontSize(1))),
                        List.of(MiniToolbar.Item.button(Component.literal("B").withStyle(ChatFormatting.BOLD), 14,
                                        () -> toggleFlag(CellFormat.Kind.BOLD, activeFormat().bold())),
                                MiniToolbar.Item.button(Component.literal("I").withStyle(ChatFormatting.ITALIC), 14,
                                        () -> toggleFlag(CellFormat.Kind.ITALIC, activeFormat().italic())),
                                MiniToolbar.Item.button(Component.literal("U").withStyle(ChatFormatting.UNDERLINE), 14,
                                        () -> toggleFlag(CellFormat.Kind.UNDERLINE, activeFormat().underline())),
                                MiniToolbar.Item.button("Borders", 40, () -> applyBorder(lastBorder)),
                                anchor[2],
                                new MiniToolbar.Item(() -> Component.literal("Fill"), 24,
                                        () -> applyFormat(new CellFormat.Change(CellFormat.Kind.FILL, lastFillColor)),
                                        () -> lastFillColor),
                                anchor[0],
                                new MiniToolbar.Item(() -> Component.literal("A"), 16,
                                        () -> applyFormat(new CellFormat.Change(CellFormat.Kind.FONT_COLOR, lastTextColor)),
                                        () -> lastTextColor),
                                anchor[1]));
            }
            case ALIGNMENT -> List.of(
                    List.of(MiniToolbar.Item.button("T", 14, () -> applyVerticalAlignment(1)),
                            MiniToolbar.Item.button("M", 14, () -> applyVerticalAlignment(2)),
                            MiniToolbar.Item.button("B", 14, () -> applyVerticalAlignment(3))),
                    List.of(MiniToolbar.Item.button("L", 14, () -> applyAlignment(1)),
                            MiniToolbar.Item.button("C", 14, () -> applyAlignment(2)),
                            MiniToolbar.Item.button("R", 14, () -> applyAlignment(3))));
            case NUMBER -> {
                anchor[0] = new MiniToolbar.Item(
                        () -> Component.literal(manager == null ? "" : FormatMenu.nameOf(activeFormat().numberFormat()) + " v"),
                        100, () -> formatMenu.open(panel[0].itemX(anchor[0]),
                                belowGroupPanel(FormatMenu.HEIGHT), this.width), null);
                yield List.of(
                        List.of(anchor[0]),
                        List.of(MiniToolbar.Item.button("$", 16, () -> applyNumberFormat("\"$\"#,##0.00")),
                                MiniToolbar.Item.button("%", 16, () -> applyNumberFormat("0%")),
                                MiniToolbar.Item.button(",", 16, () -> applyNumberFormat("#,##0.00")),
                                MiniToolbar.Item.button("+.0", 22,
                                        () -> applyFormat(new CellFormat.Change(CellFormat.Kind.DECIMALS, 1))),
                                MiniToolbar.Item.button("-.0", 22,
                                        () -> applyFormat(new CellFormat.Change(CellFormat.Kind.DECIMALS, -1)))));
            }
            case STYLES -> {
                anchor[0] = MiniToolbar.Item.button("Conditional v", 74, () ->
                        conditionalMenu.open(panel[0].itemX(anchor[0]),
                                belowGroupPanel(ConditionalMenu.HEIGHT), this.width));
                yield List.of(List.of(anchor[0]));
            }
            case EDITING -> List.of(List.of(MiniToolbar.Item.button("Clear", 30,
                    () -> applyFormat(new CellFormat.Change(CellFormat.Kind.RESET, 0)))));
        };

        panel[0] = new MiniToolbar(rows);
        return panel[0];
    }

    private String borderTip() {
        return "Borders: " + lastBorder.label();
    }

    private void toggleBorderMenu(int anchorX) {
        palette.close();
        formatMenu.close();
        pasteMenu.close();
        filterMenu.close();

        if (borderMenu.isOpen()) {
            borderMenu.close();
        } else {
            borderMenu.open(anchorX, RIBBON_ROW2_Y + BUTTON_HEIGHT + 2, this.width);
        }
    }

    // Applies a border preset to the selection with the current pen.
    private void applyBorder(BorderMenu.Option option) {
        lastBorder = option;

        if (borderButton != null) {
            borderButton.setTooltip(Tooltip.create(Component.literal(borderTip())));
        }

        int top = rangeTop();
        int bottom = actionBottom();
        int left = rangeLeft();
        int right = actionRight();

        applyFormat((row, column) -> option.changeFor(
                row, column, top, bottom, left, right, penStyle, penColor
        ));
    }

    private void togglePalette(ColorPalette.Target target, int anchorX) {
        formatMenu.close();
        pasteMenu.close();
        filterMenu.close();
        borderMenu.close();

        if (palette.isOpen() && palette.target() == target) {
            palette.close();
        } else {
            palette.open(target, anchorX, RIBBON_ROW2_Y + BUTTON_HEIGHT + 2, this.width);
        }
    }

    private void toggleFormatMenu(int anchorX) {
        palette.close();
        filterMenu.close();
        borderMenu.close();
        pasteMenu.close();

        if (formatMenu.isOpen()) {
            formatMenu.close();
        } else {
            formatMenu.open(anchorX, RIBBON_ROW1_Y + BUTTON_HEIGHT + 2, this.width);
        }
    }

    // ----- conditional formatting -----

    private void toggleConditionalMenu(int anchorX) {
        boolean wasOpen = conditionalMenu.isOpen();

        closePopups();

        if (!wasOpen) {
            conditionalMenu.open(anchorX, RIBBON_ROW1_Y + BUTTON_HEIGHT + 2, this.width);
        }
    }

    // The selected range for a rule: whole rows or columns stop at the last
    // used cell, so rules don't scan a million empty rows.
    private int[] conditionalArea() {
        return new int[]{rangeTop(), rangeLeft(), actionBottom(), actionRight()};
    }

    private void chooseConditionalItem(ConditionalMenu.Item item) {
        if (manager == null || !commitPendingEdit()) {
            return;
        }

        if (manager.isCsv()) {
            status = "Conditional formatting needs an .xlsx workbook";
            return;
        }

        int[] area = conditionalArea();
        String where = rangeRef(area[0], area[1], area[2], area[3]);

        if (item.condition() != null) {
            // Highlight and Top/Bottom rules ask for a value and a look first.
            conditionalRange = area;
            conditionalDialog = new ConditionalDialog(item.condition(), where);
            return;
        }

        if (item.dataBar() != null) {
            changeRules("adding a data bar to " + where,
                    () -> manager.addDataBar(sheetIndex, area[0], area[1], area[2], area[3], item.dataBar()));
        } else if (item.colorScale() != null) {
            changeRules("adding a color scale to " + where,
                    () -> manager.addColorScale(sheetIndex, area[0], area[1], area[2], area[3], item.colorScale()));
        } else if (item.clear() == ConditionalMenu.Clear.SELECTION) {
            // Clearing uses the whole selection, so whole-column rules are cleared too.
            int[] whole = {rangeTop(), rangeLeft(), rangeBottom(), rangeRight()};
            boolean[] changed = new boolean[1];

            changeRules("clearing rules from " + where, () -> changed[0] = manager.clearConditionalRules(
                    sheetIndex, whole[0], whole[1], whole[2], whole[3]));

            if (!changed[0]) {
                status = "No conditional formatting in " + where;
            }
        } else if (item.clear() == ConditionalMenu.Clear.SHEET) {
            boolean[] changed = new boolean[1];

            changeRules("clearing the sheet's rules", () -> changed[0] = manager.clearAllConditionalRules(sheetIndex));

            if (!changed[0]) {
                status = "This sheet has no conditional formatting";
            }
        }
    }

    // OK in the rule window: adds the rule (or shows what's wrong with the value).
    private void applyConditionalDialog() {
        ConditionalDialog dialog = conditionalDialog;
        int[] area = conditionalRange;

        if (dialog == null || area == null) {
            return;
        }

        String where = rangeRef(area[0], area[1], area[2], area[3]);

        try {
            changeRules(dialog.condition().label().replace("...", "") + " rule on " + where,
                    () -> manager.addConditionalRule(sheetIndex, area[0], area[1], area[2], area[3],
                            dialog.condition(), dialog.value(0), dialog.value(1), dialog.look()));
            conditionalDialog = null;
        } catch (IllegalArgumentException exception) {
            dialog.setError(exception.getMessage());
        }
    }

    // Runs a change to the sheet's rules as one undo step. An
    // IllegalArgumentException (a bad value) is passed on and nothing changes.
    private void changeRules(String description, Runnable change) {
        ConditionalFormats.State before = manager.captureConditionalState(sheetIndex);

        try {
            change.run();
        } catch (IllegalArgumentException exception) {
            manager.restoreConditionalState(sheetIndex, before);
            throw exception;
        } catch (RuntimeException | LinkageError exception) {
            manager.restoreConditionalState(sheetIndex, before);
            status = "Could not change the conditional formatting: " + describe(exception);
            return;
        }

        ConditionalFormats.State after = manager.captureConditionalState(sheetIndex);

        undoHistory.push(CellEdit.ruleChange(sheetIndex, rangeTop(), rangeLeft(),
                new RuleChange(before, after, description)));
        redoHistory.clear();
        markDirty();
        status = Character.toUpperCase(description.charAt(0)) + description.substring(1);
    }

    private void closePopups() {
        if (groupPanel != null) {
            groupPanel.close();
        }

        conditionalMenu.close();
        palette.close();
        formatMenu.close();
        pasteMenu.close();
        filterMenu.close();
        borderMenu.close();
        functionMenu.close();
        cellMenu.close();
        miniToolbar.close();
    }

    // The formatting bar shown with the right-click menu, laid out like
    // Excel's mini toolbar. Every button does the same as its Home ribbon button.
    private MiniToolbar buildMiniToolbar() {
        MiniToolbar.Item[] fillArrow = new MiniToolbar.Item[1];
        MiniToolbar.Item[] textArrow = new MiniToolbar.Item[1];
        MiniToolbar.Item[] borderArrow = new MiniToolbar.Item[1];

        fillArrow[0] = MiniToolbar.Item.button("v", 10, () -> {
            int x = miniToolbar.itemX(fillArrow[0]) - 24;
            closePopups();
            palette.open(ColorPalette.Target.FILL, x, belowToolbar(ColorPalette.HEIGHT), this.width);
        });
        textArrow[0] = MiniToolbar.Item.button("v", 10, () -> {
            int x = miniToolbar.itemX(textArrow[0]) - 16;
            closePopups();
            palette.open(ColorPalette.Target.TEXT, x, belowToolbar(ColorPalette.HEIGHT), this.width);
        });
        borderArrow[0] = MiniToolbar.Item.button("v", 10, () -> {
            int x = miniToolbar.itemX(borderArrow[0]) - 40;
            closePopups();
            borderMenu.open(x, belowToolbar(BorderMenu.HEIGHT), this.width);
        });

        List<MiniToolbar.Item> top = List.of(
                MiniToolbar.Item.button("-", 14, () -> changeFontSize(-1)),
                new MiniToolbar.Item(
                        () -> Component.literal(manager == null ? "" : Integer.toString(activeFormat().fontPoints())),
                        16, null, null),
                MiniToolbar.Item.button("+", 14, () -> changeFontSize(1)),
                MiniToolbar.Item.button("$", 16, () -> applyNumberFormat("\"$\"#,##0.00")),
                MiniToolbar.Item.button("%", 16, () -> applyNumberFormat("0%")),
                MiniToolbar.Item.button(",", 16, () -> applyNumberFormat("#,##0.00")),
                MiniToolbar.Item.button("+.0", 22,
                        () -> applyFormat(new CellFormat.Change(CellFormat.Kind.DECIMALS, 1))),
                MiniToolbar.Item.button("-.0", 22,
                        () -> applyFormat(new CellFormat.Change(CellFormat.Kind.DECIMALS, -1))),
                MiniToolbar.Item.button("Clear", 30,
                        () -> applyFormat(new CellFormat.Change(CellFormat.Kind.RESET, 0)))
        );

        List<MiniToolbar.Item> bottom = List.of(
                MiniToolbar.Item.button(Component.literal("B").withStyle(ChatFormatting.BOLD), 14,
                        () -> toggleFlag(CellFormat.Kind.BOLD, activeFormat().bold())),
                MiniToolbar.Item.button(Component.literal("I").withStyle(ChatFormatting.ITALIC), 14,
                        () -> toggleFlag(CellFormat.Kind.ITALIC, activeFormat().italic())),
                MiniToolbar.Item.button(Component.literal("U").withStyle(ChatFormatting.UNDERLINE), 14,
                        () -> toggleFlag(CellFormat.Kind.UNDERLINE, activeFormat().underline())),
                MiniToolbar.Item.button("L", 14, () -> applyAlignment(1)),
                MiniToolbar.Item.button("C", 14, () -> applyAlignment(2)),
                MiniToolbar.Item.button("R", 14, () -> applyAlignment(3)),
                new MiniToolbar.Item(() -> Component.literal("Fill"), 24,
                        () -> applyFormat(new CellFormat.Change(CellFormat.Kind.FILL, lastFillColor)),
                        () -> lastFillColor),
                fillArrow[0],
                new MiniToolbar.Item(() -> Component.literal("A"), 16,
                        () -> applyFormat(new CellFormat.Change(CellFormat.Kind.FONT_COLOR, lastTextColor)),
                        () -> lastTextColor),
                textArrow[0],
                MiniToolbar.Item.button("Borders", 40, () -> applyBorder(lastBorder)),
                borderArrow[0]
        );

        return new MiniToolbar(List.of(top, bottom));
    }

    // Shows the formatting bar above the right-click menu (below it if there is no room).
    private void openMiniToolbar() {
        int x = Math.max(4, Math.min(cellMenu.x(), this.width - miniToolbar.width() - 4));
        int above = cellMenu.y() - miniToolbar.height() - 4;
        int y = above >= 4
                ? above
                : Math.min(cellMenu.y() + cellMenu.menuHeight() + 4, this.height - miniToolbar.height() - 4);

        miniToolbar.open(x, Math.max(4, y));
    }

    // Where a dropdown from the formatting bar opens: under the bar, kept on screen.
    private int belowToolbar(int popupHeight) {
        return popupY(miniToolbar.y() + miniToolbar.height() + 2, popupHeight);
    }

    // Does what was picked in the right-click menu. Options with their own
    // list (Paste Options, Number Format, Insert Function) open it beside the menu.
    private void runCellMenuAction(CellMenu.Action action) {
        int besideX = cellMenu.x() + cellMenu.width() + 2;
        int rowY = cellMenu.rowY(action);

        switch (action) {
            case CUT -> copySelection(true);
            case COPY -> copySelection(false);
            case PASTE -> pasteClipboard(PasteMenu.Mode.ALL);
            case PASTE_OPTIONS -> pasteMenu.open(
                    besideX, popupY(rowY, PasteMenu.HEIGHT), this.width);
            case CLEAR -> clearSelectedCells();
            case NUMBER_FORMAT -> formatMenu.open(
                    besideX, popupY(rowY, FormatMenu.HEIGHT), this.width);
            case INSERT_FUNCTION -> functionMenu.open(
                    besideX, popupY(rowY, FunctionMenu.HEIGHT), this.width);
            case TABLE -> toggleTable();
            case FILTER -> toggleFilter();
            case SORT_ASCENDING -> sortSelectedColumn(true);
            case SORT_DESCENDING -> sortSelectedColumn(false);
            case SEARCH_TABLE -> openTableSearch();
        }
    }

    // A popup's top, moved up if it would run off the bottom of the screen.
    private int popupY(int wantedY, int popupHeight) {
        return Math.max(4, Math.min(wantedY, this.height - popupHeight - 4));
    }

    private void togglePasteMenu(int anchorX) {
        palette.close();
        filterMenu.close();
        borderMenu.close();
        formatMenu.close();

        if (pasteMenu.isOpen()) {
            pasteMenu.close();
        } else {
            pasteMenu.open(anchorX, RIBBON_ROW1_Y + BUTTON_HEIGHT + 2, this.width);
        }
    }

    private void applyNumberFormat(String format) {
        applyFormat(new CellFormat.Change(CellFormat.Kind.NUMBER_FORMAT, 0, format));
    }

    // A color picked in the palette; NONE means "No fill" / "Automatic".
    private void applyPaletteColor(ColorPalette.Target target, int color) {
        if (target == ColorPalette.Target.INK) {
            applyInkColor(color);
            return;
        }

        if (target == ColorPalette.Target.BORDER) {
            // Like Excel, the line color is used by the next border you pick.
            penColor = color;
            status = "Line color set; pick a border to draw it";
            return;
        }

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

    // 1 = top, 2 = middle, 3 = bottom. Clicking the active one again goes
    // back to bottom, Excel's default.
    private void applyVerticalAlignment(int code) {
        int current = activeFormat().vAlign();

        applyFormat(new CellFormat.Change(CellFormat.Kind.V_ALIGN, current == code ? 3 : code));
    }

    private void changeFontSize(int delta) {
        int size = Math.max(6, Math.min(72, activeFormat().fontPoints() + delta));

        applyFormat(new CellFormat.Change(CellFormat.Kind.FONT_SIZE, size));
    }

    // The formatting change for one cell of the selection (null leaves it alone).
    private interface CellChanges {
        CellFormat.Change at(int row, int column);
    }

    // Applies a formatting change to every cell in the selected range as one undo step.
    private void applyFormat(CellFormat.Change change) {
        boolean applied = applyFormat((row, column) -> change);

        if (applied && change.kind() == CellFormat.Kind.FONT_SIZE) {
            fitRowHeights(rangeTop(), actionBottom(), change.value());
        }
    }

    // Like applyFormat(change), but each cell can get its own change (borders
    // differ between the edge and the inside of the selection).
    // Returns false when nothing was attempted (no workbook, or too large).
    private boolean applyFormat(CellChanges changes) {
        if (manager == null) {
            return false;
        }

        commitPendingEdit();

        int top = rangeTop();
        int bottom = actionBottom();
        int left = rangeLeft();
        int right = actionRight();

        if ((long) (bottom - top + 1) * (right - left + 1) > MAX_CLEAR_CELLS) {
            status = "Selection too large to format";
            return false;
        }

        int changed = 0;
        boolean hasEarlierEdit = false;
        String error = null;

        try {
            for (int row = top; row <= bottom; row++) {
                for (int column = left; column <= right; column++) {
                    CellFormat.Change change = changes.at(row, column);

                    if (change == null) {
                        continue;
                    }

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

        return true;
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
        if (edit.ink() != null) {
            if (manager.ink() != null) {
                manager.ink().set(edit.sheet(), forward ? edit.ink().after() : edit.ink().before());
            }
        } else if (edit.rules() != null) {
            manager.restoreConditionalState(edit.sheet(), forward ? edit.rules().after() : edit.rules().before());
        } else if (edit.table() != null) {
            manager.restoreTableState(edit.sheet(), forward ? edit.table().after() : edit.table().before());
            filterMenu.close();
            // Filters may have hidden or shown rows.
            rowHeightCache.clear();
        } else if (edit.size()) {
            int pixels = forward ? edit.newStyle() : edit.previousStyle();

            if (edit.row() < 0) {
                manager.setColumnWidthPixels(edit.sheet(), edit.column(), pixels);
                columnWidthCache.put(sizeKey(edit.sheet(), edit.column()), pixels);
            } else {
                manager.setRowHeightPixels(edit.sheet(), edit.row(), pixels);
                rowHeightCache.put(sizeKey(edit.sheet(), edit.row()), pixels);
            }
        } else if (edit.previousStyle() >= 0) {
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

        // Under the Draw tab's Color button: the color the next stroke gets.
        if (ribbonTab == TAB_DRAW) {
            int barY = RIBBON_ROW1_Y + BUTTON_HEIGHT - 3;
            graphics.fill(inkColorButtonX + 4, barY, inkColorButtonX + 32, barY + 2,
                    0xFF000000 | currentInkColor());
        }

        // A collapsed group's extras are drawn by its dropdown instead.
        boolean fontShown = !collapsedGroups.contains(HomeGroup.FONT);
        boolean numberShown = !collapsedGroups.contains(HomeGroup.NUMBER);

        if (ribbonTab == TAB_HOME && fontShown) {
            int barY = RIBBON_ROW2_Y + BUTTON_HEIGHT - 4;

            graphics.fill(fillButtonX + 4, barY, fillButtonX + 26, barY + 3, 0xFF000000 | lastFillColor);
            graphics.fill(textButtonX + 4, barY, textButtonX + 14, barY + 3, 0xFF000000 | lastTextColor);

            // Under the Borders button: the line color, drawn in the line style.
            BorderMenu.drawHorizontal(
                    graphics, borderButtonX + 4, borderButtonX + 38,
                    RIBBON_ROW2_Y + BUTTON_HEIGHT - 1 - BorderMenu.thickness(penStyle),
                    penStyle, BorderMenu.lineColor(penColor)
            );

            graphics.drawString(
                    this.font,
                    Integer.toString(activeFormat().fontPoints()),
                    sizeLabelX + (18 - this.font.width(Integer.toString(activeFormat().fontPoints()))) / 2, RIBBON_ROW1_Y + BUTTON_TEXT_Y, 0xFFFFFFFF
            );
        }

        if (ribbonTab == TAB_HOME && numberShown) {
            // The dropdown button shows the name of the active cell's number format.
            graphics.drawString(
                    this.font,
                    FormatMenu.nameOf(activeFormat().numberFormat()) + " v",
                    formatButtonX + 6, RIBBON_ROW1_Y + BUTTON_TEXT_Y, 0xFFFFFFFF
            );
        }

        beginOverlay(graphics);

        if (tableSearch.isOpen()) {
            updateSearchCounts();
            tableSearch.place(Math.max(GRID_X, gridRight() - TableSearchBar.WIDTH - 4),
                    GRID_Y + headerHeight() + 3);
            tableSearch.render(graphics, this.font, mouseX, mouseY);
        }

        cellMenu.render(graphics, this.font, mouseX, mouseY);
        miniToolbar.render(graphics, this.font, mouseX, mouseY);

        if (groupPanel != null) {
            groupPanel.render(graphics, this.font, mouseX, mouseY);
        }

        palette.render(graphics, this.font, mouseX, mouseY);
        formatMenu.render(graphics, this.font, mouseX, mouseY, activeFormat().numberFormat());
        pasteMenu.render(graphics, this.font, mouseX, mouseY);
        borderMenu.render(graphics, this.font, mouseX, mouseY, penStyle, penColor);
        functionMenu.render(graphics, this.font, mouseX, mouseY);
        filterMenu.render(graphics, this.font, mouseX, mouseY);
        syncAutocomplete();
        autocomplete.render(graphics, this.font, mouseX, mouseY);
        conditionalMenu.render(graphics, this.font, mouseX, mouseY);

        // The rule window, over a dimmed screen (one layer higher again).
        if (conditionalDialog != null) {
            graphics.fill(0, 0, this.width, this.height, 0x80000000);
            beginOverlay(graphics);
            conditionalDialog.place(this.width, this.height);
            conditionalDialog.render(graphics, this.font, mouseX, mouseY);
            endOverlay(graphics);
        }

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

    // A conditional formatting data bar: Excel's gradient fill, fading to
    // white on the right, with a solid edge in the bar's color.
    private static void drawDataBar(GuiGraphics graphics, int x, int y, int cw, int ch,
                                    ConditionalFormats.Style style) {
        int color = Math.max(0, style.barColor());
        int left = x + 2;
        int top = y + 2;
        int bottom = y + ch - 2;
        int width = (int) Math.round((cw - 4) * style.bar());

        if (width <= 0 || bottom <= top) {
            return;
        }

        int steps = Math.min(width, 8);

        for (int i = 0; i < steps; i++) {
            int from = left + width * i / steps;
            int to = left + width * (i + 1) / steps;

            graphics.fill(from, top, to, bottom, 0xFF000000 | lighten(color, 0.7 * i / steps));
        }

        graphics.fill(left, top, left + width, top + 1, 0xFF000000 | color);
        graphics.fill(left, bottom - 1, left + width, bottom, 0xFF000000 | color);
        graphics.fill(left, top, left + 1, bottom, 0xFF000000 | color);
        graphics.fill(left + width - 1, top, left + width, bottom, 0xFF000000 | color);
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

        if (change == 0 || !rowHidden(target)) {
            return target;
        }

        int direction = change > 0 ? 1 : -1;

        for (int guard = 0; target >= 0 && guard < 1_048_576 && rowHidden(target); guard++) {
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
        pasteMenu.close();
        borderMenu.close();

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
                    state.active() ? state.allowed() : null,
                    manager.isNumericColumn(sheetIndex, table, offset)
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
            return new int[] {rangeTop(), rangeLeft(), actionBottom(), actionRight()};
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
                WorkbookManager.TableState before = manager.captureTableState(
                        sheetIndex, existing.top(), existing.left(), existing.bottom(), existing.right());

                manager.removeTable(sheetIndex, existing);
                status = existing.name() + " is a normal range again";
                recordTableChange(before, existing.top(), existing.left(), existing.bottom(), existing.right(),
                        "turning " + existing.name() + " into a range");
            } else {
                int[] area = selectionOrRegion();
                // A table always has a header row and at least one data row.
                int bottom = Math.max(area[2], area[0] + 1);
                WorkbookManager.TableState before = manager.captureTableState(
                        sheetIndex, area[0], area[1], bottom, area[3]);

                TableInfo created = manager.createTable(
                        sheetIndex, area[0], area[1], area[2], area[3], "TableStyleMedium2");

                status = "Created " + created.name();
                recordTableChange(before, area[0], area[1], bottom, area[3], "creating " + created.name());
            }

            rowHeightCache.clear();
            markDirty();
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
        } catch (RuntimeException | LinkageError exception) {
            status = "Could not change the table: " + describe(exception);
        }
    }

    // Puts a Table or Filter change on the undo history, as one step.
    // The area must be the same one the "before" state was captured for.
    private void recordTableChange(
            WorkbookManager.TableState before, int top, int left, int bottom, int right, String description
    ) {
        if (before == null) {
            return;
        }

        WorkbookManager.TableState after = manager.captureTableState(sheetIndex, top, left, bottom, right);
        undoHistory.push(CellEdit.tableChange(sheetIndex, top, left, new TableChange(before, after, description)));
        redoHistory.clear();
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
                WorkbookManager.TableState before = manager.captureTableState(
                        sheetIndex, existing.top(), existing.left(), existing.bottom(), existing.right());

                manager.setFilterButtons(sheetIndex, existing, !existing.filterButtons());
                status = existing.filterButtons() ? "Filter buttons hidden" : "Filter buttons shown";
                recordTableChange(before, existing.top(), existing.left(), existing.bottom(), existing.right(),
                        existing.filterButtons() ? "hiding the filter buttons" : "showing the filter buttons");
            } else {
                int[] area = selectionOrRegion();
                // A filter range always has a header row and at least one row below it.
                int bottom = Math.max(area[2], area[0] + 1);
                WorkbookManager.TableState before = manager.captureTableState(
                        sheetIndex, area[0], area[1], bottom, area[3]);

                manager.addFilterRange(sheetIndex, area[0], area[1], area[2], area[3]);
                status = "Filter buttons added";
                recordTableChange(before, area[0], area[1], bottom, area[3], "adding filter buttons");
            }

            rowHeightCache.clear();
            markDirty();
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
        } catch (RuntimeException | LinkageError exception) {
            status = "Could not change the filter: " + describe(exception);
        }
    }

    // ----- sorting tables -----

    // Sort Ascending / Descending (ribbon and right-click menu): sorts the
    // table the selected cell is in by the selected column.
    private void sortSelectedColumn(boolean ascending) {
        if (manager == null) {
            return;
        }

        if (!commitPendingEdit()) {
            return;
        }

        TableInfo table = manager.tableAt(sheetIndex, selectedRow, selectedColumn);

        if (table == null) {
            status = manager.isCsv()
                    ? "Tables need an .xlsx file, not a .csv"
                    : "Select a cell in a table (or filter range) to sort it";
            return;
        }

        sortTable(table, selectedColumn - table.left(), ascending);
    }

    // Numbers before text, text A to Z ignoring case; descending is the
    // reverse. Blank cells always go last, like Excel.
    private static int compareSortValues(Object a, Object b, boolean ascending) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : 1) : -1;
        }

        int result;

        if (a instanceof Double first && b instanceof Double second) {
            result = Double.compare(first, second);
        } else if (a instanceof Double || b instanceof Double) {
            result = a instanceof Double ? -1 : 1;
        } else {
            result = String.CASE_INSENSITIVE_ORDER.compare(a.toString(), b.toString());
        }

        return ascending ? result : -result;
    }

    // A range like B2:B6 (either end may have $).
    private static final Pattern RANGE_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_.])\\$?[A-Za-z]{1,3}\\$?(\\d+):\\$?[A-Za-z]{1,3}\\$?(\\d+)(?![A-Za-z0-9_(!])");

    // Whether a row adds up the table's rows above it: one of its formulas
    // uses a range over several of those rows, or a whole table column
    // (Table1[Amount], but not Table1[@Amount], which is the row's own cell).
    private boolean isTotalRow(TableInfo table, int row, int firstData) {
        for (int column = table.left(); column <= table.right(); column++) {
            String input = manager.getCellInput(sheetIndex, row, column);

            if (input == null || !input.startsWith("=")) {
                continue;
            }

            Matcher matcher = RANGE_REFERENCE.matcher(input);

            while (matcher.find()) {
                if (matcher.group(1).length() > 7 || matcher.group(2).length() > 7) {
                    continue;
                }

                int from = Integer.parseInt(matcher.group(1)) - 1;
                int to = Integer.parseInt(matcher.group(2)) - 1;
                int low = Math.min(from, to);
                int high = Math.max(from, to);

                // Ends above this row: a running total like B$2:B5 in row 5 is ordinary data.
                if (high > low && high < row && high >= firstData) {
                    return true;
                }
            }

            if (input.matches("(?s).*\\[(?!@|#This Row).*") && !input.contains("[@")) {
                return true;
            }
        }

        return false;
    }

    // Sorts a table's rows (not its header) by one of its columns. Whole rows
    // move: values, formulas (their relative references move with them, as in
    // Excel) and formatting. Filters are applied again afterwards, and the
    // whole sort is one undo step.
    private void sortTable(TableInfo table, int columnOffset, boolean ascending) {
        if (manager == null || table == null) {
            return;
        }

        if (!commitPendingEdit()) {
            return;
        }

        filterMenu.close();

        int first = table.top() + 1;
        int width = table.right() - table.left() + 1;
        // Total rows at the bottom stay where they are: Excel's Total Row, and
        // rows with a formula adding up the rows above (like =SUM(B2:B6)).
        int lastData = table.bottom() - manager.getTotalsRowCount(sheetIndex, table);
        int totalRows = table.bottom() - lastData;

        while (lastData > first && isTotalRow(table, lastData, first)) {
            lastData--;
            totalRows++;
        }

        int count = lastData - first + 1;
        int column = table.left() + columnOffset;
        String name = table.isTable() ? table.name() : "the filter range";
        boolean numeric = manager.isNumericColumn(sheetIndex, table, columnOffset);
        String how = FilterMenu.sortLabel(ascending, numeric).replace("Sort ", "");

        if (count < 2) {
            status = "There is nothing to sort in " + name;
            return;
        }

        try {
            Object[] keys = new Object[count];
            Integer[] order = new Integer[count];

            for (int i = 0; i < count; i++) {
                keys[i] = manager.getSortValue(sheetIndex, first + i, column);
                order[i] = i;
            }

            // Arrays.sort keeps equal rows in their order.
            java.util.Arrays.sort(order, (a, b) -> compareSortValues(keys[a], keys[b], ascending));

            boolean moved = false;

            for (int i = 0; i < count && !moved; i++) {
                moved = order[i] != i;
            }

            if (!moved) {
                status = name + " is already sorted " + how;
                return;
            }

            // Everything is read before anything is written.
            String[][] inputs = new String[count][width];
            int[][] styles = new int[count][width];

            for (int r = 0; r < count; r++) {
                for (int c = 0; c < width; c++) {
                    String input = manager.getCellInput(sheetIndex, first + r, table.left() + c);
                    inputs[r][c] = input == null ? "" : input;
                    styles[r][c] = manager.getCellStyleIndex(sheetIndex, first + r, table.left() + c);
                }
            }

            WorkbookManager.TableState before = manager.captureTableState(
                    sheetIndex, table.top(), table.left(), table.bottom(), table.right());
            List<CellEdit> edits = new ArrayList<>();
            int skipped = 0;

            for (int r = 0; r < count; r++) {
                int source = order[r];

                if (source == r) {
                    continue;
                }

                for (int c = 0; c < width; c++) {
                    int row = first + r;
                    int col = table.left() + c;
                    String input = inputs[source][c];
                    // The first edit starts the undo step when there is no table change before it.
                    boolean chained = before != null || !edits.isEmpty();

                    // Only references to the formula's own row move with it.
                    if (input.startsWith("=")) {
                        input = shiftFormula(input, r - source, 0, first + source);
                    }

                    try {
                        if (!input.equals(inputs[r][c])) {
                            int[] autoStyles = manager.setCellValueDeferred(sheetIndex, row, col, input);
                            edits.add(new CellEdit(sheetIndex, row, col, inputs[r][c], input, chained));

                            if (autoStyles != null) {
                                edits.add(new CellEdit(sheetIndex, row, col, "", "", true,
                                        autoStyles[0], autoStyles[1]));
                            }
                        }

                        int current = manager.getCellStyleIndex(sheetIndex, row, col);

                        if (!manager.isCsv() && current != styles[source][c]) {
                            manager.setCellStyleIndex(sheetIndex, row, col, styles[source][c]);
                            edits.add(new CellEdit(sheetIndex, row, col, "", "",
                                    before != null || !edits.isEmpty(), current, styles[source][c]));
                        }
                    } catch (RuntimeException exception) {
                        skipped++;
                    }
                }
            }

            manager.reapplyFilters(sheetIndex, table);
            manager.recalculate();

            if (before != null) {
                WorkbookManager.TableState after = manager.captureTableState(
                        sheetIndex, table.top(), table.left(), table.bottom(), table.right());
                undoHistory.push(CellEdit.tableChange(sheetIndex, table.top(), column,
                        new TableChange(before, after, "sorting " + name)));
            }

            for (CellEdit edit : edits) {
                undoHistory.push(edit);
            }

            redoHistory.clear();
            rowHeightCache.clear();
            markDirty();
            refreshEditor();

            status = "Sorted " + name + " by column " + columnName(column)
                    + ": " + how
                    + (totalRows > 0 ? (totalRows == 1 ? " (the total row stays at the bottom)"
                            : " (the " + totalRows + " total rows stay at the bottom)") : "")
                    + (skipped > 0 ? " (" + skipped + " cells could not be moved)" : "");
        } catch (RuntimeException | LinkageError exception) {
            status = "Could not sort: " + describe(exception);
        }
    }

    // ----- searching a table -----

    // Ctrl+F, the Search button or Search Table: opens the search bar for the
    // table the selected cell is in (or puts the typing back in it).
    private void openTableSearch() {
        if (manager == null) {
            return;
        }

        if (!commitPendingEdit()) {
            return;
        }

        TableInfo table = manager.tableAt(sheetIndex, selectedRow, selectedColumn);

        if (table == null && !(tableSearch.isOpen() && tableSearch.sheet() == sheetIndex)) {
            status = manager.isCsv()
                    ? "Tables need an .xlsx file, not a .csv"
                    : "Select a cell inside a table to search it";
            return;
        }

        closePopups();

        if (table != null) {
            tableSearch.open(sheetIndex, table);
        } else {
            tableSearch.setFocused(true);
        }

        if (cellEditor != null) {
            cellEditor.setFocused(false);
        }

        this.setFocused(null);
        searchMatches = null;
        status = "Type to search " + (table != null && table.isTable() ? table.name() : "the table")
                + " (Enter: next match, Esc: stop searching)";
    }

    private void closeTableSearch() {
        tableSearch.close();
        searchMatches = null;
        scrollSelectionIntoView();
        status = "Showing every row again";
    }

    // The table being searched, or null when it's gone (or on another sheet).
    private TableInfo searchedTable() {
        if (manager == null || !tableSearch.isOpen() || tableSearch.sheet() != sheetIndex) {
            return null;
        }

        TableInfo table = manager.tableAt(sheetIndex, tableSearch.top(), tableSearch.left());

        return table != null && table.top() == tableSearch.top() && table.left() == tableSearch.left()
                ? table : null;
    }

    private boolean cellMatches(int row, int column, String query) {
        try {
            return manager.getCellText(sheetIndex, row, column).toLowerCase(Locale.ROOT).contains(query);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    // Which rows of the searched table have a cell containing the text
    // (bit 0 is the header row, which always shows). Worked out again after changes.
    private java.util.BitSet searchMatches() {
        if (searchMatches != null) {
            return searchMatches;
        }

        java.util.BitSet matches = new java.util.BitSet();
        String query = tableSearch.query();
        TableInfo table = searchedTable();
        searchBottom = -1;

        if (table != null && !query.isEmpty()) {
            searchBottom = table.bottom();

            for (int row = table.top() + 1; row <= table.bottom(); row++) {
                for (int column = table.left(); column <= table.right(); column++) {
                    if (cellMatches(row, column, query)) {
                        matches.set(row - table.top());
                        break;
                    }
                }
            }
        }

        searchMatches = matches;
        return matches;
    }

    // A row of the searched table that has no cell containing the text.
    private boolean searchHides(int row) {
        if (!tableSearch.isOpen() || tableSearch.sheet() != sheetIndex || row <= tableSearch.top()
                || manager == null || tableSearch.query().isEmpty()) {
            return false;
        }

        java.util.BitSet matches = searchMatches();

        return row <= searchBottom && !matches.get(row - tableSearch.top());
    }

    private void updateSearchCounts() {
        java.util.BitSet matches = searchMatches();

        tableSearch.setCounts(matches.cardinality(), Math.max(0, searchBottom - tableSearch.top()));
    }

    private void handleSearchResult(TableSearchBar.Result result) {
        switch (result) {
            case CHANGED -> {
                searchMatches = null;
                String query = tableSearch.query();
                TableInfo table = searchedTable();

                if (query.isEmpty() || table == null) {
                    status = "Type to search the table";
                    return;
                }

                int found = searchMatches().cardinality();
                status = found == 0
                        ? "No rows contain \"" + query + "\""
                        : found + (found == 1 ? " row contains" : " rows contain") + " \"" + query + "\"";

                // The selected cell was left out: go to the first match (or the header).
                if (rowHidden(selectedRow) && table.contains(selectedRow, selectedColumn)) {
                    int next = searchMatches().nextSetBit(1);
                    selectedRow = next > 0 ? table.top() + next : table.top();
                    collapseRange();
                    refreshEditor();
                }

                scrollSelectionIntoView();
            }
            case NEXT -> jumpToMatch(1);
            case PREVIOUS -> jumpToMatch(-1);
            case CLOSE -> closeTableSearch();
            default -> {
            }
        }
    }

    // Enter / Shift+Enter in the search box: selects the next (or previous)
    // cell containing the text, going round the table.
    private void jumpToMatch(int direction) {
        TableInfo table = searchedTable();
        String query = tableSearch.query();

        if (table == null || query.isEmpty()) {
            return;
        }

        int width = table.right() - table.left() + 1;
        int total = (table.bottom() - table.top()) * width;
        java.util.BitSet matches = searchMatches();
        int start;

        if (selectedRow > table.top() && table.contains(selectedRow, selectedColumn)) {
            start = (selectedRow - table.top() - 1) * width + selectedColumn - table.left();
        } else {
            start = direction > 0 ? -1 : total;
        }

        for (int step = 1; step <= total; step++) {
            int index = Math.floorMod(start + step * direction, total);
            int row = table.top() + 1 + index / width;
            int column = table.left() + index % width;

            if (matches.get(row - table.top()) && cellMatches(row, column, query)) {
                selectedRow = row;
                selectedColumn = column;
                collapseRange();
                refreshEditor();
                scrollSelectionIntoView();
                status = "Found \"" + query + "\" in " + cellRef(row, column);
                return;
            }
        }

        status = "No cells contain \"" + query + "\"";
    }

    // ----- remembering the view between visits -----

    // Brings back the zoom, sheet, selected cell and scroll position from last time.
    private void restoreViewState() {
        zoomPercent = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, ViewStateStore.loadZoom(zoomPercent)));

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
        revealActiveTab = true;
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

        // A hub or connector is edited by one player at a time: let the next one in.
        // (Sent after any save's upload, so the server stores that first.)
        if (shared != null && shared.isConnector()) {
            ShareClient.closeConnector(shared.connector());
        }

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
                    (initialZoom - MIN_ZOOM) / (double) (MAX_ZOOM - MIN_ZOOM)
            );
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("Zoom: " + sliderZoom() + "%"));
        }

        @Override
        protected void applyValue() {
            commitPendingEdit();
            zoomPercent = sliderZoom();
            scrollSelectionIntoView();
        }

        private int sliderZoom() {
            return MIN_ZOOM + (int) Math.round(this.value * (MAX_ZOOM - MIN_ZOOM));
        }

        // Drawn here instead of with the vanilla sprites: their handle is
        // taller than this short slider and stuck out under the window.
        @Override
        //? if >=26.1 {
        /*public void extractWidgetRenderState(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        *///?} else {
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //?}
            int left = getX();
            int top = getY();
            int right = left + getWidth();
            int bottom = top + getHeight();

            graphics.fill(left, top, right, bottom, 0xFF5F6368);
            graphics.fill(left + 1, top + 1, right - 1, bottom - 1, 0xFF2D2E30);

            int handleWidth = 6;
            int handleX = left + (int) Math.round(this.value * (getWidth() - handleWidth));
            graphics.fill(handleX, top, handleX + handleWidth, bottom,
                    isHoveredOrFocused() ? 0xFFFFFFFF : 0xFFBDC1C6);

            graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                    left + getWidth() / 2, top + (getHeight() - 8) / 2 + 1, 0xFFFFFFFF);
        }
    }
}
