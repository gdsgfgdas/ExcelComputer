package io.github.reverendbrown.excelcomputer;

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
            net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
            //?} elif neoforge {
            /*net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
            *///?} else {
            /*net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
            *///?}
                    .resolve("excelcomputer")
                    .resolve("workbooks");

    private WorkbookFiles() {
    }

    public static Path getDirectory() throws IOException {
        Files.createDirectories(WORKBOOK_DIRECTORY);
        return WORKBOOK_DIRECTORY;
    }

    // Where shared workbooks are downloaded to (one folder per workbook).
    public static Path getSharedCacheDirectory() throws IOException {
        Path directory = WORKBOOK_DIRECTORY.resolveSibling("shared-cache");
        Files.createDirectories(directory);
        return directory;
    }

    // A file name in the workbooks folder that isn't taken yet, like "Budget (2).xlsx".
    public static Path freeWorkbookPath(String fileName) throws IOException {
        Path directory = getDirectory();
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        Path candidate = directory.resolve(fileName);

        for (int number = 2; Files.exists(candidate); number++) {
            candidate = directory.resolve(stem + " (" + number + ")" + extension);
        }

        return candidate;
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
