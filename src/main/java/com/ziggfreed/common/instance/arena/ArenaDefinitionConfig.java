package com.ziggfreed.common.instance.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold authority for {@link ArenaDefinitionAsset}s,
 * keyed by lowercase arena id. A minigame reads the folded arena table back through this
 * singleton and picks an arena to spawn (by id, or weighted by tag), so a pack adds,
 * removes, or re-lays-out an arena purely as DATA (drop a file under
 * {@code Server/ZiggfreedCommon/Arenas/*.json}), never a code edit.
 *
 * <p>The fold mechanics (the three layers, lower-casing, idempotent re-import, resolve
 * order) live in the shared {@link AbstractKeyedAssetConfig} base; this singleton holds
 * the {@link ArenaDefinitionAsset} itself as the resolved type (it carries clean immutable
 * accessors, so it has no separate runtime model), adds {@link #getInstance()}, and the
 * {@link #arenas()} / {@link #byId(String)} / {@link #byTag(String)} read helpers.
 *
 * <p>The store ships ZERO baked-in defaults (all content is consumer pack JSON, per the
 * library paradigm); a consumer with a Java baseline floor calls {@link #loadDefaults}
 * once at setup. The store is registered by ziggfreed-common itself at
 * {@code Server/ZiggfreedCommon/Arenas} (the {@code FrameworkAssetRegistrar} wires the
 * registrar + the {@code LoadedAssetsEvent} merge listener); a consumer authors arenas
 * there and reads them back through this singleton AFTER {@code LoadedAssetsEvent} has
 * fired.
 *
 * <p><b>Thread:</b> the fold maps are concurrent (lock-free reads, synchronized writes),
 * so the read helpers are safe from any thread; no engine call is made here.
 */
public final class ArenaDefinitionConfig extends AbstractKeyedAssetConfig<ArenaDefinitionAsset> {

    private static final ArenaDefinitionConfig INSTANCE = new ArenaDefinitionConfig();

    @Nonnull
    public static ArenaDefinitionConfig getInstance() {
        return INSTANCE;
    }

    private ArenaDefinitionConfig() {
    }

    /**
     * All effective arenas (defaults overlaid by pack overlaid by owner), in stable id
     * order. A fresh snapshot; safe to iterate.
     */
    @Nonnull
    public List<ArenaDefinitionAsset> arenas() {
        List<ArenaDefinitionAsset> out = new ArrayList<>(super.all().values());
        out.sort((a, b) -> a.getId().compareToIgnoreCase(b.getId()));
        return out;
    }

    /** Resolve one arena by id (owner, else pack, else jar default), or {@code null} if absent. */
    @Nullable
    public ArenaDefinitionAsset byId(@Nonnull String id) {
        return resolve(id);
    }

    /**
     * All effective arenas carrying {@code tag} (case-insensitive), in stable id order. A
     * blank tag yields an empty list. A fresh snapshot; safe to iterate.
     */
    @Nonnull
    public List<ArenaDefinitionAsset> byTag(@Nullable String tag) {
        if (tag == null || tag.isBlank()) {
            return List.of();
        }
        String t = tag.trim().toLowerCase(Locale.ROOT);
        List<ArenaDefinitionAsset> out = new ArrayList<>();
        for (ArenaDefinitionAsset a : arenas()) {
            if (a.hasTag(t)) {
                out.add(a);
            }
        }
        return out;
    }
}
