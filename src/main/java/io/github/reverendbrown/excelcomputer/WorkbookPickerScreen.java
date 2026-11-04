
package io.github.reverendbrown.excelcomputer;

import io.github.reverendbrown.excelcomputer.share.MySharesScreen;
import io.github.reverendbrown.excelcomputer.share.ShareClient;
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
    // In share mode, clicking a workbook shares it instead of opening it.
    private boolean shareMode;

    public WorkbookPickerScreen() {
        super(Component.literal("Excel on a Stick"));
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
                this.status = shareMode
                        ? "Click a workbook to share it (uses 1 Excel Paper)"
                        : "Select a workbook";

                int visibleCount = Math.min(workbooks.size(), 8);

                for (int i = 0; i < visibleCount; i++) {
                    Path path = workbooks.get(i);
                    String name = path.getFileName().toString();


                    this.addRenderableWidget(
                            Button.builder(Component.literal(shareMode ? "Share " + name : name), button -> {
                                if (shareMode) {
                                    share(path);
                                } else {
                                    this.minecraft.setScreen(new SpreadsheetScreen(path));
                                }
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
                        .bounds(centerX - 157, this.height - 45, 75, 20)
                        .tooltip(Tooltip.create(Component.literal(
                                "Opens the workbooks folder. Drop .xlsx or .csv files in, then press Refresh.")))
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(Component.literal("Refresh"), button -> {
                    this.clearWidgets();
                    this.init();
                }).bounds(centerX - 77, this.height - 45, 75, 20).build()
        );

        boolean canShare = ShareClient.canShare();
        Button shareButton = Button.builder(Component.literal(shareMode ? "Cancel share" : "Share..."), button -> {
                    shareMode = !shareMode;
                    this.clearWidgets();
                    this.init();
                })
                .bounds(centerX + 3, this.height - 45, 75, 20)
                .tooltip(Tooltip.create(Component.literal(canShare
                        ? "Turn a workbook into an item you can give to other players. Costs 1 Excel Paper (free in creative)."
                        : "Sharing needs the server to have Excel on a Stick installed.")))
                .build();
        shareButton.active = canShare;
        this.addRenderableWidget(shareButton);

        // Top right, out of the way of the workbook list.
        Button mySharesButton = Button.builder(Component.literal("My shared sheets"), button ->
                        this.minecraft.setScreen(new MySharesScreen(this)))
                .bounds(this.width - 110, 10, 100, 20)
                .tooltip(Tooltip.create(Component.literal(canShare
                        ? "See the sheets you have shared, when they expire, and delete them."
                        : "Sharing needs the server to have Excel on a Stick installed.")))
                .build();
        mySharesButton.active = canShare;
        this.addRenderableWidget(mySharesButton);

        this.addRenderableWidget(
                Button.builder(Component.literal("Close"), button ->
                        this.onClose()
                ).bounds(centerX + 83, this.height - 45, 75, 20).build()
        );

    }

    private void share(Path path) {
        String error = ShareClient.upload(path, null, message -> this.status = message);
        this.status = error != null ? error : "Sharing " + path.getFileName() + "...";
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
    //? if >=26.1 {
    /*public void extractRenderState(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    *///?} else {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?}
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, this.status, this.width / 2, 40, 0xFFB8DDBF);
    }
}
