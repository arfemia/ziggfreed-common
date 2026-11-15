package com.ziggfreed.common.damage.onhit;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A consumer's one rule over an on-hit step ({@link Crits#installHitRule}, {@link Lifesteal#installHitRule}): whether
 * this hit may take the step at all, decided from the hit itself (a dispatch that never crits, a tick of its own that
 * takes nothing, a dodge it is about to spare). Asked only of a hit that would otherwise take the step, after the
 * library's own checks, with the damage step's store (read, never written); cheap, and never throws into the step (a
 * rule that throws refuses).
 */
@FunctionalInterface
public interface HitRule {

    boolean allows(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Ref<EntityStore> target, @Nonnull Damage damage);
}
