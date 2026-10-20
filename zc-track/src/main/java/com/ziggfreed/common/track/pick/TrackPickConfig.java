package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.ModGateFold;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.validation.Finding;

/**
 * The {@code defaults < pack < owner} fold of every {@link TrackPickAsset}. A file may gate on a mod through its
 * top-level {@code Requires} (the mod gate): the store's fold and its owner reader count what they leave out under
 * {@link #MOD_GATE_STORE}. Every merge drops the folded picks ({@link TrackPicks#invalidate}), so the next read folds
 * from what is here now, and names once the files whose gate reads more than mod presence.
 *
 * <p><b>Generated picks.</b> The pack layer is the {@link TrackPickGeneratorAsset}s' picks
 * ({@link TrackPickGenerators}) with the hand-written files over them, so a file of a generated id replaces it, and
 * the owner layer reads over both like any pick. Either store's load rebuilds it from the other's last layer, so the
 * owner file is re-read after each ({@link TrackPickOwnerLayers#reload}). An id the mod gate refused is refused
 * whatever wrote it. A generator's axis that names a {@code Source} reads the one registered vocabulary the quest and
 * offer generators read ({@code CommerceCatalogs.axisValues}).
 *
 * <p>An owner entry keeps its pack file's lineage (its {@code Abstract} and its {@code Parent},
 * {@link TrackPickAsset#keepLineageOf}): an owner retunes a pick's leaves, never which skeleton it is.
 */
public final class TrackPickConfig extends AbstractKeyedAssetConfig<TrackPickAsset> {

    /** The store's mod-gate label, which its drop lines carry (a contract the season boot pair parses). */
    public static final String MOD_GATE_STORE = "TrackPicks";

    private static final TrackPickConfig INSTANCE = new TrackPickConfig();

    /** The hand-written files' last gated fold, which every generator merge folds under again. */
    @Nullable private ModGateFold<TrackPickAsset> authored;
    /** The generator files, by lower-cased id, as their store last loaded them. */
    @Nonnull private Map<String, TrackPickGeneratorAsset> generators = Map.of();
    /** What the last expansion of the generators found wrong (the track audit's to report). */
    @Nonnull private volatile List<Finding> generatorIssues = List.of();

    private TrackPickConfig() {
        super(MOD_GATE_STORE);
    }

    @Nonnull
    public static TrackPickConfig getInstance() {
        return INSTANCE;
    }

    /** The hand-written picks' gated fold, with the generated picks folded under them. */
    @Override
    public synchronized void mergePackLayer(@Nonnull ModGateFold<TrackPickAsset> fold) {
        authored = fold;
        super.mergePackLayer(composed(fold));
    }

    /**
     * The generator files as their store loaded them: the generated picks are written again under the hand-written
     * picks of the last fold. Re-read the owner file after it ({@link TrackPickOwnerLayers#reload}).
     */
    public synchronized void mergeGenerators(@Nonnull Map<String, TrackPickGeneratorAsset> layer) {
        Map<String, TrackPickGeneratorAsset> next = new LinkedHashMap<>();
        layer.forEach((id, generator) -> {
            if (id != null && generator != null) {
                next.put(id.toLowerCase(Locale.ROOT), generator);
            }
        });
        generators = Map.copyOf(next);
        ModGateFold<TrackPickAsset> last = authored;
        super.mergePackLayer(composed(last == null
                ? new ModGateFold<>(MOD_GATE_STORE, Map.of(), Map.of()) : last));
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, TrackPickAsset> layer) {
        super.mergePackLayer(layer);
        folded();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, TrackPickAsset> layer) {
        for (Map.Entry<String, TrackPickAsset> entry : layer.entrySet()) {
            TrackPickAsset below = entry.getKey() == null || entry.getValue() == null ? null
                    : resolveBelowOwner(entry.getKey());
            if (below != null) {
                entry.getValue().keepLineageOf(below);
            }
        }
        super.mergeOwnerLayer(layer);
        folded();
    }

    /**
     * Every layer as it stands now: the hand-written files' last fold, the generators, the owner entries and the ids
     * the owner's own gate took out. What a test or a tool that swaps the picks for a while hands to {@link #restore}.
     */
    public record Snapshot(@Nullable ModGateFold<TrackPickAsset> authored,
                           @Nonnull Map<String, TrackPickGeneratorAsset> generators,
                           @Nonnull Map<String, TrackPickAsset> owner, @Nonnull Map<String, String> ownerGatedOut) {
    }

    /** Every layer as it stands now ({@link Snapshot}). */
    @Nonnull
    public synchronized Snapshot snapshot() {
        return new Snapshot(authored, generators, ownerLayer(), ownerGatedOut());
    }

    /** Put every layer back as {@code snapshot} holds it, the generated picks written again under its files. */
    public synchronized void restore(@Nonnull Snapshot snapshot) {
        generators = Map.copyOf(snapshot.generators());
        mergePackLayer(snapshot.authored() == null
                ? new ModGateFold<>(MOD_GATE_STORE, Map.of(), Map.of()) : snapshot.authored());
        if (snapshot.authored() == null) {
            authored = null;
        }
        mergeOwnerLayer(snapshot.owner(), snapshot.ownerGatedOut());
    }

    /** What the last expansion of the generators found wrong; empty when none ran or all was well. */
    @Nonnull
    public List<Finding> generatorIssues() {
        return generatorIssues;
    }

    /** The generator files as last loaded, by lower-cased id. */
    @Nonnull
    public synchronized Map<String, TrackPickGeneratorAsset> generators() {
        return generators;
    }

    /** {@code fold}'s files over what the generators write on them, and every refusal of either. */
    @Nonnull
    private ModGateFold<TrackPickAsset> composed(@Nonnull ModGateFold<TrackPickAsset> fold) {
        if (generators.isEmpty()) {
            generatorIssues = List.of();
            return fold;
        }
        TrackPickGenerators.Expansion expansion = TrackPickGenerators.expand(fold.layer(), fold.refusedIds(),
                generators.values(), CommerceCatalogs.axisValues());
        List<Finding> issues = new ArrayList<>(expansion.issues());
        Map<String, TrackPickAsset> layer = new LinkedHashMap<>(expansion.picks());
        for (String id : expansion.picks().keySet()) {
            if (fold.layer().containsKey(id)) {
                issues.add(TrackPickGenerators.shadowed(id));
            }
        }
        // A generated pick the mod gate refused is refused only where no hand-written file stands in its place.
        Map<String, String> refused = new LinkedHashMap<>();
        expansion.refused().forEach((id, mod) -> {
            if (!fold.layer().containsKey(id)) {
                refused.put(id, mod);
            }
        });
        refused.putAll(fold.refused());
        layer.keySet().removeAll(fold.refused().keySet());
        layer.putAll(fold.layer());
        generatorIssues = List.copyOf(issues);
        return new ModGateFold<>(fold.store(), layer, refused);
    }

    private void folded() {
        TrackPicks.invalidate();
        List<String> widened = new ArrayList<>();
        for (Map.Entry<String, TrackPickAsset> entry : all().entrySet()) {
            if (entry.getValue() != null && entry.getValue().liveRequires() != null) {
                widened.add(entry.getKey());
            }
        }
        StoreGates.reportWidened(MOD_GATE_STORE, widened);
    }
}
