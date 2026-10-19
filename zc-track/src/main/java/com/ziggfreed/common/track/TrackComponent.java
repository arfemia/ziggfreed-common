package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.track.boost.BoostRun;
import com.ziggfreed.common.track.boost.HeldBoosts;
import com.ziggfreed.common.util.SafeLog;

/**
 * One player's climbs, by key: the current value and its fraction, the best level ever reached, the prestige
 * count and how far each unlock row has paid. Only {@link PlayerTrackStore} reads or writes it; everything
 * else asks the store.
 *
 * <p><b>Saved as a list of entries</b> ({@code Tracks}), each naming its track, and its namespace and run when
 * they are not the default, so a later key (a ruleset, a seasonal run) adds entries and never renames one. A
 * new fact is a new leaf APPENDED to the entry, never a rename; an absent leaf reads as nothing on record. A
 * climb with nothing on record is not saved at all, and an entry for a track that is switched off or gone is
 * kept, unread, until it comes back. How far each unlock row has paid is {@code PaidThrough}, a map from the
 * row's key to its level, so a row added, removed or moved on a live track keeps its own mark. The picks made on a
 * climb are {@code Picks} (slot to the options chosen, comma-joined), {@code Takes} (bought pick to its count) and
 * the slot choices a Remember prestige put aside ({@code Remembered}). The player's own boosts ride beside the
 * climbs ({@code Boosts}: each running start and each wait before the next start of a boost), absent when they
 * hold none, so a save from before boosts reads as none held.
 *
 * <p>{@link #register} runs once at library setup, before any world loads (a component type registered later
 * cannot be read off entities saved carrying it), and {@link #install} hangs the connect hook that attaches one
 * to every player. World thread for every read and write of a live player's record.
 */
public final class TrackComponent implements Component<EntityStore> {

    /** The engine registry id, and the save key. */
    public static final String REGISTRY_ID = "ZiggfreedCommon:Track";

    @Nullable
    public static ComponentType<EntityStore, TrackComponent> TYPE;

    public static final BuilderCodec<TrackComponent> CODEC = BuilderCodec.builder(TrackComponent.class,
                    TrackComponent::new)
            .append(new KeyedCodec<>("Tracks", new ArrayCodec<>(Entry.CODEC, Entry[]::new), false),
                    (c, v, info) -> c.load(v), (c, info) -> c.save())
            .add()
            // When this record was last saved: at a login, how long the player was away (a rested pool fills
            // over it). Written with every save, so the last one before a logout is the logout.
            .append(new KeyedCodec<>("SavedAt", Codec.LONG, false),
                    (c, v) -> c.savedAtMs = v == null ? 0L : v, c -> System.currentTimeMillis())
            .add()
            // The player's own boosts (the track store's boost seam): absent when none is held.
            .append(new KeyedCodec<>("Boosts", BoostsSave.CODEC, false),
                    (c, v) -> c.boosts = BoostsSave.read(v), c -> BoostsSave.of(c.boosts))
            .add()
            .build();

    /** Every climb on record, by {@link #storageKey}, sorted so a save is stable. */
    @Nonnull
    private final TreeMap<String, Held> climbs = new TreeMap<>();

    /** When the record read at load was saved (0 when it carries none, or once it has been asked). */
    private long savedAtMs;

    /** The player's own boosts: their running starts and their waits. Immutable, replaced whole. */
    @Nonnull
    private HeldBoosts boosts = HeldBoosts.EMPTY;

    public TrackComponent() {
    }

    /** The player's own boosts, as last kept ({@link HeldBoosts#EMPTY} when none). */
    @Nonnull
    public HeldBoosts boosts() {
        return boosts;
    }

    /** Keep {@code held} as the player's own boosts, replacing what was kept. */
    public void boosts(@Nonnull HeldBoosts held) {
        this.boosts = held;
    }

    /**
     * How long ago the record read at load was saved, asked once: the time a player was away at login (0 when
     * the record carries no save time, or it was asked already, so a world change counts no time away).
     */
    public long takeAwayMs(long nowMs) {
        long saved = savedAtMs;
        savedAtMs = 0L;
        return saved <= 0L ? 0L : Math.max(0L, nowMs - saved);
    }

    /** One climb on record: where it is filed, and what it holds. */
    private record Held(@Nonnull String namespace, @Nonnull String track, @Nonnull String run,
                        @Nonnull TrackRecord record) {
    }

