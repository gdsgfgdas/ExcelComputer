package io.github.reverendbrown.excelcomputer.logistics;

import io.github.reverendbrown.excelcomputer.ExcelComputer;
import io.github.reverendbrown.excelcomputer.link.LinkedWorkbooks;
import io.github.reverendbrown.excelcomputer.share.ShareNetwork;
import io.github.reverendbrown.excelcomputer.share.ShareProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The hubs each player knows and the names of the hubs.
 *
 * <p>A player knows every hub they have right-clicked (until they forget it or
 * it is broken) and may open and save any of them from anywhere with Excel on a
 * Stick. One of them is active: the hub their sneak-right-clicked inventories
 * join. The workbook picker shows the list as a dropdown (see HUB_LIST).
 *
 * <p>Names belong to the hub, so every player sees the same name; a hub without
 * one is called by its tier ("Iron Logistics Hub").
 *
 * <p>Access: the player who places a hub owns it. Only the owner and the
 * members they add (in the workbook picker's Access panel) may open, save or
 * choose the hub and register inventories to it; only the owner renames it,
 * manages its members or breaks it. A hub nobody owns yet (placed by a machine)
 * becomes the first opener's.
 *
 * <p>Saved in {@code <world>/excelcomputer/hubs/known.txt}, {@code names.txt},
 * {@code owners.txt} and {@code members.txt}.
 * Everything here runs on the server thread.
 */
public final class HubDirectory {
    // The most hubs one player keeps in their list; the oldest is dropped.
    private static final int MAX_KNOWN = 32;
    // The most members one hub can have (besides its owner).
    public static final int MAX_MEMBERS = 32;
    public static final int MAX_NAME_LENGTH = 32;

    /** A hub: where it is and what tier. */
    public record Hub(String dimension, BlockPos pos, HubTier tier) {
        /** The same key as LinkedWorkbooks.key for the hub's level and position. */
        public String key() {
            return dimension + "@" + pos.asLong();
        }

        boolean is(String dimension, BlockPos pos) {
            return this.dimension.equals(dimension) && this.pos.equals(pos);
        }
    }

    private static final class Known {
        final List<Hub> hubs = new ArrayList<>();
        Hub active;
    }

    private static final Map<UUID, Known> PLAYERS = new HashMap<>();
    // Hub key to the name players gave it.
    private static final Map<String, String> NAMES = new HashMap<>();
    // Hub key to who owns it: the player who placed it (or, for an older hub, who opened it first).
    private static final Map<String, Owner> OWNERS = new HashMap<>();

    private record Owner(UUID id, String name) {
    }

    // Hub key to the players the owner lets use it (id to name, in the order added).
    private static final Map<String, Map<UUID, String>> MEMBERS = new HashMap<>();
    private static boolean loaded;

    private HubDirectory() {
    }

    /** Drops everything (a different server started); called by LogisticsHubs. */
    static void reset() {
        PLAYERS.clear();
        NAMES.clear();
        OWNERS.clear();
        MEMBERS.clear();
        loaded = false;
    }

    private static void ready(MinecraftServer server) {
        LogisticsHubs.LINKED.forServer(server);

        if (!loaded) {
            loaded = true;
            load();
        }
    }

    // ----- names -----

    /** The hub's name: the one players gave it, or its tier. */
    public static String name(Hub hub) {
        return name(hub.key(), hub.tier());
    }

    static String name(String key, HubTier tier) {
        String name = NAMES.get(key);
        return name != null ? name : tier.label();
    }

    /** A name as typed, cleaned up: no control characters, trimmed, at most MAX_NAME_LENGTH. */
    static String cleanName(String name) {
        String clean = name.replaceAll("[\\p{Cntrl}]", "").trim();
        return clean.length() > MAX_NAME_LENGTH ? clean.substring(0, MAX_NAME_LENGTH).trim() : clean;
    }

    // ----- a player's hubs -----

    /**
     * The player right-clicked (or linked) a hub: it joins their list and
     * becomes active. Null if they may not use it (see {@link #checkAccess}).
     */
    static Hub use(ServerPlayer player, ServerLevel level, BlockPos pos, HubTier tier) {
        ready(level.getServer());

        if (!mayUse(player, LinkedWorkbooks.key(level, pos))) {
            return null;
        }

        Known known = PLAYERS.computeIfAbsent(player.getUUID(), id -> new Known());
        String dimension = level.dimension().toString();
        Hub hub = new Hub(dimension, pos.immutable(), tier);

        known.hubs.removeIf(other -> other.is(dimension, pos));
        known.hubs.add(hub);

        while (known.hubs.size() > MAX_KNOWN) {
            known.hubs.remove(0);
        }

        known.active = hub;
        save();
        send(player);
        return hub;
    }

    /** The player's active hub (where new inventories go), or null. */
    static Hub active(ServerPlayer player) {
        ready(player.level().getServer());
        Known known = PLAYERS.get(player.getUUID());
        // Not one they lost access to.
        return known == null || known.active == null || !mayUse(player, known.active.key()) ? null : known.active;
    }

    /**
     * The level of a hub at pos that the player knows (they may open and save
     * it from anywhere), or null. The active hub wins if two share a position.
     */
    public static ServerLevel levelOfKnown(ServerPlayer player, BlockPos pos) {
        MinecraftServer server = player.level().getServer();
        ready(server);
        Known known = PLAYERS.get(player.getUUID());

        if (known == null) {
            return null;
        }

        Hub found = known.active != null && known.active.pos().equals(pos) ? known.active : null;

        for (Hub hub : known.hubs) {
            if (found == null && hub.pos().equals(pos)) {
                found = hub;
            }
        }

        return found == null || !mayUse(player, found.key()) ? null : LogisticsHubs.level(server, found.dimension());
    }

    private static Hub find(ServerPlayer player, String dimension, BlockPos pos) {
        Known known = PLAYERS.get(player.getUUID());

        if (known != null) {
            for (Hub hub : known.hubs) {
                if (hub.is(dimension, pos)) {
                    return hub;
                }
            }
        }

        return null;
    }

    // ----- what the player asks for in the workbook picker -----

    /** Makes one of the player's hubs the active one. */
    public static void choose(ServerPlayer player, String dimension, BlockPos pos) {
        ready(player.level().getServer());
        Hub hub = find(player, dimension, pos);

        if (hub != null && !mayUse(player, hub.key())) {
            LogisticsHubs.tell(player, accessProblem(hub.key(), name(hub)));
        } else if (hub != null) {
            PLAYERS.get(player.getUUID()).active = hub;
            save();
            LogisticsHubs.tell(player, "Inventories you sneak-right-click now join " + name(hub));
        }

        send(player);
    }

    /** Renames a hub the player knows (a blank name goes back to the tier's). */
    public static void rename(ServerPlayer player, String dimension, BlockPos pos, String name) {
        MinecraftServer server = player.level().getServer();
        ready(server);
        Hub hub = find(player, dimension, pos);

        if (hub == null) {
            send(player);
            return;
        }

        if (!mayRename(player, hub.key())) {
            LogisticsHubs.tell(player, "Only " + OWNERS.get(hub.key()).name() + " (who owns it) can rename " + name(hub));
            send(player);
            return;
        }

        String clean = cleanName(name);

        if (clean.isEmpty() || clean.equals(hub.tier().label())) {
            NAMES.remove(hub.key());
        } else {
            NAMES.put(hub.key(), clean);
        }

        save();
        LogisticsHubs.renamed(hub.key());

        // Everyone who knows the hub sees the new name.
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (find(other, dimension, pos) != null) {
                send(other);
            }
        }
    }

    /** Takes a hub off the player's list (the hub itself keeps working). */
    public static void forget(ServerPlayer player, String dimension, BlockPos pos) {
        ready(player.level().getServer());
        Known known = PLAYERS.get(player.getUUID());

        if (known != null && known.hubs.removeIf(hub -> hub.is(dimension, pos))) {
            if (known.active != null && known.active.is(dimension, pos)) {
                known.active = known.hubs.isEmpty() ? null : known.hubs.get(known.hubs.size() - 1);
            }

            save();
        }

        send(player);
    }

    /** A hub was upgraded in place: every list that has it gets its new tier. */
    static void retiered(MinecraftServer server, String dimension, BlockPos pos, HubTier tier) {
        ready(server);
        Hub upgraded = new Hub(dimension, pos.immutable(), tier);
        boolean changed = false;

        for (Map.Entry<UUID, Known> entry : PLAYERS.entrySet()) {
            Known known = entry.getValue();
            boolean listed = false;

            for (int i = 0; i < known.hubs.size(); i++) {
                if (known.hubs.get(i).is(dimension, pos)) {
                    known.hubs.set(i, upgraded);
                    listed = true;
                }
            }

            if (known.active != null && known.active.is(dimension, pos)) {
                known.active = upgraded;
                listed = true;
            }

            if (listed) {
                changed = true;
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());

                if (player != null) {
                    send(player);
                }
            }
        }

        if (changed) {
            save();
        }
    }

    /** A hub was broken: it leaves every list, and its name goes. */
    static void removed(MinecraftServer server, String dimension, BlockPos pos) {
        ready(server);
        String key = dimension + "@" + pos.asLong();
        boolean changed = NAMES.remove(key) != null;
        changed |= OWNERS.remove(key) != null;
        changed |= MEMBERS.remove(key) != null;

        for (Map.Entry<UUID, Known> entry : PLAYERS.entrySet()) {
            Known known = entry.getValue();

            if (known.hubs.removeIf(hub -> hub.is(dimension, pos))) {
                changed = true;

                if (known.active != null && known.active.is(dimension, pos)) {
                    known.active = null;
                }

                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());

                if (player != null) {
                    send(player);
                }
            }
        }

        if (changed) {
            save();
        }
    }

    /**
     * Sends the player their hubs (HUB_LIST): a count, then per hub its
     * position, dimension, name, tier name, whether it is active, its owner's
     * name, whether this player owns it (may rename it and manage members),
     * and its members (a count, then id and name each; only sent to the owner).
     * Hubs the player may no longer use are left out.
     */
    public static void send(ServerPlayer player) {
        ready(player.level().getServer());
        Known known = PLAYERS.get(player.getUUID());
        List<Hub> hubs = new ArrayList<>();

        if (known != null) {
            for (Hub hub : known.hubs) {
                if (mayUse(player, hub.key())) {
                    hubs.add(hub);
                }
            }
        }

        ShareProtocol.Writer message = new ShareProtocol.Writer(ShareProtocol.HUB_LIST).writeInt(hubs.size());

        for (Hub hub : hubs) {
            boolean owns = mayRename(player, hub.key());
            Map<UUID, String> members = owns ? MEMBERS.getOrDefault(hub.key(), Map.of()) : Map.of();
            message.writeLong(hub.pos().asLong())
                    .writeString(hub.dimension())
                    .writeString(name(hub))
                    .writeString(hub.tier().label())
                    .writeBoolean(hub == known.active)
                    .writeString(OWNERS.containsKey(hub.key()) ? OWNERS.get(hub.key()).name() : "")
                    .writeBoolean(owns)
                    .writeInt(members.size());

            for (Map.Entry<UUID, String> member : members.entrySet()) {
                message.writeUuid(member.getKey()).writeString(member.getValue());
            }
        }

        ShareNetwork.sendToClient.accept(player, message.toBytes());
    }

    // ----- owners -----

    /** A player placed a hub: they own it. */
    static void placed(ServerPlayer player, ServerLevel level, BlockPos pos) {
        ready(level.getServer());
        OWNERS.put(LinkedWorkbooks.key(level, pos), new Owner(player.getUUID(), player.getName().getString()));
        save();
    }

    /**
     * Whether the hub at pos may get its workbook now (the first time someone
     * opens it): its owner may not own more linked hubs than the server allows.
     * A hub nobody owns yet (placed by a machine, or before owners were kept)
     * becomes the player's.
     *
     * @return why not, or null
     */
    static String canCreate(ServerPlayer player, ServerLevel level, BlockPos pos) {
        ready(level.getServer());
        String key = LinkedWorkbooks.key(level, pos);
        Owner owner = OWNERS.get(key);
        UUID ownerId = owner != null ? owner.id() : player.getUUID();
        int linked = 0;

        for (Map.Entry<String, Owner> entry : OWNERS.entrySet()) {
            if (entry.getValue().id().equals(ownerId) && LogisticsHubs.LINKED.isLinked(entry.getKey())) {
                linked++;
            }
        }

        int max = io.github.reverendbrown.excelcomputer.ServerConfig.maxHubsPerPlayer;

        if (linked >= max) {
            return owner == null || owner.id().equals(player.getUUID())
                    ? "You already own " + linked + " Logistics Hubs with a workbook (the server allows " + max
                    + "). Break one you don't need"
                    : owner.name() + " already owns " + linked + " Logistics Hubs with a workbook (the server allows "
                    + max + ")";
        }

        if (owner == null) {
            OWNERS.put(key, new Owner(player.getUUID(), player.getName().getString()));
            save();
        }

        return null;
    }

    // Only the owner renames a hub (anyone may, if nobody owns it).
    private static boolean mayRename(ServerPlayer player, String key) {
        Owner owner = OWNERS.get(key);
        return owner == null || owner.id().equals(player.getUUID());
    }

    // ----- access -----

    /**
     * Whether the player may open, save and choose the hub with this key and
     * register inventories to it: its owner, a member, or anyone if nobody owns it.
     */
    public static boolean mayUse(ServerPlayer player, String key) {
        ready(player.level().getServer());
        Owner owner = OWNERS.get(key);

        if (owner == null || owner.id().equals(player.getUUID())) {
            return true;
        }

        Map<UUID, String> members = MEMBERS.get(key);
        return members != null && members.containsKey(player.getUUID());
    }

    /** What to tell a player who may not use the hub. */
    static String accessProblem(String key, String hubName) {
        Owner owner = OWNERS.get(key);
        String who = owner != null ? owner.name() : "Someone else";
        return who + " owns " + hubName + ". Ask them to add you in the workbook picker (Access)";
    }

    /** Why the player can't use the hub at pos, or null if they can (the hubs' LinkedWorkbooks access check). */
    public static String checkAccess(ServerPlayer player, ServerLevel level, BlockPos pos) {
        String key = LinkedWorkbooks.key(level, pos);

        if (mayUse(player, key)) {
            return null;
        }

        HubTier tier = level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof LogisticsHubBlock block
                ? block.tier() : HubTier.values()[0];
        return accessProblem(key, name(key, tier));
    }

    /** Whether the player may break the hub at pos: only its owner (anyone, if nobody owns it). */
    static boolean mayBreak(ServerPlayer player, ServerLevel level, BlockPos pos) {
        ready(level.getServer());
        return mayRename(player, LinkedWorkbooks.key(level, pos));
    }

    /** Who owns the hub at pos, or null. */
    static String ownerName(ServerLevel level, BlockPos pos) {
        ready(level.getServer());
        Owner owner = OWNERS.get(LinkedWorkbooks.key(level, pos));
        return owner == null ? null : owner.name();
    }

    /**
     * The owner lets an online player use one of their hubs. The hub joins the
     * new member's list (and becomes active if they had none), so they can
     * open it from anywhere.
     */
    public static void addMember(ServerPlayer player, String dimension, BlockPos pos, String playerName) {
        MinecraftServer server = player.level().getServer();
        ready(server);
        Hub hub = find(player, dimension, pos);
        String wanted = playerName.trim();

        if (hub == null || !isOwner(player, hub.key())) {
            reply(player, "Only the hub's owner can give access to it");
            send(player);
            return;
        }

        ServerPlayer member = wanted.isEmpty() ? null : server.getPlayerList().getPlayerByName(wanted);

        if (member == null) {
            reply(player, "No player called \"" + wanted + "\" is online");
            send(player);
            return;
        }

        if (member == player) {
            reply(player, "You already own " + name(hub));
            send(player);
            return;
        }

        Map<UUID, String> members = MEMBERS.computeIfAbsent(hub.key(), key -> new LinkedHashMap<>());

        if (!members.containsKey(member.getUUID()) && members.size() >= MAX_MEMBERS) {
            reply(player, name(hub) + " already has " + MAX_MEMBERS + " members");
            send(player);
            return;
        }

        String memberName = member.getName().getString();
        members.put(member.getUUID(), memberName);

        // The hub joins the member's list, so they can open it from anywhere.
        Known known = PLAYERS.computeIfAbsent(member.getUUID(), id -> new Known());

        if (find(member, dimension, pos) == null) {
            known.hubs.add(hub);

            while (known.hubs.size() > MAX_KNOWN) {
                known.hubs.remove(0);
            }
        }

        if (known.active == null) {
            known.active = hub;
        }

        save();
        reply(player, memberName + " can now use " + name(hub));
        LogisticsHubs.tell(member, player.getName().getString() + " gave you access to " + name(hub)
                + ". Open it from the workbook picker");
        send(player);
        send(member);
    }

    /** The owner takes a member's access away; the hub leaves that player's list. */
    public static void removeMember(ServerPlayer player, String dimension, BlockPos pos, UUID memberId) {
        MinecraftServer server = player.level().getServer();
        ready(server);
        Hub hub = find(player, dimension, pos);

        if (hub == null || !isOwner(player, hub.key())) {
            reply(player, "Only the hub's owner can take access away");
            send(player);
            return;
        }

        Map<UUID, String> members = MEMBERS.get(hub.key());
        String memberName = members == null ? null : members.remove(memberId);

        if (members != null && members.isEmpty()) {
            MEMBERS.remove(hub.key());
        }

        Known known = PLAYERS.get(memberId);

        if (known != null && known.hubs.removeIf(other -> other.is(dimension, pos))
                && known.active != null && known.active.is(dimension, pos)) {
            known.active = null;
        }

        save();

        if (memberName != null) {
            reply(player, memberName + " can no longer use " + name(hub));
        }

        send(player);
        ServerPlayer member = server.getPlayerList().getPlayer(memberId);

        if (member != null) {
            send(member);
        }
    }

    // Owner only: a hub nobody owns has nobody to manage its members yet.
    private static boolean isOwner(ServerPlayer player, String key) {
        Owner owner = OWNERS.get(key);
        return owner != null && owner.id().equals(player.getUUID());
    }

    // An answer to the Access panel: shown on the picker's status line (sent
    // before the HUB_LIST that refreshes it) and in the action bar.
    private static void reply(ServerPlayer player, String message) {
        io.github.reverendbrown.excelcomputer.share.ShareServer.notice(player, message);
        LogisticsHubs.tell(player, message);
    }

    // ----- files -----
    // known.txt: <player> TAB <dimension> TAB <x> TAB <y> TAB <z> TAB <tier> TAB <1 if active>
    // names.txt: <hub key> TAB <name>

    private static void load() {
        try {
            Path directory = LogisticsHubs.LINKED.directory();
            Path known = directory.resolve("known.txt");
            Path names = directory.resolve("names.txt");
            Path owners = directory.resolve("owners.txt");

            if (Files.isRegularFile(known)) {
                for (String line : Files.readAllLines(known, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t", -1);

                    if (parts.length != 7) {
                        continue;
                    }

                    try {
                        Hub hub = new Hub(parts[1], new BlockPos(Integer.parseInt(parts[2]),
                                Integer.parseInt(parts[3]), Integer.parseInt(parts[4])), HubTier.valueOf(parts[5]));
                        Known entry = PLAYERS.computeIfAbsent(UUID.fromString(parts[0]), id -> new Known());
                        entry.hubs.add(hub);

                        if (parts[6].equals("1")) {
                            entry.active = hub;
                        }
                    } catch (IllegalArgumentException exception) {
                        ExcelComputer.LOGGER.warn("Skipping a bad known Logistics Hub: {}", line);
                    }
                }
            }

            if (Files.isRegularFile(owners)) {
                for (String line : Files.readAllLines(owners, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t", -1);

                    try {
                        if (parts.length == 3) {
                            OWNERS.put(parts[0], new Owner(UUID.fromString(parts[1]), parts[2]));
                        }
                    } catch (IllegalArgumentException exception) {
                        ExcelComputer.LOGGER.warn("Skipping a bad Logistics Hub owner: {}", line);
                    }
                }
            }

            Path members = directory.resolve("members.txt");

            if (Files.isRegularFile(members)) {
                for (String line : Files.readAllLines(members, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t", -1);

                    try {
                        if (parts.length == 3) {
                            MEMBERS.computeIfAbsent(parts[0], key -> new LinkedHashMap<>())
                                    .put(UUID.fromString(parts[1]), parts[2]);
                        }
                    } catch (IllegalArgumentException exception) {
                        ExcelComputer.LOGGER.warn("Skipping a bad Logistics Hub member: {}", line);
                    }
                }
            }

            if (Files.isRegularFile(names)) {
                for (String line : Files.readAllLines(names, StandardCharsets.UTF_8)) {
                    int tab = line.indexOf('\t');

                    if (tab > 0) {
                        NAMES.put(line.substring(0, tab), line.substring(tab + 1));
                    }
                }
            }
        } catch (IOException exception) {
            ExcelComputer.LOGGER.warn("Could not read the known Logistics Hubs: {}", exception.toString());
        }
    }

    private static void save() {
        StringBuilder known = new StringBuilder();

        for (Map.Entry<UUID, Known> entry : PLAYERS.entrySet()) {
            for (Hub hub : entry.getValue().hubs) {
                known.append(entry.getKey()).append('\t')
                        .append(hub.dimension()).append('\t')
                        .append(hub.pos().getX()).append('\t')
                        .append(hub.pos().getY()).append('\t')
                        .append(hub.pos().getZ()).append('\t')
                        .append(hub.tier().name()).append('\t')
                        .append(hub == entry.getValue().active ? "1" : "0").append('\n');
            }
        }

        StringBuilder owners = new StringBuilder();

        for (Map.Entry<String, Owner> entry : OWNERS.entrySet()) {
            owners.append(entry.getKey()).append('\t').append(entry.getValue().id()).append('\t')
                    .append(entry.getValue().name().replaceAll("[\\t\\r\\n]", " ")).append('\n');
        }

        StringBuilder members = new StringBuilder();

        // members.txt: <hub key> TAB <player id> TAB <player name>
        for (Map.Entry<String, Map<UUID, String>> entry : MEMBERS.entrySet()) {
            for (Map.Entry<UUID, String> member : entry.getValue().entrySet()) {
                members.append(entry.getKey()).append('\t').append(member.getKey()).append('\t')
                        .append(member.getValue().replaceAll("[\\t\\r\\n]", " ")).append('\n');
            }
        }

        StringBuilder names = new StringBuilder();

        for (Map.Entry<String, String> entry : NAMES.entrySet()) {
            names.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
        }

        try {
            Path directory = LogisticsHubs.LINKED.directory();
            LinkedWorkbooks.writeAtomically(directory.resolve("known.txt"),
                    known.toString().getBytes(StandardCharsets.UTF_8));
            LinkedWorkbooks.writeAtomically(directory.resolve("names.txt"),
                    names.toString().getBytes(StandardCharsets.UTF_8));
            LinkedWorkbooks.writeAtomically(directory.resolve("owners.txt"),
                    owners.toString().getBytes(StandardCharsets.UTF_8));
            LinkedWorkbooks.writeAtomically(directory.resolve("members.txt"),
                    members.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            ExcelComputer.LOGGER.warn("Could not save the known Logistics Hubs: {}", exception.toString());
        }
    }
}
