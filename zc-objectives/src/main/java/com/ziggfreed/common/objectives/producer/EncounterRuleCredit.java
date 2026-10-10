package com.ziggfreed.common.objectives.producer;

import java.util.function.BooleanSupplier;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.asset.EncounterRuleAsset;
import com.ziggfreed.common.encounter.asset.EncounterRuleConfig;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.util.SafeLog;

/**
 * A kill that counts as a boss fight: when a player kills a creature whose role an enabled
 * encounter rule names ({@link EncounterRuleAsset}), that player is credited with the fight,
 * attempted and won, as a scripted fight credits a participant: {@code ENCOUNTER_ATTEMPT} then
 * {@code ENCOUNTER_DEFEATED}, the role id as the target, no qualifier, one each, with an
 * {@link EncounterRulePayload}. Content counting boss fights with no {@code Target} (any fight)
 * counts it unchanged; content naming the fight names the role id.
 *
 * <p>Only the player the kill is credited to is credited with the fight. A creature that is the
 * subject of a live run is skipped ({@link EncounterRuns#isLiveSubject}): that fight credits its own
 * participants through {@link ZigEncounterProducer}, and a rule naming its role would pay its killer
 * twice.
 *
 * <p>Called by {@link ZigMobKillProducer} once its kill moment has fired, on the world thread, with
 * the death system's command buffer: both moments go through {@link ProgressDispatch}, the one route
 * every producer feeds, so every listener and both engines see them the way they see the kill.
 */
public final class EncounterRuleCredit {

    /** One kill is one fight. */
    private static final long AMOUNT = 1L;

    private EncounterRuleCredit() {
    }

    /**
     * Credit {@code credited} with the fight when {@code roleId} is a role a rule counts. Guarded
     * whole: a failure here costs the fight's credit, never the kill's.
     *
     * @param credited the player the kill was credited to
     * @param roleId   the victim's role id, as the kill moment targeted it
     */
    public static void credit(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> credited,
            @Nullable CommandBuffer<EntityStore> commandBuffer, @Nonnull Ref<EntityStore> victimRef,
            @Nonnull String roleId) {
        try {
            int fired = fanOut(roleId, EncounterRuleConfig.getInstance()::forRole,
                    () -> EncounterRuns.isLiveSubject(store, victimRef), victimRef,
                    (kindId, target, payload) -> ProgressDispatch.fire(store, credited, commandBuffer, kindId,
                            target, null, AMOUNT, payload));
            if (fired > 0) {
                SafeLog.info("[progression] " + ZigEncounterProducer.KIND_ATTEMPT + " and "
                        + ZigEncounterProducer.KIND_DEFEATED + " dispatched for a kill of " + roleId
                        + " under its encounter rule");
            }
        } catch (Throwable t) {
            SafeLog.warn("[progression] encounter rule credit failed", t);
        }
    }

    // ==================== the pure fan-out ====================

    /** Where one moment goes. A seam purely so the fan-out needs no server to test. */
    @FunctionalInterface
    interface RuleSink {

        void accept(@Nonnull String kindId, @Nonnull String target, @Nonnull EncounterRulePayload payload);
    }

    /**
     * The decision: ATTEMPT then DEFEATED, targeted at {@code roleId}, when a rule counts the role
     * and the victim is no live run's subject (asked only once a rule matched).
     *
     * @param rules       the enabled rule naming a role id, or null
     * @param liveSubject whether the victim is the subject of a live run
     * @return how many moments went out: two for a counted kill, none otherwise
     */
    static int fanOut(@Nonnull String roleId, @Nonnull Function<String, EncounterRuleAsset> rules,
            @Nonnull BooleanSupplier liveSubject, @Nonnull Ref<EntityStore> victimRef, @Nonnull RuleSink sink) {
        EncounterRuleAsset rule = rules.apply(roleId);
        if (rule == null || liveSubject.getAsBoolean()) {
            return 0;
        }
        EncounterRulePayload payload = new EncounterRulePayload(victimRef, roleId,
                rule.getId() == null ? roleId : rule.getId(), rule.getNameKey());
        sink.accept(ZigEncounterProducer.KIND_ATTEMPT, roleId, payload);
        sink.accept(ZigEncounterProducer.KIND_DEFEATED, roleId, payload);
        return 2;
    }
}
