package com.ziggfreed.common.gearset;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.stats.gearset.ZigGearSetPiecesChangedEvent;
import com.ziggfreed.common.stats.gearset.ZigGearSetTierChangedEvent;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * Answers a gear-set tier flip with the authored {@code Gear_Set_Tier} moment, and a piece going on
 * with no flip with the {@code Gear_Set_Piece} moment: the one place that sees both the gear-set
 * engine (zc-entity, which may never import presentation) and the feedback engine (zc-presentation,
 * which knows nothing about gear). Registered by the wiring root on the engine bus; every value a
 * moment carries is read straight off its event, so this class decides nothing, and the subject
 * comes from the progression runtime so a consumer's own notification preference is consulted
 * exactly as it is for a quest notice.
 *
 * <p>REGISTRATION ONLY, like every root file: no loop, switch or else here, pinned by the root's
 * class javadoc and {@code RootRegistrationOnlyTest}.
 */
public final class GearSetNoticeBridge {

    /** The moment id a tier flip is authored under, and the shipped default file's name. */
    public static final String MOMENT = "Gear_Set_Tier";

    /** The moment id a piece going on with no flip is authored under, and the shipped default file's name. */
    public static final String PIECE_MOMENT = "Gear_Set_Piece";

    private GearSetNoticeBridge() {
    }

    /** Fire the tier moment for {@code event}'s player with what the event carries. */
    public static void onTierChanged(@Nonnull ZigGearSetTierChangedEvent event) {
        try {
            Subject subject = subjectOf(event.playerRef());
            if (subject == null) {
                return;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put(FeedbackEngine.SOURCE_ARG, event.setId());
            args.put("set", event.setId());
            args.put("name", event.setName());
            args.put("pieces", event.pieces());
            args.put("total", event.fullSet());
            args.put("desc", event.tierLine());
            args.put("next", event.nextTierLine());
            args.put("hasnext", event.hasNextTier());
            args.put("active", event.active());
            FeedbackEngine.fire(MOMENT, subject, args);
        } catch (Throwable t) {
            SafeLog.warn("[gearset] the tier notice failed: " + t.getMessage());
        }
    }

    /** Fire the piece moment for {@code event}'s player with what the event carries. */
    public static void onPiecesChanged(@Nonnull ZigGearSetPiecesChangedEvent event) {
        try {
            Subject subject = subjectOf(event.playerRef());
            if (subject == null) {
                return;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put(FeedbackEngine.SOURCE_ARG, event.setId());
            args.put("set", event.setId());
            args.put("name", event.setName());
            args.put("pieces", event.pieces());
            args.put("total", event.fullSet());
            args.put("next", event.nextTierLine());
            args.put("hasnext", event.hasNextTier());
            FeedbackEngine.fire(PIECE_MOMENT, subject, args);
        } catch (Throwable t) {
            SafeLog.warn("[gearset] the piece notice failed: " + t.getMessage());
        }
    }

    /** The notice subject for {@code playerRef}, or null while the player has no live entity. */
    @Nullable
    private static Subject subjectOf(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        return ProgressionRuntime.subjects().questSubject(ref.getStore(), ref);
    }
}
