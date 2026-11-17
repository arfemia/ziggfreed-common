package com.ziggfreed.common.track.asset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.progress.gate.GateEpochs;
import com.ziggfreed.common.track.gain.GainPlans;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.track.source.TrackSources;

/**
 * The {@code defaults < pack < owner} fold of every {@link TrackAsset}. A file may gate on a mod through its
 * top-level {@code Requires} (the mod gate): the store's fold and its owner reader count what they leave out
 * under {@link #MOD_GATE_STORE}, and so does the count of unlock reward rows a row's own gate leaves out.
 *
 * <p>A track's {@code Cap} has two owner-side writers, kept apart as the curves' owner layer keeps its two: the
 * owner file {@code tracks.json} ({@link TrackOwnerLayers}) and a consumer turning the server owner's own older
 * settings into a track's Cap ({@link #ownerCap}, per owner). A consumer's Cap sits in the owner layer as if
 * {@code tracks.json} wrote that Cap and nothing else, and a {@code tracks.json} entry for the same id decodes against
 * it ({@link #resolveBelowOwner}), so the file's own {@code Cap} wins while its other leaves keep the consumer's.
 * Nobody writing one, a track's own Cap stands.
 */
public final class TrackConfig extends AbstractKeyedAssetConfig<TrackAsset> {

    /** The store's mod-gate label, which its drop lines carry (a contract the season boot pair parses). */
    public static final String MOD_GATE_STORE = "Tracks";

    private static final TrackConfig INSTANCE = new TrackConfig();

    /** The tracks modules derive in code, per owner: the defaults layer. */
    private final ProvidedLayer<TrackAsset> provided = new ProvidedLayer<>();

    /** The Caps consumers write from the server owner's own older settings, per owner: under tracks.json's Cap. */
    private final ProvidedLayer<Integer> capWords = new ProvidedLayer<>();

    /** What every consumer's Cap word says now, by lower-cased track id. */
    @Nonnull
    private volatile Map<String, Integer> fromConsumers = Map.of();

    /** Has the pack layer been folded yet? Before it, the owner file has nothing to decode against. */
    private volatile boolean packsLanded;

    /** The ids the owner file's last read holds an entry for, lower-cased. */
    @Nonnull
    private volatile Set<String> fileIds = Set.of();

    private TrackConfig() {
        super(MOD_GATE_STORE);
    }

    @Nonnull
    public static TrackConfig getInstance() {
        return INSTANCE;
    }

    /**
     * The tracks {@code owner} derives in code from assets of its own (a reputation's track, from its native
     * group and companion file), by id, replacing whatever it provided before; an empty map withdraws them. They
     * sit under every pack file and the owner's word: a pack's file of the same id replaces one whole, and an
     * owner entry in {@code tracks.json} retunes it leaf by leaf once {@link TrackOwnerLayers#reload} runs again,
     * which the provider calls after this. Any thread.
     */
    public synchronized void provide(@Nonnull String owner, @Nonnull Map<String, TrackAsset> tracks) {
        loadDefaults(provided.provide(owner, tracks));
    }

    /**
     * The {@code Cap} {@code owner} writes for each track id from the server owner's own settings kept in that
     * consumer's older files (a max level per skill), replacing whatever it wrote before; an empty map withdraws
     * them, and two owners writing one id leave it to the first. Each sits in the owner layer over every pack file
     * and the track's own Cap, as an entry in {@code tracks.json} writing that Cap alone would, so every read of the
     * track's Cap (its ceiling, a gain's stop, the validator) reads it; a {@code tracks.json} entry for the same id
     * keeps it unless it writes a Cap of its own, which wins. An id no loaded track has is kept for when one loads.
     * When the word moved and the packs have landed, {@code tracks.json} is read again, so every entry decodes
     * against it. Any thread.
     */
    public void ownerCap(@Nonnull String owner, @Nonnull Map<String, Integer> caps) {
        boolean moved;
        synchronized (this) {
            Map<String, Integer> next = Map.copyOf(capWords.provide(owner, caps));
            moved = !next.equals(fromConsumers);
            fromConsumers = next;
        }
        if (moved && packsLanded) {
            TrackOwnerLayers.reload();
        }
    }

