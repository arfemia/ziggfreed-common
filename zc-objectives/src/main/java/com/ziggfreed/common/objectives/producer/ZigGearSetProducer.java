package com.ziggfreed.common.objectives.producer;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.progress.ObjectiveTargetNames;
import com.ziggfreed.common.stats.gearset.GearSetEvents;
import com.ziggfreed.common.stats.gearset.ZigGearSetTierChangedEvent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns a gear-set tier coming on into quest and achievement progress: one {@code GEAR_SET_TIER} per
 * tier that came on, Target the set id, no Qualifier, Amount the tier's size
 * ({@link ZigGearSetTierChangedEvent#tierSize()}, the count its notice shows while it holds). The kind
 * is value-based (its file, {@code ObjectiveKinds/Gear_Set_Tier.json}, ships with the gear-set engine
 * in zc-entity), so a step keeps the largest tier it has seen and {@code Amount 4} is met by any tier
 * of four pieces or more.
 *
 * <p>An EVENT-BUS listener like {@link ZigReputationProducer}. The event fires synchronously from inside
 * the equip bridge's recompute, so every moment goes out DEFERRED onto the player's world thread
 * ({@link PlayerMomentDispatch#fireDeferred}) and is counted once the recompute has settled. A tier
 * going off moves nothing, and the engine announces no flip at login or a world change (a hydrate), so
 * only gear the player puts on in play counts.
 */
public final class ZigGearSetProducer {

    /** Fired once per tier that came on. */
    public static final String KIND = "GEAR_SET_TIER";

    private static final String LABEL = "gearset";

    private ZigGearSetProducer() {
    }

    /**
     * Listen for tier flips on the shared bus, and name the set a step of this kind targets by the
     * set's own title ({@link GearSetEvents#nameOf}), so a step with no line of its own reads the set's
     * name, not its id. Registration only, from {@code ProgressionDefaults.install}.
     */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().registerGlobal(ZigGearSetTierChangedEvent.class, ZigGearSetProducer::onTierChanged);
        ObjectiveTargetNames.register(KIND, GearSetEvents::nameOf);
    }

    /** Where one player's moment goes. A seam purely so the fan-out needs no server to test. */
    @FunctionalInterface
    interface Sink {

        void accept(@Nonnull UUID playerId, @Nonnull String kindId, @Nonnull String target,
                @Nullable String qualifier, long amount);
    }

    /** Guarded whole: a producer that throws would take the flip's other listeners down with it. */
    static void onTierChanged(@Nonnull ZigGearSetTierChangedEvent event) {
        try {
            fanOut(event, (playerId, kindId, target, qualifier, amount) -> PlayerMomentDispatch.fireDeferred(
                    LABEL, playerId, kindId, target, qualifier, amount, null));
        } catch (Throwable t) {
            SafeLog.warn("[progression] gear set tier progress failed", t);
        }
    }

    /**
     * One moment for a tier that came on with a size to count; none for a tier going off or one that
     * takes no pieces.
     *
     * @return how many moments went out
     */
    static int fanOut(@Nonnull ZigGearSetTierChangedEvent event, @Nonnull Sink sink) {
        if (!event.active() || event.tierSize() <= 0) {
            return 0;
        }
        sink.accept(event.playerId(), KIND, event.setId(), null, event.tierSize());
        return 1;
    }
}
