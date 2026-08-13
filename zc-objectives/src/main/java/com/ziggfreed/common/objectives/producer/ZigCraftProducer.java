package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;

/**
 * Fires {@code CRAFT_ITEM} for the fallback runtime, targeted at the crafted OUTPUT item's id.
 *
 * <p><b>The output item, not the recipe id.</b> A recipe id is a separate asset key, while the
 * output item id is what an author writes a "craft ten planks" objective against and what the
 * engine's own crafting objective matches. The recipe id is the fallback for a recipe that resolves
 * no output.
 *
 * <p><b>The engine fires ONE event per batch</b>, so the batch size IS the objective amount: a
 * ten-at-once craft has to finish a "craft ten" objective.
 *
 * <p><b>Known limitation.</b> The ECS subject of this event may be a workstation rather than the
 * player, and {@code CraftRecipeEvent.Post} carries no accessor naming the crafter (verified against
 * the official server source: it holds a recipe and a quantity, nothing else). So the crafting
 * player is resolved from the subject entity itself, which covers an inventory craft and misses a
 * workstation-emitted one. A workstation craft therefore does not count towards a standalone
 * server's objectives.
 */
public final class ZigCraftProducer extends EntityEventSystem<EntityStore, CraftRecipeEvent.Post> {

    /** The objective kind this producer feeds. */
    public static final String KIND = "CRAFT_ITEM";

    public ZigCraftProducer() {
        super(CraftRecipeEvent.Post.class);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        // Deliberately unfiltered: narrowing to PlayerRef would drop workstation-emitted craft
        // events entirely rather than merely failing to attribute them.
        return Archetype.empty();
    }

    @Override
    public void handle(final int index, @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull final Store<EntityStore> store,
            @Nonnull final CommandBuffer<EntityStore> commandBuffer,
            @Nonnull final CraftRecipeEvent.Post event) {
        if (!ProgressionRuntime.defaultProducesKind(KIND)) {
            return;
        }
        CraftingRecipe recipe = event.getCraftedRecipe();
        if (recipe == null) {
            return;
        }
        String target = craftedTarget(recipe);
        if (target == null) {
            return;
        }
        Ref<EntityStore> subjectRef = archetypeChunk.getReferenceTo(index);
        PlayerRef playerRef = store.getComponent(subjectRef, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }
        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        Ref<EntityStore> ref = playerEntityRef == null ? subjectRef : playerEntityRef;
        ProgressDispatch.fire(store, ref, playerRef, KIND, target, null,
                Math.max(1, event.getQuantity()));
    }

    /** The output item id an objective is authored against, or the recipe id when there is none. */
    @Nullable
    private static String craftedTarget(@Nonnull CraftingRecipe recipe) {
        var primary = recipe.getPrimaryOutput();
        String primaryId = primary == null ? null : primary.getItemId();
        if (primaryId != null && !primaryId.isBlank()) {
            return primaryId;
        }
        if (recipe.getOutputs() != null) {
            for (var output : recipe.getOutputs()) {
                String outputId = output == null ? null : output.getItemId();
                if (outputId != null && !outputId.isBlank()) {
                    return outputId;
                }
            }
        }
        String recipeId = recipe.getId();
        return recipeId == null || recipeId.isBlank() ? null : recipeId;
    }
}
