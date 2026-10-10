package com.ziggfreed.common.playerstats;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.counter.CounterStore;
import com.ziggfreed.common.counter.PackedTallies;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * One player's stat record: every tally they hold, saved with the player under
 * {@value #REGISTRY_ID}.
 *
 * <p><b>Three string fields, and the format only grows.</b> {@code Tallies} is the lifetime bag,
 * {@code key:value|...} keyed by {@link StatKeys}; {@code Seasons} is the per season-year bag,
 * {@code <event>@<year>/<stat key>:value|...}, which a stat moving while a season is live moves too
 * ({@link #moveSeason}), as does a season page's line ({@link SeasonLines}, {@code <event>@<year>/<line>});
 * {@code Imports} is the comma-joined claim ids of the
 * one-time imports already folded in ({@link #importOnce}). A new stat is a new key, never a new
 * field: append a field, never rename one. A damaged pair costs only itself, and a key no stat
 * registers rides along untouched, so a save written by a newer build or for a mod this server no
 * longer runs keeps every tally.
 *
 * <p><b>Counted stats only.</b> Sums, and the bests a Max or Min stat keeps. A gauge (a level a
 * consumer owns) and a native reading are never stored here.
 *
 * <p>It is also a {@link CounterStore} over the lifetime bag, so a {@code Counters} facade can run
 * over one record. Every override ignores its subject: this component IS one player's tallies.
 *
 * <p>Guarded per record: a write from the world thread and a read from a command or the save
 * cannot corrupt each other, and an import swaps and claims in one step.
 */
public final class PlayerStatsComponent implements Component<EntityStore>, CounterStore {

    /** The engine registry id, and the save key. */
    public static final String REGISTRY_ID = "ZiggfreedCommon:Stats";

    /** Joins one claim id to the next in {@code Imports}. Reserved inside a claim id. */
    public static final char CLAIM_SEPARATOR = ',';

    /** Null until {@link #register} succeeds; a reader peeks and treats null as no record. */
    @Nullable
    public static volatile ComponentType<EntityStore, PlayerStatsComponent> TYPE;

    private CounterMap tallies = new CounterMap();
    private CounterMap seasons = new CounterMap();
    private final Set<String> imports = new TreeSet<>();

    /** Seconds played since the last whole seconds were committed. Never saved; carried by {@link #clone}. */
    private double playtimeCarry;

    public static final BuilderCodec<PlayerStatsComponent> CODEC;

    static {
        var builder = BuilderCodec.builder(PlayerStatsComponent.class, PlayerStatsComponent::new);
        builder.append(new KeyedCodec<>("Tallies", Codec.STRING, false),
                (c, v, info) -> c.loadTallies(v),
                (c, info) -> PackedTallies.serialize(c.tallies())).add();
        builder.append(new KeyedCodec<>("Seasons", Codec.STRING, false),
                (c, v, info) -> c.loadSeasons(v),
                (c, info) -> PackedTallies.serialize(c.seasons())).add();
        builder.append(new KeyedCodec<>("Imports", Codec.STRING, false),
                (c, v, info) -> c.loadImports(v),
                (c, info) -> c.savedImports()).add();
        CODEC = builder.build();
    }

    public PlayerStatsComponent() {
    }

    // ==================== The save format ====================

    synchronized void loadTallies(@Nullable String packed) {
        tallies = PackedTallies.deserialize(packed);
    }

    synchronized void loadSeasons(@Nullable String packed) {
        seasons = PackedTallies.deserialize(packed);
    }

    synchronized void loadImports(@Nullable String joined) {
        imports.clear();
        if (joined == null || joined.isBlank()) {
            return;
        }
        for (String claim : joined.split(String.valueOf(CLAIM_SEPARATOR))) {
            if (!claim.isBlank()) {
                imports.add(claim.trim());
            }
        }
    }

    /** {@code Imports} as saved: the claim ids, sorted, comma-joined. */
    @Nonnull
    synchronized String savedImports() {
        return String.join(String.valueOf(CLAIM_SEPARATOR), imports);
    }

    // ==================== Reads ====================

    /** One lifetime tally by its full key, or {@code 0}; the key is matched without regard to case. */
    public synchronized long get(@Nonnull String key) {
        return tallies.get(key);
    }

    /** Every lifetime tally, as an unmodifiable snapshot. */
    @Nonnull
    public synchronized Map<String, Long> tallies() {
        return tallies.all();
    }

    /** Every per season-year tally, as an unmodifiable snapshot. */
    @Nonnull
    public synchronized Map<String, Long> seasons() {
        return seasons.all();
    }

    /** The claim ids of every import already folded into this record, sorted. */
    @Nonnull
    public synchronized Set<String> imports() {
        return Set.copyOf(imports);
    }

    /** Has the import claimed as {@code claimId} already been folded in? */
    public synchronized boolean hasImported(@Nonnull String claimId) {
        return imports.contains(claimId.trim());
    }

    /** A frozen copy of this record, for a reader handed it off the world thread. */
    @Nonnull
    public synchronized StatsView view() {
        return new StatsView(tallies.all(), seasons.all());
    }

    // ==================== Writes ====================

    /** Add {@code delta} to the lifetime tally under {@code key} and return the new value. */
    public synchronized long add(@Nonnull String key, long delta) {
        return tallies.add(key, delta);
    }

    /** Raise the lifetime tally under {@code key} to {@code value} when that is higher; true when it moved. */
    public synchronized boolean raise(@Nonnull String key, long value) {
        return tallies.highWater(key, value);
    }

    /**
     * Lower the lifetime tally under {@code key} to {@code value} when that is lower, or when nothing
     * is recorded yet (a best time starts at the first one). A value of zero or less is never a best,
     * since zero is what an absent key reads. True when it moved.
     */
    public synchronized boolean lower(@Nonnull String key, long value) {
        return lower(tallies, key, value);
    }

    /**
     * Move the per season-year tally under {@code key} ({@link StatKeys#season}) the way {@code kind} moves
     * its lifetime one: a Max raises it, a Min lowers it, anything else adds. A key the save could not keep
     * writes nothing.
     */
    public synchronized void moveSeason(@Nonnull StatKind kind, @Nonnull String key, long value) {
        if (!PackedTallies.usableKey(key)) {
            return;
        }
        switch (kind) {
            case MAX -> seasons.highWater(key, value);
            case MIN -> lower(seasons, key, value);
            default -> seasons.add(key, value);
        }
    }

    /** {@link #lower(String, long)} on {@code bag}: a best that shrinks, any value beating none. */
    private static boolean lower(@Nonnull CounterMap bag, @Nonnull String key, long value) {
        if (value <= 0L) {
            return false;
        }
        long current = bag.get(key);
        if (current > 0L && current <= value) {
            return false;
        }
        bag.set(key, value);
        return true;
    }

    /**
     * Fold {@code lifetime} into this record once, under {@code claimId}: every tally in it is ADDED to
     * the lifetime bag and the claim is recorded, in one guarded step. The merged bag is built aside and
     * swapped in only whole, so a throw part way changes nothing, and a second call with the same claim
     * changes nothing either.
     *
     * @return true when this call folded the import, false when the claim was already held
     * @throws IllegalArgumentException when {@code claimId} is blank or carries {@value #CLAIM_SEPARATOR}
     *                                  or {@code |}, which the save could not keep
     */
    public synchronized boolean importOnce(@Nonnull String claimId, @Nullable Map<String, Long> lifetime) {
        return importOnce(claimId, lifetime, null);
    }

    /**
     * {@link #importOnce(String, Map)} with a per season-year bag too: every tally in {@code seasonal} is ADDED
     * to the {@code Seasons} bag. Both merged bags are built aside and swapped in with the claim in the one
     * guarded step, so a throw part way changes neither, and a second call with the same claim changes nothing.
     *
     * @return true when this call folded the import, false when the claim was already held
     * @throws IllegalArgumentException when {@code claimId} is blank or carries {@value #CLAIM_SEPARATOR}
     *                                  or {@code |}, which the save could not keep
     */
    public synchronized boolean importOnce(@Nonnull String claimId, @Nullable Map<String, Long> lifetime,
            @Nullable Map<String, Long> seasonal) {
        String claim = usableClaim(claimId);
        if (imports.contains(claim)) {
            return false;
        }
        CounterMap mergedTallies = merged(tallies, lifetime);
        CounterMap mergedSeasons = merged(seasons, seasonal);
        tallies = mergedTallies;
        seasons = mergedSeasons;
        imports.add(claim);
        return true;
    }

    /** A copy of {@code bag} with every savable tally of {@code added} added to it. */
    @Nonnull
    private static CounterMap merged(@Nonnull CounterMap bag, @Nullable Map<String, Long> added) {
        CounterMap merged = bag.copy();
        if (added != null) {
            for (Map.Entry<String, Long> entry : added.entrySet()) {
                String key = entry.getKey();
                Long value = entry.getValue();
                if (PackedTallies.usableKey(key) && value != null && value > 0L) {
                    merged.add(key.trim(), value);
                }
            }
        }
        return merged;
    }

    /**
     * Carry {@code seconds} of play, and take back the whole seconds to commit once at least
     * {@code commitAt} have gathered ({@code 0} until then); the fraction stays carried. With
     * {@code commitAt} of zero or less, every whole second gathered so far is taken (a logout).
     */
    public synchronized long accruePlaytime(double seconds, double commitAt) {
        if (seconds > 0d) {
            playtimeCarry += seconds;
        }
        if (playtimeCarry < Math.max(commitAt, 1d)) {
            return 0L;
        }
        long whole = (long) playtimeCarry;
        playtimeCarry -= whole;
        return whole;
    }

    @Nonnull
    private static String usableClaim(@Nullable String claimId) {
        if (claimId == null || claimId.isBlank() || claimId.indexOf(CLAIM_SEPARATOR) >= 0
                || !PackedTallies.usableKey(claimId)) {
            throw new IllegalArgumentException("an import claim id must be non-blank and carry no ','"
                    + " or '|': " + claimId);
        }
        return claimId.trim();
    }

    // ==================== The counter framework's store seam ====================

    @Override
    public synchronized long get(@Nonnull Subject subject, @Nonnull String key) {
        return tallies.get(key);
    }

    @Override
    public synchronized void put(@Nonnull Subject subject, @Nonnull String key, long value) {
        if (PackedTallies.usableKey(key)) {
            tallies.set(key, value);
        }
    }

    @Override
    @Nonnull
    public synchronized Map<String, Long> all(@Nonnull Subject subject) {
        return tallies.all();
    }

    @Override
    public synchronized void clear(@Nonnull Subject subject) {
        tallies.clear();
    }

    // ==================== Registration and lookup ====================

    /** Register the type with the entity-store registry, once, at library setup. Never throws. */
    @Nullable
    public static ComponentType<EntityStore, PlayerStatsComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(PlayerStatsComponent.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn("[stats] could not register PlayerStatsComponent", t);
            return null;
        }
    }

    /** Hang the connect hook that attaches a record to every player. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().register(PlayerConnectEvent.class, PlayerStatsComponent::onPlayerConnect);
    }

    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            ComponentType<EntityStore, PlayerStatsComponent> type = TYPE;
            if (type == null) {
                return;
            }
            event.getHolder().ensureAndGetComponent(type);
        } catch (Throwable t) {
            SafeLog.warn("[stats] could not attach the stat record", t);
        }
    }

    /**
     * {@code ref}'s record, peeked: null when the type never registered, the entity carries none (not a
     * player), or the read fails. Read on the world thread that owns {@code store}.
     */
    @Nullable
    public static PlayerStatsComponent peek(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            ComponentType<EntityStore, PlayerStatsComponent> type = TYPE;
            return type == null || !ref.isValid() ? null : store.getComponent(ref, type);
        } catch (Throwable t) {
            SafeLog.warn("[stats] could not read a player's stat record: " + t.getMessage());
            return null;
        }
    }

    @Override
    @SuppressWarnings("CloneDeclaresCloneNotSupported")
    public synchronized PlayerStatsComponent clone() {
        PlayerStatsComponent copy = new PlayerStatsComponent();
        copy.tallies = tallies.copy();
        copy.seasons = seasons.copy();
        copy.imports.addAll(imports);
        copy.playtimeCarry = playtimeCarry;
        return copy;
    }
}
