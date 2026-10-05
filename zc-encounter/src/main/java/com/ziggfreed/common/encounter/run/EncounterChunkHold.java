package com.ziggfreed.common.encounter.run;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.world.TickingSections;

/**
 * Keeps an OPEN fight's chunk sections ticking: while a run is engaged and not yet settled, the tick
 * resets the active timer of the encounter entity's chunk section and of its subject's (the boss the
 * fight is about, which can stand in another section), and of their columns, the lever the engine pulls
 * for a section a player's hot sphere covers, so the fight keeps running long enough for the binding's
 * own guards to decide it. Without it a section nobody is near stops ticking within seconds, the
 * encounter entity is unloaded with it, and the run ends as a world unload before
 * {@code WipeGraceSeconds} could let a player run back or {@code MaxRunSeconds} could time it out. On
 * Update 7 the section is what counts: it sleeps on its own timer whatever its column does (zc-world's
 * {@code TickingSections}), so holding the column alone no longer holds the fight.
 *
 * <p>The hold is the open window, and for an OWNED run (one a consumer spawned with an owner key,
 * a round standing its boss up at an arena the party has not reached yet) the wait before it as
 * well: an owned run's owner, difficulty, party and multiplier live only on the run, which no
 * chunk save carries, so an owned encounter unloaded before its party arrived would come back as
 * a fresh, unowned run. A settled run (defeated or wiped) lets go either way, so a script's
 * re-arm wait, or a boss standing in a section everyone left, follows the engine's ordinary cold
 * schedule: the section sleeps, the entity is stored with it, and the script starts over from its
 * start state the next time it wakes. An unowned run that has not engaged (a placed world boss, a
 * console spawn) is never held. Nothing is pinned loaded, and a section that is not ticking any more
 * is simply not held.
 *
 * <p>World thread only, and called from inside the encounter tick system, so it never wakes a section
 * (a wake re-adds entities, which a store refuses while it processes): it reads the section and resets
 * two timers. Never throws.
 */
public final class EncounterChunkHold {

    private EncounterChunkHold() {
    }

    /**
     * Reset the active timer of the chunk section under {@code encounterRef}, and its column's; answers
     * whether the section was ticking. The encounter tick calls it for the encounter entity and again for
     * its subject, so a fight whose boss stands in another section holds both.
     */
    public static boolean holdTicking(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef) {
        try {
            TransformComponent at = store.getComponent(encounterRef, TransformComponent.getComponentType());
            if (at == null) {
                return false;
            }
            World world = store.getExternalData().getWorld();
            return TickingSections.holdTicking(world, at.getPosition().x, at.getPosition().y, at.getPosition().z);
        } catch (Throwable t) {
            return false;
        }
    }
}
