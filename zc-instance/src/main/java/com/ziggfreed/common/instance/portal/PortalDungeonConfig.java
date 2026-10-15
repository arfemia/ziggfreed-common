package com.ziggfreed.common.instance.portal;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.portalworld.PortalType;
import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The portal dungeons the library keeps run records for ({@link PortalDungeonAsset}), folded
 * {@code defaults < pack < owner} like every keyed asset type, by PortalType id matched without regard to case.
 */
public final class PortalDungeonConfig extends AbstractKeyedAssetConfig<PortalDungeonAsset> {

    private static final PortalDungeonConfig INSTANCE = new PortalDungeonConfig();

    /** Whether any file follows a dungeon, so a server with none skips every world arrival at once. */
    private volatile boolean anyEnabled;

    @Nonnull
    public static PortalDungeonConfig getInstance() {
        return INSTANCE;
    }

    private PortalDungeonConfig() {
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, PortalDungeonAsset> layer) {
        super.mergePackLayer(layer);
        rebuild();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, PortalDungeonAsset> layer) {
        super.mergeOwnerLayer(layer);
        rebuild();
    }

    /** Whether any loaded file follows a dungeon it is switched on for. */
    public boolean anyEnabled() {
        return anyEnabled;
    }

    /** The file following PortalType {@code portalTypeId} while it is switched on, or null. */
    @Nullable
    public PortalDungeonAsset enabled(@Nullable String portalTypeId) {
        if (portalTypeId == null || portalTypeId.isBlank()) {
            return null;
        }
        PortalDungeonAsset asset = resolve(portalTypeId.trim());
        return asset != null && asset.isEnabled() ? asset : null;
    }

    /**
     * The key portal dungeon {@code portalTypeId} is named by: its file's {@code NameKey}, else the game's own name
     * for the portal ({@code PortalType}'s description), else null when neither is known (no file, or no such
     * PortalType loaded).
     */
    @Nullable
    public String nameKeyOf(@Nonnull String portalTypeId) {
        PortalDungeonAsset asset = resolve(portalTypeId.trim());
        if (asset == null) {
            return null;
        }
        if (asset.nameKey() != null) {
            return asset.nameKey();
        }
        try {
            PortalType type = PortalType.getAssetMap().getAsset(portalTypeId.trim());
            String key = type == null || type.getDescription() == null ? null : type.getDescription().getDisplayNameKey();
            return key == null || key.isBlank() ? null : key.trim();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private void rebuild() {
        boolean any = false;
        for (PortalDungeonAsset asset : all().values()) {
            any |= asset.isEnabled();
        }
        anyEnabled = any;
    }
}
