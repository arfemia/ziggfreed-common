package com.ziggfreed.common.world.placed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.interaction.components.PlacedByInteractionComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.ChunkSections;

/**
 * What a player put there themselves - the record that stops "place it, break it, place it again"
 * from paying out. Placed BLOCKS are remembered on the chunk they belong to; placed ITEM ids are
 * counted separately, so a placed sapling cannot be harvested straight back for credit.
 *
 * <p><b>Any mod counting block breaks or pickups wants this, which is why it lives here.</b> A
 * reward for breaking a block is a reward for finding one, and a block the breaker set down a
 * moment ago was never found. The ledger answers that one question and holds no opinion about what
 * the answer is worth, so a quest producer, an XP engine and a statistics counter can all ask it
 * and can never disagree about whether a given block was placed.
 *
 * <p><b>Nobody earns from a placement, whoever breaks it.</b> Anything else is a two-player version
 * of the same exploit, with one player standing ore up for another to mine. A placement that SHOULD
 * pay - an admin building a mine for players to work - is settled when the block goes down, by
 * recording it outside the guard: see {@link PlacedBlockRecorder} for which placements the guard holds.
 *
 * <p><b>Who placed a block is one answer, kept apart from credit.</b> Every real placement is recorded
 * with its placer whatever the {@link Policy} says ({@link #placedBy}, {@link #wasPlaced}): the game's own
 * record ({@code PlacedByInteractionComponent}) on a block that keeps one (a crop, a chest, a bench), the
 * section's own palette elsewhere (stone, soil, planks, which the game never records). The policy decides
 * only credit, what {@link #isPlaced} and {@link #consumePlacement} answer for XP and loot; a rule about who
 * built a block (a shape that tears down only your own walls, growth for the planter) asks the placed-by
 * answer, so it works with the guard off or the builder exempt.
 *
 * <p><b>Where a placement is kept.</b> A block's mark lives on its own chunk section, one bit per
 * block, saved and loaded with the chunk by the engine ({@link PlacedBlockSection}). Only loaded
 * chunks cost anything, a lookup is an array index, and there is nothing to scan, copy or write on
 * a timer. Placed items are counted in memory and deliberately not persisted at all: they are
 * forgotten in minutes and a restart takes longer than that.
 *
 * <p><b>One native event has SEVERAL readers.</b> A single break is seen by the library's own
 * producer and by every consumer's own event system in the same tick, in an order nobody specifies,
 * and every one of them has to be told the same thing. Blocks get that from the position itself:
 * spending a mark also remembers the position for {@link #READ_GRACE_MS}, which keeps answering
 * "placed" to whoever reads the same break second - far longer than one tick, and far shorter than
 * any human place-then-break cycle. Without it the second reader would be told the block was
 * ordinary and would pay out on exactly the exploit the first one refused. Placed ITEMS are counted
 * rather than positioned, so several copies of one id share a row and the moment has to be named: a
 * reader passes a {@code momentKey} that is stable for the one event it is handling (the picked-up
 * stack's own identity does it), and a second reader arriving with the SAME key inside the grace
 * window is told the same answer without a second copy being spent.
 */
public final class PlacedBlockLedger {

    /**
     * How long a spent item row keeps answering "placed", so every reader of ONE native event
     * agrees. See the class javadoc.
     */
    public static final long READ_GRACE_MS = 1_000L;

    /** How rarely the item half is swept, off ordinary pickup traffic rather than a scheduler. */
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    /** The file earlier builds kept placements in, retired on the first boot that finds it. */
    private static final String LEGACY_FILE = "placed-blocks.json";

    private static final PlacedBlockLedger INSTANCE = new PlacedBlockLedger();

    @Nonnull
    public static PlacedBlockLedger getInstance() {
        return INSTANCE;
    }

    /**
     * How strict the ledger is and how long it remembers. Read LIVE, so a consumer whose owner
     * config already carries these installs a policy that reads its own values and never has to
     * push an update when that config reloads.
     */
    public interface Policy {

        /** The library's own answer: remember placements, and forget a placed item after five minutes. */
        Policy DEFAULT = new Policy() {
        };

        /**
         * Guard placements at all. False makes every credit ask ({@link #isPlaced}, {@link #consumePlacement},
         * {@link #consumePlacedItem}) answer "not placed"; who placed a block is still recorded and answered.
         */
        default boolean enabled() {
            return true;
        }

