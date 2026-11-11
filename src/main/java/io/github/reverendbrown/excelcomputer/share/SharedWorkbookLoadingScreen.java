package io.github.reverendbrown.excelcomputer.share;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

// Shown while a shared workbook downloads from the server.
public class SharedWorkbookLoadingScreen extends Screen {
    private final UUID id;
    private final String name;
    private String status = "Downloading...";
    private boolean failed;
    private boolean showing = true;

    public SharedWorkbookLoadingScreen(UUID id, String name) {
        super(Component.literal(name.isEmpty() ? "Shared workbook" : name));
        this.id = id;
        this.name = name;
    }

    public UUID id() {
        return id;
    }

    public boolean isShowing() {
        return showing;
    }

    void progress(int received, int total) {
        status = "Downloading... " + (int) (received * 100L / Math.max(1, total)) + "%";
    }

    void fail(String message) {
        status = message;
        failed = true;
    }

    // Like the spreadsheet: the game keeps running behind the window, even in singleplayer.
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(
                Button.builder(Component.literal("Close"), button -> this.onClose())
                        .bounds(this.width / 2 - 50, this.height / 2 + 20, 100, 20)
                        .build()
        );
    }

    @Override
    public void removed() {
        showing = false;
        ShareClient.loadingScreenClosed(this);
        super.removed();
    }

    @Override
    //? if >=26.1 {
    /*public void extractRenderState(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    *///?} else {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?}
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 30, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, this.status, this.width / 2, this.height / 2 - 10,
                failed ? 0xFFFF7777 : 0xFFB8DDBF);
    }
}
