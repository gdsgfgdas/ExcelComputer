package io.github.reverendbrown.excelcomputer;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFSimpleShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.drawingml.x2006.main.CTAdjPoint2D;
import org.openxmlformats.schemas.drawingml.x2006.main.CTCustomGeometry2D;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGeomRect;
import org.openxmlformats.schemas.drawingml.x2006.main.CTLineProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTPath2D;
import org.openxmlformats.schemas.drawingml.x2006.main.CTPositiveFixedPercentage;
import org.openxmlformats.schemas.drawingml.x2006.main.CTSRgbColor;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTransform2D;
import org.openxmlformats.schemas.drawingml.x2006.main.STLineCap;
import org.openxmlformats.schemas.drawingml.x2006.main.STPathFillMode;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTDrawing;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTShape;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTTwoCellAnchor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pen and highlighter drawings on the sheets (the Draw tab). Each stroke is
 * saved as a freeform shape (a custom geometry path) in the sheet's drawing
 * part, so Excel shows it too. Only shapes this class wrote (marked by their
 * description) are read back or replaced; anything else in the drawing is left alone.
 *
 * <p>Points are kept in cell space: x is a column index plus how far across
 * that column (0 to 1), y the same for rows. Drawings stay on the cells they
 * were drawn over when columns and rows are resized.
 *
 * <p>Uses only POI and plain Java, like the rest of {@link WorkbookManager}.
 */
public final class InkDrawings {
    /**
     * One stroke. color is 0xRRGGBB; width is in pixels at 100% zoom; a
     * highlighter stroke is drawn see-through. xs and ys are in cell space.
     */
    public record Stroke(int color, float width, boolean highlighter, double[] xs, double[] ys) {
        public int size() {
            return xs.length;
        }
    }

    // Marks the shapes this class wrote.
    private static final String DESCRIPTION = "Excel on a Stick drawing";
    private static final long EMU_PER_PIXEL = 9525;
    private static final long EMU_PER_POINT = 12700;
    // Highlighter strokes are saved at this opacity (in 1/1000 %).
    private static final int HIGHLIGHTER_ALPHA = 40_000;
    // Stops a broken anchor from walking a million columns.
    private static final int MAX_INDEX = 1_048_576;

    private final XSSFWorkbook workbook;
    // Sheet index to its strokes, read the first time the sheet is asked for.
    private final Map<Integer, List<Stroke>> strokes = new HashMap<>();
    // Sheets whose strokes changed since the last save.
    private final Set<Integer> changed = new HashSet<>();

    InkDrawings(XSSFWorkbook workbook) {
        this.workbook = workbook;
    }

    /** The sheet's strokes, oldest first (read-only). */
    public List<Stroke> strokes(int sheet) {
        return Collections.unmodifiableList(loaded(sheet));
    }

    public void add(int sheet, Stroke stroke) {
        loaded(sheet).add(stroke);
        changed.add(sheet);
    }

