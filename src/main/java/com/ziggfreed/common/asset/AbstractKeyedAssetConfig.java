package com.ziggfreed.common.asset;

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
 * re-import is idempotent. Common's framework stores ship ZERO jar defaults (all content
 * is consumer pack JSON), so {@link #loadDefaults} is optional; a consumer with a Java
 * baseline calls it once at setup.
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

    protected AbstractKeyedAssetConfig() {
    }

    /** Seed the jar baseline layer (Java-authored entries). Replaces any prior defaults. */
    public synchronized void loadDefaults(@Nonnull Map<String, T> jarDefaults) {
        defaults.clear();
        defaults.putAll(lower(jarDefaults));
    }

    /** Rebuild the pack layer from a load event's decoded entries (idempotent on re-import). */
    public synchronized void mergePackLayer(@Nonnull Map<String, T> layer) {
        pack.clear();
        pack.putAll(lower(layer));
    }

    /** Rebuild the owner-override layer (a {@code mods/<mod>/<type>.json} file, same CODEC). */
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, T> layer) {
        owner.clear();
        owner.putAll(lower(layer));
    }

    /** Resolve {@code id}: owner, else pack, else jar default, else {@code null}. */
    @Nullable
    public T resolve(@Nonnull String id) {
        String k = id.toLowerCase(Locale.ROOT);
        T o = owner.get(k);
        if (o != null) {
            return o;
        }
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
     * owner). A fresh snapshot; safe to iterate.
     */
    @Nonnull
    public Map<String, T> all() {
        Map<String, T> out = new LinkedHashMap<>();
        out.putAll(defaults);
        out.putAll(pack);
        out.putAll(owner);
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
}
