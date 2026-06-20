package com.ziggfreed.common.entity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Swaps a PLAYER's rendered model and restores it, mod-agnostic. Mirrors the engine's
 * own {@code builtin.model.pages.ChangeModelPage}: {@link #apply} is the page's
 * ChangeModel path (resolve a {@link ModelAsset}, build a scaled {@link Model}, replace
 * the immutable {@link ModelComponent}); {@link #restore} is the page's ResetModel path
 * (rebuild from the player's {@link PlayerSkinComponent} via
 * {@code CosmeticsModule.get().createModel(skin)} so the correct cosmetic model comes
 * back, NOT a cached {@code Model} reference which would be stale).
 *
 * <p>WORLD-THREAD ONLY: the caller guarantees the world thread for every {@code Store} /
 * {@code Ref} read and {@code putComponent} (this primitive does not hop threads itself).
 * Every engine-touching call is try-guarded, so a missing asset / bad ref degrades to a
 * silent no-op and never throws into the caller. A missing model id is warned at most
 * once per id.
 */
public final class PlayerModelService {

    /** Model ids already warned about, so a missing id logs once, not every call. */
    @Nonnull
    private static final Set<String> WARNED_MISSING = ConcurrentHashMap.newKeySet();

    private PlayerModelService() {
    }

    /**
     * Swap the entity's rendered model to {@code modelId} at {@code scale}.
     *
     * @param ref     the entity (a player) whose model to replace
     * @param store   the entity store (world thread)
     * @param modelId a {@link ModelAsset} id; missing/invalid = no-op (warned once)
     * @param scale   render scale; clamped to the asset's valid range (must be {@code > 0})
     * @return {@code true} if a new model was applied, {@code false} on any no-op
     */
    public static boolean apply(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull String modelId, float scale) {
        try {
            ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(modelId);
            if (modelAsset == null) {
                if (WARNED_MISSING.add(modelId)) {
                    ZiggfreedCommonPlugin.LOGGER.atWarning()
                        .log("PlayerModelService.apply: no ModelAsset for id '" + modelId + "' (no-op)");
                }
                return false;
            }
            // createScaledModel throws on scale <= 0; floor it to the smallest positive value.
            float safeScale = scale > 0.0F ? scale : 1.0F;
            Model model = Model.createScaledModel(modelAsset, safeScale);
            store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(model));
            return true;
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("PlayerModelService.apply failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Restore the player's correct cosmetic model from their {@link PlayerSkinComponent}
     * (the engine ResetModel path). Idempotent and safe to call on any player; a no-op
     * for an entity with no skin component or when the cosmetics rebuild yields null.
     *
     * @param ref   the player whose model to restore
     * @param store the entity store (world thread)
     */
    public static void restore(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            PlayerSkinComponent skin = store.getComponent(ref, PlayerSkinComponent.getComponentType());
            if (skin == null) {
                return;
            }
            Model model = CosmeticsModule.get().createModel(skin.getPlayerSkin());
            if (model == null) {
                return;
            }
            store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(model));
            skin.setNetworkOutdated();
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("PlayerModelService.restore failed: " + t.getMessage());
        }
    }

    /**
     * @param modelId a {@link ModelAsset} id to test
     * @return {@code true} if the id resolves to a registered model (early preset validation)
     */
    public static boolean modelExists(@Nonnull String modelId) {
        try {
            return ModelAsset.getAssetMap().getAsset(modelId) != null;
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("PlayerModelService.modelExists failed: " + t.getMessage());
            return false;
        }
    }
}
