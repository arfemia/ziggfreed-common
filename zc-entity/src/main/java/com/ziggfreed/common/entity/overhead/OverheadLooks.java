package com.ziggfreed.common.entity.overhead;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.Phobia;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.ItemPropEntityService;
import com.ziggfreed.common.icon.IconSpec;

/**
 * How a look becomes an entity: the two ways an {@link IconSpec} is drawn in the world.
 *
 * <ul>
 * <li>An ITEM ID floats as that item, through the same engine-sanctioned prop recipe every other
 * item display in the library uses ({@link ItemPropEntityService}), taken out of the hit indexes
 * and left to the client's own dropped-item idle motion when the look says so.</li>
 * <li>A TEXTURE PATH is drawn on the library's flat CARD: two crossed quads that read from every
 * angle, shipped as a {@code ModelAsset} of this jar's own, with the picture swapped in per look
 * on the live {@link Model} rather than through a file per picture. The engine writes a model's
 * texture straight into the packet, so no {@code ModelAsset} has to exist for the picture itself;
 * the card asset is only there so the model carries the shape, box and scale the engine's own
 * scaled-model factory computes.</li>
 * </ul>
 *
 * <p>Both are network-replicated, never persisted, intangible and unpickable: a cue, not a thing.
 */
final class OverheadLooks {

    /** The id of the shipped card asset ({@code Server/Models/Zc_Overhead_Card.json}). */
    static final String CARD_ASSET_ID = "Zc_Overhead_Card";

    /** The card mesh, Common-rooted, for the bare fallback when the asset store has no card. */
    static final String CARD_MODEL_PATH = "Items/ZiggfreedCommon/Zc_Overhead_Card.blockymodel";

    private static final Rotation3f UPRIGHT = new Rotation3f(0f, 0f, 0f);

    private OverheadLooks() {
    }

    /**
     * Build (but do not add) the marker entity for {@code look} at {@code position}, or null when
     * the look names nothing drawable or the engine refuses the build.
     */
    @Nullable
    static Holder<EntityStore> build(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull OverheadIndicatorAsset look, @Nonnull Vector3d position) {
        IconSpec icon = look.getIcon();
        if (icon == null) {
            return null;
        }
        String itemId = icon.itemId();
        if (itemId != null) {
            ItemPropEntityService.Options options = ItemPropEntityService.Options.DEFAULT.withIntangible();
            if (look.spins()) {
                options = options.withDroppedItemAnimation();
            }
            return ItemPropEntityService.buildHolder(accessor, itemId, position, UPRIGHT,
                    look.effectiveScale(), options);
        }
        String texture = icon.texturePath();
        return texture == null ? null : buildCard(accessor, texture, position, look.effectiveScale());
    }

    @Nullable
    private static Holder<EntityStore> buildCard(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull String texture, @Nonnull Vector3d position, float scale) {
        try {
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            holder.addComponent(NetworkId.getComponentType(),
                    new NetworkId(accessor.getExternalData().takeNextNetworkId()));
            holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(position, UPRIGHT));
            holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(cardModel(texture, scale)));
            holder.addComponent(Intangible.getComponentType(), Intangible.INSTANCE);
            holder.ensureComponent(UUIDComponent.getComponentType());
            holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
            return holder;
        } catch (Throwable t) {
            OverheadIndicators.warn("the card for '" + texture + "' could not be built: " + t.getMessage(), t);
            return null;
        }
    }

    /**
     * The card model wearing {@code texture}: the shipped asset's own scaled model with the picture
     * swapped, or a bare model over the mesh path when the asset store has no card to copy from.
     */
    @Nonnull
    private static Model cardModel(@Nonnull String texture, float scale) {
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(CARD_ASSET_ID);
        if (asset == null) {
            return new Model(null, scale, null, null, null, CARD_MODEL_PATH, texture, null, null,
                    0f, 0f, 0f, 0f, null, null, null, null, null, null, null, Phobia.None, null);
        }
        Model base = Model.createStaticScaledModel(asset, scale);
        return new Model(base.getModelAssetId(), base.getScale(), base.getRandomAttachmentIds(),
                base.getAttachments(), base.getBoundingBox(), base.getModel(), texture, base.getGradientSet(),
                base.getGradientId(), base.getEyeHeight(), base.getCrouchOffset(), base.getSittingOffset(),
                base.getSleepingOffset(), base.getAnimationSetMap(), base.getCamera(), base.getLight(),
                base.getParticles(), base.getTrails(), base.getPhysicsValues(), base.getDetailBoxes(),
                base.getPhobia(), base.getPhobiaModelAssetId());
    }
}
