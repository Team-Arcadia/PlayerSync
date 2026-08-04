package vip.fubuki.playersync.sync.addons;

import vip.fubuki.playersync.PlayerSync;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session ownership registry for external container storages
 * (Sophisticated Backpacks / Sophisticated Storage / Refined Storage 2 disks).
 *
 * <h2>Why this exists</h2>
 * All three mods keep the real contents of a carried container in a world-level
 * {@code SavedData} keyed by a storage UUID; the item the player holds is only a
 * reference. PlayerSync therefore has to mirror that SavedData entry through the
 * database on every server transfer.
 *
 * <p>The pre-2.1.6 implementation could not tell these two states apart:
 * <ul>
 *   <li><b>absent because empty</b> — the player legitimately emptied the container
 *       (or the mod pruned the entry at world load, which Sophisticated Backpacks
 *       does for any non-player entry with an empty item list), and</li>
 *   <li><b>absent because unknown</b> — this server has simply never loaded that
 *       container, so its local SavedData holds nothing meaningful.</li>
 * </ul>
 *
 * <p>Both surfaced as an empty {@code CompoundTag}, and the old guard skipped the
 * write whenever the database still held real data. That is exactly the reported
 * duplication (#238): a backpack emptied into a chest kept its old blob in the
 * database, and the next join re-injected the contents the player had just moved
 * out — the items then existed twice.
 *
 * <p>This registry records which storage UUIDs PlayerSync has actually established
 * for a player during the current session (restored from the database, or observed
 * as genuinely absent from the database). Only for those is an absent local entry a
 * trustworthy signal that the container is empty, in which case the save path writes
 * an explicit empty tombstone instead of skipping. Everything else is left untouched,
 * so a container this server never loaded can never overwrite fresher data.
 *
 * <p>All methods are safe to call from any thread; entries are dropped when the
 * player's session ends (see {@link #clearPlayer(String)}).
 */
public final class StorageOwnership {

    private StorageOwnership() {}

    /** playerUuid → storage UUIDs this session established. */
    private static final Map<String, Set<UUID>> OWNED = new ConcurrentHashMap<>();

    /** Marks a storage UUID as established for this player's current session. */
    public static void mark(String playerUuid, UUID storageUuid) {
        if (playerUuid == null || storageUuid == null) return;
        OWNED.computeIfAbsent(playerUuid, k -> ConcurrentHashMap.newKeySet()).add(storageUuid);
    }

    /** @return true if this session established the given storage UUID for the player. */
    public static boolean isOwned(String playerUuid, UUID storageUuid) {
        if (playerUuid == null || storageUuid == null) return false;
        Set<UUID> set = OWNED.get(playerUuid);
        return set != null && set.contains(storageUuid);
    }

    /** Drops every storage UUID recorded for the player. Called when the session ends. */
    public static void clearPlayer(String playerUuid) {
        if (playerUuid == null) return;
        Set<UUID> removed = OWNED.remove(playerUuid);
        if (removed != null && !removed.isEmpty()) {
            PlayerSync.LOGGER.debug("[storage-session] released {} storage handle(s) for {}", removed.size(), playerUuid);
        }
    }

    /** Read-only view used by the admin diagnostics command. */
    public static Set<UUID> view(String playerUuid) {
        Set<UUID> set = OWNED.get(playerUuid);
        return set == null ? Set.of() : Collections.unmodifiableSet(set);
    }

    /** Total number of tracked storage handles across all online players. */
    public static int trackedCount() {
        int n = 0;
        for (Set<UUID> s : OWNED.values()) n += s.size();
        return n;
    }

    /** Drops everything — used on server shutdown so nothing survives into a restart. */
    public static void clearAll() {
        OWNED.clear();
    }
}