    /** The record of {@code key}'s climb, or {@link TrackRecord#EMPTY} when none is on record. */
    @Nonnull
    public TrackRecord get(@Nonnull TrackKey key) {
        Held held = climbs.get(storageKey(key.namespace(), key.track(), key.run()));
        return held == null ? TrackRecord.EMPTY : held.record();
    }

    /** Keep {@code record} for {@code key}'s climb; a record holding nothing is forgotten instead. */
    public void put(@Nonnull TrackKey key, @Nonnull TrackRecord record) {
        keep(key.namespace(), key.track(), key.run(), record);
    }

    /** Keep {@code record} under the parts as given (already normalized); nothing on record forgets it. */
    private void keep(@Nonnull String namespace, @Nonnull String track, @Nonnull String run,
            @Nonnull TrackRecord record) {
        String storage = storageKey(namespace, track, run);
        if (record.isEmpty()) {
            climbs.remove(storage);
        } else {
            climbs.put(storage, new Held(namespace, track, run, record));
        }
    }

    /** The track ids this record holds a default climb (no namespace, no run) for, lower-cased. */
    @Nonnull
    public List<String> defaultTracks() {
        List<String> out = new ArrayList<>();
        for (Held held : climbs.values()) {
            if (held.namespace().isEmpty() && held.run().isEmpty()) {
                out.add(held.track());
            }
        }
        return out;
    }

    /** How many climbs are on record. */
    public int size() {
        return climbs.size();
    }

    @Nonnull
    static String storageKey(@Nonnull String namespace, @Nonnull String track, @Nonnull String run) {
        return namespace + '|' + track + '|' + run;
    }

    private void load(@Nullable Entry[] entries) {
        climbs.clear();
        if (entries == null) {
            return;
        }
        for (Entry entry : entries) {
            if (entry == null || entry.track == null || entry.track.isBlank()) {
                continue;
            }
            Map<String, Integer> paid = new HashMap<>();
            if (entry.paidThrough != null) {
                for (Map.Entry<String, Integer> mark : entry.paidThrough.entrySet()) {
                    if (mark.getKey() != null && mark.getValue() != null) {
                        paid.merge(TrackKey.normalize(mark.getKey()), mark.getValue(), Math::max);
                    }
                }
            }
            TrackRecord record = new TrackRecord(entry.value == null ? 0L : entry.value,
                    entry.fraction == null ? 0.0 : entry.fraction, entry.best == null ? 0 : entry.best,
                    entry.prestige == null ? 0 : entry.prestige, paid, entry.rested == null ? 0L : entry.rested,
                    new PickState(choices(entry.picks), entry.takes == null ? Map.of() : entry.takes,
                            choices(entry.remembered)));
            keep(TrackKey.normalize(entry.namespace), TrackKey.normalize(entry.track),
                    TrackKey.normalize(entry.run), record);
        }
    }

