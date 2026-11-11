
package io.github.reverendbrown.excelcomputer;

import io.github.reverendbrown.excelcomputer.share.MySharesScreen;
import io.github.reverendbrown.excelcomputer.share.ShareClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
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
    // Set when picking a workbook to link to the Redstone Connector or Logistics Hub at this position.
    private final BlockPos linkTarget;
    private final Runnable hubListener = this::refresh;
    // The hub dropdown is open (its entries take the place of the workbook list).
    private boolean hubMenuOpen;
    // The hub being renamed (the hub row shows a text box instead), and what is typed so far.
    private ShareClient.KnownHub renamingHub;
    private String renameDraft = "";
    // The Access panel is open for the active hub (its rows take the place of the
    // workbook list), and the player name typed so far.
    private boolean accessOpen;
    private String memberDraft = "";
    // A message for the status line that survives the next rebuild (refresh() resets the status).
    private String notice;
    // The workbook list: the first row shown, how many rows fit, how many workbooks there are.
    private int scroll;
    private int listRows;
    private int listSize;
    // "6-10 of 14" next to the arrows, or null.
    private String positionLabel;
    private int positionX;
    private int positionY;

    public WorkbookPickerScreen() {
        this(null);
    }

    public WorkbookPickerScreen(BlockPos linkTarget) {
        super(Component.literal(linkTarget == null ? "Excel on a Stick" : "Link a workbook to the " + ShareClient.linkedLabel(linkTarget)));
        this.linkTarget = linkTarget;

        // The player's Logistics Hubs, for the hub row (refresh() runs on the answer).
        if (linkTarget == null) {
            ShareClient.hubListener = hubListener;
            ShareClient.queryHubs();
        }
    }

    // Like the spreadsheet: the game keeps running behind the window, even in singleplayer.
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        positionLabel = null;
        listRows = 0;
        int centerX = this.width / 2;
        List<ShareClient.KnownHub> hubs = linkTarget == null ? ShareClient.knownHubs() : List.of();
        // Room for the hub row under the status line.
        int startY = hubs.isEmpty() ? 65 : 80;

        ShareClient.KnownHub activeHub = ShareClient.activeHub();

        if (hubs.isEmpty()) {
            hubMenuOpen = false;
            renamingHub = null;
        }

        // Only the owner manages access (and the hub may be gone or no longer active).
        if (activeHub == null || !activeHub.mayRename()) {
            accessOpen = false;
        }

        try {
            List<Path> workbooks = WorkbookFiles.listWorkbooks();

            if (workbooks.isEmpty()) {
                this.status = "No .xlsx files found in the workbooks folder";
            } else {
                this.status = linkTarget != null
                        ? ShareClient.isHub(linkTarget)
                        ? "Click a workbook to link it. It gets Inventories, Stock and Transfers sheets"
                        : "Click a workbook to link it. It gets a Redstone sheet that controls the connector"
                        : shareMode
                        ? "Click a workbook to share it (uses 1 Excel Paper)"
                        : "Select a workbook";

                // As many rows as fit above the bottom buttons; the arrows (or the
                // mouse wheel) scroll through the rest. None while the hub dropdown
                // or the Access panel is open: their entries are drawn there.
                int fits = Math.max(1, (this.height - 50 - startY) / 25);
                int visibleCount = hubMenuOpen || accessOpen ? 0 : Math.min(workbooks.size(), fits);
                listRows = visibleCount;
                listSize = workbooks.size();
                scroll = Math.max(0, Math.min(scroll, workbooks.size() - visibleCount));

                if (visibleCount > 0 && workbooks.size() > visibleCount) {
                    Button up = Button.builder(Component.literal("▲"), button -> scrollBy(-1))
                            .bounds(centerX + 114, startY, 20, 20)
                            .tooltip(Tooltip.create(Component.literal("Show earlier workbooks (or use the mouse wheel)")))
                            .build();
                    up.active = scroll > 0;
                    this.addRenderableWidget(up);

                    Button down = Button.builder(Component.literal("▼"), button -> scrollBy(1))
                            .bounds(centerX + 114, startY + (visibleCount - 1) * 25, 20, 20)
                            .tooltip(Tooltip.create(Component.literal("Show later workbooks (or use the mouse wheel)")))
                            .build();
                    down.active = scroll + visibleCount < workbooks.size();
                    this.addRenderableWidget(down);

                    if (visibleCount >= 3) {
                        positionLabel = (scroll + 1) + "-" + (scroll + visibleCount) + " of " + workbooks.size();
                        positionY = startY + (visibleCount - 1) * 25 / 2 + 6;
                        positionX = centerX + 124;
                    } else {
                        positionLabel = null;
                    }
                } else {
                    positionLabel = null;
                }

                for (int i = 0; i < visibleCount; i++) {
                    Path path = workbooks.get(scroll + i);
                    String name = path.getFileName().toString();


                    this.addRenderableWidget(
                            Button.builder(Component.literal(linkTarget != null ? "Link " + name : shareMode ? "Share " + name : name), button -> {
                                if (linkTarget != null) {
                                    link(path);
                                } else if (shareMode) {
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

        boolean canShare = ShareClient.canShare() && linkTarget == null;
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

        if (!hubs.isEmpty()) {
            addHubControls(centerX, hubs);
        }

        // Last, so a fresh message (e.g. the server's answer about access) wins.
        if (notice != null) {
            this.status = notice;
            notice = null;
        }

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

        // Top left: the step-by-step guide.
        this.addRenderableWidget(Button.builder(Component.literal("Guide"), button ->
                        this.minecraft.setScreen(new io.github.reverendbrown.excelcomputer.guide.GuideScreen(this, "start")))
                .bounds(10, 10, 50, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "How to use Excel on a Stick and the Logistics Hubs, step by step."
                                + " Tip: hold W over an Excel item in your inventory.")))
                .build());

        this.addRenderableWidget(
                Button.builder(Component.literal("Close"), button ->
                        this.onClose()
                ).bounds(centerX + 83, this.height - 45, 75, 20).build()
        );

    }

    @Override
    public void removed() {
        // Only if it's still this screen's (a newer picker may have set its own).
        if (ShareClient.hubListener == hubListener) {
            ShareClient.hubListener = null;
        }

        super.removed();
    }

    // The hub row under the status line: a dropdown of the player's Logistics
    // Hubs (the one shown is active: new inventories join it), Open and Rename.
    // While renaming, the row is a text box with Save and Cancel instead.
    private void addHubControls(int centerX, List<ShareClient.KnownHub> hubs) {
        int y = 54;

        if (renamingHub != null) {
            ShareClient.KnownHub hub = renamingHub;
            EditBox box = new EditBox(this.font, centerX - 110, y, 130, 20, Component.literal("Hub name"));
            box.setMaxLength(32);
            box.setValue(renameDraft);
            box.setResponder(text -> renameDraft = text);
            this.addRenderableWidget(box);
            this.setFocused(box);

            this.addRenderableWidget(Button.builder(Component.literal("Save"), button -> {
                        ShareClient.renameHub(hub, renameDraft);
                        notice = renameDraft.isBlank() ? "Renamed back to " + hub.tier()
                                : "Renamed to " + renameDraft.trim();
                        renamingHub = null;
                        refresh();
                    })
                    .bounds(centerX + 24, y, 42, 20)
                    .tooltip(Tooltip.create(Component.literal(
                            "Everyone who uses this hub sees the new name. Leave it blank to go back to \""
                                    + hub.tier() + "\".")))
                    .build());
            this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> {
                        renamingHub = null;
                        refresh();
                    })
                    .bounds(centerX + 70, y, 40, 20)
                    .build());
            this.status = "Type a new name for " + hub.name();
            return;
        }

        ShareClient.KnownHub active = ShareClient.activeHub();
        String shown = active != null ? active.name() : "Choose a hub";

        this.addRenderableWidget(Button.builder(Component.literal(shown + (hubMenuOpen ? " ▲" : " ▼")),
                        button -> {
                            hubMenuOpen = !hubMenuOpen;
                            accessOpen = false;
                            refresh();
                        })
                .bounds(centerX - 110, y, 130, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Your Logistics Hubs. The one shown is the one Open opens, and the one inventories join"
                                + " when you sneak-right-click them with the stick.")))
                .build());

        Button open = Button.builder(Component.literal("Open"), button -> {
                    if (active != null) {
                        ShareClient.openConnector(active.pos());
                    }
                })
                .bounds(centerX + 24, y, 42, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Edit the hub's workbook, wherever you are. Shortcut: sneak and use the stick.")))
                .build();
        open.active = active != null;
        this.addRenderableWidget(open);

        Button rename = Button.builder(Component.literal("Rename"), button -> {
                    renamingHub = active;
                    renameDraft = active.name();
                    hubMenuOpen = false;
                    refresh();
                })
                .bounds(centerX + 70, y, 40, 20)
                .tooltip(Tooltip.create(Component.literal("Give the hub a name everyone who uses it sees.")))
                .build();
        rename.active = active != null && active.mayRename();

        if (active != null && !active.mayRename()) {
            rename.setTooltip(Tooltip.create(Component.literal("Only " + active.owner() + ", who owns this hub, can rename it.")));
        }
        this.addRenderableWidget(rename);

        Button access = Button.builder(Component.literal("Access"), button -> {
                    accessOpen = !accessOpen;
                    hubMenuOpen = false;
                    refresh();
                })
                .bounds(centerX + 114, y, 44, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Choose who else may open this hub's workbook and add inventories to it."
                                + " Only one person can edit it at a time.")))
                .build();
        access.active = active != null && active.mayRename();

        if (active != null && !active.mayRename()) {
            access.setTooltip(Tooltip.create(Component.literal(
                    "Only " + active.owner() + ", who owns this hub, decides who may use it.")));
        }
        this.addRenderableWidget(access);

        if (accessOpen && active != null) {
            addAccessPanel(centerX, y + 24, active);
            return;
        }

        if (!hubMenuOpen) {
            return;
        }

        // The dropdown: one row per hub (choose it) with an X (forget it).
        int top = y + 24;
        int rows = Math.max(1, Math.min(hubs.size(), (this.height - 50 - top) / 22));

        for (int i = 0; i < rows; i++) {
            ShareClient.KnownHub hub = hubs.get(i);
            BlockPos pos = hub.pos();
            String where = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
            String label = (hub.active() ? "● " : "") + hub.name() + "  (" + where + ")";

            // A label wider than the button scrolls sideways; drop the position
            // instead (the tooltip still shows it).
            if (this.font.width(label) > 196 - 8) {
                label = (hub.active() ? "● " : "") + hub.name();
            }

            this.addRenderableWidget(Button.builder(
                            Component.literal(label),
                            button -> {
                                ShareClient.chooseHub(hub);
                                hubMenuOpen = false;
                                notice = "Inventories you sneak-right-click now join " + hub.name();
                                refresh();
                            })
                    .bounds(centerX - 110, top + i * 22, 196, 20)
                    .tooltip(Tooltip.create(Component.literal(
                            hub.tier() + " at " + where + " in " + hub.dimensionName()
                                    + (hub.owner().isEmpty() ? "" : ", owned by " + hub.owner()))))
                    .build());

            this.addRenderableWidget(Button.builder(Component.literal("✕"), button -> {
                        ShareClient.forgetHub(hub);
                        notice = "Forgot " + hub.name() + ". Right-click it to add it back";
                    })
                    .bounds(centerX + 90, top + i * 22, 20, 20)
                    .tooltip(Tooltip.create(Component.literal(
                            "Take this hub off your list. It keeps working; right-click it to add it back.")))
                    .build());
        }

        this.status = rows < hubs.size()
                ? "Choose a hub (" + rows + " of " + hubs.size() + " shown; make the window taller for more)"
                : "Choose a hub";
    }

    // The Access panel, in place of the workbook list: a box to add an online
    // player by name, then one row per member with Remove.
    private void addAccessPanel(int centerX, int top, ShareClient.KnownHub hub) {
        EditBox box = new EditBox(this.font, centerX - 110, top, 130, 20, Component.literal("Player name"));
        box.setMaxLength(16);
        box.setValue(memberDraft);
        box.setResponder(text -> memberDraft = text);
        box.setHint(Component.literal("Player name"));
        this.addRenderableWidget(box);
        this.setFocused(box);

        this.addRenderableWidget(Button.builder(Component.literal("Add"), button -> {
                    if (!memberDraft.isBlank()) {
                        ShareClient.addHubMember(hub, memberDraft.trim(), text -> notice = text);
                        notice = "Giving " + memberDraft.trim() + " access...";
                        memberDraft = "";
                        refresh();
                    }
                })
                .bounds(centerX + 24, top, 42, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "The player must be online. They can then open the workbook and add inventories,"
                                + " but not rename the hub, change who has access or break it.")))
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> {
                    accessOpen = false;
                    refresh();
                })
                .bounds(centerX + 70, top, 40, 20)
                .build());

        List<ShareClient.HubMember> members = hub.members();
        int rowsTop = top + 24;
        int rows = Math.max(0, Math.min(members.size(), (this.height - 50 - rowsTop) / 22));

        for (int i = 0; i < rows; i++) {
            ShareClient.HubMember member = members.get(i);
            Button name = Button.builder(Component.literal(member.name()), button -> {
                    })
                    .bounds(centerX - 110, rowsTop + i * 22, 176, 20)
                    .build();
            name.active = false;
            this.addRenderableWidget(name);
            this.addRenderableWidget(Button.builder(Component.literal("Remove"), button -> {
                        ShareClient.removeHubMember(hub, member.id(), text -> notice = text);
                        notice = "Removing " + member.name() + "...";
                        refresh();
                    })
                    .bounds(centerX + 70, rowsTop + i * 22, 40, 20)
                    .tooltip(Tooltip.create(Component.literal(
                            member.name() + " will no longer be able to open " + hub.name() + ".")))
                    .build());
        }

        this.status = members.isEmpty()
                ? "Only you can use " + hub.name() + ". Type a player's name to let them use it too"
                : rows < members.size()
                ? "Who may use " + hub.name() + " (" + rows + " of " + members.size()
                        + " shown; make the window taller for more)"
                : "Who may use " + hub.name() + " besides you";
    }

    // Moves the workbook list by rows (negative: up).
    private void scrollBy(int rows) {
        int next = Math.max(0, Math.min(scroll + rows, listSize - listRows));

        if (next != scroll) {
            scroll = next;
            refresh();
        }
    }

    // The mouse wheel scrolls the workbook list (one row per notch).
    @Override
    //? if >=1.20.2 {
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double vertical) {
    *///?}
        if (listSize > listRows && listRows > 0 && vertical != 0) {
            scrollBy(vertical > 0 ? -1 : 1);
            return true;
        }

        //? if >=1.20.2 {
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        //?} else {
        /*return super.mouseScrolled(mouseX, mouseY, vertical);
        *///?}
    }

    /** Rebuilds the buttons (e.g. once the server sent the player's hubs). */
    public void refresh() {
        this.clearWidgets();
        this.init();
    }

    // Sends the workbook to the connector or hub; the server adds its sheets
    // and, once it is linked, the block's copy opens to set it up.
    private void link(Path path) {
        if (!path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            this.status = "Only .xlsx workbooks can be linked (a .csv has only one sheet)";
            return;
        }

        String error = ShareClient.linkConnector(path, linkTarget, message -> this.status = message);
        this.status = error != null ? error : "Linking " + path.getFileName() + "...";
    }

    private void share(Path path) {
        this.status = "Sharing " + path.getFileName() + "...";
        ShareClient.shareWithPreview(path, message -> this.status = message);
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

        if (positionLabel != null) {
            graphics.drawCenteredString(this.font, positionLabel, positionX, positionY, 0xFFA0A0A0);
        }
    }
}
