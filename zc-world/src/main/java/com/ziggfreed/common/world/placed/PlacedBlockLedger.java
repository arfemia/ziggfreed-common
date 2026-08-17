package com.ziggfreed.common.world.placed;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a player put there themselves - the record that stops "place it, break it, place it again"
 * from paying out. Block positions are scoped by world, and placed ITEM ids are counted separately
 * so a placed sapling cannot be harvested straight back for credit.
 *
 * <p><b>Any mod counting block breaks or pickups wants this, which is why it lives here.</b> A
 * reward for breaking a block is a reward for finding one, and a block the breaker set down a
 * moment ago was never found. The ledger answers that one question and holds no opinion about what
 * the answer is worth, so a quest producer, an XP engine and a statistics counter can all ask it
 * and can never disagree about whether a given block was placed.
 *
 * <p><b>The library default is STRICT: nobody earns from a placement, whoever breaks it.</b> That
 * is the right answer on a server where players would otherwise hand each other ore rather than
 * mine it. Setting {@link Policy#strict()} false narrows the refusal to the placer alone, which is
 * the fairer reading in a shared world: one player's building work then stops poisoning every block
 * their neighbours mine, at the cost of a two-player version of the exploit.
 *
 * <p><b>One native event has SEVERAL readers.</b> A single break is seen by the library's own
 * producer and by every consumer's own event system in the same tick, in an order nobody
 * specifies, and every one of them has to be told the same thing while the moment costs the ledger
 * exactly once. Blocks get that for free, because a position IS the moment: a consumed row keeps
 * answering "placed" for {@link #READ_GRACE_MS} before it is dropped, which is far longer than one
 * tick and far shorter than any human place-then-break cycle. Placed ITEMS are counted rather than
 * positioned, so several copies of one id share a row and the moment has to be named: a reader
 * passes a {@code momentKey} that is stable for the one event it is handling (the picked-up stack's
 * own identity does it), and a second reader arriving with the SAME key inside the grace window is
 * told the same answer without a second copy being spent.
 *
 * <p>Persisted to {@code mods/ziggfreedcommon/placed-blocks.json}. Blocks are saved; placed items
 * are not, because they are forgotten in minutes and a restart takes longer than that. Reads are
 * lock-free: a position index sits beside the per-player sets, so a break costs one hash probe
 * rather than a scan of every player's placements.
 */
public final class PlacedBlockLedger {

    /**
     * How long a consumed row keeps answering "placed", so every reader of ONE native event
     * agrees. See the class javadoc.
     */
    public static final long READ_GRACE_MS = 1_000L;

    /** How rarely housekeeping runs off ordinary placement traffic. */
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** On-disk shape: placer uuid to a list of {worldUuid, x, y, z, placedTime} rows. */
    private static final Type SAVE_TYPE = new TypeToken<Map<String, List<SavedBlock>>>() {}.getType();

    private static final PlacedBlockLedger INSTANCE = new PlacedBlockLedger();

    @Nonnull
    public static PlacedBlockLedger getInstance() {
        return INSTANCE;
    }

    /**
     * How strict the ledger is and how long it remembers. Four independent knobs, read LIVE, so a
     * consumer whose owner config already carries some of them installs a policy that reads its
     * own values and never has to push an update when that config reloads.
     */
    public interface Policy {

        /**
         * The library's own answer: remember placements, let nobody earn from any of them, and
         * forget a placed item after five minutes.
         */
        Policy DEFAULT = new Policy() {
        };

        /** Record placements at all. False makes every ask answer "not placed". */
        default boolean enabled() {
            return true;
        }

        /** True: no player earns from ANY placement. False: only the placer is refused their own. */
        default boolean strict() {
            return true;
        }

        /** Minutes before a placed BLOCK is forgotten; {@code 0} remembers it for good. */
        default int blockExpireMinutes() {
            return 0;
        }

        /** Minutes before a placed ITEM is forgotten. Always finite - a dropped item does not last. */
        default int itemExpireMinutes() {
            return 5;
        }
    }

    /** A block position, scoped to a world so two worlds' coordinates never collide. */
    public record BlockPosition(@Nonnull String worldUuid, int x, int y, int z) {
    }

    /** One remembered placement, in its placer's own set. */
    private record TrackedBlock(@Nonnull BlockPosition position, long placedTime) {
    }

    /**
     * The position index's value: who placed it, when, and when a reader first consumed it
     * ({@code 0} while nobody has). Kept in lockstep with {@link #playerPlacedBlocks}.
     */
    private record IndexEntry(@Nonnull UUID placer, long placedTime, long consumedAt) {

        @Nonnull
        IndexEntry consumedAt(long now) {
            return new IndexEntry(placer, placedTime, now);
        }
    }

    /** A placed item id and how many of it are still owed an answer. */
    private static final class TrackedItem {

        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long lastPlacedTime = System.currentTimeMillis();
        private volatile long lastConsumedAt;
        private volatile long lastMomentKey;

        void increment() {
            count.incrementAndGet();
            lastPlacedTime = System.currentTimeMillis();
        }
    }

    /** Placer uuid to the placements they are remembered for. */
    private final Map<UUID, Set<TrackedBlock>> playerPlacedBlocks = new ConcurrentHashMap<>();

    /** Position to placer plus timestamps. The O(1) side of every ask; always in step with the sets. */
    private final Map<BlockPosition, IndexEntry> placedByPosition = new ConcurrentHashMap<>();

    /** Placer uuid to item id to how many of that item they put down. */
    private final Map<UUID, Map<String, TrackedItem>> playerPlacedItems = new ConcurrentHashMap<>();

    @Nonnull
    private volatile Policy policy = Policy.DEFAULT;

    /** Who installed the policy in force, or null while the library's own default is. */
    @Nullable
    private volatile String policyOwner;

    /** When housekeeping last ran, so a long session reclaims spent rows without a scheduler. */
    private volatile long lastCleanup = System.currentTimeMillis();

    @Nullable
    private volatile Path dataPath = Paths.get("mods", "ziggfreedcommon", "placed-blocks.json");

    private PlacedBlockLedger() {
    }

    /**
     * Install the policy the ledger reads, naming who installed it. It is read live on every ask,
     * so a consumer wiring its own owner config in here never has to re-push after a reload.
     *
     * <p>ONE slot, like every other one-slot part in this library: a SECOND owner arriving is
     * reported at SEVERE naming both, because two mods each answering "how strict is this server"
     * is a disagreement nobody can see from in game. The later one still wins, since refusing it
     * would leave the ledger answering with a config its owner has already stopped honouring.
     */
    public void setPolicy(@Nonnull String owner, @Nullable Policy value) {
        String previous = policyOwner;
        if (value != null && previous != null && !previous.equals(owner)) {
            SafeLog.severe("[placed] two mods installed a placed-block policy: '" + previous
                    + "' then '" + owner + "'. The later one is in force; they cannot both be.");
        }
        this.policy = value == null ? Policy.DEFAULT : value;
        this.policyOwner = value == null ? null : owner;
    }

    /** The policy in force. */
    @Nonnull
    public Policy policy() {
        return policy;
    }

    // ==================== placements ====================

    /** Remember that {@code placerUuid} put a block down at this position. */
    public void trackPlacement(@Nonnull UUID placerUuid, @Nonnull UUID worldUuid, int x, int y, int z) {
        if (!policy.enabled()) {
            return;
        }
        maybeCleanup();
        BlockPosition pos = new BlockPosition(worldUuid.toString(), x, y, z);
        long now = System.currentTimeMillis();
        playerPlacedBlocks
                .computeIfAbsent(placerUuid, key -> ConcurrentHashMap.newKeySet())
                .add(new TrackedBlock(pos, now));

        // A fresh placement always wins the position, so the row left behind by whoever filled this
        // spot before is dropped from THEIR set too, rather than left dangling. That includes the
        // same player re-filling a spot they had already used: the older row is keyed by its own
        // timestamp, so leaving it would double-count the position in the set and in the file, and
        // a restart could hand the position back the OLDER timestamp and expire the guard early.
        IndexEntry previous = placedByPosition.put(pos, new IndexEntry(placerUuid, now, 0L));
        if (previous != null && !(previous.placer().equals(placerUuid) && previous.placedTime() == now)) {
            forgetFromPlacerSet(pos, previous);
        }
    }

    /**
     * Did {@code breakerUuid} just break something that had been placed? A true answer consumes
     * the row, which then keeps answering true for {@link #READ_GRACE_MS} so every system reading
     * the same break agrees, and is dropped after that. This is the question a break-time handler
     * asks.
     */
    public boolean consumePlacement(@Nonnull UUID breakerUuid, @Nonnull UUID worldUuid, int x, int y, int z) {
        if (!policy.enabled()) {
            return false;
        }
        BlockPosition pos = new BlockPosition(worldUuid.toString(), x, y, z);
        IndexEntry entry = placedByPosition.get(pos);
        if (entry == null || !answersFor(entry, breakerUuid)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (entry.consumedAt() != 0L) {
            if (now - entry.consumedAt() <= READ_GRACE_MS) {
                return true;
            }
            drop(pos, entry);
            return false;
        }
        if (blockExpired(entry, now)) {
            drop(pos, entry);
            return false;
        }
        placedByPosition.replace(pos, entry, entry.consumedAt(now));
        return true;
    }

    /**
     * The same question WITHOUT consuming anything: the read for a caller that is only LOOKING.
     *
     * <p>It exists because the consuming read cannot be used to observe this ledger - asking spends
     * the row - so anything that wants to know the state without changing it needs its own door.
     * Today that is `PlacedBlockLedgerTest`, which pins the expiry and grace rules by watching a
     * row it must not spend; a break-time handler always wants {@link #consumePlacement}.
     */
    public boolean isPlaced(@Nonnull UUID breakerUuid, @Nonnull UUID worldUuid, int x, int y, int z) {
        if (!policy.enabled()) {
            return false;
        }
        BlockPosition pos = new BlockPosition(worldUuid.toString(), x, y, z);
        IndexEntry entry = placedByPosition.get(pos);
        if (entry == null || !answersFor(entry, breakerUuid)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (entry.consumedAt() != 0L) {
            return now - entry.consumedAt() <= READ_GRACE_MS;
        }
        return !blockExpired(entry, now);
    }

    /** Forget everything remembered about one player. */
    public void clearPlayer(@Nonnull UUID playerUuid) {
        Set<TrackedBlock> placements = playerPlacedBlocks.remove(playerUuid);
        if (placements != null) {
            for (TrackedBlock placement : placements) {
                dropIfStillOwnedBy(placement, playerUuid);
            }
        }
        playerPlacedItems.remove(playerUuid);
    }

    // ==================== placed items ====================

    /** Remember that {@code placerUuid} put one of {@code itemId} down. */
    public void trackPlacedItem(@Nonnull UUID placerUuid, @Nonnull String itemId) {
        if (!policy.enabled()) {
            return;
        }
        playerPlacedItems
                .computeIfAbsent(placerUuid, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(itemId, key -> new TrackedItem())
                .increment();
    }

    /**
     * Did this player just pick up something they had put down? A true answer spends exactly one of
     * the remembered copies, however many systems handle that one pickup.
     *
     * <p>{@code momentKey} is what makes "one pickup" mean one: pass anything stable and unique to
     * the event being handled and shared by every reader of it (the picked-up stack's own object
     * identity is the obvious one). A second reader arriving with the same key inside {@link
     * #READ_GRACE_MS} is told the same answer and spends nothing, so N placed copies buy N
     * refusals rather than N divided by however many mods happen to be installed. Pass {@code 0}
     * when there is genuinely one reader and every call is its own moment.
     *
     * <p>Always keyed to the picker themselves: an item lying on the ground carries no memory of
     * who dropped it, so there is no honest strict reading of this half.
     */
    public boolean consumePlacedItem(@Nonnull UUID playerUuid, @Nonnull String itemId, long momentKey) {
        if (!policy.enabled()) {
            return false;
        }
        Map<String, TrackedItem> items = playerPlacedItems.get(playerUuid);
        if (items == null) {
            return false;
        }
        TrackedItem tracked = items.get(itemId);
        if (tracked == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (momentKey != 0L && tracked.lastMomentKey == momentKey
                && now - tracked.lastConsumedAt <= READ_GRACE_MS) {
            // Another reader of the pickup a copy has already been spent on: same answer, same copy.
            return true;
        }
        if (tracked.count.get() <= 0) {
            items.remove(itemId);
            return false;
        }
        if (itemExpired(tracked, now)) {
            items.remove(itemId);
            return false;
        }
        tracked.lastConsumedAt = now;
        tracked.lastMomentKey = momentKey;
        tracked.count.decrementAndGet();
        return true;
    }

    // ==================== housekeeping ====================

    /** Drop everything that has aged out or has already been answered for. Cheap; call it periodically. */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        int blockExpireMinutes = policy.blockExpireMinutes();
        long blockExpireMillis = blockExpireMinutes * 60_000L;

        for (Map.Entry<UUID, Set<TrackedBlock>> entry : playerPlacedBlocks.entrySet()) {
            UUID placer = entry.getKey();
            entry.getValue().removeIf(placement -> {
                boolean aged = blockExpireMinutes > 0 && (now - placement.placedTime()) >= blockExpireMillis;
                IndexEntry indexed = placedByPosition.get(placement.position());
                boolean spent = indexed != null
                        && indexed.placer().equals(placer)
                        && indexed.consumedAt() != 0L
                        && (now - indexed.consumedAt()) > READ_GRACE_MS;
                if (aged || spent) {
                    dropIfStillOwnedBy(placement, placer);
                    return true;
                }
                return false;
            });
        }
        playerPlacedBlocks.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        long itemExpireMillis = Math.max(1, policy.itemExpireMinutes()) * 60_000L;
        for (Map<String, TrackedItem> items : playerPlacedItems.values()) {
            items.entrySet().removeIf(entry -> {
                TrackedItem tracked = entry.getValue();
                boolean spent = tracked.count.get() <= 0
                        && (tracked.lastConsumedAt == 0L || now - tracked.lastConsumedAt > READ_GRACE_MS);
                return spent || (now - tracked.lastPlacedTime) >= itemExpireMillis;
            });
        }
        playerPlacedItems.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /**
     * Housekeeping on a slow beat, driven by ordinary traffic rather than by a scheduler. Every
     * placement is a chance to reclaim rows that were spent or aged out minutes ago, so a
     * long-running server does not hold every placement it ever saw until shutdown.
     */
    private void maybeCleanup() {
        long now = System.currentTimeMillis();
        if (now - lastCleanup < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanup = now;
        cleanupExpired();
    }

    /** How many placements are remembered right now. */
    public int trackedBlockCount() {
        return playerPlacedBlocks.values().stream().mapToInt(Set::size).sum();
    }

    /** How many placed items are still owed an answer. */
    public int trackedItemCount() {
        return playerPlacedItems.values().stream()
                .mapToInt(items -> items.values().stream()
                        .mapToInt(item -> Math.max(0, item.count.get()))
                        .sum())
                .sum();
    }

    /** How many players the ledger remembers anything about. */
    public int trackedPlayerCount() {
        Set<UUID> everyone = ConcurrentHashMap.newKeySet();
        everyone.addAll(playerPlacedBlocks.keySet());
        everyone.addAll(playerPlacedItems.keySet());
        return everyone.size();
    }

    // ==================== persistence ====================

    /** One saved placement. Gson reads and writes this shape directly. */
    private static final class SavedBlock {

        String worldUuid;
        int x;
        int y;
        int z;
        long placedTime;

        SavedBlock() {
        }

        SavedBlock(String worldUuid, int x, int y, int z, long placedTime) {
            this.worldUuid = worldUuid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.placedTime = placedTime;
        }
    }

    /** Point the ledger at a different file (a test's temp dir, or a consumer with its own data dir). */
    public void setFile(@Nullable Path path) {
        this.dataPath = path;
    }

    /**
     * Where the ledger writes today, or null once somebody has pointed it at nothing. Read it before
     * redirecting the ledger so the original can be put back afterwards; a null path makes
     * {@link #load()} and {@link #save()} no-ops, which is exactly what a test must not leave behind.
     */
    @Nullable
    public Path file() {
        return dataPath;
    }

    /**
     * Read the saved placements back, every row as it was written.
     *
     * <p>Nothing is aged out here, deliberately. Loading happens at boot, before a consumer has had
     * a chance to install its policy, so an expiry applied at this moment would be measured against
     * the library default rather than against the owner's number. Every read applies the live
     * policy instead, and housekeeping sweeps what has aged out once the server is running.
     */
    public void load() {
        playerPlacedBlocks.clear();
        placedByPosition.clear();
        Path path = dataPath;
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            Map<String, List<SavedBlock>> loaded = GSON.fromJson(reader, SAVE_TYPE);
            if (loaded == null) {
                return;
            }
            for (Map.Entry<String, List<SavedBlock>> entry : loaded.entrySet()) {
                UUID placer = parseUuid(entry.getKey());
                if (placer == null || entry.getValue() == null) {
                    continue;
                }
                Set<TrackedBlock> placements = ConcurrentHashMap.newKeySet();
                for (SavedBlock saved : entry.getValue()) {
                    if (saved == null) {
                        continue;
                    }
                    BlockPosition pos = new BlockPosition(
                            saved.worldUuid == null ? "" : saved.worldUuid, saved.x, saved.y, saved.z);
                    placements.add(new TrackedBlock(pos, saved.placedTime));
                    placedByPosition.put(pos, new IndexEntry(placer, saved.placedTime, 0L));
                }
                if (!placements.isEmpty()) {
                    playerPlacedBlocks.put(placer, placements);
                }
            }
            SafeLog.info("[placed] ledger loaded: " + trackedBlockCount() + " placements for "
                    + playerPlacedBlocks.size() + " players");
        } catch (Exception e) {
            SafeLog.warn("[placed] could not read the ledger", e);
        }
    }

    /** Write the placements out. Placed items are deliberately not saved; they expire in minutes. */
    public void save() {
        Path path = dataPath;
        if (path == null) {
            return;
        }
        try {
            cleanupExpired();
            if (playerPlacedBlocks.isEmpty()) {
                Files.deleteIfExists(path);
                return;
            }
            Map<String, List<SavedBlock>> document = new HashMap<>();
            for (Map.Entry<UUID, Set<TrackedBlock>> entry : playerPlacedBlocks.entrySet()) {
                List<SavedBlock> rows = new ArrayList<>();
                for (TrackedBlock placement : entry.getValue()) {
                    rows.add(new SavedBlock(
                            placement.position().worldUuid(),
                            placement.position().x(),
                            placement.position().y(),
                            placement.position().z(),
                            placement.placedTime()));
                }
                if (!rows.isEmpty()) {
                    document.put(entry.getKey().toString(), rows);
                }
            }
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(document, SAVE_TYPE, writer);
            }
            SafeLog.info("[placed] ledger saved: " + trackedBlockCount() + " placements for "
                    + playerPlacedBlocks.size() + " players");
        } catch (Exception e) {
            SafeLog.warn("[placed] could not write the ledger", e);
        }
    }

    /** Drop everything, in memory only. The test reset. */
    public void clear() {
        playerPlacedBlocks.clear();
        placedByPosition.clear();
        playerPlacedItems.clear();
    }

    // ==================== internals ====================

    /** Does this row answer for {@code asker}? Strict says every row does; otherwise only their own. */
    private boolean answersFor(@Nonnull IndexEntry entry, @Nonnull UUID asker) {
        return policy.strict() || entry.placer().equals(asker);
    }

    private boolean blockExpired(@Nonnull IndexEntry entry, long now) {
        int minutes = policy.blockExpireMinutes();
        return minutes > 0 && (now - entry.placedTime()) / 60_000L >= minutes;
    }

    private boolean itemExpired(@Nonnull TrackedItem tracked, long now) {
        long minutes = Math.max(1, policy.itemExpireMinutes());
        return (now - tracked.lastPlacedTime) / 60_000L >= minutes;
    }

    /** Drop a row from the index and its placer's set. Compare-and-remove, so a re-place survives. */
    private void drop(@Nonnull BlockPosition pos, @Nonnull IndexEntry entry) {
        if (placedByPosition.remove(pos, entry)) {
            forgetFromPlacerSet(pos, entry);
        }
    }

    private void forgetFromPlacerSet(@Nonnull BlockPosition pos, @Nonnull IndexEntry entry) {
        Set<TrackedBlock> placements = playerPlacedBlocks.get(entry.placer());
        if (placements != null) {
            placements.remove(new TrackedBlock(pos, entry.placedTime()));
        }
    }

    private void dropIfStillOwnedBy(@Nonnull TrackedBlock placement, @Nonnull UUID placer) {
        IndexEntry indexed = placedByPosition.get(placement.position());
        if (indexed != null
                && indexed.placer().equals(placer)
                && indexed.placedTime() == placement.placedTime()) {
            placedByPosition.remove(placement.position(), indexed);
        }
    }

    @Nullable
    private static UUID parseUuid(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