        /**
         * Should this player's placements be guarded? False leaves whatever they put down
         * earning as a block that was always there does, so anybody who breaks it earns from it
         * normally.
         *
         * <p>This is for the case the guard would otherwise get backwards: someone standing up an
         * ore vein, a farm or a quarry FOR other players to work. Creative-mode placements are
         * already exempt without asking (see {@link PlacedBlockRecorder}); this is how a consumer
         * exempts a builder who is in survival, typically by asking whether they hold a permission.
         *
         * <p>It decides only CREDIT: the placement is still recorded with its placer ({@link #placedBy}).
         * Whether the placer earns for placing is a separate question that this never touches, so an
         * exempt builder still gets whatever their placement is normally worth.
         */
        default boolean guardsPlacementsBy(@Nonnull PlayerRef placer) {
            return true;
        }

        /** Minutes before a placed ITEM is forgotten. Always finite - a dropped item does not last. */
        default int itemExpireMinutes() {
            return 5;
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

    /** A block position, scoped to a world so two worlds' coordinates never collide. */
    public record BlockPosition(@Nonnull UUID worldUuid, int x, int y, int z) {
    }

    /** A mark spent at {@code at}, who had placed it, and whether the guard held it, kept for the grace window. */
    private record Spent(long at, @Nullable UUID placer, boolean guarded) {
    }

    /** Placer uuid to item id to how many of that item they put down. */
    private final Map<UUID, Map<String, TrackedItem>> playerPlacedItems = new ConcurrentHashMap<>();

    /**
     * Positions whose mark was spent in the last {@link #READ_GRACE_MS}, so every reader of ONE
     * break is told the same thing. Never more than the positions broken in the last second, and
     * swept on the same beat as the item half.
     */
    private final Map<BlockPosition, Spent> recentlySpent = new ConcurrentHashMap<>();

    @Nonnull
    private volatile Policy policy = Policy.DEFAULT;

    /** Who installed the policy in force, or null while the library's own default is. */
    @Nullable
    private volatile String policyOwner;

    /** When the item half was last swept, so a long session reclaims rows without a scheduler. */
    private volatile long lastCleanup = System.currentTimeMillis();

    /** When spent positions were last swept; their own beat, see {@link #sweepRecentlySpent}. */
    private volatile long lastSpentSweep = System.currentTimeMillis();

    /** Where the retired file is looked for; null once somebody has pointed it at nothing. */
    @Nullable
    private volatile Path legacyPath = Paths.get("mods", "ziggfreedcommon", LEGACY_FILE);

    private PlacedBlockLedger() {
    }

    /**
     * Install the policy the ledger reads, naming who installed it. It is read live on every ask,
     * so a consumer wiring its own owner config in here never has to re-push after a reload.
     *
     * <p>ONE slot, like every other one-slot part in this library: a SECOND owner arriving is
     * refused and logged, rather than silently replacing the first.
     */
    /** The policy in force, for the recorder's exemption ask. */
    @Nonnull
    public Policy policy() {
        return policy;
    }

    public void setPolicy(@Nonnull String owner, @Nonnull Policy newPolicy) {
        String current = policyOwner;
        if (current != null && !current.equals(owner)) {
            SafeLog.warn("[placed] '" + owner + "' tried to install a second placed-block policy;"
                    + " '" + current + "' already owns it, so the new one is ignored");
            return;
        }
        policyOwner = owner;
        policy = newPolicy;
    }

    // ==================== placements ====================

    /**
     * Remember that a player put a block down at this position, the guard holding it while the policy is on: the
     * form for a library writer whose placement the guard always holds (a temporary block).
     */
    public void trackPlacement(@Nonnull UUID placerUuid, @Nonnull UUID worldUuid, int x, int y, int z) {
        trackPlacement(placerUuid, worldUuid, x, y, z, true);
    }

    /**
     * Remember that a player put a block down at this position, and who, whatever the policy says: the placed-by
     * answer ({@link #placedBy}, {@link #wasPlaced}) reads it for every real placement. {@code guarded} says whether
     * the anti-exploit guard holds it (the placer is neither exempt nor building in creative); a placement made while
     * the policy is off is never guarded, so turning the guard on later refuses no credit for it.
     */
    public void trackPlacement(@Nonnull UUID placerUuid, @Nonnull UUID worldUuid, int x, int y, int z,
            boolean guarded) {
        boolean held = guarded && policy.enabled();
        withSection(worldUuid, x, y, z, (store, sectionRef) -> {
            PlacedBlockSection.mark(store, sectionRef, x, y, z, placerUuid, held);
            return true;
        });
    }

    /**
     * Who placed the block at this position, whatever the policy says: the game's own record where the block keeps one
     * (its block entity's {@code PlacedByInteractionComponent}: a crop, a chest, a bench), else the player this ledger
     * recorded, also for a mark spent by a reader of the same break inside {@link #READ_GRACE_MS}; null for a block
     * nobody placed or one placed before placers were kept. The one answer every "who built this" rule reads.
     */
    @Nullable
    public UUID placedBy(@Nonnull UUID worldUuid, int x, int y, int z) {
        UUID[] found = new UUID[1];
        boolean marked = withSection(worldUuid, x, y, z, (store, sectionRef) -> {
            found[0] = placerAt(store, sectionRef, blockEntity(store, sectionRef, x, y, z), x, y, z);
            return found[0] != null || PlacedBlockSection.isPlaced(store, sectionRef, x, y, z);
        });
        if (marked) {
            return found[0];
        }
        Spent spent = spentWithinGrace(worldUuid, x, y, z);
        return spent == null ? null : spent.placer();
    }

    /**
     * Was the block at this position put there by a player, whoever and whatever the policy says? The placed-by
     * answer's yes or no ({@link #placedBy}), true also for a block placed before placers were kept and for a mark
     * spent by a reader of the same break inside {@link #READ_GRACE_MS}. Never spends anything; credit is
     * {@link #isPlaced} and {@link #consumePlacement}.
     */
    public boolean wasPlaced(@Nonnull UUID worldUuid, int x, int y, int z) {
        boolean marked = withSection(worldUuid, x, y, z, (store, sectionRef) ->
                PlacedBlockSection.isPlaced(store, sectionRef, x, y, z)
                        || nativePlacer(store, blockEntity(store, sectionRef, x, y, z)) != null);
        return marked || spentWithinGrace(worldUuid, x, y, z) != null;
    }

    /** Whether {@code playerUuid} placed the block at this position, as {@link #placedBy} reads it. */
    public boolean isPlacedBy(@Nonnull UUID playerUuid, @Nonnull UUID worldUuid, int x, int y, int z) {
        return playerUuid.equals(placedBy(worldUuid, x, y, z));
    }

    /**
     * {@link #placedBy}'s answer for a caller already holding the chunk store on the world thread (a chunk-store
     * system walking block entities): the game's own record on {@code block} (the cell's block entity, null for none),
     * else the placer the section at {@code sectionRef} recorded at the cell. Answers no spent mark: a block a system
     * walks is still standing.
     */
    @Nullable
    public static UUID placerAt(@Nonnull ComponentAccessor<ChunkStore> chunks, @Nonnull Ref<ChunkStore> sectionRef,
            @Nullable Ref<ChunkStore> block, int x, int y, int z) {
        UUID recorded = nativePlacer(chunks, block);
        return recorded != null ? recorded : PlacedBlockSection.placerOf(chunks, sectionRef, x, y, z);
    }

    /** The game's own placer on a block entity, or null for none, no block entity, or a read that failed. */
    @Nullable
    private static UUID nativePlacer(@Nonnull ComponentAccessor<ChunkStore> chunks, @Nullable Ref<ChunkStore> block) {
        if (block == null || !block.isValid()) {
            return null;
        }
        try {
            PlacedByInteractionComponent placed = chunks.getComponent(block,
                    PlacedByInteractionComponent.getComponentType());
            return placed == null ? null : placed.getWhoPlacedUuid();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The cell's block entity, or null when it keeps none. */
    @Nullable
    private static Ref<ChunkStore> blockEntity(@Nonnull ComponentAccessor<ChunkStore> chunks,
            @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z) {
        try {
            return BlockModule.getBlockEntity(chunks, sectionRef, x, y, z);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The mark spent at this position inside {@link #READ_GRACE_MS}, or null for none. */
    @Nullable
    private Spent spentWithinGrace(@Nonnull UUID worldUuid, int x, int y, int z) {
        Spent spent = recentlySpent.get(new BlockPosition(worldUuid, x, y, z));
        return spent != null && System.currentTimeMillis() - spent.at() <= READ_GRACE_MS ? spent : null;
    }

    // ==================== spared placements ====================

    /**
     * Remember that {@code block}, now at this position, was placed without using up its item (a thrift
     * step spared it), so breaking it must hand nothing back. The mark keeps what the block is
     * ({@link #sparedIdentity}), so a different block standing in the cell later never reads as spared.
     * Kept whatever the policy says, since it guards against making an item from nothing rather than
     * against credit.
     */
    public void markSpared(@Nonnull UUID worldUuid, int x, int y, int z, @Nonnull BlockType block) {
        String identity = sparedIdentity(block);
        withSection(worldUuid, x, y, z,
                (store, sectionRef) -> PlacedBlockSection.markSpared(store, sectionRef, x, y, z, identity));
    }

    /** Forget a spared mark at this position: the block it named is gone. */
    public void clearSpared(@Nonnull UUID worldUuid, int x, int y, int z) {
        withSection(worldUuid, x, y, z,
                (store, sectionRef) -> PlacedBlockSection.clearSpared(store, sectionRef, x, y, z));
    }

    /**
     * Was {@code block}, the block at this position now, placed without using up its item? A mark that
     * names another block (the spared one went by physics or by another mod's write, which nothing
     * hears, and something else stands there now) is forgotten here and answers false, so it never
     * costs that block its drop.
     */
    public boolean isSpared(@Nonnull UUID worldUuid, int x, int y, int z, @Nonnull BlockType block) {
        String identity = sparedIdentity(block);
        return withSection(worldUuid, x, y, z, (store, sectionRef) -> {
            String was = PlacedBlockSection.sparedAs(store, sectionRef, x, y, z);
            if (was == null) {
                return false;
            }
            if (was.equals(identity)) {
                return true;
            }
            PlacedBlockSection.clearSpared(store, sectionRef, x, y, z);
            return false;
        });
    }

    /**
     * What a spared mark records of a block: its own item's id, which every state and rotation of the
     * block shares (a door swung open, a lamp lit), or the block's id when no item holds it.
     */
    @Nonnull
    public static String sparedIdentity(@Nonnull BlockType block) {
        Item item = block.getItem();
        String id = item == null ? null : item.getId();
        if (id == null) {
            id = block.getId();
        }
        return id == null ? "" : id;
    }

    /**
     * Does this break earn no credit, because it takes away a placement the guard holds? The question a
     * break-time handler asks. The block's record is spent whatever the answer and whatever the policy, which is
     * correct however many readers follow: the block itself is gone, and every reader of the same break inside
     * {@link #READ_GRACE_MS} is told the same thing (this answer, and who placed it).
     */
    public boolean consumePlacement(@Nonnull UUID breakerUuid, @Nonnull UUID worldUuid, int x, int y, int z) {
        maybeCleanup();
        long now = System.currentTimeMillis();
        sweepRecentlySpent(now);
        BlockPosition position = new BlockPosition(worldUuid, x, y, z);
        Spent spent = recentlySpent.get(position);
        if (spent != null) {
            if (now - spent.at() <= READ_GRACE_MS) {
                // Somebody else already read this same break. Same answer, nothing spent twice.
                return credits(spent.guarded());
            }
            recentlySpent.remove(position, spent);
        }
        Spent[] taken = new Spent[1];
        boolean placed = withSection(worldUuid, x, y, z, (store, sectionRef) -> {
            UUID placer = placerAt(store, sectionRef, blockEntity(store, sectionRef, x, y, z), x, y, z);
            boolean guarded = PlacedBlockSection.isGuarded(store, sectionRef, x, y, z);
            if (!PlacedBlockSection.consume(store, sectionRef, x, y, z)) {
                return false;
            }
            taken[0] = new Spent(now, placer, guarded);
            return true;
        });
        if (!placed || taken[0] == null) {
            return false;
        }
        recentlySpent.put(position, taken[0]);
        return credits(taken[0].guarded());
    }

    /**
     * The same question WITHOUT clearing anything: the read for a caller that is only LOOKING.
     * A break-time handler always wants {@link #consumePlacement}. A rule about who built a block
     * reads {@link #wasPlaced} or {@link #placedBy} instead, which the policy never touches.
     */
    public boolean isPlaced(@Nonnull UUID breakerUuid, @Nonnull UUID worldUuid, int x, int y, int z) {
        if (!policy.enabled()) {
            return false;
        }
        return withSection(worldUuid, x, y, z,
                (store, sectionRef) -> PlacedBlockSection.isGuarded(store, sectionRef, x, y, z));
    }

    /** Whether a placement the guard did or did not hold refuses credit now: only while the policy is on. */
    private boolean credits(boolean guarded) {
        return guarded && policy.enabled();
    }

    /** What a caller wants done once the block's chunk section has been resolved. */
    private interface SectionWork {
        boolean run(@Nonnull Store<ChunkStore> store, @Nonnull Ref<ChunkStore> sectionRef);
    }

    /**
     * Resolve the chunk section holding this position and run {@code work} against it, answering
     * false when there is no section to ask.
     *
     * <p>An unloaded chunk answers "not placed", which is the same answer it would have given while
     * loaded for a block nobody placed, and the only honest one: the record lives with the chunk,
     * so if the chunk is not here neither is the record. Nothing can break a block in a chunk that
     * is not loaded, so this is a guard rather than a case that happens in play.
     */
    private boolean withSection(@Nonnull UUID worldUuid, int x, int y, int z, @Nonnull SectionWork work) {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return false;
            }
            World world = universe.getWorld(worldUuid);
            if (world == null || !world.isAlive()) {
                return false;
            }
            var chunkStore = world.getChunkStore();
            if (chunkStore == null) {
                return false;
            }
            Ref<ChunkStore> sectionRef = ChunkSections.at(chunkStore, x, y, z);
            if (sectionRef == null) {
                return false;
            }
            return work.run(chunkStore.getStore(), sectionRef);
        } catch (Throwable t) {
            SafeLog.warn("[placed] could not reach the chunk holding (" + x + ", " + y + ", " + z
                    + "); treating it as never placed", t);
            return false;
        }
    }

    /**
     * Forget the placed ITEMS remembered for one player. Placed BLOCKS are not per-player - they
     * belong to their chunk and are cleared when the block goes - so nothing about them is dropped
     * here.
     */
    public void clearPlayer(@Nonnull UUID playerUuid) {
        playerPlacedItems.remove(playerUuid);
    }

    // ==================== placed items ====================

    /** Remember that {@code placerUuid} put one of {@code itemId} down. */
    public void trackPlacedItem(@Nonnull UUID placerUuid, @Nonnull String itemId) {
        if (!policy.enabled()) {
            return;
        }
        maybeCleanup();
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
        maybeCleanup();
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

    private boolean itemExpired(@Nonnull TrackedItem tracked, long now) {
        long expireMillis = Math.max(1, policy.itemExpireMinutes()) * 60_000L;
        return now - tracked.lastPlacedTime >= expireMillis;
    }

    // ==================== housekeeping ====================

    /** Drop placed-item rows that have aged out or have already been answered for. */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        recentlySpent.entrySet().removeIf(entry -> now - entry.getValue().at() > READ_GRACE_MS);
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
     * Drop spent positions older than the grace window. Its own beat rather than the item half's,
     * because these rows live for a second and a mass harvest would otherwise pile up a minute of
     * them between sweeps.
     */
    private void sweepRecentlySpent(long now) {
        if (recentlySpent.isEmpty() || now - lastSpentSweep <= READ_GRACE_MS) {
            return;
        }
        lastSpentSweep = now;
        recentlySpent.entrySet().removeIf(entry -> now - entry.getValue().at() > READ_GRACE_MS);
    }

    /**
     * Sweep the item half on a slow beat, driven by ordinary pickup traffic rather than by a
     * scheduler. Cheap: the item half holds a row per item id a player has recently put down, and
     * every one of them expires in minutes.
     */
    private void maybeCleanup() {
        long now = System.currentTimeMillis();
        if (now - lastCleanup >= CLEANUP_INTERVAL_MS) {
            lastCleanup = now;
            cleanupExpired();
        }
    }

    /** How many placed items are still owed an answer. */
    public int trackedItemCount() {
        return playerPlacedItems.values().stream().mapToInt(Map::size).sum();
    }

    // ==================== the retired file ====================

    /**
     * Point the legacy-file check somewhere else (a test's own directory), or at nothing.
     */
    public void setLegacyPath(@Nullable Path path) {
        this.legacyPath = path;
    }

    /**
     * Retire the file earlier builds kept placements in.
     *
     * <p>Placements live on their chunks now, and a saved row cannot be put back onto a chunk that
     * is not loaded - holding the whole file in memory until enough chunks came in would keep
     * exactly the cost this move removes. So the file is renamed aside with one notice rather than
     * carried across: blocks placed before the update stop being remembered and pay out if broken,
     * and everything placed from here on is guarded again. It is an anti-exploit record, not
     * anybody's progress.
     */
    public void retireLegacyFile() {
        Path path = legacyPath;
        if (path == null || !Files.exists(path)) {
            return;
        }
        Path retired = path.resolveSibling(path.getFileName() + ".legacy");
        try {
            Files.move(path, retired, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            SafeLog.warn("[placed] " + path + " is no longer read: placements are kept on the chunk"
                    + " they belong to now, saved and loaded with it. The old file has been renamed"
                    + " to " + retired.getFileName() + " and nothing was carried across, so blocks"
                    + " placed before this update are no longer remembered. Everything placed from"
                    + " now on is guarded as before.");
        } catch (Exception e) {
            SafeLog.warn("[placed] " + path + " is no longer read, but it could not be renamed"
                    + " aside; it is harmless where it is and can be deleted by hand", e);
        }
    }

    /** Drop the in-memory halves (placed items, and positions inside the grace window). The test reset. */
    public void clear() {
        playerPlacedItems.clear();
        recentlySpent.clear();
    }
}
