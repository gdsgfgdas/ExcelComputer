package io.github.reverendbrown.excelcomputer.share;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

// Lists the sheets this player has shared, with a Delete button for each.
public class MySharesScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int LIST_TOP = 55;
    private static final int LIST_WIDTH = 300;

    private final Screen parent;
    private List<ShareClient.MyShare> shares;
    private int limit = 10;
    private int firstRow;
    private static final String LOADING = "Loading your shared sheets...";

    private String status = LOADING;
    // Delete needs two clicks: the first one arms it for that sheet.
    private UUID armedDelete;
    private boolean showing = true;

    public MySharesScreen(Screen parent) {
        super(Component.literal("My shared sheets"));
        this.parent = parent;
    }

    public boolean isShowing() {
        return showing;
    }

    void setShares(List<ShareClient.MyShare> shares, int limit) {
        this.shares = new ArrayList<>(shares);
        this.limit = limit;
        this.armedDelete = null;
        if (LOADING.equals(this.status)) {
            this.status = "";
        }
        this.firstRow = Math.max(0, Math.min(firstRow, shares.size() - visibleRows()));
        rebuildWidgets();
    }

    void setStatus(String status) {
        this.status = status;
    }

    @Override
    protected void init() {
        if (shares == null && ShareClient.canShare()) {
            ShareClient.requestMyShares(this);
        } else if (shares == null) {
            status = "This server does not have Excel on a Stick installed";
        }

        int left = this.width / 2 - LIST_WIDTH / 2;

        if (shares != null) {
            int rows = visibleRows();

            for (int i = firstRow; i < Math.min(shares.size(), firstRow + rows); i++) {
                ShareClient.MyShare share = shares.get(i);
                int y = LIST_TOP + (i - firstRow) * ROW_HEIGHT;
                boolean armed = share.id().equals(armedDelete);

                this.addRenderableWidget(
                        Button.builder(Component.literal(armed ? "Confirm" : "Delete"), button -> {
                            if (share.id().equals(armedDelete)) {
                                armedDelete = null;
                                status = "Deleting " + share.name() + "...";
                                ShareClient.deleteShare(share.id());
                            } else {
                                armedDelete = share.id();
                                status = "Click Confirm to delete " + share.name()
                                        + ". Every item for it stops working";
                                rebuildWidgets();
                            }
                        }).bounds(left + LIST_WIDTH - 60, y, 60, 20).build()
                );
            }

            if (shares.size() > rows) {
                Button up = Button.builder(Component.literal("^"), button -> {
                    firstRow = Math.max(0, firstRow - 1);
                    rebuildWidgets();
                }).bounds(left + LIST_WIDTH + 6, LIST_TOP, 20, 20).build();
                up.active = firstRow > 0;
                this.addRenderableWidget(up);

                Button down = Button.builder(Component.literal("v"), button -> {
                    firstRow = Math.min(shares.size() - rows, firstRow + 1);
                    rebuildWidgets();
                }).bounds(left + LIST_WIDTH + 6, LIST_TOP + (rows - 1) * ROW_HEIGHT, 20, 20).build();
                down.active = firstRow + rows < shares.size();
                this.addRenderableWidget(down);
            }
        }

        this.addRenderableWidget(
                Button.builder(Component.literal("Back"), button -> this.onClose())
                        .bounds(this.width / 2 - 50, this.height - 30, 100, 20)
                        .build()
        );
    }

    private int visibleRows() {
        return Math.max(1, (this.height - 40 - LIST_TOP) / ROW_HEIGHT);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        showing = false;
        super.removed();
    }

    private static String size(long bytes) {
        return bytes >= 1024 * 1024
                ? String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
                : Math.max(1, bytes / 1024) + " KB";
    }

    @Override
    //? if >=26.1 {
    /*public void extractRenderState(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    *///?} else {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?}
        super.render(graphics, mouseX, mouseY, partialTick);

        String heading = shares == null
                ? this.title.getString()
                : this.title.getString() + " (" + shares.size() + " of " + limit + ")";
        graphics.drawCenteredString(this.font, heading, this.width / 2, 15, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, this.status, this.width / 2, 32, 0xFFB8DDBF);

        if (shares == null) {
            return;
        }

        if (shares.isEmpty()) {
            graphics.drawCenteredString(this.font, "You have no shared sheets", this.width / 2, LIST_TOP + 6, 0xFF9AA0A6);
            return;
        }

        int left = this.width / 2 - LIST_WIDTH / 2;
        int textWidth = LIST_WIDTH - 66;
        int rows = visibleRows();

        for (int i = firstRow; i < Math.min(shares.size(), firstRow + rows); i++) {
            ShareClient.MyShare share = shares.get(i);
            int y = LIST_TOP + (i - firstRow) * ROW_HEIGHT;
            String details = size(share.sizeBytes()) + ", " + ShareProtocol.describeExpiry(share.expiresAt());

            graphics.drawString(this.font, this.font.plainSubstrByWidth(share.name(), textWidth),
                    left, y + 1, 0xFFFFFFFF);
            graphics.drawString(this.font, this.font.plainSubstrByWidth(details, textWidth),
                    left, y + 11, 0xFF9AA0A6);
        }
    }
}
