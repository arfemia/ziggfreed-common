package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.Arrays;
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
 * climb are its one {@code Build} leaf ({@link BuildSave}): {@code Choices} (each slot made, to the options chosen
 * there, in the order chosen), {@code Order} (every choice and take in the order it was made: a slot key for a choice,
 * a pick id once for each take, so a pick taken three times is in it three times and its count is read off it),
 * {@code Remembered} (what a Remember prestige put aside: its {@code Choices} and its {@code Takes} in order),
 * {@code Loadouts} (each saved build: its {@code Slot}, {@code Choices} and {@code Order}, as the picks in use write
 * them) and {@code Active} (the saved build the picks in use came from). The player's own boosts ride beside the
 * climbs ({@code Boosts}: each running start and each wait before the next start of a boost), absent when they hold
 * none, so a save from before boosts reads as none held.
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
                    BuildSave.read(entry.build));
            keep(TrackKey.normalize(entry.namespace), TrackKey.normalize(entry.track),
                    TrackKey.normalize(entry.run), record);
        }
    }

    /** Saved slot choices as lists; a missing leaf reads as none. */
    @Nonnull
    private static Map<String, List<String>> choices(@Nullable Map<String, String[]> saved) {
        if (saved == null || saved.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new HashMap<>();
        saved.forEach((slot, options) -> {
            if (slot != null && options != null) {
                out.put(slot, Arrays.asList(options));
            }
        });
        return out;
    }

    /** Slot choices as a save keeps them (sorted by slot), or null for none. */
    @Nullable
    private static Map<String, String[]> saved(@Nonnull Map<String, List<String>> choices) {
        if (choices.isEmpty()) {
            return null;
        }
        Map<String, String[]> out = new LinkedHashMap<>();
        new TreeMap<>(choices).forEach((slot, options) -> out.put(slot, options.toArray(new String[0])));
        return out;
    }

    /** A saved id list as a list; a missing leaf reads as none. */
    @Nonnull
    private static List<String> list(@Nullable String[] saved) {
        return saved == null ? List.of() : Arrays.asList(saved);
    }

    /** An id list as a save keeps it, or null for none. */
    @Nullable
    private static String[] savedIds(@Nonnull List<String> ids) {
        return ids.isEmpty() ? null : ids.toArray(new String[0]);
    }

    /** How many times each pick id is taken in {@code order} (every entry that is no slot key). */
    @Nonnull
    private static Map<String, Integer> takesIn(@Nonnull List<String> order) {
        Map<String, Integer> out = new HashMap<>();
        for (String step : order) {
            if (step != null && !PickState.isSlotKey(step)) {
                out.merge(step, 1, Integer::sum);
            }
        }
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

    /**
     * Register the type with the entity-store registry, once, at library setup. Not guarded: a failed registration
     * throws here, at setup, rather than as every climb going unsaved later.
     */
    @Nonnull
    public static ComponentType<EntityStore, TrackComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        TYPE = registry.registerComponent(TrackComponent.class, REGISTRY_ID, CODEC);
        return TYPE;
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
     * The saved shape of the player's own boosts: {@code Runs} (each start's boost, percent, start, end, who
     * started it and the one track it names, if any) and {@code Cooldowns} (per boost id, when its next start may come, in epoch milliseconds). A new
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
                                row.by == null ? "" : row.by, row.track));
                    }
                }
            }
            HeldBoosts held = new HeldBoosts(runs, save.cooldowns == null ? Map.of() : save.cooldowns);
            return held.isEmpty() ? HeldBoosts.EMPTY : held;
        }
    }

    /** One saved start of a boost; {@code Track} only when the start names one. */
    static final class RunSave {

        @Nullable String boost;
        @Nullable Double percent;
        @Nullable Long startedAt;
        @Nullable Long endsAt;
        @Nullable String by;
        @Nullable String track;

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
                .append(new KeyedCodec<>("Track", Codec.STRING, false), (o, v) -> o.track = v, o -> o.track)
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
            row.track = run.track();
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
        @Nullable BuildSave build;

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
                // The picks made on the climb, every one of them in this one leaf.
                .append(new KeyedCodec<>("Build", BuildSave.CODEC, false), (o, v) -> o.build = v, o -> o.build)
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
            e.build = BuildSave.of(record.picks());
            return e;
        }
    }

    /**
     * The saved shape of the picks made on one climb, every one of them in one leaf ({@code Build}): {@code Choices}
     * (each slot made, to the options chosen there, in the order chosen), {@code Order} (every choice, as its slot
     * key, and every take, as its pick id, once per take, in the order made: a take's count is read off it),
     * {@code Remembered} (what a Remember prestige put aside), {@code Loadouts} (the saved builds) and {@code Active}
     * (the saved build the picks in use came from). Absent when nothing is picked, remembered or saved. A new fact
     * is a new leaf, never a rename.
     */
    static final class BuildSave {

        @Nullable Map<String, String[]> choices;
        @Nullable String[] order;
        @Nullable RememberedSave remembered;
        @Nullable LoadoutSave[] loadouts;
        @Nullable Integer active;

        static final BuilderCodec<BuildSave> CODEC = BuilderCodec.builder(BuildSave.class, BuildSave::new)
                .append(new KeyedCodec<>("Choices", new MapCodec<>(Codec.STRING_ARRAY, LinkedHashMap::new, false),
                        false), (o, v) -> o.choices = v, o -> o.choices)
                .add()
                .append(new KeyedCodec<>("Order", Codec.STRING_ARRAY, false), (o, v) -> o.order = v, o -> o.order)
                .add()
                .append(new KeyedCodec<>("Remembered", RememberedSave.CODEC, false),
                        (o, v) -> o.remembered = v, o -> o.remembered)
                .add()
                .append(new KeyedCodec<>("Loadouts", new ArrayCodec<>(LoadoutSave.CODEC, LoadoutSave[]::new),
                        false), (o, v) -> o.loadouts = v, o -> o.loadouts)
                .add()
                .append(new KeyedCodec<>("Active", Codec.INTEGER, false), (o, v) -> o.active = v, o -> o.active)
                .add()
                .build();

        BuildSave() {
        }

        /** {@code picks} as saved, or null (the leaf left out) when nothing is picked, remembered or saved. */
        @Nullable
        static BuildSave of(@Nonnull PickState picks) {
            if (picks.isEmpty()) {
                return null;
            }
            BuildSave save = new BuildSave();
            save.choices = saved(picks.slots());
            save.order = savedIds(picks.order());
            save.remembered = RememberedSave.of(picks);
            if (!picks.loadouts().isEmpty()) {
                List<LoadoutSave> builds = new ArrayList<>();
                new TreeMap<>(picks.loadouts()).forEach((slot, build) -> builds.add(LoadoutSave.of(slot, build)));
                save.loadouts = builds.toArray(new LoadoutSave[0]);
            }
            save.active = picks.activeLoadout() == 0 ? null : picks.activeLoadout();
            return save;
        }

        /** What {@code save} holds; {@link PickState#EMPTY} for none. */
        @Nonnull
        static PickState read(@Nullable BuildSave save) {
            if (save == null) {
                return PickState.EMPTY;
            }
            List<String> order = list(save.order);
            RememberedSave aside = save.remembered;
            Map<Integer, PickState.Loadout> builds = new HashMap<>();
            if (save.loadouts != null) {
                for (LoadoutSave build : save.loadouts) {
                    if (build != null && build.slot != null) {
                        builds.put(build.slot, new PickState.Loadout(choices(build.choices), list(build.order)));
                    }
                }
            }
            return new PickState(choices(save.choices), takesIn(order),
                    aside == null ? Map.of() : choices(aside.choices), order,
                    aside == null ? List.of() : list(aside.takes), builds, save.active == null ? 0 : save.active);
        }
    }

    /** What a Remember prestige put aside: its slot {@code Choices} and its {@code Takes}, oldest first. */
    static final class RememberedSave {

        @Nullable Map<String, String[]> choices;
        @Nullable String[] takes;

        static final BuilderCodec<RememberedSave> CODEC = BuilderCodec.builder(RememberedSave.class,
                        RememberedSave::new)
                .append(new KeyedCodec<>("Choices", new MapCodec<>(Codec.STRING_ARRAY, LinkedHashMap::new, false),
                        false), (o, v) -> o.choices = v, o -> o.choices)
                .add()
                .append(new KeyedCodec<>("Takes", Codec.STRING_ARRAY, false), (o, v) -> o.takes = v, o -> o.takes)
                .add()
                .build();

        RememberedSave() {
        }

        /** What {@code picks} holds aside, or null when nothing is. */
        @Nullable
        static RememberedSave of(@Nonnull PickState picks) {
            if (picks.remembered().isEmpty() && picks.rememberedTakes().isEmpty()) {
                return null;
            }
            RememberedSave save = new RememberedSave();
            save.choices = saved(picks.remembered());
            save.takes = savedIds(picks.rememberedTakes());
            return save;
        }
    }

    /** One saved build: its {@code Slot}, {@code Choices} and {@code Order}, as {@link BuildSave} writes them. */
    static final class LoadoutSave {

        @Nullable Integer slot;
        @Nullable Map<String, String[]> choices;
        @Nullable String[] order;

        static final BuilderCodec<LoadoutSave> CODEC = BuilderCodec.builder(LoadoutSave.class, LoadoutSave::new)
                .append(new KeyedCodec<>("Slot", Codec.INTEGER, false), (o, v) -> o.slot = v, o -> o.slot)
                .add()
                .append(new KeyedCodec<>("Choices", new MapCodec<>(Codec.STRING_ARRAY, LinkedHashMap::new, false),
                        false), (o, v) -> o.choices = v, o -> o.choices)
                .add()
                .append(new KeyedCodec<>("Order", Codec.STRING_ARRAY, false), (o, v) -> o.order = v, o -> o.order)
                .add()
                .build();

        LoadoutSave() {
        }

        @Nonnull
        static LoadoutSave of(int slot, @Nonnull PickState.Loadout build) {
            LoadoutSave save = new LoadoutSave();
            save.slot = slot;
            save.choices = saved(build.slots());
            save.order = savedIds(build.order());
            return save;
        }
    }
}
