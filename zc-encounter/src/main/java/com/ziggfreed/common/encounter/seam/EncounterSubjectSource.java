package com.ziggfreed.common.encounter.seam;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.subject.Subject;

/**
 * Who a live player IS to the engines that pay and notify them: the {@link Subject} a loot grant is
 * issued to and a feedback moment is drawn for. The wiring root fills it with the progression
 * runtime's own subject source, so the handle a consumer attaches (its notification preferences,
 * its persisted state) is the one an encounter payout sees, exactly as a quest payout does.
 *
 * <p>Asked on the player's world thread. Answer null when there is not enough to build one.
 */
@FunctionalInterface
public interface EncounterSubjectSource {

    /** The subject for the player at {@code playerRef}, or null. */
    @Nullable
    Subject subjectFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef);
}
