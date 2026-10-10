package com.ziggfreed.common.playerstats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.leaderboard.statboard.StatSection;
import com.ziggfreed.common.instance.leaderboard.statboard.StatSectionConfig;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.Occurrences;
import com.ziggfreed.common.playerstats.asset.StatConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * The player stat store's one way in: how a mod counts a stat for a player, and how it reads one back.
 * The library's own producers count through here, and a consumer counts what it adds over them through
 * here too, never into a record of its own.
 *
 * <p><b>Three ways to move a counted stat, and the stat's kind picks one.</b> {@link #add} accumulates
 * (a Sum: kills, blocks, seconds played); {@link #raise} keeps a best that grows (a Max); {@link #lower}
 * keeps a best that shrinks (a Min, such as a fastest time). Each writes the grand total
 * ({@code <stat>}) and, when a {@code thing} is named, that line of the breakdown
 * ({@code <stat>/<thing>}), both keyed by {@link StatKeys}, moves the same keys' copy for every season
 * live on the calendar ({@code <event>@<year>/<key>}, one copy per season-year however many runs it has),
 * then hands the same keys to the stat board ({@link StatRankFeed}), which ranks each live run in its own
 * bucket. A {@link #gauge} is a number the consumer owns (a skill level), and a native stat
 * ({@link #readNative}) a number the engine already records: both are ranked, never stored in the player's
 * record.
 *
 * <p>A consumer that pages its seasons keeps their per-season tallies here too ({@link #season}): each page
 * line counts under the page's own keys, while the consumer's own switch is on.
 *
 * <p>Writes are called on the world thread that owns {@code store}, the way every producer runs. A
 * player with no record (the component never registered, or an entity that is not a player) counts
 * nothing; a stat id the key format cannot hold ({@link StatKeys#usableStatId}) counts nothing and is
 * reported once. With the store switched off ({@link StatsSwitch}) nothing is counted or ranked.
 */
public final class PlayerStats {

    /** A season page's Records section id: this, then the page's event id. */
    public static final String SEASON_SECTION_PREFIX = "season_";

    /** Where a season page's Records section sits: this plus the page's order, after the authored sections. */
    public static final int SEASON_SECTION_ORDER = 1000;

    private static final Set<String> WARNED_IDS = ConcurrentHashMap.newKeySet();

    /** Whether a failed read of the live seasons has been said, so the log says it once. */
    private static final AtomicBoolean WARNED_SEASONS = new AtomicBoolean();

    private static volatile StatRankFeed feed = StatRankFeed.NONE;

    private PlayerStats() {
    }

    /**
     * Add {@code amount} to {@code statId} for the player at {@code ref}: to its grand total and, when
     * {@code thing} is named, to that line of its breakdown. An amount of zero or less counts nothing,
     * since a tally never goes down.
     */
    public static void add(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String statId, @Nullable String thing, long amount) {
        write(store, ref, StatKind.SUM, statId, thing, amount);
    }

    /**
     * Raise {@code statId}'s best (a Max stat) to {@code value} when that beats it: the grand total holds
     * the best over every thing, and the named {@code thing}'s line its own best.
     */
    public static void raise(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String statId, @Nullable String thing, long value) {
        write(store, ref, StatKind.MAX, statId, thing, value);
    }

    /**
     * Lower {@code statId}'s best (a Min stat, such as a fastest time) to {@code value} when that beats
     * it, or set it when none is recorded: the grand total holds the best over every thing, and the
     * named {@code thing}'s line its own best. A value of zero or less is never a best.
     */
    public static void lower(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String statId, @Nullable String thing, long value) {
        write(store, ref, StatKind.MIN, statId, thing, value);
    }

    /**
     * Rank a number the consumer owns (a Gauge stat, such as a skill level) for the player
     * {@code uuid}, online or not: it sets {@code statId}'s {@code thing} line (the grand total when no
     * thing is named) on the stat board's all-time ranking and is never stored in the player's record.
     * {@code name} is the name the board shows, or null to keep the one it holds. Callable from any
     * thread.
     */
    public static void gauge(@Nonnull UUID uuid, @Nullable String name, @Nonnull String statId,
            @Nullable String thing, long value) {
        if (!StatsSwitch.isOn() || !usable(statId)) {
            return;
        }
        String key = StatKeys.usableThing(thing) ? StatKeys.line(statId, thing) : StatKeys.total(statId);
        Map<String, Long> values = new LinkedHashMap<>();
        values.put(key, value);
        rank(StatKind.GAUGE, uuid, name, values);
    }

    /**
     * Fold {@code lifetime} into the player's record once, claimed as {@code claimId} (for example
     * {@code mymod:statistics@1}): every tally is added to the lifetime bag under its own key, and the
     * claim is kept in the record, so a second call, a relog or a restart changes nothing. The merge is
     * built aside and swapped in whole, so a throw before the swap changes nothing and claims nothing.
     * It runs with the store switched off too: it moves saved data rather than counting anything. A fold
     * that lands re-ranks the player's whole record on the stat board at once (the ready pass's re-sync,
     * skipped while the store is off), so an import made after the player is ready shows on Records
     * without waiting for their next login. Called on the world thread that owns {@code store}.
     *
     * @return true when this call folded the import; false when it was already claimed, or the player
     *         has no record to fold it into
     * @throws IllegalArgumentException when {@code claimId} is blank or carries {@code ,} or {@code |}
     */
    public static boolean importOnce(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String claimId, @Nullable Map<String, Long> lifetime) {
        return importOnce(store, ref, claimId, lifetime, null);
    }

    /**
     * {@link #importOnce(Store, Ref, String, Map)} with a per season-year bag too: {@code seasons}
     * ({@code <event>@<year>/<key>}, {@link StatKeys#season}) is added to the record's {@code Seasons}, and
     * both bags are swapped in and claimed in the one guarded step, so the record never holds one without
     * the other. Called on the world thread that owns {@code store}.
     *
     * @return true when this call folded the import; false when it was already claimed, or the player
     *         has no record to fold it into
     * @throws IllegalArgumentException when {@code claimId} is blank or carries {@code ,} or {@code |}
     */
    public static boolean importOnce(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String claimId, @Nullable Map<String, Long> lifetime, @Nullable Map<String, Long> seasons) {
        PlayerStatsComponent record = PlayerStatsComponent.peek(store, ref);
        if (record == null || !record.importOnce(claimId, lifetime, seasons)) {
            return false;
        }
        resync(store, ref);
        return true;
    }

    /**
     * Has the import claimed as {@code claimId} been folded into the player's record? False for a player
     * with no record. Read on the world thread that owns {@code store}.
     */
    public static boolean hasImported(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String claimId) {
        PlayerStatsComponent record = PlayerStatsComponent.peek(store, ref);
        return record != null && record.hasImported(claimId);
    }

    /**
     * Keep {@code owner}'s season pages in the store, in place of what it registered before (none, or an
     * empty list, takes them out): each page's lines count off the shared moment stream into every
     * player's record under the page's own keys ({@link SeasonLines}), while {@code enabled} says the owner
     * is on, whatever the store's own switch says; what a moment adds is handed to {@code grew} (each key
     * written and the amount it rose) and ranked on the stat board while the store is on. Each page gets a
     * Records section of its own, {@code season_<event>} (a section file of that id wins), named by the
     * page's {@code nameKey}, else by the calendar's title for the event, after the authored sections in page
     * order, ranking every line of the page
     * ({@code <event>/*}) and showing its lines while the owner is on. Callable from any thread; a page
     * load calls it again with the whole set.
     */
    public static void season(@Nonnull String owner, @Nullable Collection<SeasonLines.Page> pages,
            @Nonnull BooleanSupplier enabled, @Nonnull Consumer<Map<String, Long>> grew) {
        if (owner.isBlank()) {
            throw new IllegalArgumentException("a season page owner must be named");
        }
        SeasonLines.Index index = SeasonLines.Index.of(pages);
        SeasonLines.register(owner, index, enabled, grew);
        List<StatSection> sections = new ArrayList<>();
        for (SeasonLines.Page page : index.pages()) {
            sections.add(new StatSection(SEASON_SECTION_PREFIX + page.eventId(), page.nameKey(),
                    (int) Math.min(Integer.MAX_VALUE, SEASON_SECTION_ORDER + (long) page.order()),
                    List.of(StatKeys.line(page.eventId(), StatSection.EVERY_THING)), page.eventId()));
        }
        StatSectionConfig.getInstance().generated(owner, sections);
    }

    /**
     * Read every native stat for the player at {@code ref} now and rank it: a number the engine already
     * records (objectives finished, standing with each reputation), asked of the reader some module
     * registered for that stat's id (zc-core's {@code NativeReaders}) and set outright on the stat board's
     * all-time ranking, never kept in the player's record. The library calls it when the player is ready and
     * as Records opens for them; a mod that knows such a number just moved may call it too. Nothing happens
     * with the store switched off. Called on the world thread that owns {@code store}.
     */
    public static void readNative(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        if (!StatsSwitch.isOn() || feed == StatRankFeed.NONE) {
            return;
        }
        PlayerRef player = playerRef(store, ref);
        if (player == null) {
            return;
        }
        Map<String, Long> readings = NativeStats.read(store, ref, StatConfig.getInstance().index());
        if (!readings.isEmpty()) {
            rank(StatKind.NATIVE, player.getUuid(), player.getUsername(), readings);
        }
    }

    /**
     * A frozen copy of the player's record, to read and never write: {@link StatsView#EMPTY} when they
     * have none. Read on the world thread that owns {@code store}.
     */
    @Nonnull
    public static StatsView view(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        PlayerStatsComponent record = PlayerStatsComponent.peek(store, ref);
        return record == null ? StatsView.EMPTY : record.view();
    }

    // ==================== The library's own wiring ====================

    /** Put the stat board in charge of ranking what is counted. One call, at library setup; null ranks nothing. */
    static void rankInto(@Nullable StatRankFeed rankFeed) {
        feed = rankFeed == null ? StatRankFeed.NONE : rankFeed;
    }

    /** One write the moment counter worked out, moved its stat's own way. */
    static void count(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull StatCounter.Write write) {
        write(store, ref, write.kind(), write.statId(), write.thing(), write.value());
    }

    /**
     * One moment's writes for one owner's season page lines: added to the player's record, handed to the
     * owner's growth sink, and the every-season keys ranked as a Sum while the store is on. A sink or a board
     * that throws costs only itself, never the count.
     *
     * @return false when the player has no record, so nothing more need be counted for them
     */
    static boolean countSeasonLines(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull List<SeasonLines.Write> writes, @Nonnull Consumer<Map<String, Long>> grew) {
        PlayerStatsComponent record = PlayerStatsComponent.peek(store, ref);
        if (record == null) {
            return false;
        }
        SeasonLines.apply(record, writes);
        Map<String, Long> grown = new LinkedHashMap<>();
        Map<String, Long> lifetime = new LinkedHashMap<>();
        for (SeasonLines.Write write : writes) {
            grown.merge(write.key(), write.amount(), Long::sum);
            if (!write.season()) {
                lifetime.merge(write.key(), write.amount(), Long::sum);
            }
        }
        try {
            grew.accept(Map.copyOf(grown));
        } catch (Throwable t) {
            SafeLog.warn("[stats] a season page's growth sink failed: " + t.getMessage());
        }
        if (StatsSwitch.isOn() && feed != StatRankFeed.NONE && !lifetime.isEmpty()) {
            PlayerRef player = playerRef(store, ref);
            if (player != null) {
                rank(StatKind.SUM, player.getUuid(), player.getUsername(), lifetime);
            }
        }
        return true;
    }

    /**
     * Hand the stat board this player's whole record, so its all-time ranking matches what the player
     * holds (a record imported, or counted while the board was off). Called on the player's world thread.
     */
    static void resync(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        if (!StatsSwitch.isOn()) {
            return;
        }
        PlayerStatsComponent record = PlayerStatsComponent.peek(store, ref);
        PlayerRef player = record == null ? null : playerRef(store, ref);
        if (player == null) {
            return;
        }
        Map<String, Long> tallies = record.tallies();
        if (!tallies.isEmpty()) {
            rank(StatKind.GAUGE, player.getUuid(), player.getUsername(), tallies);
        }
    }

    // ==================== Internals ====================

    private static void write(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull StatKind kind, @Nonnull String statId, @Nullable String thing, long value) {
        if (value <= 0L || !StatsSwitch.isOn() || !usable(statId)) {
            return;
        }
        PlayerStatsComponent record = PlayerStatsComponent.peek(store, ref);
        if (record == null) {
            return;
        }
        Map<String, Long> keys = new LinkedHashMap<>();
        keys.put(StatKeys.total(statId), value);
        if (StatKeys.usableThing(thing)) {
            keys.put(StatKeys.line(statId, thing), value);
        }
        for (String key : keys.keySet()) {
            switch (kind) {
                case MAX -> record.raise(key, value);
                case MIN -> record.lower(key, value);
                default -> record.add(key, value);
            }
        }
        for (Occurrence run : liveRuns()) {
            for (String key : keys.keySet()) {
                // A season page's line of that event keeps its own season-year copy under the same key.
                if (!SeasonLines.owns(run.eventId(), key)) {
                    record.moveSeason(kind, StatKeys.season(run.eventId(), run.year(), key), value);
                }
            }
        }
        if (feed == StatRankFeed.NONE) {
            return;
        }
        PlayerRef player = playerRef(store, ref);
        if (player != null) {
            rank(kind, player.getUuid(), player.getUsername(), keys);
        }
    }

    /** Hand {@code values} to the board the {@code kind}'s way; a failing board costs only its ranking. */
    private static void rank(@Nonnull StatKind kind, @Nonnull UUID uuid, @Nullable String name,
            @Nonnull Map<String, Long> values) {
        StatRankFeed current = feed;
        try {
            switch (kind) {
                case SUM -> current.add(uuid, name, values);
                case MAX -> current.raise(uuid, name, values);
                case MIN -> current.lower(uuid, name, values);
                default -> current.setAllTime(uuid, name, values);
            }
        } catch (Throwable t) {
            SafeLog.warn("[stats] the stat board could not rank a write: " + t.getMessage());
        }
    }

    /**
     * Every season live now on the server's own clock (never the stat board's trial clock: this is the
     * record, not a ranking), whose events a {@code Seasons} key can name; the calendar is walked at most once
     * a second however many writes ask ({@link Occurrences#liveAllCached}). A calendar that throws costs the
     * season copies of this write, never the count, and says so once.
     */
    @Nonnull
    private static Collection<Occurrence> liveRuns() {
        Map<String, Occurrence> live;
        try {
            live = Occurrences.liveAllCached(System.currentTimeMillis());
        } catch (Throwable t) {
            if (WARNED_SEASONS.compareAndSet(false, true)) {
                SafeLog.warn("[stats] the calendar could not say which seasons are live, so this write moves no"
                        + " season's copy: " + t.getMessage());
            }
            return List.of();
        }
        if (live.isEmpty()) {
            return List.of();
        }
        List<Occurrence> usable = new ArrayList<>(live.size());
        for (Occurrence run : live.values()) {
            if (StatKeys.usableEvent(run.eventId())) {
                usable.add(run);
            }
        }
        return usable;
    }

    @Nullable
    private static PlayerRef playerRef(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            return ref.isValid() ? store.getComponent(ref, PlayerRef.getComponentType()) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Is {@code statId} a key the store can hold? Reported once per id when it is not. */
    private static boolean usable(@Nullable String statId) {
        if (StatKeys.usableStatId(statId)) {
            return true;
        }
        if (WARNED_IDS.add(String.valueOf(statId))) {
            SafeLog.warn("[stats] the stat id '" + statId + "' cannot be counted: it is blank or carries"
                    + " / @ | or :");
        }
        return false;
    }
}
