
package com.example.excelcomputer;

//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.loading.FMLPaths;
 *///?}

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class WorkbookFiles {
    private static final Path WORKBOOK_DIRECTORY =
            //? if fabric {
            FabricLoader.getInstance().getGameDir()
                    //?} else {
                    /*FMLPaths.GAMEDIR.get()
                     *///?}
                    .resolve("excelcomputer")
                    .resolve("workbooks");

    private WorkbookFiles() {
    }

    public static Path getDirectory() throws IOException {
        Files.createDirectories(WORKBOOK_DIRECTORY);
        return WORKBOOK_DIRECTORY;
    }

    public static List<Path> listWorkbooks() throws IOException {
        Path directory = getDirectory();

        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String filename = path.getFileName()
                                .toString()
                                .toLowerCase(Locale.ROOT);

                        return filename.endsWith(".xlsx")
                                || filename.endsWith(".csv");
                    })
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }
}
