package com.ziggfreed.common.gearset;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.stats.gearset.ZigGearSetTierChangedEvent;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * Answers a gear-set tier flip with the authored {@code Gear_Set_Tier} moment: the one place that
 * sees both the gear-set engine (zc-entity, which may never import presentation) and the feedback
 * engine (zc-presentation, which knows nothing about gear). Registered by the wiring root on the
 * engine bus; every value the moment carries is read straight off the event, so this class
 * decides nothing, and the subject comes from the progression runtime so a consumer's own
 * notification preference is consulted exactly as it is for a quest notice.
 *
 * <p>REGISTRATION ONLY, like every root file: no loop, switch or else here, pinned by the root's
 * class javadoc and {@code RootRegistrationOnlyTest}.
 */
public final class GearSetNoticeBridge {

    /** The moment id a tier flip is authored under, and the shipped default file's name. */
    public static final String MOMENT = "Gear_Set_Tier";

    private GearSetNoticeBridge() {
    }

    /** Fire the moment for {@code event}'s player with what the event carries. */
    public static void onTierChanged(@Nonnull ZigGearSetTierChangedEvent event) {
        try {
            PlayerRef playerRef = event.playerRef();
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }
            Subject subject = ProgressionRuntime.subjects().questSubject(ref.getStore(), ref);
            if (subject == null) {
                return;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put(FeedbackEngine.SOURCE_ARG, event.setId());
            args.put("set", event.setId());
            args.put("name", event.setName());
            args.put("pieces", event.pieces());
            args.put("total", event.members());
            args.put("desc", event.tierLine());
            args.put("active", event.active());
            FeedbackEngine.fire(MOMENT, subject, args);
        } catch (Throwable t) {
            SafeLog.warn("[gearset] the tier notice failed: " + t.getMessage());
        }
    }
}
