package com.example.excelcomputer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Remembers your zoom level and where you were in each workbook, so reopening a
 * workbook brings you back to the same place. It is kept in a small file next
 * to the workbooks folder, so your .xlsx files are never touched.
 */
public final class ViewStateStore {
    /** Where you were in one workbook. */
    public record ViewState(
            int sheet,
            int selectedRow,
            int selectedColumn,
            int firstRow,
            int firstColumn
    ) {}

    private ViewStateStore() {
    }

    private static Path file() throws IOException {
        return WorkbookFiles.getDirectory().resolveSibling("view-state.properties");
    }

    private static Properties read() {
        Properties properties = new Properties();

        try {
            Path file = file();

            if (Files.isRegularFile(file)) {
                try (InputStream input = Files.newInputStream(file)) {
                    properties.load(input);
                }
            }
        } catch (IOException | RuntimeException exception) {
            // A missing or unreadable file just means nothing was remembered.
        }

        return properties;
    }

    private static int number(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, "").trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** The zoom level used last time (shared by all workbooks). */
    public static int loadZoom(int fallback) {
        return number(read(), "zoom", fallback);
    }

    /** Where you were in this workbook, or null if it was never opened before. */
    public static ViewState load(String workbookName) {
        Properties properties = read();
        String prefix = workbookName + ".";

        if (properties.getProperty(prefix + "sheet") == null) {
            return null;
        }

        return new ViewState(
                number(properties, prefix + "sheet", 0),
                number(properties, prefix + "selectedRow", 0),
                number(properties, prefix + "selectedColumn", 0),
                number(properties, prefix + "firstRow", 0),
                number(properties, prefix + "firstColumn", 0)
        );
    }

    public static void save(String workbookName, int zoom, ViewState state) {
        Properties properties = read();
        String prefix = workbookName + ".";

        properties.setProperty("zoom", Integer.toString(zoom));
        properties.setProperty(prefix + "sheet", Integer.toString(state.sheet()));
        properties.setProperty(prefix + "selectedRow", Integer.toString(state.selectedRow()));
        properties.setProperty(prefix + "selectedColumn", Integer.toString(state.selectedColumn()));
        properties.setProperty(prefix + "firstRow", Integer.toString(state.firstRow()));
        properties.setProperty(prefix + "firstColumn", Integer.toString(state.firstColumn()));

        try {
            Path file = file();
            Path temp = file.resolveSibling("view-state.properties.tmp");

            try (OutputStream output = Files.newOutputStream(temp)) {
                properties.store(output, "Excel Computer: remembered zoom and position per workbook");
            }

            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            ExcelComputer.LOGGER.warn("Could not save the remembered view", exception);
        }
    }
}
