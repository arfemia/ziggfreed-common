package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.ziggfreed.common.progress.runtime.MomentPayload;

/**
 * What rides with a {@code CRAFT_ITEM} moment beyond the crafted OUTPUT item's id: the native event
 * and the RECIPE id, which is a different key from the output item on purpose. An objective is
 * authored against what was made; a consumer keying a per-recipe value (an XP table, a blacklist)
 * needs the recipe, and the moment's target cannot be both.
 *
 * @param event    the engine's own craft event, exactly as {@link ZigCraftProducer} saw it
 * @param recipeId the crafted recipe's own asset id
 */
public record CraftPayload(@Nonnull CraftRecipeEvent.Post event, @Nonnull String recipeId)
        implements MomentPayload {
}
