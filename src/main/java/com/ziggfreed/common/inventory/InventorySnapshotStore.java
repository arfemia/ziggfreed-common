package com.ziggfreed.common.inventory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.io.FileUtil;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * A durable, crash-safe per-player store of full {@link InventorySnapshot}s for a minigame's
 * preserve/restore lifecycle - the persistence sibling of the snapshot primitive, and the twin of
 * {@code instance.reward.PendingRewardStore}. The consumer {@link #captureAndStrip}s a player's gear
 * on round entry (persisted to disk BEFORE the live inventory is touched, so a crash mid-strip never
 * eats gear) and {@link #restoreAndClear}s it on exit OR on the player's next login - so a server
 * crash, disconnect, or restart while a player is mid-round never loses their inventory.
 *
 * <p>File-backed JSON, atomic write; each {@link ItemStack} is persisted faithfully via its own
 * engine {@code CODEC} (id + quantity + durability + metadata). Thread-safe: the map is concurrent
 * and {@link #flush} is serialized, so concurrent per-player entries from different world threads
 * never lose or corrupt the file.
 */
public final class InventorySnapshotStore {

    private final String fileName;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ConcurrentHashMap<UUID, InventorySnapshot> snapshots = new ConcurrentHashMap<>();
    @Nullable private volatile Path file;

    /** Gson-friendly persisted shape of one captured section. */
    private static final class StoredSection {
        int sectionId;
        byte activeSlot;
        Map<String, String> slots; // slot index -> ItemStack codec JSON
    }

    private static final class Dto {
        Map<String, List<StoredSection>> players;
    }

    public InventorySnapshotStore(@Nonnull String name) {
        this.fileName = name.endsWith(".json") ? name : name + ".json";
    }

    /** Resolve the file under {@code dataDir} and load any snapshots left over from a crash/restart. */
    public void init(@Nullable Path dataDir) {
        if (dataDir == null) {
            return;
        }
        this.file = dataDir.resolve(fileName);
        load();
    }

    // ==================== high-level lifecycle (ordering-safe) ====================

    /**
     * Capture {@code uuid}'s full inventory, persist it to disk, THEN strip per {@code policy}. The
     * persist-before-strip order is the crash-safety invariant: if the process dies between the two,
     * the snapshot on disk still holds the full inventory and the next-login restore re-applies it.
     * World thread only.
     */
    public void captureAndStrip(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                @Nonnull UUID uuid, @Nonnull InventoryStripPolicy policy) {
        InventorySnapshot snap = InventorySnapshot.capture(store, ref);
        snapshots.put(uuid, snap);
        flush(); // persist BEFORE we mutate the live inventory
        InventorySnapshot.strip(store, ref, policy);
    }

    /**
     * If a snapshot is held for {@code uuid}, restore it onto {@code ref} (clearing any items gained
     * in-round) then drop it from the store. {@link InventorySnapshot#apply} clears-then-reapplies,
     * so a retry after a partial failure converges. The snapshot is dropped only AFTER a successful
     * apply, so a throw leaves it on disk for the next-login retry. World thread only.
     *
     * @return true if a snapshot was applied
     */
    public boolean restoreAndClear(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                   @Nonnull UUID uuid) {
        InventorySnapshot snap = snapshots.get(uuid);
        if (snap == null) {
            return false;
        }
        snap.apply(store, ref); // idempotent (clear-all then reapply)
        snapshots.remove(uuid);
        flush();
        return true;
    }

    /** Whether a snapshot is currently held for {@code uuid}. */
    public boolean has(@Nonnull UUID uuid) {
        return snapshots.containsKey(uuid);
    }

    // ==================== persistence ====================

    private void load() {
        Path f = file;
        if (f == null || !Files.exists(f)) {
            return;
        }
        try {
            Dto dto = gson.fromJson(Files.readString(f), Dto.class);
            snapshots.clear();
            if (dto != null && dto.players != null) {
                for (Map.Entry<String, List<StoredSection>> e : dto.players.entrySet()) {
                    if (e.getValue() == null) {
                        continue;
                    }
                    try {
                        snapshots.put(UUID.fromString(e.getKey()), fromStored(e.getValue()));
                    } catch (Throwable ignored) {
                        // skip a malformed uuid key
                    }
                }
            }
        } catch (Throwable t) {
            warn(fileName + " unreadable; starting empty: " + t.getMessage());
        }
    }

    private synchronized void flush() {
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            Dto dto = new Dto();
            dto.players = new HashMap<>();
            for (Map.Entry<UUID, InventorySnapshot> e : snapshots.entrySet()) {
                dto.players.put(e.getKey().toString(), toStored(e.getValue()));
            }
            if (f.getParent() != null) {
                Files.createDirectories(f.getParent());
            }
            FileUtil.writeStringAtomic(f, gson.toJson(dto), true);
        } catch (Throwable t) {
            warn(fileName + " flush failed: " + t.getMessage());
        }
    }

    @Nonnull
    private static List<StoredSection> toStored(@Nonnull InventorySnapshot snap) {
        List<StoredSection> out = new ArrayList<>(snap.sections().size());
        for (InventorySnapshot.SectionData sd : snap.sections()) {
            StoredSection ss = new StoredSection();
            ss.sectionId = sd.sectionId();
            ss.activeSlot = sd.activeSlot();
            ss.slots = new LinkedHashMap<>();
            for (Map.Entry<Short, ItemStack> slot : sd.slots().entrySet()) {
                String json = encodeStack(slot.getValue());
                if (json != null) {
                    ss.slots.put(Short.toString(slot.getKey()), json);
                }
            }
            out.add(ss);
        }
        return out;
    }

    @Nonnull
    private static InventorySnapshot fromStored(@Nonnull List<StoredSection> stored) {
        List<InventorySnapshot.SectionData> sections = new ArrayList<>(stored.size());
        for (StoredSection ss : stored) {
            Map<Short, ItemStack> slots = new LinkedHashMap<>();
            if (ss.slots != null) {
                for (Map.Entry<String, String> e : ss.slots.entrySet()) {
                    try {
                        ItemStack stack = decodeStack(e.getValue());
                        if (stack != null) {
                            slots.put(Short.parseShort(e.getKey()), stack);
                        }
                    } catch (Throwable ignored) {
                        // skip a malformed slot key / a now-removed item id
                    }
                }
            }
            sections.add(new InventorySnapshot.SectionData(ss.sectionId, ss.activeSlot, slots));
        }
        return new InventorySnapshot(sections);
    }

    @Nullable
    private static String encodeStack(@Nonnull ItemStack stack) {
        try {
            return ItemStack.CODEC.encode(stack).asDocument().toJson();
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static ItemStack decodeStack(@Nonnull String json) {
        try {
            return ItemStack.CODEC.decode(BsonDocument.parse(json));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void warn(@Nonnull String msg) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("%s", msg);
        } catch (Throwable ignored) {
            // a log-manager-less unit JVM must not crash on the logging facade itself
        }
    }
}
