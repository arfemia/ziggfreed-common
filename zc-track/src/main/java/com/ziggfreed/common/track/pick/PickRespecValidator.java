package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.proc.ProcAsset;
import com.ziggfreed.common.track.TrackValidator;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * The kind respecs' audit ({@link TrackPickRespecAsset}), filed under the track audit's domain (inside
 * {@code TrackValidator.audit}); every finding is a WARNING, since a respec may name what a mod this server does not
 * run ships:
 *
 * <ul>
 *   <li>{@link #RESPEC_WITHOUT_KIND}: a respec whose {@code ResetsAs} is missing or no kind, which clears nothing;</li>
 *   <li>{@link #RESPEC_SHARE_OUT_OF_RANGE}: a {@code Refund} share outside 0 to 1 (held to it when read);</li>
 *   <li>{@link #RESPEC_KEEPS_NOTHING_KNOWN}: a {@code Keep} pattern naming no loaded pick (a typo keeps nothing);</li>
 *   <li>{@link #RESPEC_CLEARS_NOTHING}: a respec of a kind no loaded pick reads as, which clears nothing.</li>
 * </ul>
 */
public final class PickRespecValidator {

    public static final String RESPEC_WITHOUT_KIND = "RESPEC_WITHOUT_KIND";
    public static final String RESPEC_SHARE_OUT_OF_RANGE = "RESPEC_SHARE_OUT_OF_RANGE";
    public static final String RESPEC_KEEPS_NOTHING_KNOWN = "RESPEC_KEEPS_NOTHING_KNOWN";
    public static final String RESPEC_CLEARS_NOTHING = "RESPEC_CLEARS_NOTHING";

    private static final String DOMAIN = TrackValidator.DOMAIN;

    private PickRespecValidator() {
    }

    /** The engine walk over every switched-on respec and the live picks. Never throws. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            return audit(TrackPickRespecConfig.getInstance().liveRespecs(), TrackPicks.all());
        } catch (Throwable t) {
            SafeLog.warn("[track] the respec audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /** The pure core: {@code respecs} (the switched-on ones) against {@code picks} (by lower-cased id). */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<TrackPickRespecAsset> respecs,
            @Nonnull Map<String, TrackPicks.PickDef> picks) {
        List<Finding> out = new ArrayList<>();
        for (TrackPickRespecAsset respec : respecs) {
            String id = respec.getId();
            String where = "track pick respec '" + id + "'";
            if (respec.kind() == null) {
                out.add(Finding.warning(DOMAIN, RESPEC_WITHOUT_KIND, where + " names "
                        + (respec.getResetsAs() == null ? "no ResetsAs" : "the kind '" + respec.getResetsAs()
                        + "', which is none of Picks, Mastery, TrainerPicks, Abilities and Techniques")
                        + ", so it clears nothing", id));
            } else {
                boolean any = false;
                for (TrackPicks.PickDef pick : picks.values()) {
                    if (respec.clears(pick.asset())) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    out.add(Finding.warning(DOMAIN, RESPEC_CLEARS_NOTHING, where + " clears "
                            + respec.kind().word() + " picks, and no loaded pick it does not keep is one, so it "
                            + "clears nothing here", id));
                }
            }
            TrackPickRespecAsset.Refund refund = respec.refund();
            if (refund != null) {
                for (String bad : refund.outOfRange()) {
                    out.add(Finding.warning(DOMAIN, RESPEC_SHARE_OUT_OF_RANGE, where + " gives back the share "
                            + bad + ", outside 0 to 1, so it is held to that range", id));
                }
            }
            for (String pattern : respec.keep()) {
                boolean named = false;
                for (String pickId : picks.keySet()) {
                    if (ProcAsset.namesId(List.of(pattern), pickId)) {
                        named = true;
                        break;
                    }
                }
                if (!named) {
                    out.add(Finding.warning(DOMAIN, RESPEC_KEEPS_NOTHING_KNOWN, where + " keeps '" + pattern
                            + "', which names no loaded pick, so it keeps nothing", id));
                }
            }
        }
        return out;
    }
}