    /** Saved slot choices (comma-joined) as lists; a missing leaf reads as none. */
    @Nonnull
    private static Map<String, List<String>> choices(@Nullable Map<String, String> saved) {
        if (saved == null || saved.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new HashMap<>();
        saved.forEach((slot, options) -> {
            if (slot != null) {
                out.put(slot, PickState.split(options));
            }
        });
        return out;
    }

    /** Slot choices as a save keeps them (comma-joined, sorted), or null for none. */
    @Nullable
    private static Map<String, String> saved(@Nonnull Map<String, List<String>> choices) {
        if (choices.isEmpty()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        new TreeMap<>(choices).forEach((slot, options) -> out.put(slot, PickState.join(options)));
        return out;
    }

    @Nonnull
    private Entry[] save() {
        List<Entry> out = new ArrayList<>(climbs.size());
        for (Held held : climbs.values()) {
            out.add(Entry.of(held));
        }
        return out.toArray(new Entry[0]);
    }

    /** Register the type with the entity-store registry, once, at library setup. Never throws. */
    @Nullable
    public static ComponentType<EntityStore, TrackComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(TrackComponent.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn("[track] could not register the track record", t);
            return null;
        }
    }

    /** Hang the connect hook that attaches a record to every player. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().register(PlayerConnectEvent.class, TrackComponent::onPlayerConnect);
    }

    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            ComponentType<EntityStore, TrackComponent> type = TYPE;
            if (type != null) {
                event.getHolder().ensureAndGetComponent(type);
            }
        } catch (Throwable t) {
            SafeLog.warn("[track] could not attach the track record", t);
        }
    }

    @Override
    @SuppressWarnings("CloneDeclaresCloneNotSupported")
    public TrackComponent clone() {
        TrackComponent copy = new TrackComponent();
        copy.climbs.putAll(climbs);
        copy.savedAtMs = savedAtMs;
        copy.boosts = boosts;
        return copy;
    }

    /**
     * The saved shape of the player's own boosts: {@code Runs} (each start's boost, percent, start, end and who
     * started it) and {@code Cooldowns} (per boost id, when its next start may come, in epoch milliseconds). A new
     * fact is a new leaf, never a rename.
     */
    static final class BoostsSave {

        @Nullable RunSave[] runs;
        @Nullable Map<String, Long> cooldowns;

        static final BuilderCodec<BoostsSave> CODEC = BuilderCodec.builder(BoostsSave.class, BoostsSave::new)
                .append(new KeyedCodec<>("Runs", new ArrayCodec<>(RunSave.CODEC, RunSave[]::new), false),
                        (o, v) -> o.runs = v, o -> o.runs)
                .add()
                .append(new KeyedCodec<>("Cooldowns", new MapCodec<>(Codec.LONG, LinkedHashMap::new, false), false),
                        (o, v) -> o.cooldowns = v, o -> o.cooldowns)
                .add()
                .build();

        BoostsSave() {
        }

        /** {@code held} as saved, or null (the leaf left out) when nothing is held. */
        @Nullable
        static BoostsSave of(@Nonnull HeldBoosts held) {
            if (held.isEmpty()) {
                return null;
            }
            BoostsSave save = new BoostsSave();
            if (!held.runs().isEmpty()) {
                List<RunSave> rows = new ArrayList<>(held.runs().size());
                for (BoostRun run : held.runs()) {
                    rows.add(RunSave.of(run));
                }
                save.runs = rows.toArray(new RunSave[0]);
            }
            if (!held.cooldowns().isEmpty()) {
                // Sorted, so a save is stable.
                save.cooldowns = new LinkedHashMap<>(new TreeMap<>(held.cooldowns()));
            }
            return save;
        }

        /** What {@code save} holds, a row naming no boost left out; {@link HeldBoosts#EMPTY} for none. */
        @Nonnull
        static HeldBoosts read(@Nullable BoostsSave save) {
            if (save == null) {
                return HeldBoosts.EMPTY;
            }
            List<BoostRun> runs = new ArrayList<>();
            if (save.runs != null) {
                for (RunSave row : save.runs) {
                    if (row != null && row.boost != null && !row.boost.isBlank()) {
                        runs.add(new BoostRun(row.boost, row.percent == null ? 0.0 : row.percent,
                                row.startedAt == null ? 0L : row.startedAt, row.endsAt == null ? 0L : row.endsAt,
                                row.by == null ? "" : row.by));
                    }
                }
            }
            HeldBoosts held = new HeldBoosts(runs, save.cooldowns == null ? Map.of() : save.cooldowns);
            return held.isEmpty() ? HeldBoosts.EMPTY : held;
        }
    }

    /** One saved start of a boost. */
    static final class RunSave {

        @Nullable String boost;
        @Nullable Double percent;
        @Nullable Long startedAt;
        @Nullable Long endsAt;
        @Nullable String by;

        static final BuilderCodec<RunSave> CODEC = BuilderCodec.builder(RunSave.class, RunSave::new)
                .append(new KeyedCodec<>("Boost", Codec.STRING, false), (o, v) -> o.boost = v, o -> o.boost)
                .add()
                .append(new KeyedCodec<>("Percent", Codec.DOUBLE, false), (o, v) -> o.percent = v, o -> o.percent)
                .add()
                .append(new KeyedCodec<>("StartedAt", Codec.LONG, false), (o, v) -> o.startedAt = v,
                        o -> o.startedAt)
                .add()
                .append(new KeyedCodec<>("EndsAt", Codec.LONG, false), (o, v) -> o.endsAt = v, o -> o.endsAt)
                .add()
                .append(new KeyedCodec<>("By", Codec.STRING, false), (o, v) -> o.by = v, o -> o.by)
                .add()
                .build();

        RunSave() {
        }

        @Nonnull
        static RunSave of(@Nonnull BoostRun run) {
            RunSave row = new RunSave();
            row.boost = run.boost();
            row.percent = run.percent();
            row.startedAt = run.startedAt();
            row.endsAt = run.endsAt();
            row.by = run.by().isEmpty() ? null : run.by();
            return row;
        }
    }

    /** One saved climb. Records are immutable, so a copy shares them safely. */
    static final class Entry {

        @Nullable String track;
        @Nullable String namespace;
        @Nullable String run;
        @Nullable Long value;
        @Nullable Double fraction;
        @Nullable Integer best;
        @Nullable Integer prestige;
        @Nullable Map<String, Integer> paidThrough;
        @Nullable Long rested;
        @Nullable Map<String, String> picks;
        @Nullable Map<String, Integer> takes;
        @Nullable Map<String, String> remembered;

        static final BuilderCodec<Entry> CODEC = BuilderCodec.builder(Entry.class, Entry::new)
                .append(new KeyedCodec<>("Track", Codec.STRING, false), (o, v) -> o.track = v, o -> o.track)
                .add()
                .append(new KeyedCodec<>("Namespace", Codec.STRING, false), (o, v) -> o.namespace = v,
                        o -> o.namespace)
                .add()
                .append(new KeyedCodec<>("Run", Codec.STRING, false), (o, v) -> o.run = v, o -> o.run)
                .add()
                .append(new KeyedCodec<>("Value", Codec.LONG, false), (o, v) -> o.value = v, o -> o.value)
                .add()
                .append(new KeyedCodec<>("Fraction", Codec.DOUBLE, false), (o, v) -> o.fraction = v,
                        o -> o.fraction)
                .add()
                .append(new KeyedCodec<>("Best", Codec.INTEGER, false), (o, v) -> o.best = v, o -> o.best)
                .add()
                .append(new KeyedCodec<>("Prestige", Codec.INTEGER, false), (o, v) -> o.prestige = v,
                        o -> o.prestige)
                .add()
                .append(new KeyedCodec<>("PaidThrough", new MapCodec<>(Codec.INTEGER, LinkedHashMap::new, false),
                                false), (o, v) -> o.paidThrough = v, o -> o.paidThrough)
                .add()
                .append(new KeyedCodec<>("Rested", Codec.LONG, false), (o, v) -> o.rested = v, o -> o.rested)
                .add()
                // The picks: per choice slot the options chosen, comma-joined; per bought pick its count;
                // and the slot choices a Remember prestige put aside, comma-joined.
                .append(new KeyedCodec<>("Picks", new MapCodec<>(Codec.STRING, LinkedHashMap::new, false), false),
                        (o, v) -> o.picks = v, o -> o.picks)
                .add()
                .append(new KeyedCodec<>("Takes", new MapCodec<>(Codec.INTEGER, LinkedHashMap::new, false), false),
                        (o, v) -> o.takes = v, o -> o.takes)
                .add()
                .append(new KeyedCodec<>("Remembered", new MapCodec<>(Codec.STRING, LinkedHashMap::new, false),
                        false), (o, v) -> o.remembered = v, o -> o.remembered)
                .add()
                .build();

        Entry() {
        }

        /** {@code held} as saved: a leaf at its default left out. */
        @Nonnull
        static Entry of(@Nonnull Held held) {
            Entry e = new Entry();
            TrackRecord record = held.record();
            e.track = held.track();
            e.namespace = held.namespace().isEmpty() ? null : held.namespace();
            e.run = held.run().isEmpty() ? null : held.run();
            e.value = record.value();
            e.fraction = record.fraction() == 0.0 ? null : record.fraction();
            e.best = record.best() == 0 ? null : record.best();
            e.prestige = record.prestige() == 0 ? null : record.prestige();
            e.rested = record.rested() == 0L ? null : record.rested();
            if (!record.paid().isEmpty()) {
                // Sorted, so a save is stable.
                e.paidThrough = new LinkedHashMap<>(new TreeMap<>(record.paid()));
            }
            PickState picks = record.picks();
            e.picks = saved(picks.slots());
            e.takes = picks.takes().isEmpty() ? null : new LinkedHashMap<>(new TreeMap<>(picks.takes()));
            e.remembered = saved(picks.remembered());
            return e;
        }
    }
}
