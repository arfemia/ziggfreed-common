package com.ziggfreed.common.track.source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.diminish.DiminishSpec;
import com.ziggfreed.common.match.MomentTable;
import com.ziggfreed.common.match.When;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackConfig;

/**
 * Every track source folded into one table per track ({@link MomentTable}, zc-core), and the index a produced
 * moment is answered through: the kinds some source listens for, and per kind the tracks whose table has a row for
 * it. Built on the first ask after a source or track fold ({@link #invalidate}), in sorted id order, so two files
 * writing one pattern for one moment resolve to the one whose id sorts last.
 *
 * <p><b>What it costs when nothing listens.</b> {@link #listens} is one lookup in a map of the kinds some source
 * names; the reaction ({@link TrackSourceReactions}) asks it before reading anything off the moment.
 *
 * <p><b>Summed kinds.</b> A kind fired many times a second ({@link #batches}: {@code DEAL_DAMAGE}, {@code HEAL_GIVEN},
 * {@code SPRINT_DISTANCE} and {@code SWIM_DISTANCE}, plus any {@link #batchKind} adds) is summed per player and
 * track and paid once a tick ({@link SourceBatch}), the same totals as one gain a moment, so the gain pipeline and
 * {@code ZigTrackChangedEvent} run once per batch.
 */
public final class TrackSources {

    /** The damage a player deals a creature: the kind zc's damage producer fires for every credited hit. */
    public static final String DEAL_DAMAGE = "DEAL_DAMAGE";

    /**
     * A heal a player gives themselves or an ally: the kind zc's heal producer fires, Target {@value #HEAL_SELF} or
     * {@value #HEAL_PLAYER}, Qualifier the heal's source (the track or skill it names), its amount the health it
     * restored out of the healed player's recent creature damage.
     */
    public static final String HEAL_GIVEN = "HEAL_GIVEN";

    /** A {@value #HEAL_GIVEN} moment's Target for a heal on oneself. */
    public static final String HEAL_SELF = "Self";

    /** A {@value #HEAL_GIVEN} moment's Target for a heal on an allied player. */
    public static final String HEAL_PLAYER = "Player";

    /** What every gain a source pays is labelled with, before the source's id. */
    public static final String SOURCE_PREFIX = "tracksource:";

    private static final Set<String> BATCHED = ConcurrentHashMap.newKeySet();

    private static final AtomicInteger GENERATION = new AtomicInteger();

    static {
        BATCHED.add(DEAL_DAMAGE);
        BATCHED.add(HEAL_GIVEN);
        BATCHED.add("SPRINT_DISTANCE");
        BATCHED.add("SWIM_DISTANCE");
    }

    /**
     * What one row pays, folded: the source it came from, the track it pays onto, its row, and the source's own
     * knobs.
     *
     * @param sourceId  the source's id, lower-cased
     * @param trackId   the track's id as its file spells it
     * @param label     what the gain is labelled with ({@value #SOURCE_PREFIX} and the source id)
     * @param row       what it pays and the level it waits for
     * @param requires  the source's live {@code Requires}, or null
     * @param diminish  the source's diminishing returns, or null
     * @param skipSpawned whether a spawner's mob pays nothing
     */
    public record Paid(@Nonnull String sourceId, @Nonnull String trackId, @Nonnull String label,
                       @Nonnull SourceRow row, @Nullable GateSpec requires, @Nullable DiminishSpec diminish,
                       boolean skipSpawned) {
    }

    /** One track's table, for the moments of one kind. */
    record TrackTable(@Nonnull String trackId, @Nonnull MomentTable<Paid> table) {
    }

    /** One fold: per kind the tracks listening, per track its table. */
    record Folded(@Nonnull Map<String, List<TrackTable>> byKind, @Nonnull Map<String, MomentTable<Paid>> byTrack) {
    }

    @Nullable private static volatile Folded folded;

    private TrackSources() {
    }

    /** Does some source listen for {@code kindId}? One lookup. */
    public static boolean listens(@Nullable String kindId) {
        return kindId != null && lookup(folded().byKind(), kindId) != null;
    }

    /** Is {@code kindId} summed per player and track and paid once a tick? */
    public static boolean batches(@Nullable String kindId) {
        if (kindId == null || BATCHED.contains(kindId)) {
            return kindId != null;
        }
        String kind = MomentTable.canonical(kindId) ? null : When.normalizeKind(kindId);
        return kind != null && BATCHED.contains(kind);
    }

    /** Sum {@code kindId}'s moments per player and track and pay them once a tick (a mod's own frequent kind). */
    public static void batchKind(@Nullable String kindId) {
        String kind = When.normalizeKind(kindId);
        if (kind != null) {
            BATCHED.add(kind);
        }
    }

