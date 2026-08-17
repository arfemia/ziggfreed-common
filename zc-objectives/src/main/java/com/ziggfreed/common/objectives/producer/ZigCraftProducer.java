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

/**
 * Fires {@code CRAFT_ITEM} for the fallback runtime, targeted at the crafted OUTPUT item's id.
 *
 * <p><b>This producer always runs.</b> There is no claim and no stand-down: nothing may register a
 * competing producer for the same native event, so a craft is dispatched here exactly once.
 *
 * <p><b>The output item, not the recipe id.</b> A recipe id is a separate asset key, while the
 * output item id is what an author writes a "craft ten planks" objective against and what the
 * engine's own crafting objective matches. The recipe id is the fallback for a recipe that resolves
 * no output.
 *
 * <p><b>The engine fires ONE event per batch</b>, so the batch size IS the objective amount: a
 * ten-at-once craft has to finish a "craft ten" objective. The moment carries a
 * {@link CraftPayload} naming the RECIPE beside the output-item target, for a consumer keying
 * something per recipe (an XP table, a blacklist) rather than per thing made.
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
        CraftingRecipe recipe = event.getCraftedRecipe();
        if (recipe == null) {
            return;
        }
        // A recipe with no id of its own is not one this counts, even where an output resolves. A
        // consumer's own statistics half reads the same recipe and stops on the same test, and one
        // craft counted as an objective but never as a statistic is exactly how the two halves of
        // a single action come to disagree about what happened.
        String recipeId = recipe.getId();
        if (recipeId == null || recipeId.isBlank()) {
            return;
        }
        String target = craftedTarget(recipe, recipeId);
        Ref<EntityStore> subjectRef = archetypeChunk.getReferenceTo(index);
        PlayerRef playerRef = store.getComponent(subjectRef, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }
        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        Ref<EntityStore> ref = playerEntityRef == null ? subjectRef : playerEntityRef;
        ProgressDispatch.fire(store, ref, commandBuffer, KIND, target, null,
                craftBatchAmount(event.getQuantity()), new CraftPayload(event, recipeId));
    }

    /**
     * The moment's amount for one craft event: every unit of the batch, and never less than one.
     * The engine fires ONE event per batch action, so a ten-at-once craft has to finish a "craft
     * ten" objective; and a quantity the engine reports as zero or negative is still one thing
     * crafted, not nothing. Package-visible so the clamp is pinned with no store anywhere near it.
     */
    static long craftBatchAmount(int rawQuantity) {
        return Math.max(1, rawQuantity);
    }

    /** The output item id an objective is authored against, falling back to the recipe's own id. */
    @Nonnull
    private static String craftedTarget(@Nonnull CraftingRecipe recipe, @Nonnull String recipeId) {
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
        return recipeId;
    }
}
