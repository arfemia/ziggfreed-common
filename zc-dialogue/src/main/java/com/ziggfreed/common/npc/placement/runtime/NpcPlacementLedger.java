package com.ziggfreed.common.npc.placement.runtime;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ziggfreed.common.util.SafeLog;

/**
 * The persisted record of what has ALREADY been placed: {@code (world, placementId, anchorKey)}
 * to the spawned NPC's uuid.
 *
 * <p><b>This ledger is the PLACE authority, and that is the single most important rule in this
 * package.</b> A chunk unload REMOVES an entity from the store and restores it when the chunk
 * ticks again, so a sweep over resident entities cannot tell "never placed" from "placed, chunk
 * asleep". Placing on absence therefore spawns a second NPC every time a player walks back into
 * range, which duplicates every placement in the world over an afternoon. A row here survives the
 * chunk sleeping AND the server restarting, so it is the only thing that can answer "has this
 * instance been placed" truthfully.
 *
 * <p>The mirror-image rule lives on {@link PlacedNpcComponent}: the component is the DESPAWN
 * authority. Neither can do the other's job, which is why there are two.
 *
 * <p>Persisted to {@code mods/ziggfreedcommon/npc-placement-ledger.json}. Reads are lock-free;
 * writes are synchronized and small. A row is dropped when its placement is removed, gate-denied,
 * or its world is gone.
 */
public final class NpcPlacementLedger {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Joiner for the composite key; a NUL cannot appear in a world name, id, or anchor key. */
    private static final char KEY_SEP = '\0';

    private static final NpcPlacementLedger INSTANCE = new NpcPlacementLedger();

    @Nonnull
    public static NpcPlacementLedger getInstance() {
        return INSTANCE;
    }

    /** Gson root document; unknown fields are ignored so the shape stays additive. */
    private static final class Doc {
        Map<String, String> uuidByKey = new HashMap<>();
    }

    @Nullable
    private volatile Path dataPath = Paths.get("mods", "ziggfreedcommon", "npc-placement-ledger.json");

    private final Map<String, UUID> uuidByKey = new ConcurrentHashMap<>();

    private NpcPlacementLedger() {
    }

    /** Point the ledger at a different file and reload (tests, or a consumer with its own data dir). */
    public void setFile(@Nullable Path path) {
        this.dataPath = path;
        load();
    }

    // ==================== keys ====================

    /**
     * The composite row key. Its three parts are exactly what makes a placement INSTANCE unique:
     * two concurrent instances of one dungeon share a placement id but never a world, and two
     * anchors of one placement share a world but never an anchor key.
     */
    @Nonnull
    public static String key(@Nonnull String worldName, @Nonnull String placementId, @Nonnull String anchorKey) {
        return worldName.toLowerCase(Locale.ROOT) + KEY_SEP
                + placementId.toLowerCase(Locale.ROOT) + KEY_SEP
                + anchorKey.toLowerCase(Locale.ROOT);
    }

    // ==================== io ====================