    // A fold may switch a track on or off, or move its curve or cap, which every player's track readings answer:
    // the gate server epoch moves, so no verdict kept over them outlives it. It may also change a track's gain
    // factors or switch off a track a source pays or a pick sits on, so every compiled gain plan, the source index
    // and the folded picks go too (the picks' fold rebuilds every online player's grants). The defaults layer (what
    // modules provide) moves them the same way.
    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, TrackAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        folded();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, TrackAsset> layer) {
        super.mergePackLayer(layer);
        packsLanded = true;
        folded();
    }

    /** The owner file's layer ({@link TrackOwnerLayers}), with every consumer's Cap word under it id by id. */
    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, TrackAsset> layer) {
        super.mergeOwnerLayer(withCapWords(layer));
        folded();
    }

    /**
     * What an owner entry for {@code id} decodes against: the packs' answer (else a module's), with the consumers'
     * Cap word for it written over, so a {@code tracks.json} entry keeps that Cap unless it writes its own.
     */
    @Nullable
    @Override
    public TrackAsset resolveBelowOwner(@Nonnull String id) {
        TrackAsset below = super.resolveBelowOwner(id);
        Integer cap = below == null ? null : fromConsumers.get(key(id));
        return cap == null ? below : below.cappedAt(cap);
    }

    /** The owner file's {@code layer} with a capped track for every Cap word whose id the file has no entry for. */
    @Nonnull
    private Map<String, TrackAsset> withCapWords(@Nonnull Map<String, TrackAsset> layer) {
        Map<String, TrackAsset> file = new LinkedHashMap<>();
        layer.forEach((id, track) -> {
            if (id != null && track != null) {
                file.put(key(id), track);
            }
        });
        fileIds = Set.copyOf(file.keySet());
        Map<String, Integer> words = fromConsumers;
        if (words.isEmpty()) {
            return layer;
        }
        Map<String, TrackAsset> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> word : words.entrySet()) {
            // An id the file writes was decoded against the word already (resolveBelowOwner).
            TrackAsset below = file.containsKey(word.getKey()) ? null : super.resolveBelowOwner(word.getKey());
            if (below != null) {
                out.put(word.getKey(), below.cappedAt(word.getValue()));
            }
        }
        out.putAll(file);
        return out;
    }

    /**
     * The Cap the owner file's own entry for {@code trackId} holds over what that entry decoded against (the packs'
     * Cap and every consumer's Cap word), or null when the file writes no other Cap there: what a consumer's page
     * reads to say that {@code tracks.json}, not its own older setting, decides the track's Cap. Any thread.
     */
    @Nullable
    public Integer ownerFileCap(@Nullable String trackId) {
        if (trackId == null || trackId.isBlank() || !fileIds.contains(key(trackId))) {
            return null;
        }
        TrackAsset owned = resolve(trackId.trim());
        TrackAsset below = resolveBelowOwner(trackId.trim());
        Integer cap = owned == null ? null : owned.cap();
        return cap == null || below == null || cap.equals(below.cap()) ? null : cap;
    }

    @Nonnull
    private static String key(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    private static void folded() {
        GateEpochs.bumpServer();
        GainPlans.invalidateAll();
        TrackSources.invalidate();
        TrackPicks.invalidate();
    }

    /**
     * The track {@code trackId} names (any case) when it is switched on and its id is usable, else null: what
     * every reading and every write asks first, so a switched-off track is absent everywhere.
     */
    @Nullable
    public TrackAsset live(@Nullable String trackId) {
        if (!TrackAsset.usableId(trackId)) {
            return null;
        }
        TrackAsset track = resolve(trackId.trim());
        return track != null && track.isEnabled() ? track : null;
    }

    /** Every switched-on track with a usable id, by {@code Order} then id. */
    @Nonnull
    public List<TrackAsset> liveTracks() {
        List<TrackAsset> out = new ArrayList<>();
        for (TrackAsset track : all().values()) {
            if (track != null && track.isEnabled() && TrackAsset.usableId(track.getId())) {
                out.add(track);
            }
        }
        out.sort(Comparator.comparingInt(TrackAsset::order).thenComparing(TrackAsset::getId,
                String.CASE_INSENSITIVE_ORDER));
        return out;
    }
}