    /** Removes that stroke (the same object); returns whether it was there. */
    public boolean remove(int sheet, Stroke stroke) {
        List<Stroke> list = loaded(sheet);

        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == stroke) {
                list.remove(i);
                changed.add(sheet);
                return true;
            }
        }

        return false;
    }

    /** Replaces all of the sheet's strokes (undo, Clear). */
    public void set(int sheet, List<Stroke> replacement) {
        List<Stroke> list = loaded(sheet);
        list.clear();
        list.addAll(replacement);
        changed.add(sheet);
    }

    private List<Stroke> loaded(int sheet) {
        return strokes.computeIfAbsent(sheet, this::read);
    }

    // ----- reading -----

    private List<Stroke> read(int sheetIndex) {
        List<Stroke> list = new ArrayList<>();

        try {
            XSSFSheet sheet = workbook.getSheetAt(sheetIndex);
            XSSFDrawing drawing = sheet.getDrawingPatriarch();

            if (drawing == null) {
                return list;
            }

            Sizes sizes = new Sizes(sheet);

            for (XSSFShape shape : drawing.getShapes()) {
                if (shape instanceof XSSFSimpleShape simple && ours(simple.getCTShape())
                        && shape.getAnchor() instanceof XSSFClientAnchor anchor) {
                    Stroke stroke = read(simple.getCTShape(), anchor, sizes);

                    if (stroke != null) {
                        list.add(stroke);
                    }
                }
            }
        } catch (RuntimeException exception) {
            // A drawing we can't read: show none rather than fail to open the sheet.
        }

        return list;
    }

    private static boolean ours(CTShape shape) {
        return shape.getNvSpPr() != null && shape.getNvSpPr().getCNvPr() != null
                && DESCRIPTION.equals(shape.getNvSpPr().getCNvPr().getDescr());
    }

    private static Stroke read(CTShape shape, XSSFClientAnchor anchor, Sizes sizes) {
        CTShapeProperties properties = shape.getSpPr();

        if (properties == null || properties.getCustGeom() == null
                || properties.getCustGeom().getPathLst() == null
                || properties.getCustGeom().getPathLst().sizeOfPathArray() == 0) {
            return null;
        }

        CTPath2D path = properties.getCustGeom().getPathLst().getPathArray(0);
        // moveTo first, then the lnTo points, in order (how this class writes them).
        List<CTAdjPoint2D> points = new ArrayList<>();

        if (path.sizeOfMoveToArray() > 0) {
            points.add(path.getMoveToArray(0).getPt());
        }

        for (int i = 0; i < path.sizeOfLnToArray(); i++) {
            points.add(path.getLnToArray(i).getPt());
        }

        if (points.isEmpty()) {
            return null;
        }

        long left = sizes.columnStart(anchor.getCol1()) + anchor.getDx1();
        long top = sizes.rowStart(anchor.getRow1()) + anchor.getDy1();
        long right = sizes.columnStart(anchor.getCol2()) + anchor.getDx2();
        long bottom = sizes.rowStart(anchor.getRow2()) + anchor.getDy2();
        double scaleX = path.getW() > 0 ? (double) (right - left) / path.getW() : 0;
        double scaleY = path.getH() > 0 ? (double) (bottom - top) / path.getH() : 0;
        double[] xs = new double[points.size()];
        double[] ys = new double[points.size()];

        for (int i = 0; i < points.size(); i++) {
            xs[i] = sizes.columnAt(left + Math.round(number(points.get(i).getX()) * scaleX));
            ys[i] = sizes.rowAt(top + Math.round(number(points.get(i).getY()) * scaleY));
        }

        int color = 0;
        boolean highlighter = false;
        float width = 2;
        CTLineProperties line = properties.getLn();

        if (line != null) {
            if (line.isSetW()) {
                width = Math.max(1, line.getW() / (float) EMU_PER_PIXEL);
            }

            if (line.getSolidFill() != null && line.getSolidFill().getSrgbClr() != null) {
                CTSRgbColor rgb = line.getSolidFill().getSrgbClr();
                byte[] value = rgb.getVal();

                if (value != null && value.length == 3) {
                    color = (value[0] & 0xFF) << 16 | (value[1] & 0xFF) << 8 | value[2] & 0xFF;
                }

                for (CTPositiveFixedPercentage alpha : rgb.getAlphaList()) {
                    highlighter |= number(alpha.getVal()) < 100_000;
                }
            }
        }

        return new Stroke(color, width, highlighter, xs, ys);
    }

    // Coordinates and percentages are numbers, or (rarely) text such as "50%".
    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }

        try {
            String text = String.valueOf(value).trim();
            return text.endsWith("%")
                    ? Double.parseDouble(text.substring(0, text.length() - 1)) * 1000
                    : Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    // ----- writing (just before the workbook is saved) -----

    /** Writes the changed sheets' strokes into their drawings. Called by WorkbookManager.save. */
    void writeChanges() {
        for (int sheetIndex : changed) {
            if (sheetIndex >= workbook.getNumberOfSheets()) {
                continue;
            }

            XSSFSheet sheet = workbook.getSheetAt(sheetIndex);
            List<Stroke> list = strokes.getOrDefault(sheetIndex, List.of());
            XSSFDrawing drawing = list.isEmpty() ? sheet.getDrawingPatriarch() : sheet.createDrawingPatriarch();

            if (drawing == null) {
                continue;
            }

            // Take out what was written before, then write the strokes as they are now.
            CTDrawing xml = drawing.getCTDrawing();

            for (int i = xml.sizeOfTwoCellAnchorArray() - 1; i >= 0; i--) {
                CTTwoCellAnchor anchor = xml.getTwoCellAnchorArray(i);

                if (anchor.isSetSp() && ours(anchor.getSp())) {
                    xml.removeTwoCellAnchor(i);
                }
            }

            Sizes sizes = new Sizes(sheet);
            int number = 1;

            for (Stroke stroke : list) {
                if (stroke.size() > 0) {
                    write(drawing, stroke, sizes, number++);
                }
            }
        }

        changed.clear();
    }

    private static void write(XSSFDrawing drawing, Stroke stroke, Sizes sizes, int number) {
        int count = stroke.size();
        long[] xs = new long[count];
        long[] ys = new long[count];
        long minX = Long.MAX_VALUE;
        long minY = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE;
        long maxY = Long.MIN_VALUE;

        for (int i = 0; i < count; i++) {
            xs[i] = sizes.columnEmu(stroke.xs()[i]);
            ys[i] = sizes.rowEmu(stroke.ys()[i]);
            minX = Math.min(minX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxX = Math.max(maxX, xs[i]);
            maxY = Math.max(maxY, ys[i]);
        }

        // A dot (or a straight line) still needs a box with some size.
        maxX = Math.max(maxX, minX + EMU_PER_PIXEL);
        maxY = Math.max(maxY, minY + EMU_PER_PIXEL);

        int column1 = sizes.columnIndex(minX);
        int row1 = sizes.rowIndex(minY);
        int column2 = sizes.columnIndex(maxX);
        int row2 = sizes.rowIndex(maxY);
        XSSFClientAnchor anchor = new XSSFClientAnchor(
                (int) (minX - sizes.columnStart(column1)), (int) (minY - sizes.rowStart(row1)),
                (int) (maxX - sizes.columnStart(column2)), (int) (maxY - sizes.rowStart(row2)),
                column1, row1, column2, row2);
        anchor.setAnchorType(org.apache.poi.ss.usermodel.ClientAnchor.AnchorType.MOVE_AND_RESIZE);

        XSSFSimpleShape shape = drawing.createSimpleShape(anchor);
        CTShape xml = shape.getCTShape();
        xml.getNvSpPr().getCNvPr().setName("Drawing " + number);
        xml.getNvSpPr().getCNvPr().setDescr(DESCRIPTION);
        // No text in a drawing.
        if (xml.isSetTxBody()) {
            xml.unsetTxBody();
        }

        CTShapeProperties properties = xml.getSpPr();
        long width = maxX - minX;
        long height = maxY - minY;
        CTTransform2D transform = properties.isSetXfrm() ? properties.getXfrm() : properties.addNewXfrm();
        (transform.isSetOff() ? transform.getOff() : transform.addNewOff()).setX(minX);
        transform.getOff().setY(minY);
        (transform.isSetExt() ? transform.getExt() : transform.addNewExt()).setCx(width);
        transform.getExt().setCy(height);

        if (properties.isSetPrstGeom()) {
            properties.unsetPrstGeom();
        }

        CTCustomGeometry2D geometry = properties.addNewCustGeom();
        geometry.addNewAvLst();
        geometry.addNewGdLst();
        geometry.addNewAhLst();
        geometry.addNewCxnLst();
        CTGeomRect rect = geometry.addNewRect();
        rect.setL("l");
        rect.setT("t");
        rect.setR("r");
        rect.setB("b");
        CTPath2D path = geometry.addNewPathLst().addNewPath();
        path.setW(width);
        path.setH(height);
        path.setFill(STPathFillMode.NONE);

        CTAdjPoint2D start = path.addNewMoveTo().addNewPt();
        start.setX(xs[0] - minX);
        start.setY(ys[0] - minY);

        // One point is still a stroke (a dot): a line to the same place.
        for (int i = count == 1 ? 0 : 1; i < count; i++) {
            CTAdjPoint2D point = path.addNewLnTo().addNewPt();
            point.setX(xs[i] - minX);
            point.setY(ys[i] - minY);
        }

        properties.addNewNoFill();

        CTLineProperties line = properties.addNewLn();
        line.setW((int) Math.max(EMU_PER_PIXEL, Math.round(stroke.width() * EMU_PER_PIXEL)));
        line.setCap(STLineCap.RND);
        CTSRgbColor color = line.addNewSolidFill().addNewSrgbClr();
        color.setVal(new byte[]{
                (byte) (stroke.color() >> 16), (byte) (stroke.color() >> 8), (byte) stroke.color()
        });

        if (stroke.highlighter()) {
            color.addNewAlpha().setVal(HIGHLIGHTER_ALPHA);
        }

        line.addNewRound();
    }

    // ----- column and row positions in EMU (what drawings are anchored in) -----

    // Where each column and row starts, from the sheet's sizes, worked out as far as needed.
    private static final class Sizes {
        private final XSSFSheet sheet;
        private final List<Long> columnStarts = new ArrayList<>(List.of(0L));
        private final List<Long> rowStarts = new ArrayList<>(List.of(0L));

        Sizes(XSSFSheet sheet) {
            this.sheet = sheet;
        }

        long columnSize(int column) {
            return Math.max(1, Math.round(sheet.getColumnWidthInPixels(column))) * EMU_PER_PIXEL;
        }

        long rowSize(int row) {
            Row r = sheet.getRow(row);
            float points = r != null ? r.getHeightInPoints() : sheet.getDefaultRowHeightInPoints();
            return Math.max(1, Math.round(points * EMU_PER_POINT));
        }

        long columnStart(int column) {
            column = Math.max(0, Math.min(column, MAX_INDEX));

            while (columnStarts.size() <= column) {
                int last = columnStarts.size() - 1;
                columnStarts.add(columnStarts.get(last) + columnSize(last));
            }

            return columnStarts.get(column);
        }

        long rowStart(int row) {
            row = Math.max(0, Math.min(row, MAX_INDEX));

            while (rowStarts.size() <= row) {
                int last = rowStarts.size() - 1;
                rowStarts.add(rowStarts.get(last) + rowSize(last));
            }

            return rowStarts.get(row);
        }

        // Cell space (index plus fraction) to EMU from the sheet's top-left corner.
        long columnEmu(double x) {
            int column = (int) Math.floor(Math.max(0, x));
            return columnStart(column) + Math.round((Math.max(0, x) - column) * columnSize(column));
        }

        long rowEmu(double y) {
            int row = (int) Math.floor(Math.max(0, y));
            return rowStart(row) + Math.round((Math.max(0, y) - row) * rowSize(row));
        }

        int columnIndex(long emu) {
            int column = 0;

            while (column < MAX_INDEX && columnStart(column + 1) <= emu) {
                column++;
            }

            return column;
        }

        int rowIndex(long emu) {
            int row = 0;

            while (row < MAX_INDEX && rowStart(row + 1) <= emu) {
                row++;
            }

            return row;
        }

        // EMU to cell space.
        double columnAt(long emu) {
            int column = columnIndex(emu);
            return column + (double) (emu - columnStart(column)) / columnSize(column);
        }

        double rowAt(long emu) {
            int row = rowIndex(emu);
            return row + (double) (emu - rowStart(row)) / rowSize(row);
        }
    }
}
