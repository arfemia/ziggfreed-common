package com.ziggfreed.common.npc.placement.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.universe.Universe;
import com.ziggfreed.common.util.RecordFile;
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
 * <p><b>The file.</b> Persisted to {@link #DEFAULT_FILE} through zc-core's {@code util/RecordFile}:
 * written whole and atomically on every real change (a change that changes nothing writes nothing),
 * the copy before it kept as {@code .bak}, a torn file read from that copy, so a cut-short write never
 * loads as an empty ledger. Reads are lock-free; writes are serialized and small.
 *
 * <p><b>What it keeps, and for how long.</b> One row per placement INSTANCE (a placement in one world
 * at one anchor), so it follows the live placements, never the spawns or the sweeps: a re-spawn or an
 * adoption re-points the row. A row is dropped when its placement is removed, gate-denied, or its world
 * is deleted ({@link NpcPlacementReconciler#onWorldRemoved}); a world merely removed (every world at a
 * server stop, one an admin unloads) keeps its rows, since it comes back with its NPCs. Two things keep
 * the file from outliving its worlds: every load compacts it (a row it cannot read, or a second spelling
 * of one key, is dropped and the file written again), and once the universe has loaded its worlds
 * ({@link #pruneVanishedWorlds}) every row naming a world that is neither loaded nor on disk is dropped:
 * an instance or portal world deleted while the server was down, or one a crash left for the next start
 * to delete. Such a world can never come back under its name, and its NPCs went with it, so no re-adopt
 * needs its rows. A world deleted this run is remembered, so a late sweep or spawn callback for it cannot
 * write its rows back ({@link #worldAdded} forgets that when a world of the name is added again).
 */
public final class NpcPlacementLedger {

    /** The ledger's file, beside the library's owner files. */
    public static final Path DEFAULT_FILE = Paths.get("mods", "ziggfreedcommon", "npc-placement-ledger.json");

    /** Joiner for the composite key; a NUL cannot appear in a world name, id, or anchor key. */
    private static final char KEY_SEP = '\0';

    /** How many deleted worlds this run remembers, so a late write for one cannot bring its rows back. */
    private static final int GONE_WORLDS_MAX = 256;

    private static final NpcPlacementLedger INSTANCE = new NpcPlacementLedger();

    @Nonnull
    public static NpcPlacementLedger getInstance() {
        return INSTANCE;
    }

    /** Gson root document; unknown fields are ignored so the shape stays additive. */
    private static final class Doc {
        Map<String, String> uuidByKey;
    }

    @Nullable
    private volatile Path dataPath = DEFAULT_FILE;

    private final Map<String, UUID> uuidByKey = new ConcurrentHashMap<>();

    /** Lower-cased names of the worlds deleted this run, oldest first; guarded by itself. */
    private final Set<String> goneWorlds = new LinkedHashSet<>();

    /** Rows the last read dropped or folded together, so the load writes the file again without them. */
    private int compacted;

    private final RecordFile file = RecordFile.builder("placement", "the placed NPCs", new RecordFile.Body() {
                @Override
                @Nonnull
                public JsonObject write() {
                    Doc doc = new Doc();
                    doc.uuidByKey = new TreeMap<>();
                    for (Map.Entry<String, UUID> e : uuidByKey.entrySet()) {
                        doc.uuidByKey.put(e.getKey(), e.getValue().toString());
                    }
                    return RecordFile.gson().toJsonTree(doc).getAsJsonObject();
                }

                @Override
                public void read(@Nonnull JsonObject saved, int version) {
                    readDoc(RecordFile.gson().fromJson(saved, Doc.class));
                }

                @Override
                public void forget() {
                    uuidByKey.clear();
                }
            })
            .lostWrite("a restart may place an NPC that already stands, or forget one taken out")
            .build();

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

    /**
     * (Re)read the ledger file and compact it. A missing file is an empty ledger; a torn one reads the
     * copy before it; a row that cannot be read, or a second spelling of a key already read, is dropped,
     * and the file is written again without it.
     */
    public void load() {
        synchronized (file) {
            compacted = 0;
            synchronized (goneWorlds) {
                goneWorlds.clear(); // a reload is a restart's first step: this run has deleted nothing yet
            }
            file.persistTo(dataPath, null);
            if (file.file() == null) {
                return;
            }
            SafeLog.info("[placement] ledger loaded: " + uuidByKey.size() + " placed instance(s)");
            if (compacted > 0) {
                SafeLog.info("[placement] the ledger dropped " + compacted
                        + " row(s) it could not read or held twice, and is written again without them");
                file.writeNow();
            }
        }
    }

    /** Fill memory from a read file, folding every key to the spelling {@link #key} writes. */
    private void readDoc(@Nullable Doc doc) {
        uuidByKey.clear();
        if (doc == null || doc.uuidByKey == null) {
            return;
        }
        for (Map.Entry<String, String> e : doc.uuidByKey.entrySet()) {
            String folded = foldedKey(e.getKey());
            UUID uuid = uuidOrNull(e.getValue());
            if (folded == null || uuid == null) {
                compacted++;
                continue;
            }
            if (uuidByKey.putIfAbsent(folded, uuid) != null || !folded.equals(e.getKey())) {
                compacted++;
            }
        }
    }

    /** {@code raw} as {@link #key} spells it, or null for a key without its three non-blank parts. */
    @Nullable
    static String foldedKey(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(String.valueOf(KEY_SEP), -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            return null;
        }
        return key(parts[0], parts[1], parts[2]);
    }

    @Nullable
    private static UUID uuidOrNull(@Nullable String text) {
        try {
            return text == null ? null : UUID.fromString(text.trim());
        } catch (IllegalArgumentException e) {
            return null;
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

    /**
     * Record (or re-point) this instance's NPC uuid and persist. A row already naming {@code uuid} writes
     * nothing; a world deleted this run takes no row (a late sweep or spawn callback for it).
     */
    public void record(@Nonnull String worldName, @Nonnull String placementId, @Nonnull String anchorKey,
            @Nonnull UUID uuid) {
        if (isGone(worldName)) {
            SafeLog.fine("[placement] not recording '" + placementId + "' in '" + worldName
                    + "': that world was deleted");
            return;
        }
        if (!uuid.equals(uuidByKey.put(key(worldName, placementId, anchorKey), uuid))) {
            file.writeNow();
        }
    }

    /** Drop this instance's row and persist. Returns true when a row was actually removed. */
    public boolean drop(@Nonnull String worldName, @Nonnull String placementId, @Nonnull String anchorKey) {
        if (uuidByKey.remove(key(worldName, placementId, anchorKey)) != null) {
            file.writeNow();
            return true;
        }
        return false;
    }

    /** Drop every row whose uuid is {@code uuid} (the despawn path, which knows the entity not the key). */
    public boolean dropByUuid(@Nonnull UUID uuid) {
        boolean removed = uuidByKey.entrySet().removeIf(e -> uuid.equals(e.getValue()));
        if (removed) {
            file.writeNow();
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

    /**
     * Drop every row for a world that was deleted, never one merely removed
     * ({@link NpcPlacementReconciler#onWorldRemoved}): a deleted world never comes back under its name.
     * The world is remembered for the rest of the run, so nothing writes its rows back.
     */
    public void dropWorld(@Nonnull String worldName) {
        markGone(worldName);
        String prefix = worldName.toLowerCase(Locale.ROOT) + KEY_SEP;
        if (uuidByKey.keySet().removeIf(k -> k.startsWith(prefix))) {
            file.writeNow();
        }
    }

    /** A world of this name was added: it takes rows again, even if one of its name was deleted earlier this run. */
    public void worldAdded(@Nonnull String worldName) {
        synchronized (goneWorlds) {
            goneWorlds.remove(worldName.toLowerCase(Locale.ROOT));
        }
    }

    // ==================== pruning ====================

    /**
     * Drop every row whose world {@code worldStillThere} denies (it is given the lower-cased world name)
     * and persist once. Returns how many rows went. The pure half of {@link #pruneVanishedWorlds}.
     */
    public int pruneWorlds(@Nonnull Predicate<String> worldStillThere) {
        Set<String> vanished = new HashSet<>();
        int removed = 0;
        for (Iterator<String> it = uuidByKey.keySet().iterator(); it.hasNext(); ) {
            String k = it.next();
            int sep = k.indexOf(KEY_SEP);
            String world = sep < 0 ? k : k.substring(0, sep);
            if (!worldStillThere.test(world)) {
                it.remove();
                vanished.add(world);
                removed++;
            }
        }
        if (removed > 0) {
            SafeLog.info("[placement] the ledger dropped " + removed + " row(s) naming " + vanished.size()
                    + " world(s) that no longer exist (deleted while the server was down, or left by a crash"
                    + " for this start to delete); " + uuidByKey.size() + " placed instance(s) remain");
            file.writeNow();
        }
        return removed;
    }

    /**
     * Once the universe has loaded its worlds (its {@code AllWorldsLoadedEvent}, when the start has also
     * deleted every world flagged to go at a start): drop every row naming a world that is neither loaded
     * nor a folder in the universe's worlds folder. Prunes nothing when it cannot tell (no universe, a
     * worlds folder it cannot list, no world at all). Never throws.
     */
    public int pruneVanishedWorlds() {
        Set<String> live = liveWorldNames();
        if (live == null) {
            SafeLog.fine("[placement] cannot tell which worlds exist, so the ledger keeps every row");
            return 0;
        }
        return pruneWorlds(live::contains);
    }

    /** Every world loaded or on disk, lower-cased; null when that cannot be told. */
    @Nullable
    private static Set<String> liveWorldNames() {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return null;
            }
            Set<String> live = new HashSet<>();
            for (String name : universe.getWorlds().keySet()) {
                live.add(name.toLowerCase(Locale.ROOT));
            }
            Path worlds = universe.getWorldsPath();
            if (worlds == null || !Files.isDirectory(worlds)) {
                return null;
            }
            try (Stream<Path> folders = Files.list(worlds)) {
                folders.filter(Files::isDirectory)
                        .forEach(folder -> live.add(folder.getFileName().toString().toLowerCase(Locale.ROOT)));
            }
            return live.isEmpty() ? null : live;
        } catch (Throwable t) {
            SafeLog.fine("[placement] could not list the worlds: " + t.getMessage());
            return null;
        }
    }

    private boolean isGone(@Nonnull String worldName) {
        synchronized (goneWorlds) {
            return goneWorlds.contains(worldName.toLowerCase(Locale.ROOT));
        }
    }

    private void markGone(@Nonnull String worldName) {
        synchronized (goneWorlds) {
            goneWorlds.add(worldName.toLowerCase(Locale.ROOT));
            while (goneWorlds.size() > GONE_WORLDS_MAX) {
                Iterator<String> oldest = goneWorlds.iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    /** How many rows exist in total (diagnostics, tests). */
    public int size() {
        return uuidByKey.size();
    }

    /** Drop everything in memory without touching the file (tests). */
    void clearForTests() {
        uuidByKey.clear();
        synchronized (goneWorlds) {
            goneWorlds.clear();
        }
        file.clean();
    }

    /** One recorded placement instance. */
    public record Row(@Nonnull String worldName, @Nonnull String placementId,
                      @Nonnull String anchorKey, @Nonnull UUID uuid) {
    }
}
