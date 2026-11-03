package io.github.reverendbrown.excelcomputer;

import java.util.Set;

/**
 * A table, or a plain filter range, in a worksheet. Rows and columns are
 * 0-based and include the header row (the top row).
 */
public record TableInfo(
        String name,                 // "" for a plain filter range that is not a table
        int top,
        int left,
        int bottom,
        int right,
        boolean filterButtons,       // the small arrows in the header cells
        String style,                // for example "TableStyleMedium2"; "" for none
        boolean bandedRows,
        Set<Integer> filteredColumns // columns (0 = first column of the table) with a filter on
) {
    /** What a column's filter currently does. */
    public record FilterState(boolean active, boolean custom, Set<String> allowed) {
        /** No filter on the column. */
        public static final FilterState NONE = new FilterState(false, false, Set.of());
    }

    public boolean isTable() {
        return !name.isEmpty();
    }

    public boolean contains(int row, int column) {
        return row >= top && row <= bottom && column >= left && column <= right;
    }

    public boolean overlaps(int otherTop, int otherLeft, int otherBottom, int otherRight) {
        return top <= otherBottom && bottom >= otherTop
                && left <= otherRight && right >= otherLeft;
    }
}
