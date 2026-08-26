package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * "This killed entity carries THAT qualifier." The seam the kill producer asks at fire time, so a
 * kill moment can carry a qualifier for the killed entity - e.g. a difficulty tier a companion mod
 * attributes to the mobs it scales - and content authoring that qualifier on a {@code KILL_ENTITY}
 * criterion actually matches.
 *
 * <p>Without it every kill fires unqualified: an authored qualifier on a kill criterion can never
 * match, because the producer knows nothing about what any mod layered onto the victim. With it the
 * ONE primary dispatch per kill carries the answer, so a qualified criterion matches the kills it
 * names while an unqualified criterion keeps matching every kill (the matching rule reads an empty
 * AUTHORED qualifier as "any"). There is deliberately no second qualified re-fire - that would count
 * one kill twice for every unqualified criterion.
 *
 * <p><b>Contributions STACK, first real answer wins.</b> Every registered qualifier is asked in
 * registration order and the first non-null answer stands, so two mods that each qualify their own
 * kind of entity never have to know the other exists; one that THROWS is skipped with a warn and
 * the next is asked. Nothing registered means every kill fires unqualified, which is what a bare
 * server has always done.
 *
 * <p>Asked on the world thread, inside the death dispatch, with the victim's ref still valid.
 * Answer null for an entity this contribution knows nothing about.
 */
@FunctionalInterface
public interface KillQualifier {

    /** Qualifies nothing: the composed answer on a runtime nobody registered one into. */
    KillQualifier NONE = (store, victimRef) -> null;

    /**
     * The qualifier this kill carries, or null when this contribution does not know one.
     *
     * @param store     the store the victim lives in
     * @param victimRef the killed entity
     */
    @Nullable
    String qualifierFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> victimRef);
}
