package com.ziggfreed.common.asset;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The generic, mod-agnostic {@code defaults < pack < owner} fold for a keyed framework
 * config, lifted out of the per-type config singletons that every consumer was
 * re-deriving (Kweebec's {@code PresetConfig}/{@code HunterArchetypeConfig}/... and
 * common's own {@link com.ziggfreed.common.instance.preset.InstancePresetConfig}). A
 * concrete config singleton extends this with one resolved value type {@code T} and adds
 * only its type-specific getters.
 *
 * <p>Per-id resolution: {@link #resolve} returns the owner entry, else the pack entry,
 * else the jar default, else {@code null}. Each layer is rebuilt WHOLESALE from its
 * source (the pack layer from {@link AssetMergeAdapter#layer} on every load), so a hot
 * re-import is idempotent. Common's framework stores keep the {@code defaults} layer for a
 * consumer with a JAVA baseline ({@link #loadDefaults} is optional and called once at
 * setup); a neutral default this library itself ships is an ordinary JSON file in its own
 * jar's asset pack, so it rides the PACK layer and a consumer's same-id file replaces it by
 * pack order rather than by layer.
 *
 * <p>The mod gate (M295): a gated store folds through {@link #mergePackLayer(ModGateFold)}, which keeps
 * what the gate refused, and its owner reader hands in the ids the owner's own gate took out through
 * {@link #mergeOwnerLayer(Map, Map)}. Such an id is gone from every layer, so the pack's ungated version
 * never stands in for the owner's restriction, and both kinds read as refused ({@link #modGateRefused()}).
 * A gated config declares its contract label through {@link #AbstractKeyedAssetConfig(String)}.
 *
 * <p>Ids are lower-cased on every layer so author casing never splits an entry. All
 * writes are synchronized; the maps are concurrent for lock-free reads. The instance is
 * process-wide (one framework per server); a shared store across two minigames disambiguates
 * by id, so ids should be owner-prefixed when two consumers may run together.
 *
 * @param <T> the resolved runtime model type (e.g. {@code InstancePreset})
 */
public abstract class AbstractKeyedAssetConfig<T> {

    private final Map<String, T> defaults = new ConcurrentHashMap<>();
    private final Map<String, T> pack = new ConcurrentHashMap<>();
    private final Map<String, T> owner = new ConcurrentHashMap<>();

    /** The store's contract label, as the subclass declares it; null for a config that declares none. */
    @Nullable
    private final String declaredModGateStore;

    /** The last gated pack fold's store name: the label of a config that declares none. */
    @Nullable
    private volatile String foldModGateStore;

    /** What the last pack fold's mod gate refused: lower-cased id to the missing mod. */
    @Nonnull
    private volatile Map<String, String> packRefused = Map.of();

    /**
     * What the last owner read took out on the owner's own gate (M295's ruling): lower-cased id to the
     * missing mod. Such an id answers nothing on any layer, pack and jar default included.
     */
    @Nonnull
    private volatile Map<String, String> ownerGatedOut = Map.of();

    /**
     * The refusals a gated merge hands to the overridable merge it runs, so the base merge swaps the layer
     * and its refusals in one step and no reader sees them reset in between. Touched only under the lock.
     */
    @Nullable
    private Map<String, String> pendingPackRefused;

    /** The owner read's take-outs, handed over the same way. Touched only under the lock. */
    @Nullable
    private Map<String, String> pendingOwnerGatedOut;

    /** A config whose files carry no mod gate. */
    protected AbstractKeyedAssetConfig() {
        this(null);
    }

    /**
     * A config whose files may gate on a mod.
     *
     * @param modGateStore the store's contract label (its {@code MOD_GATE_STORE}), which every drop line
     *                     carries, the owner's included, whether or not a gated fold has run yet
     */
    protected AbstractKeyedAssetConfig(@Nullable String modGateStore) {
        this.declaredModGateStore = modGateStore;
    }

    /** Seed the jar baseline layer (Java-authored entries). Replaces any prior defaults. */
    public synchronized void loadDefaults(@Nonnull Map<String, T> jarDefaults) {
        defaults.clear();
        defaults.putAll(lower(jarDefaults));
    }

    /**
     * Rebuild the pack layer from a load event's decoded entries (idempotent on re-import). A layer
     * handed in this way refused nothing, so no owner override follows an earlier fold's refusal; one
     * handed in by {@link #mergePackLayer(ModGateFold)} carries that fold's refusals in the same step.
     */
    public synchronized void mergePackLayer(@Nonnull Map<String, T> layer) {
        Map<String, String> refused = pendingPackRefused;
        pendingPackRefused = null;
        replacePack(layer, refused == null ? Map.of() : refused);
    }

    /**
     * Rebuild the pack layer from a gated store's fold ({@link AssetMergeAdapter#gate}) and remember
     * what its mod gate refused, so the owner layer read after it drops an override of a refused file
     * along with that file ({@link OwnerLayerReader}). The subclass's own {@link #mergePackLayer(Map)}
     * still runs, derived views and all, and the refusals land with the layer, never after it.
     */
    public synchronized void mergePackLayer(@Nonnull ModGateFold<T> fold) {
        foldModGateStore = fold.store();
        pendingPackRefused = lowerKeys(fold.refused());
        try {
            mergePackLayer(fold.layer());
        } finally {
            pendingPackRefused = null;
        }
    }

    /** The pack layer and its refusals, replaced together. */
    private void replacePack(@Nonnull Map<String, T> layer, @Nonnull Map<String, String> refused) {
        packRefused = refused;
        pack.clear();
        pack.putAll(lower(layer));
    }

    /**
     * Every id the mod gate keeps out of this store, lower-cased, to its missing mod; empty when nothing.
     * The last pack fold's refusals and the ids the owner's own gate took out: what everything downstream
     * treats as absent on purpose.
     */
    @Nonnull
    public Map<String, String> modGateRefused() {
        Map<String, String> fromPack = packRefused;
        Map<String, String> fromOwner = ownerGatedOut;
        if (fromOwner.isEmpty()) {
            return fromPack;
        }
        if (fromPack.isEmpty()) {
            return fromOwner;
        }
        Map<String, String> out = new LinkedHashMap<>(fromPack);
        fromOwner.forEach(out::putIfAbsent);
        return Collections.unmodifiableMap(out);
    }

    /**
     * What the last pack fold's mod gate refused, lower-cased id to the missing mod: what an owner reader
     * reads, so an override of a refused pack file goes with that file.
     */
    @Nonnull
    public Map<String, String> packModGateRefused() {
        return packRefused;
    }

    /**
     * The store's contract label, which its drop lines carry: the one the config declares, else the last
     * gated fold's store name, else the config's own class name.
     */
    @Nonnull
    public String modGateStore() {
        if (declaredModGateStore != null) {
            return declaredModGateStore;
        }
        String store = foldModGateStore;
        return store == null ? getClass().getSimpleName() : store;
    }

    /**
     * Rebuild the owner-override layer (a {@code mods/<mod>/<type>.json} file, same CODEC). A layer handed
     * in this way takes nothing out; one handed in by {@link #mergeOwnerLayer(Map, Map)} carries its
     * take-outs in the same step.
     */
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, T> layer) {
        Map<String, String> gatedOut = pendingOwnerGatedOut;
        pendingOwnerGatedOut = null;
        replaceOwner(layer, gatedOut == null ? Map.of() : gatedOut);
    }

    /**
     * Rebuild the owner layer and take {@code gatedOut}'s ids out of the store whole: each is an owner
     * entry gated on a missing mod, so the pack's version and the jar default under its id answer nothing
     * either, and it counts as refused ({@link #modGateRefused()}). The subclass's own
     * {@link #mergeOwnerLayer(Map)} still runs.
     *
     * @param gatedOut each taken-out id to the missing mod that took it out
     */
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, T> layer, @Nonnull Map<String, String> gatedOut) {
        pendingOwnerGatedOut = lowerKeys(gatedOut);
        try {
            mergeOwnerLayer(layer);
        } finally {
            pendingOwnerGatedOut = null;
        }
    }

    /** The owner layer and its take-outs, replaced together. */
    private void replaceOwner(@Nonnull Map<String, T> layer, @Nonnull Map<String, String> gatedOut) {
        ownerGatedOut = gatedOut;
        owner.clear();
        owner.putAll(lower(layer));
    }

    /**
     * Resolve {@code id}: owner, else pack, else jar default, else {@code null}. An id the owner's own gate
     * took out is {@code null} on every layer.
     */
    @Nullable
    public T resolve(@Nonnull String id) {
        String k = id.toLowerCase(Locale.ROOT);
        if (ownerGatedOut.containsKey(k)) {
            return null;
        }
        T o = owner.get(k);
        if (o != null) {
            return o;
        }
        return resolveBelow(k);
    }

    /**
     * Resolve {@code id} from the layers under the owner's: pack, else jar default, else {@code null}. What
     * an owner entry decodes against, so a re-read inherits from the packs every time and never from what
     * the last read left, an id it took out included.
     */
    @Nullable
    public T resolveBelowOwner(@Nonnull String id) {
        return resolveBelow(id.toLowerCase(Locale.ROOT));
    }

    @Nullable
    private T resolveBelow(@Nonnull String k) {
        T p = pack.get(k);
        if (p != null) {
            return p;
        }
        return defaults.get(k);
    }

    /** Resolve, falling back to {@code fallback} when no layer has the entry. */
    @Nonnull
    public T resolveOrDefault(@Nonnull String id, @Nonnull T fallback) {
        T p = resolve(id);
        return p != null ? p : fallback;
    }

    /** True when any layer holds {@code id}. */
    public boolean has(@Nonnull String id) {
        return resolve(id) != null;
    }

    /**
     * The fully-folded {@code id -> value} view (defaults overlaid by pack overlaid by
     * owner), without the ids the owner's own gate took out. A fresh snapshot; safe to iterate.
     */
    @Nonnull
    public Map<String, T> all() {
        Map<String, T> out = new LinkedHashMap<>();
        out.putAll(defaults);
        out.putAll(pack);
        out.putAll(owner);
        out.keySet().removeAll(ownerGatedOut.keySet());
        return out;
    }

    /** All effective ids (lowercase), sorted for stable listing. */
    @Nonnull
    public List<String> ids() {
        return all().keySet().stream().sorted().toList();
    }

    @Nonnull
    private Map<String, T> lower(@Nonnull Map<String, T> in) {
        Map<String, T> out = new LinkedHashMap<>();
        for (Map.Entry<String, T> e : in.entrySet()) {
            if (e.getValue() != null) {
                out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        return out;
    }

    /** A refusal map keyed by lower-cased, trimmed id (its missing mod may be null), read-only. */
    @Nonnull
    private static Map<String, String> lowerKeys(@Nonnull Map<String, String> in) {
        if (in.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : in.entrySet()) {
            if (e.getKey() != null && !e.getKey().isBlank()) {
                out.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