    /** {@code trackId}'s folded table (any case), or an empty one (an audit, an admin view). */
    @Nonnull
    public static MomentTable<Paid> tableOf(@Nullable String trackId) {
        MomentTable<Paid> table = trackId == null ? null : folded().byTrack().get(TrackKey.normalize(trackId));
        return table == null ? MomentTable.empty() : table;
    }

    /**
     * A source or track folded: the next ask folds again. Taken under the fold's own lock, so a fold already in
     * flight finishes first and its table is dropped here, never stored after this (a stale table outliving the
     * reload).
     */
    public static void invalidate() {
        synchronized (TrackSources.class) {
            folded = null;
            GENERATION.incrementAndGet();
        }
    }

    /**
     * How many times the sources (or the tracks they pay onto) have folded since start. Only ever grows. A consumer
     * keeping a view of its own source files (a table it reads at its own moments) folds it into its epoch and builds
     * the view again when it moves, with no callback.
     */
    public static int generation() {
        return GENERATION.get();
    }

    /** The tracks with a row for {@code kindId}, or null when none has. */
    @Nullable
    static List<TrackTable> forKind(@Nonnull String kindId) {
        return lookup(folded().byKind(), kindId);
    }

    /**
     * {@code kindId}'s tables: one lookup, and a second through {@link When#normalizeKind} only for a kind not
     * already spelt as a producer fires it (upper case), so a moment no source names allocates nothing.
     */
    @Nullable
    private static List<TrackTable> lookup(@Nonnull Map<String, List<TrackTable>> byKind, @Nonnull String kindId) {
        if (byKind.isEmpty()) {
            return null;
        }
        List<TrackTable> tables = byKind.get(kindId);
        if (tables != null || MomentTable.canonical(kindId)) {
            return tables;
        }
        String kind = When.normalizeKind(kindId);
        return kind == null ? null : byKind.get(kind);
    }

    @Nonnull
    private static Folded folded() {
        Folded local = folded;
        if (local == null) {
            synchronized (TrackSources.class) {
                local = folded;
                if (local == null) {
                    local = fold(TrackSourceConfig.getInstance().all());
                    folded = local;
                }
            }
        }
        return local;
    }

    /** Fold {@code sources}: enabled ones naming a kind and a switched-on track, in sorted id order. */
    @Nonnull
    static Folded fold(@Nonnull Map<String, TrackSourceAsset> sources) {
        List<String> ids = new ArrayList<>(sources.keySet());
        Collections.sort(ids);
        Map<String, List<MomentTable.Row<Paid>>> rowsByTrack = new LinkedHashMap<>();
        for (String id : ids) {
            TrackSourceAsset source = sources.get(id);
            When when = source == null ? null : source.when();
            if (source == null || !source.isEnabled() || when == null || when.kindId() == null) {
                continue;
            }
            TrackAsset track = TrackConfig.getInstance().live(source.trackId());
            if (track == null) {
                continue;
            }
            String label = SOURCE_PREFIX + id;
            List<MomentTable.Row<Paid>> rows = rowsByTrack.computeIfAbsent(TrackKey.normalize(track.getId()),
                    key -> new ArrayList<>());
            SourceRow base = source.baseRow();
            if (base != null) {
                rows.add(MomentTable.Row.of(when, paid(id, track, label, base, source)));
            }
            for (Map.Entry<String, SourceRow> row : source.rows().entrySet()) {
                if (row.getValue() != null) {
                    rows.add(MomentTable.Row.of(when.kindId(), row.getKey(), when.qualifier(),
                            paid(id, track, label, row.getValue().withDefaultMax(source.max()), source)));
                }
            }
        }
        Map<String, MomentTable<Paid>> byTrack = new LinkedHashMap<>();
        Map<String, List<TrackTable>> byKind = new LinkedHashMap<>();
        for (Map.Entry<String, List<MomentTable.Row<Paid>>> e : rowsByTrack.entrySet()) {
            MomentTable<Paid> table = MomentTable.fold(e.getValue());
            if (table.size() == 0) {
                continue;
            }
            byTrack.put(e.getKey(), table);
            for (String kind : table.kinds()) {
                byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(new TrackTable(e.getKey(), table));
            }
        }
        Map<String, List<TrackTable>> frozen = new LinkedHashMap<>();
        byKind.forEach((kind, tables) -> frozen.put(kind, List.copyOf(tables)));
        return new Folded(Collections.unmodifiableMap(frozen), Collections.unmodifiableMap(byTrack));
    }

    @Nonnull
    private static Paid paid(@Nonnull String id, @Nonnull TrackAsset track, @Nonnull String label,
            @Nonnull SourceRow row, @Nonnull TrackSourceAsset source) {
        return new Paid(id, track.getId(), label, row, source.liveRequires(), source.diminish(),
                source.skipsSpawnerMobs());
    }
}
