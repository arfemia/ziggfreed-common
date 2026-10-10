package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.runtime.MomentPayload;

/**
 * What rides with an {@code ENCOUNTER_ATTEMPT} or {@code ENCOUNTER_DEFEATED} moment an encounter rule
 * fires ({@link EncounterRuleCredit}) beyond its role-id target: the creature killed, the rule that
 * counted it, and the lang key that rule names the fight by. Unlike {@link EncounterPayload} it
 * carries no run, no native event and no share: a rule's fight is one kill, whole to the player who
 * landed it. A reaction tells the two apart by which payload the moment carries.
 *
 * @param victimRef the creature that died; its corpse still holds a position and a uuid
 * @param roleId    the creature's role id, the moment's own target
 * @param ruleId    the encounter rule that counted the kill
 * @param nameKey   the rule's {@code NameKey}, or null when it names none
 */
public record EncounterRulePayload(@Nonnull Ref<EntityStore> victimRef, @Nonnull String roleId,
                                   @Nonnull String ruleId, @Nullable String nameKey) implements MomentPayload {
}
