
package com.example.excelcomputer;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class WorkbookPickerScreen extends Screen {
    private String status = "Choose a workbook to open";

    public WorkbookPickerScreen() {
        super(Component.literal("Excel Computer"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = 65;

        try {
            List<Path> workbooks = WorkbookFiles.listWorkbooks();

            if (workbooks.isEmpty()) {
                this.status = "No .xlsx files found in the workbooks folder";
            } else {
                this.status = "Select a workbook";

                int visibleCount = Math.min(workbooks.size(), 8);

                for (int i = 0; i < visibleCount; i++) {
                    Path path = workbooks.get(i);
                    String name = path.getFileName().toString();


                    this.addRenderableWidget(
                            Button.builder(Component.literal(name), button -> {
                                this.minecraft.setScreen(new SpreadsheetScreen(path));
                            }).bounds(
                                    centerX - 110,
                                    startY + i * 25,
                                    220,
                                    20
                            ).build()
                    );

                }
            }
        } catch (IOException exception) {
            this.status = "Could not read the workbooks folder";
        }

        this.addRenderableWidget(
                Button.builder(Component.literal("Refresh"), button -> {
                    this.clearWidgets();
                    this.init();
                }).bounds(centerX - 100, this.height - 45, 95, 20).build()
        );

        this.addRenderableWidget(
                Button.builder(Component.literal("Close"), button ->
                        this.onClose()
                ).bounds(centerX + 5, this.height - 45, 95, 20).build()
        );
    }
}
