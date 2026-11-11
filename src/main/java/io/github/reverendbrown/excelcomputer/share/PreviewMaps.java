package io.github.reverendbrown.excelcomputer.share;

import io.github.reverendbrown.excelcomputer.item.SharedWorkbookItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
//? if >=1.21 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.saveddata.maps.MapId;
//?} else {
/*import net.minecraft.world.item.MapItem;
*///?}

import java.util.UUID;

/**
 * The map pictures of shared workbooks (see SheetPreview). Each shared
 * workbook with a picture has a locked vanilla map whose pixels are the
 * picture, and its items carry that map's id. Minecraft then draws the
 * picture when the item is held or put in an item frame (1.20.1 needs the
 * mixins in the mixin package for that), and sends it to players like any
 * map. The owner's saves repaint the same map. Runs on the server thread.
 */
public final class PreviewMaps {
    /** No map. */
    public static final int NONE = -1;

    private PreviewMaps() {
    }

    /**
     * Paints the picture on the map with this id, or makes a new map if
     * there is none. Returns the map id.
     */
    public static int paint(MinecraftServer server, int mapId, byte[] colors) {
        if (colors == null || colors.length != ShareProtocol.PREVIEW_BYTES) {
            return mapId;
        }

        ServerLevel overworld = server.overworld();
        MapItemSavedData data = mapId == NONE ? null : overworld.getMapData(key(mapId));

        if (data == null) {
            // Locked, and not tracking anyone: nothing but the picture ever shows on it.
            data = MapItemSavedData.createFresh(0, 0, (byte) 0, false, false, Level.OVERWORLD).locked();
            System.arraycopy(colors, 0, data.colors, 0, colors.length);
            //? if >=1.21 {
            MapId id = overworld.getFreeMapId();
            overworld.setMapData(id, data);
            data.setDirty();
            return id.id();
            //?} else {
            /*int id = overworld.getFreeMapId();
            overworld.setMapData(MapItem.makeKey(id), data);
            data.setDirty();
            return id;
            *///?}
        }

        // setColor marks the changed rows, so players get only what changed.
        for (int i = 0; i < colors.length; i++) {
            if (data.colors[i] != colors[i]) {
                data.setColor(i % 128, i / 128, colors[i]);
            }
        }

        return mapId;
    }

    //? if >=1.21 {
    private static MapId key(int mapId) {
        return new MapId(mapId);
    }
    //?} else {
    /*private static String key(int mapId) {
        return MapItem.makeKey(mapId);
    }
    *///?}

    /** Gives an item its shared workbook's map, if it hasn't got it yet. */
    public static void attach(ItemStack stack, int mapId) {
        if (mapId == NONE) {
            return;
        }

        //? if >=1.21 {
        MapId current = stack.get(DataComponents.MAP_ID);

        if (current == null || current.id() != mapId) {
            stack.set(DataComponents.MAP_ID, new MapId(mapId));
            SharedWorkbookItem.hideMapTooltip(stack);
        }
        //?} else {
        /*Integer current = MapItem.getMapId(stack);

        if (current == null || current != mapId) {
            stack.getOrCreateTag().putInt("map", mapId);
        }
        *///?}
    }

    /**
     * Gives the player's items for this shared workbook its map (items made
     * before it had one get it when used).
     */
    public static void attachAll(ServerPlayer player, UUID id, int mapId) {
        if (mapId == NONE) {
            return;
        }

        var inventory = player.getInventory();

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);

            if (id.equals(SharedWorkbookItem.getSharedId(stack))) {
                attach(stack, mapId);
            }
        }
    }
}
