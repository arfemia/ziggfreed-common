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
 * row's key to its level, so a row added, removed or moved on a live track keeps its own mark.
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
            .build();

    /** Every climb on record, by {@link #storageKey}, sorted so a save is stable. */
    @Nonnull
    private final TreeMap<String, Held> climbs = new TreeMap<>();

    public TrackComponent() {
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
                    entry.prestige == null ? 0 : entry.prestige, paid);
            keep(TrackKey.normalize(entry.namespace), TrackKey.normalize(entry.track),
                    TrackKey.normalize(entry.run), record);
        }
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
        return copy;
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
            if (!record.paid().isEmpty()) {
                // Sorted, so a save is stable.
                e.paidThrough = new LinkedHashMap<>(new TreeMap<>(record.paid()));
            }
            return e;
        }
    }
}
