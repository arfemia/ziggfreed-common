package com.ziggfreed.common.track.asset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
 */
public final class TrackConfig extends AbstractKeyedAssetConfig<TrackAsset> {

    /** The store's mod-gate label, which its drop lines carry (a contract the season boot pair parses). */
    public static final String MOD_GATE_STORE = "Tracks";

    private static final TrackConfig INSTANCE = new TrackConfig();

    /** The tracks modules derive in code, per owner: the defaults layer. */
    private final ProvidedLayer<TrackAsset> provided = new ProvidedLayer<>();

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
        folded();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, TrackAsset> layer) {
        super.mergeOwnerLayer(layer);
        folded();
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
