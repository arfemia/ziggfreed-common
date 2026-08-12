package com.ziggfreed.common.npc;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * One credited conversation: this player talked to this character, and here is every id that
 * character answers to.
 *
 * <p>A RECORD rather than a widening method signature, for the same reason the placement engine's
 * interact context is one: a new component here never breaks an existing sink, while adding a
 * parameter to a functional interface breaks every implementation at once.
 *
 * <p>{@code npcId} is the PRIMARY - what the character is, the id a nameplate reads. {@code answersTo}
 * is the whole set, primary first, in authoring order; a sink crediting per id must skip the primary
 * on the alias pass or one conversation counts twice.
 *
 * <p>{@code npcRef} is null for a credit that came from something other than standing in front of an
 * entity, which is why it is the one nullable handle here.
 */
public record TalkCredit(@Nonnull Store<EntityStore> store,
                         @Nonnull Ref<EntityStore> playerRef,
                         @Nonnull PlayerRef player,
                         @Nullable Ref<EntityStore> npcRef,
                         @Nonnull String npcId,
                         @Nonnull List<String> answersTo,
                         @Nullable String qualifier) {

    public TalkCredit {
        answersTo = List.copyOf(answersTo);
    }

    /** Every id this conversation credits EXCEPT the primary, which the caller credits on its own. */
    @Nonnull
    public List<String> aliases() {
        return answersTo.stream().filter(id -> !id.equalsIgnoreCase(npcId)).toList();
    }
}
