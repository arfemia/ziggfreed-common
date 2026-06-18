package com.ziggfreed.common.instance.preset;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The {@code defaults < pack < owner} fold authority for {@link InstancePreset}s, keyed
 * by lowercase preset id - the cross-cutting twin of a consumer's gameplay preset config
 * (e.g. Kweebec's {@code PresetConfig} for {@code RuleSet}s). The two key on the SAME id,
 * so a consumer resolves both for one preset without a parallel schema.
 *
 * <p>Per-id override semantics (no template DSL, no mutator stacking - that is
 * consumer-gameplay-specific): {@link #resolve} returns the owner entry, else the pack
 * entry, else the jar default. Each layer is rebuilt wholesale from its source (the
 * pack layer from {@code AssetMergeAdapter.layer(event)} on every load), so a hot
 * re-import is idempotent. A consumer holds the runtime (4th) override tier itself
 * (e.g. Kweebec's {@code KweebecNightmareAPI}), composing it above {@link #resolve}.
 *
 * <p>The singleton is process-wide (one instance-experience framework per server); all
 * writes are synchronized and the maps are concurrent for lock-free reads.
 */
public final class InstancePresetConfig {

    private static final InstancePresetConfig INSTANCE = new InstancePresetConfig();

    @Nonnull
    public static InstancePresetConfig getInstance() {
        return INSTANCE;
    }

    private final Map<String, InstancePreset> defaults = new ConcurrentHashMap<>();
    private final Map<String, InstancePreset> pack = new ConcurrentHashMap<>();
    private final Map<String, InstancePreset> owner = new ConcurrentHashMap<>();

    private InstancePresetConfig() {
    }

    /** Seed the jar baseline layer (Java-authored presets). Replaces any prior defaults. */
    public synchronized void loadDefaults(@Nonnull Map<String, InstancePreset> jarDefaults) {
        defaults.clear();
        defaults.putAll(lower(jarDefaults));
    }

    /** Rebuild the pack layer from a load event's decoded entries (idempotent on re-import). */
    public synchronized void mergePackLayer(@Nonnull Map<String, InstancePreset> layer) {
        pack.clear();
        pack.putAll(lower(layer));
    }

    /** Rebuild the owner-override layer ({@code mods/<mod>/instances.json}, same CODEC). */
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, InstancePreset> layer) {
        owner.clear();
        owner.putAll(lower(layer));
    }

    /** Resolve the preset for {@code id}: owner, else pack, else jar default, else {@code null}. */
    @Nullable
    public InstancePreset resolve(@Nonnull String id) {
        String k = id.toLowerCase(Locale.ROOT);
        InstancePreset o = owner.get(k);
        if (o != null) {
            return o;
        }
        InstancePreset p = pack.get(k);
        if (p != null) {
            return p;
        }
        return defaults.get(k);
    }

    /** Resolve, falling back to {@code fallback} when no layer has the preset. */
    @Nonnull
    public InstancePreset resolveOrDefault(@Nonnull String id, @Nonnull InstancePreset fallback) {
        InstancePreset p = resolve(id);
        return p != null ? p : fallback;
    }

    @Nonnull
    private static Map<String, InstancePreset> lower(@Nonnull Map<String, InstancePreset> in) {
        Map<String, InstancePreset> out = new LinkedHashMap<>();
        for (Map.Entry<String, InstancePreset> e : in.entrySet()) {
            if (e.getValue() != null) {
                out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        return out;
    }
}