    /** (Re)read the ledger file. A missing file is an empty ledger; a malformed one warns and clears. */
    public void load() {
        uuidByKey.clear();
        Path path = dataPath;
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            Doc doc = GSON.fromJson(reader, Doc.class);
            if (doc == null || doc.uuidByKey == null) {
                return;
            }
            for (Map.Entry<String, String> e : doc.uuidByKey.entrySet()) {
                try {
                    uuidByKey.put(e.getKey(), UUID.fromString(e.getValue()));
                } catch (IllegalArgumentException ignored) {
                    // Skip a malformed uuid rather than failing the whole load.
                }
            }
            SafeLog.info("[placement] ledger loaded: " + uuidByKey.size() + " placed instance(s)");
        } catch (Exception e) {
            SafeLog.warn("[placement] could not read " + path + ": " + e.getMessage());
        }
    }

    private synchronized void save() {
        Path path = dataPath;
        if (path == null) {
            return;
        }
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Doc doc = new Doc();
            doc.uuidByKey = new LinkedHashMap<>();
            for (Map.Entry<String, UUID> e : uuidByKey.entrySet()) {
                doc.uuidByKey.put(e.getKey(), e.getValue().toString());
            }
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(doc, writer);
            }
        } catch (Exception e) {
            SafeLog.warn("[placement] could not write " + path + ": " + e.getMessage());
        }
    }

    // ==================== rows ====================

    /** Has this instance already been placed? The one question the place rule must ask. */
    public boolean hasRow(@Nonnull String worldName, @Nonnull String placementId, @Nonnull String anchorKey) {
        return uuidByKey.containsKey(key(worldName, placementId, anchorKey));
    }

    /** The recorded NPC uuid for this instance, or {@code null} when it was never placed. */
    @Nullable
    public UUID uuidOf(@Nonnull String worldName, @Nonnull String placementId, @Nonnull String anchorKey) {
        return uuidByKey.get(key(worldName, placementId, anchorKey));
    }

    /** Record (or re-point) this instance's NPC uuid and persist. */
    public void record(@Nonnull String worldName, @Nonnull String placementId, @Nonnull String anchorKey,
            @Nonnull UUID uuid) {
        uuidByKey.put(key(worldName, placementId, anchorKey), uuid);
        save();
    }

    /** Drop this instance's row and persist. Returns true when a row was actually removed. */
    public boolean drop(@Nonnull String worldName, @Nonnull String placementId, @Nonnull String anchorKey) {
        if (uuidByKey.remove(key(worldName, placementId, anchorKey)) != null) {
            save();
            return true;
        }
        return false;
    }

    /** Drop every row whose uuid is {@code uuid} (the despawn path, which knows the entity not the key). */
    public boolean dropByUuid(@Nonnull UUID uuid) {
        boolean removed = uuidByKey.entrySet().removeIf(e -> uuid.equals(e.getValue()));
        if (removed) {
            save();
        }
        return removed;
    }

    /**
     * How many instances of {@code placementId} are recorded in {@code worldName}. This is what
     * {@code Limits.MaxPerWorld} counts, and it counts ACROSS anchor groups by construction
     * because every group's instances share the placement id.
     */
    public int countInWorld(@Nonnull String worldName, @Nonnull String placementId) {
        String prefix = worldName.toLowerCase(Locale.ROOT) + KEY_SEP + placementId.toLowerCase(Locale.ROOT) + KEY_SEP;
        int count = 0;
        for (String k : uuidByKey.keySet()) {
            if (k.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    /** Every recorded row for a world, as {@code (placementId, anchorKey, uuid)} triples. */
    @Nonnull
    public List<Row> rowsInWorld(@Nonnull String worldName) {
        String prefix = worldName.toLowerCase(Locale.ROOT) + KEY_SEP;
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, UUID> e : uuidByKey.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            String[] parts = e.getKey().split(String.valueOf(KEY_SEP), -1);
            if (parts.length == 3) {
                out.add(new Row(parts[0], parts[1], parts[2], e.getValue()));
            }
        }
        return out;
    }

    /** Drop every row for a world (the world was deleted; an instance world is never coming back). */
    public void dropWorld(@Nonnull String worldName) {
        String prefix = worldName.toLowerCase(Locale.ROOT) + KEY_SEP;
        if (uuidByKey.keySet().removeIf(k -> k.startsWith(prefix))) {
            save();
        }
    }

    /** How many rows exist in total (diagnostics, tests). */
    public int size() {
        return uuidByKey.size();
    }

    /** Drop everything in memory without touching the file (tests). */
    void clearForTests() {
        uuidByKey.clear();
    }

    /** One recorded placement instance. */
    public record Row(@Nonnull String worldName, @Nonnull String placementId,
                      @Nonnull String anchorKey, @Nonnull UUID uuid) {
    }
}
