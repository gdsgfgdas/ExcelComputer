
package com.example.excelcomputer;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
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
                Button.builder(Component.literal("Open folder"), button -> openFolder())
                        .bounds(centerX - 147, this.height - 45, 95, 20)
                        .tooltip(Tooltip.create(Component.literal(
                                "Opens the workbooks folder. Drop .xlsx or .csv files in, then press Refresh.")))
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(Component.literal("Refresh"), button -> {
                    this.clearWidgets();
                    this.init();
                }).bounds(centerX - 47, this.height - 45, 95, 20).build()
        );

        this.addRenderableWidget(
                Button.builder(Component.literal("Close"), button ->
                        this.onClose()
                ).bounds(centerX + 53, this.height - 45, 95, 20).build()
        );

    }
    // Opens the workbooks folder in the system's file explorer.
    private void openFolder() {
        try {
            Util.getPlatform().openUri(WorkbookFiles.getDirectory().toUri());
            this.status = "Drop .xlsx or .csv files into the folder, then press Refresh";
        } catch (IOException | RuntimeException exception) {
            this.status = "Could not open the folder";
        }
    }

    // Shows the title and the status line (the picker never displayed its status text before).
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, this.status, this.width / 2, 40, 0xFFB8DDBF);
    }
}
